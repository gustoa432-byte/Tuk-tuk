//! Opt-in username as an internet address — not a public profile, not a roster.
//!
//! Exact lookup only. No similar results, no listing, no auth-UUID as mesh id.

use axum::extract::{Query, State};
use axum::http::HeaderMap;
use axum::Json;
use libsql::params;
use serde::{Deserialize, Serialize};

use crate::node_id::derive_node_id;
use crate::oracle::auth::require_active_node;
use crate::state::{now_ms, AppError, AppState};

const USERNAME_COOLDOWN_MS: i64 = 30 * 24 * 60 * 60 * 1000;

const RESERVED: &[&str] = &[
    "qqube_official",
    "qqube",
    "qq",
    "tuktuk",
    "admin",
    "official",
    "support",
    "system",
];

#[derive(Debug, Deserialize)]
pub struct LookupQuery {
    username: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ClaimRequest {
    username: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UserAddress {
    pub public_id: String,
    pub username: String,
    pub public_key: String,
}

pub fn normalize_username(raw: &str) -> String {
    raw.trim().trim_start_matches('@').to_ascii_lowercase()
}

pub fn username_valid(normalized: &str) -> bool {
    let len = normalized.len();
    if len < 3 || len > 20 {
        return false;
    }
    if RESERVED.contains(&normalized) {
        return false;
    }
    normalized
        .chars()
        .all(|c| matches!(c, 'a'..='z' | '0'..='9' | '_'))
}

fn address_from_row(username: String, public_ble_key: String) -> Result<UserAddress, AppError> {
    if public_ble_key.is_empty() {
        return Err(AppError::not_found("user_not_found"));
    }
    let public_id = derive_node_id(&public_ble_key).map_err(|_| AppError::not_found("user_not_found"))?;
    Ok(UserAddress {
        public_id,
        username,
        public_key: public_ble_key,
    })
}

/// Exact match. 404 on unknown / unclaimed / no key. Never returns similar names.
pub async fn lookup(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(q): Query<LookupQuery>,
) -> Result<Json<UserAddress>, AppError> {
    let principal = require_active_node(&state, &headers).await?;
    let ip = crate::rate_limit::client_ip(&headers);
    state.rate_limits.check_lookup(&principal.user_id, &ip)?;

    let username = normalize_username(q.username.as_deref().unwrap_or(""));
    if !username_valid(&username) {
        return Err(AppError::bad("username_invalid"));
    }

    let mut rows = state
        .db
        .query(
            r#"
            SELECT username, public_ble_key
            FROM users
            WHERE username = ?1
            LIMIT 1
            "#,
            params![username.clone()],
        )
        .await?;
    let row = rows
        .next()
        .await?
        .ok_or_else(|| AppError::not_found("user_not_found"))?;
    let stored: String = row.get(0)?;
    let public_ble_key: String = row.get(1)?;
    Ok(Json(address_from_row(stored, public_ble_key)?))
}

pub async fn me(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<UserAddress>, AppError> {
    let principal = require_active_node(&state, &headers).await?;
    let mut rows = state
        .db
        .query(
            "SELECT COALESCE(username, ''), public_ble_key FROM users WHERE id = ?1",
            params![principal.user_id.clone()],
        )
        .await?;
    let row = rows
        .next()
        .await?
        .ok_or_else(|| AppError::not_found("user_not_found"))?;
    let username: String = row.get(0)?;
    let public_ble_key: String = row.get(1)?;
    let public_id = if public_ble_key.is_empty() {
        String::new()
    } else {
        derive_node_id(&public_ble_key).unwrap_or_default()
    };
    Ok(Json(UserAddress {
        public_id,
        username,
        public_key: public_ble_key,
    }))
}

pub async fn claim(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(body): Json<ClaimRequest>,
) -> Result<Json<UserAddress>, AppError> {
    let principal = require_active_node(&state, &headers).await?;
    let ip = crate::rate_limit::client_ip(&headers);
    state
        .rate_limits
        .check_username_claim(&principal.user_id, &ip)?;

    let username = normalize_username(&body.username);
    if !username_valid(&username) {
        return Err(AppError::bad("username_invalid"));
    }

    let mut rows = state
        .db
        .query(
            r#"
            SELECT COALESCE(username, ''), username_changed_at, public_ble_key
            FROM users WHERE id = ?1
            "#,
            params![principal.user_id.clone()],
        )
        .await?;
    let row = rows
        .next()
        .await?
        .ok_or_else(|| AppError::not_found("user_not_found"))?;
    let current: String = row.get(0)?;
    let changed_at: i64 = row.get(1)?;
    let public_ble_key: String = row.get(2)?;

    if current == username {
        return Ok(Json(own_address(username, public_ble_key)));
    }

    let now = now_ms();
    if !current.is_empty() && changed_at > 0 && now.saturating_sub(changed_at) < USERNAME_COOLDOWN_MS
    {
        return Err(AppError::too_many("username_cooldown"));
    }

    let mut taken = state
        .db
        .query(
            "SELECT id FROM users WHERE username = ?1 AND id != ?2 LIMIT 1",
            params![username.clone(), principal.user_id.clone()],
        )
        .await?;
    if taken.next().await?.is_some() {
        return Err(AppError::conflict("username_taken"));
    }

    state
        .db
        .execute(
            "UPDATE users SET username = ?1, username_changed_at = ?2 WHERE id = ?3",
            params![username.clone(), now, principal.user_id.clone()],
        )
        .await?;

    Ok(Json(own_address(username, public_ble_key)))
}

fn own_address(username: String, public_ble_key: String) -> UserAddress {
    let public_id = if public_ble_key.is_empty() {
        String::new()
    } else {
        derive_node_id(&public_ble_key).unwrap_or_default()
    };
    UserAddress {
        public_id,
        username,
        public_key: public_ble_key,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalize_strips_at_and_case() {
        assert_eq!(normalize_username(" @Alice_1 "), "alice_1");
        assert_eq!(normalize_username("bob"), "bob");
    }

    #[test]
    fn charset_and_reserved() {
        assert!(username_valid("alice"));
        assert!(username_valid("a_1"));
        assert!(!username_valid("ab"));
        assert!(!username_valid("Alice"));
        assert!(!username_valid("alice-1"));
        assert!(!username_valid("qqube_official"));
        assert!(!username_valid("qq"));
        assert!(!username_valid("not valid"));
        assert!(!username_valid("alice%"));
    }
}

#[cfg(test)]
mod api_tests {
    use super::*;
    use crate::config::Config;
    use crate::jwt_util::issue_token;
    use crate::rate_limit::RateLimitState;
    use axum::http::header::AUTHORIZATION;
    use std::sync::Arc;

    const SECRET: &str = "test-secret";
    const KEY_A: &str = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFB";
    const KEY_B: &str = "QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJC";

    async fn state() -> AppState {
        let db = libsql::Builder::new_local(":memory:")
            .build()
            .await
            .unwrap()
            .connect()
            .unwrap();
        crate::db::init_schema(&db).await.unwrap();
        AppState {
            db: Arc::new(db),
            cfg: Arc::new(Config::for_tests(SECRET)),
            rate_limits: Arc::new(RateLimitState::new()),
        }
    }

    fn auth(key: &str, user: &str) -> HeaderMap {
        let token = issue_token(SECRET, user, "email", "", key).unwrap();
        let mut h = HeaderMap::new();
        h.insert(
            AUTHORIZATION,
            format!("Bearer {token}").parse().unwrap(),
        );
        h
    }

    async fn insert_user(st: &AppState, id: &str, key: &str, username: Option<&str>) {
        st.db
            .execute(
                r#"INSERT INTO users (id, auth_method, auth_id, public_ble_key, created_at, username, username_changed_at)
                   VALUES (?1, 'email', ?2, ?3, 1, ?4, 0)"#,
                params![
                    id,
                    format!("{id}@x"),
                    key,
                    username.map(|s| s.to_string())
                ],
            )
            .await
            .unwrap();
    }

    #[tokio::test]
    async fn lookup_exact_only_and_404_unknown() {
        let st = state().await;
        insert_user(&st, "user-b", KEY_B, Some("bob")).await;
        let a = auth(KEY_A, "user-a");
        insert_user(&st, "user-a", KEY_A, None).await;

        let hit = lookup(
            State(st.clone()),
            a.clone(),
            Query(LookupQuery {
                username: Some("bob".into()),
            }),
        )
        .await
        .unwrap()
        .0;
        assert_eq!(hit.username, "bob");
        assert_eq!(hit.public_key, KEY_B);
        assert_eq!(hit.public_id, derive_node_id(KEY_B).unwrap());

        let miss = lookup(
            State(st.clone()),
            a.clone(),
            Query(LookupQuery {
                username: Some("bobby".into()),
            }),
        )
        .await
        .unwrap_err();
        assert_eq!(miss.message, "user_not_found");

        let similar = lookup(
            State(st),
            a,
            Query(LookupQuery {
                username: Some("bo".into()),
            }),
        )
        .await
        .unwrap_err();
        assert_eq!(similar.message, "username_invalid");
    }

    #[tokio::test]
    async fn unclaimed_is_not_findable() {
        let st = state().await;
        insert_user(&st, "user-b", KEY_B, None).await;
        insert_user(&st, "user-a", KEY_A, None).await;
        let a = auth(KEY_A, "user-a");
        let err = lookup(
            State(st),
            a,
            Query(LookupQuery {
                username: Some("bob".into()),
            }),
        )
        .await
        .unwrap_err();
        assert_eq!(err.message, "user_not_found");
    }
}
