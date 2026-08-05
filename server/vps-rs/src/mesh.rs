//! Legacy mesh store-and-forward API (VpsBridge-compatible).

use axum::extract::{Query, State};
use axum::http::HeaderMap;
use axum::response::{IntoResponse, Response};
use axum::Json;
use libsql::params;
use serde::{Deserialize, Serialize};

use crate::moderation::reject_if_banned;
use crate::oracle::auth::require_node;
use crate::state::{now_ms, AppError, AppState};

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct RegisterRequest {
    node_id: Option<String>,
    nick: Option<String>,
    pubkey: Option<String>,
}

#[derive(Debug, Serialize)]
pub(crate) struct HealthResponse {
    ok: bool,
    nodes: i64,
    envelopes: i64,
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

async fn scalar_i64(conn: &libsql::Connection, sql: &str) -> Result<i64, AppError> {
    let mut rows = conn.query(sql, ()).await?;
    let row = rows.next().await?.ok_or_else(|| AppError::internal("empty scalar"))?;
    Ok(row.get::<i64>(0)?)
}

pub async fn health(State(state): State<AppState>) -> Result<Json<HealthResponse>, AppError> {
    Ok(Json(HealthResponse {
        ok: true,
        nodes: scalar_i64(&state.db, "SELECT COUNT(*) FROM nodes").await?,
        envelopes: scalar_i64(&state.db, "SELECT COUNT(*) FROM envelopes").await?,
    }))
}

pub async fn directory(State(state): State<AppState>) -> Result<Json<DirectoryResponse>, AppError> {
    let mut rows = state
        .db
        .query(
            "SELECT node_id, nick, pubkey, seen_at FROM nodes ORDER BY seen_at DESC",
            (),
        )
        .await?;
    let mut nodes = Vec::new();
    while let Some(row) = rows.next().await? {
        nodes.push(DirectoryNode {
            node_id: row.get(0)?,
            nick: row.get(1)?,
            pubkey: row.get(2)?,
            seen_at: row.get(3)?,
        });
    }
    Ok(Json(DirectoryResponse { nodes }))
}

pub async fn register(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(body): Json<RegisterRequest>,
) -> Result<Response, AppError> {
    let principal = require_node(&state, &headers)?;
    reject_if_banned(&state.db, &principal.node_id).await?;
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
                body.pubkey.unwrap_or_default(),
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
    let principal = require_node(&state, &headers)?;
    reject_if_banned(&state.db, &principal.node_id).await?;
    let mut accepted = 0u32;
    for env in body.envelopes {
        if env.id.is_empty() {
            continue;
        }
        // Bind sender to authenticated mesh device — no anonymous / spoofed from.
        let from = if env.from.is_empty() || env.from == principal.node_id {
            principal.node_id.clone()
        } else {
            return Err(AppError::unauthorized("envelope_from_mismatch_jwt"));
        };
        let receiver = env.to.unwrap_or_default();
        let receiver_sql: Option<String> = if receiver.is_empty() || receiver == "*" {
            None
        } else {
            Some(receiver)
        };
        let created_at = env.ts.filter(|t| *t > 0).unwrap_or_else(now_ms);
        let kind = env
            .kind
            .filter(|k| !k.is_empty())
            .unwrap_or_else(|| "mesh_bytes".into());

        let changed = state
            .db
            .execute(
                r#"
                INSERT OR IGNORE INTO envelopes
                    (id, sender_id, receiver_id, payload, kind, created_at)
                VALUES (?1, ?2, ?3, ?4, ?5, ?6)
                "#,
                params![
                    env.id,
                    from,
                    receiver_sql,
                    env.payload_b64,
                    kind,
                    created_at
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
    let principal = require_node(&state, &headers)?;
    reject_if_banned(&state.db, &principal.node_id).await?;
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
            SELECT id, sender_id, COALESCE(receiver_id, '*'), payload, created_at, kind
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
        });
    }
    Ok(Json(PullResponse { envelopes }))
}
