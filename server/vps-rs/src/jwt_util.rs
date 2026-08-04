//! JWT issue / verify helpers.

use chrono::{Duration, Utc};
use jsonwebtoken::{decode, encode, DecodingKey, EncodingKey, Header, Validation};
use serde::{Deserialize, Serialize};

use crate::state::AppError;

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct Claims {
    pub sub: String,
    pub auth_method: String,
    pub auth_id: String,
    pub public_ble_key: String,
    pub exp: i64,
    pub iat: i64,
}

pub fn issue_token(
    secret: &str,
    user_id: &str,
    auth_method: &str,
    auth_id: &str,
    public_ble_key: &str,
) -> Result<String, AppError> {
    let now = Utc::now();
    let claims = Claims {
        sub: user_id.to_string(),
        auth_method: auth_method.to_string(),
        auth_id: auth_id.to_string(),
        public_ble_key: public_ble_key.to_string(),
        iat: now.timestamp(),
        exp: (now + Duration::days(30)).timestamp(),
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
