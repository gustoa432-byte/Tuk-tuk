//! Legacy mesh store-and-forward API (VpsBridge-compatible).

use axum::extract::{Query, State};
use axum::http::HeaderMap;
use axum::response::{IntoResponse, Response};
use axum::Json;
use libsql::params;
use serde::{Deserialize, Serialize};

use crate::moderation::reject_if_banned;
use crate::node_id::derive_node_id;
use crate::oracle::auth::require_active_node;
use crate::state::{now_ms, AppError, AppState};

/// Undelivered mailbox items live this long (hours, not days).
const UNDELIVERED_RETENTION_MS: i64 = 12 * 60 * 60 * 1000;
/// Broadcast has no single addressee to delete on — keep it even shorter.
const BROADCAST_RETENTION_MS: i64 = 6 * 60 * 60 * 1000;
/// Grace window after a pull, so a client that crashed mid-batch can re-pull.
const DELIVERED_GRACE_MS: i64 = 60 * 60 * 1000;
/// Max envelopes queued for one recipient before we refuse more.
const MAX_QUEUE_PER_RECIPIENT: i64 = 500;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct RegisterRequest {
    node_id: Option<String>,
    nick: Option<String>,
    pubkey: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct DirectoryNode {
    node_id: String,
    nick: String,
    pubkey: String,
    seen_at: i64,
}

#[derive(Debug, Serialize)]
pub(crate) struct DirectoryResponse {
    nodes: Vec<DirectoryNode>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct EnvelopeIn {
    id: String,
    from: String,
    to: Option<String>,
    #[serde(alias = "payload")]
    payload_b64: String,
    ts: Option<i64>,
    kind: Option<String>,
    /// Sender RSA public key so the addressee can reply without a prior lookup.
    #[serde(default)]
    sender_pub_key: Option<String>,
}

#[derive(Debug, Deserialize)]
pub(crate) struct PushRequest {
    envelopes: Vec<EnvelopeIn>,
}

#[derive(Debug, Serialize)]
pub(crate) struct PushResponse {
    ok: bool,
    accepted: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct EnvelopeOut {
    id: String,
    from: String,
    to: String,
    payload_b64: String,
    ts: i64,
    kind: String,
    #[serde(default)]
    sender_pub_key: String,
}

#[derive(Debug, Serialize)]
pub(crate) struct PullResponse {
    envelopes: Vec<EnvelopeOut>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct PullQuery {
    node_id: Option<String>,
    since: Option<i64>,
}

#[derive(Debug, Deserialize)]
pub(crate) struct AckRequest {
    #[serde(default)]
    ids: Vec<String>,
}

#[derive(Debug, Serialize)]
pub(crate) struct AckResponse {
    ok: bool,
    deleted: u64,
}

pub async fn health(State(state): State<AppState>) -> Result<Json<serde_json::Value>, AppError> {
    // No roster sizes for anonymous recon — just liveness.
    let _ = &state;
    Ok(Json(serde_json::json!({ "ok": true })))
}

pub async fn directory(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<DirectoryResponse>, AppError> {
    // Bulk roster is off the product path: it was a phone book that
    // auto-created contacts and handed out keys. Exact username lookup
    // is the only internet find. Keep the route so old clients get 200 + empty.
    let principal = require_active_node(&state, &headers).await?;
    reject_if_banned(&state.db, &principal.user_id, &principal.node_id).await?;
    let _ = &state;
    Ok(Json(DirectoryResponse { nodes: Vec::new() }))
}

pub async fn register(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(body): Json<RegisterRequest>,
) -> Result<Response, AppError> {
    let principal = require_active_node(&state, &headers).await?;
    reject_if_banned(&state.db, &principal.user_id, &principal.node_id).await?;
    let ip = crate::rate_limit::client_ip(&headers);
    // Generous on purpose: 0.1.116 re-registers on every 12s sync tick. The row
    // itself is already unforgeable (node_id is bound to the JWT and must be
    // derivable from the pubkey), so this only bounds request volume.
    state.rate_limits.check_register(&principal.node_id, &ip)?;
    let header_id = headers
        .get("X-Node-Id")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .to_string();
    let requested = body
        .node_id
        .filter(|s| !s.is_empty())
        .or_else(|| (!header_id.is_empty()).then_some(header_id));

    let node_id = match requested {
        Some(id) if id == principal.node_id => id,
        Some(_) => {
            return Err(AppError::unauthorized("node_id_mismatch_jwt"));
        }
        None => principal.node_id.clone(),
    };

    let pubkey = body.pubkey.unwrap_or_default().trim().to_string();
    if pubkey.is_empty() {
        return Err(AppError::bad("pubkey_required"));
    }
    let derived = derive_node_id(&pubkey)
        .map_err(|e| AppError::bad(format!("invalid_pubkey: {e}")))?;
    if derived != node_id {
        return Err(AppError::bad("pubkey_node_id_mismatch"));
    }

    state
        .db
        .execute(
            r#"
            INSERT INTO nodes (node_id, nick, pubkey, seen_at)
            VALUES (?1, ?2, ?3, ?4)
            ON CONFLICT(node_id) DO UPDATE SET
                nick = excluded.nick,
                pubkey = excluded.pubkey,
                seen_at = excluded.seen_at
            "#,
            params![
                node_id,
                body.nick.unwrap_or_default(),
                pubkey,
                now_ms()
            ],
        )
        .await?;

    Ok(Json(serde_json::json!({ "ok": true })).into_response())
}

pub async fn push(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(body): Json<PushRequest>,
) -> Result<Json<PushResponse>, AppError> {
    let principal = require_active_node(&state, &headers).await?;
    reject_if_banned(&state.db, &principal.user_id, &principal.node_id).await?;
    let ip = crate::rate_limit::client_ip(&headers);
    state
        .rate_limits
        .check_push(&principal.node_id, &ip)?;
    // Soft cap: refuse absurd batches even within body limit.
    if body.envelopes.len() > 50 {
        return Err(AppError::bad("too_many_envelopes"));
    }
    let mut accepted = 0u32;
    let mut broadcast_count = 0u32;
    let account_pub = account_pubkey(&state, &principal.user_id).await?;
    for env in body.envelopes {
        if env.id.is_empty() {
            continue;
        }
        if env.payload_b64.len() > 96 * 1024 {
            return Err(AppError::bad("payload_too_large"));
        }
        // Bind sender to authenticated mesh device — no anonymous / spoofed from.
        let from = if env.from.is_empty() || env.from == principal.node_id {
            principal.node_id.clone()
        } else {
            return Err(AppError::unauthorized("envelope_from_mismatch_jwt"));
        };
        let receiver = env.to.unwrap_or_default();
        let is_broadcast = receiver.is_empty() || receiver == "*";
        if is_broadcast {
            broadcast_count += 1;
            if broadcast_count > 10 {
                return Err(AppError::bad("too_many_broadcast_envelopes"));
            }
            if env.payload_b64.len() > 24 * 1024 {
                return Err(AppError::bad("broadcast_payload_too_large"));
            }
        }
        let receiver_sql: Option<String> = if is_broadcast {
            None
        } else {
            // One account must not be able to fill another user's mailbox.
            let depth = queue_depth(&state, &receiver).await?;
            if depth >= MAX_QUEUE_PER_RECIPIENT {
                return Err(AppError::too_many("recipient_queue_full"));
            }
            Some(receiver)
        };
        let created_at = env.ts.filter(|t| *t > 0).unwrap_or_else(now_ms);
        let kind = env
            .kind
            .filter(|k| !k.is_empty())
            .unwrap_or_else(|| "mesh_bytes".into());
        let sender_pub_key =
            resolve_sender_pub_key(&principal.node_id, env.sender_pub_key, &account_pub)?;

        let changed = state
            .db
            .execute(
                r#"
                INSERT OR IGNORE INTO envelopes
                    (id, sender_id, receiver_id, payload, kind, created_at, sender_pub_key)
                VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)
                "#,
                params![
                    env.id,
                    from,
                    receiver_sql,
                    env.payload_b64,
                    kind,
                    created_at,
                    sender_pub_key
                ],
            )
            .await?;
        if changed > 0 {
            accepted += 1;
        }
    }
    Ok(Json(PushResponse {
        ok: true,
        accepted,
    }))
}

pub async fn pull(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(q): Query<PullQuery>,
) -> Result<Json<PullResponse>, AppError> {
    let principal = require_active_node(&state, &headers).await?;
    reject_if_banned(&state.db, &principal.user_id, &principal.node_id).await?;
    // Ignore spoofable query node_id — always pull for the JWT device.
    let node_id = principal.node_id;
    if let Some(claimed) = q.node_id.as_ref().filter(|s| !s.is_empty()) {
        if *claimed != node_id {
            return Err(AppError::unauthorized("node_id_mismatch_jwt"));
        }
    }
    let since = q.since.unwrap_or(0);
    let mut rows = state
        .db
        .query(
            r#"
            SELECT id, sender_id, COALESCE(receiver_id, '*'), payload, created_at, kind,
                   COALESCE(sender_pub_key, '')
            FROM envelopes
            WHERE created_at > ?1
              AND sender_id != ?2
              AND (
                    receiver_id IS NULL
                 OR receiver_id = ''
                 OR receiver_id = '*'
                 OR receiver_id = ?2
              )
            ORDER BY created_at ASC
            LIMIT 500
            "#,
            params![since, node_id],
        )
        .await?;

    let mut envelopes = Vec::new();
    while let Some(row) = rows.next().await? {
        envelopes.push(EnvelopeOut {
            id: row.get(0)?,
            from: row.get(1)?,
            to: row.get(2)?,
            payload_b64: row.get(3)?,
            ts: row.get(4)?,
            kind: row.get(5)?,
            sender_pub_key: row.get(6)?,
        });
    }

    // Delete-on-pull, softened: mark the mailbox items as delivered instead of
    // dropping them inside the same request. The pruner removes them an hour
    // later, which keeps a client that died mid-batch (or an old client that
    // reset its `since` cursor) from losing mail it never processed.
    // Newer clients call `/v1/ack` and the rows go immediately.
    let now = now_ms();
    for env in &envelopes {
        if env.to != node_id {
            continue; // broadcast: other nodes still need it
        }
        state
            .db
            .execute(
                r#"
                UPDATE envelopes
                SET delivered_at = ?1
                WHERE id = ?2 AND receiver_id = ?3 AND delivered_at = 0
                "#,
                params![now, env.id.clone(), node_id.clone()],
            )
            .await?;
    }

    Ok(Json(PullResponse { envelopes }))
}

/// `POST /v1/ack` — the addressee processed these envelopes; drop them now.
/// Only ever deletes from the caller's own mailbox.
pub async fn ack(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(body): Json<AckRequest>,
) -> Result<Json<AckResponse>, AppError> {
    let principal = require_active_node(&state, &headers).await?;
    if body.ids.len() > 500 {
        return Err(AppError::bad("too_many_ids"));
    }
    let mut deleted = 0u64;
    for id in body.ids {
        if id.is_empty() {
            continue;
        }
        deleted += state
            .db
            .execute(
                "DELETE FROM envelopes WHERE id = ?1 AND receiver_id = ?2",
                params![id, principal.node_id.clone()],
            )
            .await?;
    }
    Ok(Json(AckResponse { ok: true, deleted }))
}

fn resolve_sender_pub_key(
    jwt_node: &str,
    advertised: Option<String>,
    account_pub: &str,
) -> Result<String, AppError> {
    let advertised = advertised.unwrap_or_default();
    let advertised = advertised.trim();
    if !advertised.is_empty() {
        return match derive_node_id(advertised) {
            Ok(derived) if derived == jwt_node => Ok(advertised.to_string()),
            _ => Err(AppError::unauthorized("sender_pub_key_mismatch_jwt")),
        };
    }
    if account_pub.is_empty() {
        return Ok(String::new());
    }
    match derive_node_id(account_pub) {
        Ok(derived) if derived == jwt_node => Ok(account_pub.to_string()),
        _ => Ok(String::new()),
    }
}

async fn account_pubkey(state: &AppState, user_id: &str) -> Result<String, AppError> {
    let mut rows = state
        .db
        .query(
            "SELECT public_ble_key FROM users WHERE id = ?1",
            params![user_id.to_string()],
        )
        .await?;
    Ok(rows
        .next()
        .await?
        .map(|r| r.get::<String>(0))
        .transpose()?
        .unwrap_or_default())
}

async fn queue_depth(state: &AppState, receiver: &str) -> Result<i64, AppError> {
    let mut rows = state
        .db
        .query(
            "SELECT COUNT(*) FROM envelopes WHERE receiver_id = ?1 AND delivered_at = 0",
            params![receiver.to_string()],
        )
        .await?;
    let count: i64 = rows
        .next()
        .await?
        .map(|r| r.get::<i64>(0))
        .transpose()?
        .unwrap_or(0);
    Ok(count)
}

/// Retention: envelopes are transit state, not storage.
///  - delivered mailbox items go one hour after the pull;
///  - undelivered mailbox items live 12h;
///  - broadcast (no addressee to delete on) lives 6h.
pub async fn prune_old_envelopes(conn: &libsql::Connection) -> Result<u64, AppError> {
    let now = now_ms();
    let mut deleted = conn
        .execute(
            r#"
            DELETE FROM envelopes
            WHERE delivered_at > 0 AND delivered_at < ?1
            "#,
            params![now.saturating_sub(DELIVERED_GRACE_MS)],
        )
        .await?;
    deleted += conn
        .execute(
            r#"
            DELETE FROM envelopes
            WHERE created_at > 0
              AND created_at < ?1
              AND receiver_id IS NOT NULL
              AND receiver_id != ''
              AND receiver_id != '*'
            "#,
            params![now.saturating_sub(UNDELIVERED_RETENTION_MS)],
        )
        .await?;
    deleted += conn
        .execute(
            r#"
            DELETE FROM envelopes
            WHERE created_at > 0
              AND created_at < ?1
              AND (receiver_id IS NULL OR receiver_id = '' OR receiver_id = '*')
            "#,
            params![now.saturating_sub(BROADCAST_RETENTION_MS)],
        )
        .await?;
    Ok(deleted)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::Config;
    use crate::jwt_util::issue_token;
    use crate::rate_limit::RateLimitState;
    use axum::http::header::AUTHORIZATION;
    use std::sync::Arc;

    const SECRET: &str = "test-secret";
    // Two distinct base64 keys → two distinct derived node ids.
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
        crate::moderation::init_schema(&db).await.unwrap();
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

    fn node(key: &str) -> String {
        derive_node_id(key).unwrap()
    }

    fn envelope(id: &str, from: &str, to: Option<&str>) -> EnvelopeIn {
        EnvelopeIn {
            id: id.into(),
            from: from.into(),
            to: to.map(|s| s.to_string()),
            payload_b64: "cGF5bG9hZA==".into(),
            ts: Some(now_ms()),
            kind: Some("mesh_bytes".into()),
            sender_pub_key: None,
        }
    }

    async fn push_one(st: &AppState, h: &HeaderMap, env: EnvelopeIn) -> Result<u32, AppError> {
        push(
            State(st.clone()),
            h.clone(),
            Json(PushRequest {
                envelopes: vec![env],
            }),
        )
        .await
        .map(|r| r.0.accepted)
    }

    async fn pull_for(st: &AppState, h: &HeaderMap) -> Vec<EnvelopeOut> {
        pull(
            State(st.clone()),
            h.clone(),
            Query(PullQuery {
                node_id: None,
                since: Some(0),
            }),
        )
        .await
        .unwrap()
        .0
        .envelopes
    }

    #[tokio::test]
    async fn cannot_read_another_nodes_mailbox() {
        let st = state().await;
        let (a, b) = (auth(KEY_A, "user-a"), auth(KEY_B, "user-b"));
        push_one(&st, &a, envelope("m1", "", Some(&node(KEY_B))))
            .await
            .unwrap();

        // B is the addressee and sees it; A (the sender) and nobody else does.
        assert_eq!(pull_for(&st, &b).await.len(), 1);
        assert!(pull_for(&st, &a).await.is_empty());
    }

    #[tokio::test]
    async fn cannot_spoof_envelope_sender() {
        let st = state().await;
        let a = auth(KEY_A, "user-a");
        let err = push_one(&st, &a, envelope("m2", &node(KEY_B), Some("someone")))
            .await
            .unwrap_err();
        assert_eq!(err.message, "envelope_from_mismatch_jwt");
    }

    #[tokio::test]
    async fn cannot_pull_with_a_foreign_node_id() {
        let st = state().await;
        let a = auth(KEY_A, "user-a");
        let err = pull(
            State(st.clone()),
            a,
            Query(PullQuery {
                node_id: Some(node(KEY_B)),
                since: Some(0),
            }),
        )
        .await
        .unwrap_err();
        assert_eq!(err.message, "node_id_mismatch_jwt");
    }

    #[tokio::test]
    async fn cannot_register_another_nodes_identity() {
        let st = state().await;
        let a = auth(KEY_A, "user-a");
        // Claiming B's node id outright.
        let err = register(
            State(st.clone()),
            a.clone(),
            Json(RegisterRequest {
                node_id: Some(node(KEY_B)),
                nick: None,
                pubkey: Some(KEY_B.into()),
            }),
        )
        .await
        .unwrap_err();
        assert_eq!(err.message, "node_id_mismatch_jwt");

        // Own node id but someone else's pubkey — must not poison the directory.
        let err = register(
            State(st.clone()),
            a.clone(),
            Json(RegisterRequest {
                node_id: Some(node(KEY_A)),
                nick: None,
                pubkey: Some(KEY_B.into()),
            }),
        )
        .await
        .unwrap_err();
        assert_eq!(err.message, "pubkey_node_id_mismatch");
    }

    #[tokio::test]
    async fn ack_only_deletes_own_mail() {
        let st = state().await;
        let (a, b) = (auth(KEY_A, "user-a"), auth(KEY_B, "user-b"));
        push_one(&st, &a, envelope("m3", "", Some(&node(KEY_B))))
            .await
            .unwrap();

        // A tries to delete mail addressed to B.
        let deleted = ack(
            State(st.clone()),
            a.clone(),
            Json(AckRequest {
                ids: vec!["m3".into()],
            }),
        )
        .await
        .unwrap()
        .0
        .deleted;
        assert_eq!(deleted, 0);
        assert_eq!(pull_for(&st, &b).await.len(), 1);

        // B can.
        let deleted = ack(
            State(st.clone()),
            b.clone(),
            Json(AckRequest {
                ids: vec!["m3".into()],
            }),
        )
        .await
        .unwrap()
        .0
        .deleted;
        assert_eq!(deleted, 1);
        assert!(pull_for(&st, &b).await.is_empty());
    }

    #[tokio::test]
    async fn pull_marks_delivered_and_prune_keeps_undelivered() {
        let st = state().await;
        let (a, b) = (auth(KEY_A, "user-a"), auth(KEY_B, "user-b"));
        push_one(&st, &a, envelope("m4", "", Some(&node(KEY_B))))
            .await
            .unwrap();
        assert_eq!(pull_for(&st, &b).await.len(), 1);

        let mut rows = st
            .db
            .query(
                "SELECT delivered_at FROM envelopes WHERE id = 'm4'",
                (),
            )
            .await
            .unwrap();
        let delivered_at: i64 = rows.next().await.unwrap().unwrap().get(0).unwrap();
        assert!(delivered_at > 0, "pull must stamp delivered_at");

        // Inside the grace window nothing is dropped: a client that died
        // mid-batch can still re-pull with a reset cursor.
        assert_eq!(prune_old_envelopes(&st.db).await.unwrap(), 0);
        assert_eq!(pull_for(&st, &b).await.len(), 1);

        // Past the grace window it goes.
        st.db
            .execute(
                "UPDATE envelopes SET delivered_at = ?1 WHERE id = 'm4'",
                params![now_ms() - DELIVERED_GRACE_MS - 1_000],
            )
            .await
            .unwrap();
        assert_eq!(prune_old_envelopes(&st.db).await.unwrap(), 1);
        assert!(pull_for(&st, &b).await.is_empty());
    }

    #[tokio::test]
    async fn recipient_queue_is_capped() {
        let st = state().await;
        let a = auth(KEY_A, "user-a");
        let target = node(KEY_B);
        for i in 0..MAX_QUEUE_PER_RECIPIENT {
            st.db
                .execute(
                    r#"INSERT INTO envelopes (id, sender_id, receiver_id, payload, kind, created_at)
                       VALUES (?1, ?2, ?3, 'x', 'mesh_bytes', ?4)"#,
                    params![format!("seed-{i}"), node(KEY_A), target.clone(), now_ms()],
                )
                .await
                .unwrap();
        }
        let err = push_one(&st, &a, envelope("overflow", "", Some(&target)))
            .await
            .unwrap_err();
        assert_eq!(err.message, "recipient_queue_full");
    }

    #[tokio::test]
    async fn pull_carries_sender_pub_key_from_envelope() {
        let st = state().await;
        let (a, b) = (auth(KEY_A, "user-a"), auth(KEY_B, "user-b"));
        let mut env = envelope("m-key", "", Some(&node(KEY_B)));
        env.sender_pub_key = Some(KEY_A.into());
        push_one(&st, &a, env).await.unwrap();
        let got = pull_for(&st, &b).await;
        assert_eq!(got.len(), 1);
        assert_eq!(got[0].sender_pub_key, KEY_A);
        assert_eq!(got[0].from, node(KEY_A));
    }

    #[tokio::test]
    async fn pull_fills_sender_pub_key_from_account_if_omitted() {
        let st = state().await;
        st.db
            .execute(
                r#"INSERT INTO users (id, auth_method, auth_id, public_ble_key, created_at)
                   VALUES ('user-a', 'email', 'a@x', ?1, 1)"#,
                params![KEY_A],
            )
            .await
            .unwrap();
        let (a, b) = (auth(KEY_A, "user-a"), auth(KEY_B, "user-b"));
        push_one(&st, &a, envelope("m-fill", "", Some(&node(KEY_B))))
            .await
            .unwrap();
        let got = pull_for(&st, &b).await;
        assert_eq!(got[0].sender_pub_key, KEY_A);
    }

    #[tokio::test]
    async fn rejects_spoofed_sender_pub_key() {
        let st = state().await;
        let a = auth(KEY_A, "user-a");
        let mut env = envelope("m-spoof", "", Some(&node(KEY_B)));
        env.sender_pub_key = Some(KEY_B.into());
        let err = push_one(&st, &a, env).await.unwrap_err();
        assert_eq!(err.message, "sender_pub_key_mismatch_jwt");
    }

    #[tokio::test]
    async fn directory_returns_empty_roster() {
        let st = state().await;
        let a = auth(KEY_A, "user-a");
        st.db
            .execute(
                r#"INSERT INTO nodes (node_id, nick, pubkey, seen_at)
                   VALUES (?1, 'alice', ?2, 1)"#,
                params![node(KEY_A), KEY_A],
            )
            .await
            .unwrap();
        let resp = directory(State(st), a).await.unwrap().0;
        assert!(
            resp.nodes.is_empty(),
            "directory must not leak the mesh roster"
        );
    }

    #[tokio::test]
    async fn push_does_not_mark_mailbox_delivered() {
        let st = state().await;
        let (a, b) = (auth(KEY_A, "user-a"), auth(KEY_B, "user-b"));
        push_one(&st, &a, envelope("m-push", "", Some(&node(KEY_B))))
            .await
            .unwrap();
        let mut rows = st
            .db
            .query(
                "SELECT delivered_at FROM envelopes WHERE id = 'm-push'",
                (),
            )
            .await
            .unwrap();
        let delivered_at: i64 = rows.next().await.unwrap().unwrap().get(0).unwrap();
        assert_eq!(
            delivered_at, 0,
            "gateway accept is custody, not recipient ACK"
        );
        assert_eq!(pull_for(&st, &b).await.len(), 1);
    }
}

/// Drop moderation report plaintext older than 30 days.
pub async fn prune_old_reports(conn: &libsql::Connection) -> Result<u64, AppError> {
    const RETENTION_MS: i64 = 30 * 24 * 60 * 60 * 1000;
    let cutoff = now_ms().saturating_sub(RETENTION_MS);
    let changed = conn
        .execute(
            "DELETE FROM reports WHERE created_at > 0 AND created_at < ?1",
            params![cutoff],
        )
        .await?;
    Ok(changed)
}
