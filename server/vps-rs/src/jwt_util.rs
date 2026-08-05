//! JWT issue / verify helpers.

use chrono::{Duration, Utc};
use jsonwebtoken::{decode, encode, DecodingKey, EncodingKey, Header, Validation};
use serde::{Deserialize, Serialize};

use crate::node_id::derive_node_id;
use crate::state::AppError;

/// Access-token lifetime (was 30d — shortened to limit stolen-token window).
const ACCESS_TTL_DAYS: i64 = 7;

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
    let node_id = derive_node_id(public_ble_key)
        .map_err(|e| AppError::bad(format!("invalid_public_ble_key: {e}")))?;
    let now = Utc::now();
    let claims = Claims {
        sub: user_id.to_string(),
        auth_method: auth_method.to_string(),
        auth_id: String::new(),
        public_ble_key: public_ble_key.to_string(),
        node_id,
        iat: now.timestamp(),
        exp: (now + Duration::days(ACCESS_TTL_DAYS)).timestamp(),
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
