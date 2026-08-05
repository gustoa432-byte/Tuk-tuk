//! Email OTP + Telegram Mini App auth.

use axum::extract::State;
use axum::http::HeaderMap;
use axum::Json;
use hmac::{Hmac, Mac};
use libsql::params;
use rand::Rng;
use serde::{Deserialize, Serialize};
use sha2::Sha256;
use tracing::{info, warn};

use crate::jwt_util::{bearer_from_header, issue_token, verify_token};
use crate::moderation::is_account_banned;
use crate::node_id::derive_node_id;
use crate::state::{now_ms, AppError, AppState};

type HmacSha256 = Hmac<Sha256>;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EmailSendRequest {
    pub email: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EmailSendResponse {
    pub ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub dev_code: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EmailVerifyRequest {
    pub email: String,
    pub otp: String,
    pub public_ble_key: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TelegramAuthRequest {
    /// Raw `window.Telegram.WebApp.initData` query string.
    pub init_data: String,
    pub public_ble_key: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AuthResponse {
    pub ok: bool,
    pub token: String,
    pub user_id: String,
    pub auth_method: String,
    pub auth_id: String,
    pub public_ble_key: String,
    /// Mesh node id bound into the JWT (same derivation as Android NodeIdentity).
    pub node_id: String,
}

pub async fn email_send(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(body): Json<EmailSendRequest>,
) -> Result<Json<EmailSendResponse>, AppError> {
    let email = normalize_email(&body.email)?;
    let ip = crate::rate_limit::client_ip(&headers);
    state.rate_limits.check_otp_send(&email, &ip)?;
    let code = format!("{:06}", rand::thread_rng().gen_range(0..1_000_000));
    let expires_at = now_ms() + 5 * 60 * 1000;

    state
        .db
        .execute(
            r#"
            INSERT INTO email_otps (email, code, expires_at)
            VALUES (?1, ?2, ?3)
            ON CONFLICT(email) DO UPDATE SET
                code = excluded.code,
                expires_at = excluded.expires_at
            "#,
            params![email.clone(), code.clone(), expires_at],
        )
        .await?;

    let mut dev_code = None;
    if state.cfg.smtp_ready() {
        if let Err(e) = crate::mail::send_otp(&state.cfg, &email, &code).await {
            warn!(error = %e.message, %email, "SMTP send failed");
            return Err(AppError::internal("smtp_send_failed"));
        }
    } else if state.cfg.otp_dev_log {
        info!(%email, %code, "OTP (dev log — SMTP not configured)");
        dev_code = Some(code);
    } else {
        return Err(AppError::internal("smtp_not_configured"));
    }

    Ok(Json(EmailSendResponse {
        ok: true,
        dev_code,
    }))
}

pub async fn email_verify(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(body): Json<EmailVerifyRequest>,
) -> Result<Json<AuthResponse>, AppError> {
    let email = normalize_email(&body.email)?;
    let ip = crate::rate_limit::client_ip(&headers);
    state.rate_limits.check_otp_verify(&email, &ip)?;
    let otp = body.otp.trim().to_string();
    let ble = body.public_ble_key.trim().to_string();
    if ble.is_empty() {
        return Err(AppError::bad("public_ble_key_required"));
    }

    let mut rows = state
        .db
        .query(
            "SELECT code, expires_at FROM email_otps WHERE email = ?1",
            params![email.clone()],
        )
        .await?;
    let row = rows
        .next()
        .await?
        .ok_or_else(|| AppError::unauthorized("otp_not_found"))?;
    let stored: String = row.get(0)?;
    let expires_at: i64 = row.get(1)?;
    if now_ms() > expires_at {
        return Err(AppError::unauthorized("otp_expired"));
    }
    if stored != otp {
        return Err(AppError::unauthorized("otp_invalid"));
    }

    state
        .db
        .execute("DELETE FROM email_otps WHERE email = ?1", params![email.clone()])
        .await?;

    upsert_user_and_token(&state, "email", &email, &ble).await
}

pub async fn telegram_auth(
    State(state): State<AppState>,
    Json(body): Json<TelegramAuthRequest>,
) -> Result<Json<AuthResponse>, AppError> {
    let token = state
        .cfg
        .telegram_bot_token
        .as_ref()
        .ok_or_else(|| AppError::internal("telegram_bot_token_not_configured"))?;
    let ble = body.public_ble_key.trim().to_string();
    if ble.is_empty() {
        return Err(AppError::bad("public_ble_key_required"));
    }

    let tg_user_id = verify_telegram_init_data(token, &body.init_data)?;
    upsert_user_and_token(&state, "tg", &tg_user_id, &ble).await
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RefreshRequest {
    /// Current device BLE public key (preferred). Falls back to JWT claim.
    #[serde(default)]
    pub public_ble_key: String,
}

/// Quiet token renewal. Bearer required; BLE key rotation is forbidden here —
/// stolen JWT must not rebind `public_ble_key` / node_id (use email/TG verify).
pub async fn refresh(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(body): Json<RefreshRequest>,
) -> Result<Json<AuthResponse>, AppError> {
    let auth = headers
        .get(axum::http::header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok());
    let token = bearer_from_header(auth)?;
    let claims = verify_token(&state.cfg.jwt_secret, token)?;
    let ip = crate::rate_limit::client_ip(&headers);
    state
        .rate_limits
        .check_refresh(&claims.sub, &ip)?;

    if is_account_banned(&state.db, &claims.sub).await? {
        return Err(AppError::forbidden("account_banned"));
    }

    let mut rows = state
        .db
        .query(
            "SELECT auth_method, auth_id, public_ble_key FROM users WHERE id = ?1",
            params![claims.sub.clone()],
        )
        .await?;
    let row = rows
        .next()
        .await?
        .ok_or_else(|| AppError::unauthorized("user_not_found"))?;
    let method: String = row.get(0)?;
    let auth_id: String = row.get(1)?;
    let stored_ble: String = row.get(2)?;

    let mut ble = body.public_ble_key.trim().to_string();
    if ble.is_empty() {
        ble = claims.public_ble_key.trim().to_string();
    }
    if ble.is_empty() {
        ble = stored_ble.clone();
    }
    if ble.is_empty() {
        return Err(AppError::bad("public_ble_key_required"));
    }
    // Hard bind: refresh cannot change the device key bound into the JWT / DB.
    if ble != claims.public_ble_key.trim() || ble != stored_ble.trim() {
        return Err(AppError::unauthorized("ble_key_rotation_requires_reauth"));
    }

    let node_id = derive_node_id(&ble)
        .map_err(|e| AppError::bad(format!("invalid_public_ble_key: {e}")))?;
    let token = issue_token(
        &state.cfg.jwt_secret,
        &claims.sub,
        &method,
        &auth_id,
        &ble,
    )?;

    Ok(Json(AuthResponse {
        ok: true,
        token,
        user_id: claims.sub,
        auth_method: method,
        auth_id,
        public_ble_key: ble,
        node_id,
    }))
}

async fn upsert_user_and_token(
    state: &AppState,
    method: &str,
    auth_id: &str,
    public_ble_key: &str,
) -> Result<Json<AuthResponse>, AppError> {
    // Fail closed: key must self-certify before we touch users / issue JWT.
    let node_id = derive_node_id(public_ble_key)
        .map_err(|e| AppError::bad(format!("invalid_public_ble_key: {e}")))?;

    let now = now_ms();
    let mut existing = state
        .db
        .query(
            "SELECT id FROM users WHERE auth_method = ?1 AND auth_id = ?2",
            params![method, auth_id],
        )
        .await?;

    let user_id = if let Some(row) = existing.next().await? {
        let id: String = row.get(0)?;
        if is_account_banned(&state.db, &id).await? {
            return Err(AppError::forbidden("account_banned"));
        }
        state
            .db
            .execute(
                "UPDATE users SET public_ble_key = ?1 WHERE id = ?2",
                params![public_ble_key, id.clone()],
            )
            .await?;
        id
    } else {
        let id = uuid::Uuid::new_v4().to_string();
        // New accounts cannot already be banned; still check for safety.
        if is_account_banned(&state.db, &id).await? {
            return Err(AppError::forbidden("account_banned"));
        }
        state
            .db
            .execute(
                r#"
                INSERT INTO users (id, auth_method, auth_id, public_ble_key, created_at)
                VALUES (?1, ?2, ?3, ?4, ?5)
                "#,
                params![id.clone(), method, auth_id, public_ble_key, now],
            )
            .await?;
        id
    };

    let token = issue_token(
        &state.cfg.jwt_secret,
        &user_id,
        method,
        auth_id,
        public_ble_key,
    )?;
    // issue_token derives the same way — assert lockstep.
    let issued_node = crate::jwt_util::verify_token(&state.cfg.jwt_secret, &token)?.node_id;
    if issued_node != node_id {
        return Err(AppError::internal("node_id_derive_mismatch"));
    }

    Ok(Json(AuthResponse {
        ok: true,
        token,
        user_id,
        auth_method: method.to_string(),
        auth_id: auth_id.to_string(),
        public_ble_key: public_ble_key.to_string(),
        node_id,
    }))
}

fn normalize_email(raw: &str) -> Result<String, AppError> {
    let e = raw.trim().to_lowercase();
    if e.is_empty() || !e.contains('@') || e.len() > 320 {
        return Err(AppError::bad("invalid_email"));
    }
    Ok(e)
}

/// Validate Telegram WebApp `initData` per Bot API docs.
/// Rejects replayed payloads: `auth_date` must be within [now − 300s, now + skew].
fn verify_telegram_init_data(bot_token: &str, init_data: &str) -> Result<String, AppError> {
    /// Max age of a valid Mini App `auth_date` (seconds).
    const AUTH_MAX_AGE_SECS: i64 = 300;
    /// Allow minor client/server clock drift into the near future.
    const AUTH_CLOCK_SKEW_SECS: i64 = 60;

    let pairs: Vec<(String, String)> = init_data
        .split('&')
        .filter_map(|p| {
            let (k, v) = p.split_once('=')?;
            Some((
                urlencoding::decode(k).ok()?.into_owned(),
                urlencoding::decode(v).ok()?.into_owned(),
            ))
        })
        .collect();

    let hash = pairs
        .iter()
        .find(|(k, _)| k == "hash")
        .map(|(_, v)| v.clone())
        .ok_or_else(|| AppError::unauthorized("telegram_hash_missing"))?;

    let mut check: Vec<(String, String)> = pairs
        .into_iter()
        .filter(|(k, _)| k != "hash")
        .collect();
    check.sort_by(|a, b| a.0.cmp(&b.0));
    let data_check_string = check
        .iter()
        .map(|(k, v)| format!("{k}={v}"))
        .collect::<Vec<_>>()
        .join("\n");

    let mut secret_hmac = HmacSha256::new_from_slice(b"WebAppData")
        .map_err(|_| AppError::internal("hmac_init"))?;
    secret_hmac.update(bot_token.as_bytes());
    let secret_key = secret_hmac.finalize().into_bytes();

    let mut mac = HmacSha256::new_from_slice(&secret_key)
        .map_err(|_| AppError::internal("hmac_init"))?;
    mac.update(data_check_string.as_bytes());
    let calc = hex::encode(mac.finalize().into_bytes());
    if calc != hash {
        return Err(AppError::unauthorized("telegram_hash_invalid"));
    }

    // Freshness: Telegram includes auth_date (unix seconds) in the signed payload.
    let auth_date_raw = check
        .iter()
        .find(|(k, _)| k == "auth_date")
        .map(|(_, v)| v.as_str())
        .ok_or_else(|| AppError::unauthorized("telegram_auth_date_missing"))?;
    let auth_date: i64 = auth_date_raw.parse().map_err(|_| {
        AppError::unauthorized("telegram_auth_date_invalid")
    })?;
    if auth_date <= 0 {
        return Err(AppError::unauthorized("telegram_auth_date_invalid"));
    }
    let now_secs = now_ms().saturating_div(1000);
    // Reject far-future timestamps (clock skew / forged field after HMAC would already fail;
    // this also covers malformed large values without overflow panics).
    if auth_date > now_secs.saturating_add(AUTH_CLOCK_SKEW_SECS) {
        return Err(AppError::unauthorized("telegram_auth_date_in_future"));
    }
    let age_secs = now_secs.saturating_sub(auth_date);
    if age_secs > AUTH_MAX_AGE_SECS {
        return Err(AppError::unauthorized("telegram_auth_date_expired"));
    }

    let user_json = check
        .iter()
        .find(|(k, _)| k == "user")
        .map(|(_, v)| v.clone())
        .ok_or_else(|| AppError::unauthorized("telegram_user_missing"))?;
    let user: serde_json::Value = serde_json::from_str(&user_json)
        .map_err(|_| AppError::bad("telegram_user_json"))?;
    let id = user
        .get("id")
        .and_then(|v| v.as_i64().or_else(|| v.as_u64().map(|u| u as i64)))
        .ok_or_else(|| AppError::bad("telegram_user_id"))?;
    Ok(id.to_string())
}
