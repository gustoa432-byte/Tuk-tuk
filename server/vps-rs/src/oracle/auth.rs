//! JWT claim helpers for Oracle (extract `node_id`).

use axum::http::HeaderMap;

use crate::jwt_util::{bearer_from_header, ensure_token_active, verify_token, Claims};
use crate::state::{AppError, AppState};

/// Authenticated mesh device: account UUID + device node id.
#[derive(Debug, Clone)]
pub struct OraclePrincipal {
    pub user_id: String,
    pub node_id: String,
    /// Token id (empty for tokens issued before revocation support).
    pub jti: String,
}

/// Require Bearer JWT and a non-empty `node_id` claim (signature + claims only).
///
/// Kept for callers that must not touch the database; every HTTP endpoint now
/// uses [`require_active_node`] so revoked tokens are actually rejected.
#[allow(dead_code)]
pub fn require_node(state: &AppState, headers: &HeaderMap) -> Result<OraclePrincipal, AppError> {
    let claims = claims_from_headers(state, headers)?;
    principal_from_claims(claims)
}

/// [`require_node`] plus the revocation gate — use this on every endpoint.
pub async fn require_active_node(
    state: &AppState,
    headers: &HeaderMap,
) -> Result<OraclePrincipal, AppError> {
    let claims = claims_from_headers(state, headers)?;
    ensure_token_active(&state.db, &claims).await?;
    principal_from_claims(claims)
}

pub fn claims_from_headers(state: &AppState, headers: &HeaderMap) -> Result<Claims, AppError> {
    let auth = headers
        .get(axum::http::header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok());
    let token = bearer_from_header(auth)?;
    verify_token(&state.cfg.jwt_secret, token)
}

fn principal_from_claims(claims: Claims) -> Result<OraclePrincipal, AppError> {
    let node_id = claims.node_id.trim().to_string();
    if node_id.is_empty() {
        return Err(AppError::unauthorized(
            "jwt_missing_node_id_reauth_required",
        ));
    }
    Ok(OraclePrincipal {
        user_id: claims.sub,
        node_id,
        jti: claims.jti,
    })
}
