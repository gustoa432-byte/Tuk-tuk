//! Hidden BLE handshake via online contacts.

use axum::extract::State;
use axum::http::HeaderMap;
use axum::Json;
use libsql::params;
use serde::{Deserialize, Serialize};

use crate::jwt_util::{bearer_from_header, verify_token};
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
    pub public_ble_key: String,
}

pub async fn add_contact(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(body): Json<AddContactRequest>,
) -> Result<Json<AddContactResponse>, AppError> {
    let auth = headers
        .get(axum::http::header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok());
    let token = bearer_from_header(auth)?;
    let claims = verify_token(&state.cfg.jwt_secret, token)?;

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
    // Directed edge: me → them (and reverse for mutual discovery convenience).
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
    state
        .db
        .execute(
            r#"
            INSERT OR IGNORE INTO contacts (user_id_1, user_id_2, created_at)
            VALUES (?1, ?2, ?3)
            "#,
            params![user_id.clone(), claims.sub.clone(), now],
        )
        .await?;

    Ok(Json(AddContactResponse {
        ok: true,
        user_id,
        public_ble_key,
    }))
}
