//! JWT claim helpers for Oracle (extract `node_id`).

use axum::http::HeaderMap;

use crate::jwt_util::{bearer_from_header, verify_token, Claims};
use crate::state::{AppError, AppState};

/// Authenticated mesh device: account UUID + device node id.
#[derive(Debug, Clone)]
pub struct OraclePrincipal {
    pub user_id: String,
    pub node_id: String,
}

/// Require Bearer JWT and a non-empty `node_id` claim.
pub fn require_node(state: &AppState, headers: &HeaderMap) -> Result<OraclePrincipal, AppError> {
    let auth = headers
        .get(axum::http::header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok());
    let token = bearer_from_header(auth)?;
    let claims = verify_token(&state.cfg.jwt_secret, token)?;
    principal_from_claims(claims)
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
    })
}
