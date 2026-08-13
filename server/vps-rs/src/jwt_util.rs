//! JWT issue / verify helpers.

use chrono::{Duration, Utc};
use jsonwebtoken::{decode, encode, DecodingKey, EncodingKey, Header, Validation};
use libsql::params;
use serde::{Deserialize, Serialize};

use crate::node_id::derive_node_id;
use crate::state::{now_ms, AppError};

/// Default access-token lifetime in hours (was 30d, then 7d).
/// Override with `TUKTUK_JWT_TTL_HOURS` — see [`crate::config::Config`].
/// Kept at 7d by default on purpose: clients ≤ 0.1.116 only refresh when the
/// server explicitly asks them to, so a shorter default would log them out.
pub const DEFAULT_ACCESS_TTL_HOURS: i64 = 24 * 7;

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct Claims {
    /// Account UUID (one user, possibly many devices).
    pub sub: String,
    pub auth_method: String,
    /// Intentionally empty in new tokens — PII stays server-side only.
    #[serde(default)]
    pub auth_id: String,
    pub public_ble_key: String,
    /// Mesh device id for this token (derived from `public_ble_key` at issue).
    #[serde(default)]
    pub node_id: String,
    /// Token id for revocation. Empty on tokens issued before this existed.
    #[serde(default)]
    pub jti: String,
    pub exp: i64,
    pub iat: i64,
}

pub fn issue_token(
    secret: &str,
    user_id: &str,
    auth_method: &str,
    _auth_id: &str,
    public_ble_key: &str,
) -> Result<String, AppError> {
    issue_token_with_ttl(
        secret,
        user_id,
        auth_method,
        public_ble_key,
        DEFAULT_ACCESS_TTL_HOURS,
    )
}

pub fn issue_token_with_ttl(
    secret: &str,
    user_id: &str,
    auth_method: &str,
    public_ble_key: &str,
    ttl_hours: i64,
) -> Result<String, AppError> {
    let node_id = derive_node_id(public_ble_key)
        .map_err(|e| AppError::bad(format!("invalid_public_ble_key: {e}")))?;
    let now = Utc::now();
    let ttl = ttl_hours.clamp(1, 24 * 30);
    let claims = Claims {
        sub: user_id.to_string(),
        auth_method: auth_method.to_string(),
        auth_id: String::new(),
        public_ble_key: public_ble_key.to_string(),
        node_id,
        jti: uuid::Uuid::new_v4().to_string(),
        iat: now.timestamp(),
        exp: (now + Duration::hours(ttl)).timestamp(),
    };
    encode(
        &Header::default(),
        &claims,
        &EncodingKey::from_secret(secret.as_bytes()),
    )
    .map_err(AppError::from)
}

pub fn verify_token(secret: &str, token: &str) -> Result<Claims, AppError> {
    let data = decode::<Claims>(
        token,
        &DecodingKey::from_secret(secret.as_bytes()),
        &Validation::default(),
    )?;
    Ok(data.claims)
}

/// Revocation gate. Two mechanisms, because tokens issued before `jti` existed
/// cannot be denied individually:
///  - `revoked_tokens.jti` — one specific token (logout on this device);
///  - `token_epochs.not_before` — everything issued for the account before a
///    point in time (logout everywhere / suspected compromise).
pub async fn ensure_token_active(
    conn: &libsql::Connection,
    claims: &Claims,
) -> Result<(), AppError> {
    if !claims.jti.is_empty() {
        let mut rows = conn
            .query(
                "SELECT 1 FROM revoked_tokens WHERE jti = ?1 LIMIT 1",
                params![claims.jti.clone()],
            )
            .await?;
        if rows.next().await?.is_some() {
            return Err(AppError::unauthorized("token_revoked"));
        }
    }
    let mut rows = conn
        .query(
            "SELECT not_before FROM token_epochs WHERE user_id = ?1 LIMIT 1",
            params![claims.sub.clone()],
        )
        .await?;
    if let Some(row) = rows.next().await? {
        let not_before: i64 = row.get(0)?;
        // `iat` is in seconds, the epoch marker in ms.
        if claims.iat.saturating_mul(1000) < not_before {
            return Err(AppError::unauthorized("token_revoked"));
        }
    }
    Ok(())
}

pub async fn revoke_token(conn: &libsql::Connection, claims: &Claims) -> Result<(), AppError> {
    if claims.jti.is_empty() {
        // Legacy token without an id: fall back to an account-wide epoch so the
        // request is not silently a no-op.
        return revoke_all_for_user(conn, &claims.sub).await;
    }
    conn.execute(
        r#"
        INSERT OR IGNORE INTO revoked_tokens (jti, user_id, revoked_at)
        VALUES (?1, ?2, ?3)
        "#,
        params![claims.jti.clone(), claims.sub.clone(), now_ms()],
    )
    .await?;
    Ok(())
}

pub async fn revoke_all_for_user(
    conn: &libsql::Connection,
    user_id: &str,
) -> Result<(), AppError> {
    conn.execute(
        r#"
        INSERT INTO token_epochs (user_id, not_before)
        VALUES (?1, ?2)
        ON CONFLICT(user_id) DO UPDATE SET not_before = excluded.not_before
        "#,
        params![user_id.to_string(), now_ms()],
    )
    .await?;
    Ok(())
}

/// A revoked token stops mattering once it would have expired anyway.
/// Keep entries for the max issuable TTL (30d) plus a day of slack.
pub async fn prune_revocations(conn: &libsql::Connection) -> Result<u64, AppError> {
    let cutoff = now_ms().saturating_sub(31 * 24 * 3_600_000);
    let n = conn
        .execute(
            "DELETE FROM revoked_tokens WHERE revoked_at < ?1",
            params![cutoff],
        )
        .await?;
    Ok(n)
}

pub fn bearer_from_header(auth: Option<&str>) -> Result<&str, AppError> {
    let raw = auth.ok_or_else(|| AppError::unauthorized("missing Authorization"))?;
    let token = raw
        .strip_prefix("Bearer ")
        .or_else(|| raw.strip_prefix("bearer "))
        .ok_or_else(|| AppError::unauthorized("expected Bearer token"))?;
    if token.is_empty() {
        return Err(AppError::unauthorized("empty token"));
    }
    Ok(token)
}

#[cfg(test)]
mod tests {
    use super::*;

    // Any valid RSA-ish base64 key works: derive_node_id only hashes the bytes.
    const KEY: &str = "dGVzdC1wdWJsaWMta2V5LWJ5dGVz";

    #[test]
    fn issued_tokens_carry_jti_and_node_id() {
        let a = issue_token("secret", "user-1", "email", "", KEY).unwrap();
        let b = issue_token("secret", "user-1", "email", "", KEY).unwrap();
        let ca = verify_token("secret", &a).unwrap();
        let cb = verify_token("secret", &b).unwrap();
        assert!(!ca.jti.is_empty());
        assert_ne!(ca.jti, cb.jti, "jti must be unique per token");
        assert_eq!(ca.node_id, derive_node_id(KEY).unwrap());
        assert!(ca.exp > ca.iat);
    }

    #[test]
    fn tokens_from_another_secret_are_rejected() {
        let t = issue_token("secret", "user-1", "email", "", KEY).unwrap();
        assert!(verify_token("other-secret", &t).is_err());
    }

    #[test]
    fn ttl_is_clamped_to_a_sane_range() {
        let t = issue_token_with_ttl("secret", "u", "email", KEY, 100_000).unwrap();
        let c = verify_token("secret", &t).unwrap();
        assert!(c.exp - c.iat <= 30 * 24 * 3600);
    }
}
