//! Hidden BLE handshake via online contacts.
//!
//! Consent model: adding someone only records *your* side of the edge. The
//! target's `public_ble_key` is handed out only once they added you back.
//! Before that this endpoint is a request, not a lookup — otherwise anyone
//! holding a user UUID could harvest keys (and therefore mesh node ids) with no
//! consent at all. QR / out-of-band remains the primary exchange.

use axum::extract::State;
use axum::http::HeaderMap;
use axum::Json;
use libsql::params;
use serde::{Deserialize, Serialize};

use crate::jwt_util::ensure_token_active;
use crate::oracle::auth::claims_from_headers;
use crate::state::{now_ms, AppError, AppState};

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AddContactRequest {
    /// Target TukTuk user id (UUID from auth response).
    pub user_id: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AddContactResponse {
    pub ok: bool,
    pub user_id: String,
    /// Empty until the other side adds you back.
    pub public_ble_key: String,
    /// True when we recorded the request but consent is still missing.
    pub pending: bool,
}

pub async fn add_contact(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(body): Json<AddContactRequest>,
) -> Result<Json<AddContactResponse>, AppError> {
    let claims = claims_from_headers(&state, &headers)?;
    ensure_token_active(&state.db, &claims).await?;
    let ip = crate::rate_limit::client_ip(&headers);
    // Bound UUID guessing / enumeration attempts.
    state.rate_limits.check_contacts(&claims.sub, &ip)?;

    let target = body.user_id.trim().to_string();
    if target.is_empty() {
        return Err(AppError::bad("user_id_required"));
    }
    if target == claims.sub {
        return Err(AppError::bad("cannot_add_self"));
    }

    let mut rows = state
        .db
        .query(
            "SELECT id, public_ble_key FROM users WHERE id = ?1",
            params![target.clone()],
        )
        .await?;
    let row = rows
        .next()
        .await?
        .ok_or_else(|| AppError::not_found("user_not_found"))?;
    let user_id: String = row.get(0)?;
    let public_ble_key: String = row.get(1)?;
    if public_ble_key.is_empty() {
        return Err(AppError::bad("contact_missing_ble_key"));
    }

    let now = now_ms();
    // Only our own directed edge. The reverse edge used to be inserted here,
    // which manufactured "mutual consent" out of a one-sided request.
    state
        .db
        .execute(
            r#"
            INSERT OR IGNORE INTO contacts (user_id_1, user_id_2, created_at)
            VALUES (?1, ?2, ?3)
            "#,
            params![claims.sub.clone(), user_id.clone(), now],
        )
        .await?;

    let mut reverse = state
        .db
        .query(
            "SELECT 1 FROM contacts WHERE user_id_1 = ?1 AND user_id_2 = ?2 LIMIT 1",
            params![user_id.clone(), claims.sub.clone()],
        )
        .await?;
    let mutual = reverse.next().await?.is_some();

    Ok(Json(AddContactResponse {
        ok: true,
        user_id,
        public_ble_key: if mutual { public_ble_key } else { String::new() },
        pending: !mutual,
    }))
}
