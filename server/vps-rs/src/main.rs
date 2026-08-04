//! TukTuk VPS store-and-forward (phase 1): Axum + local libSQL.
//!
//! API is wire-compatible with the former Python MVP and Android `VpsBridge`:
//!   POST /v1/register
//!   GET  /v1/directory
//!   POST /v1/push
//!   GET  /v1/pull?nodeId=&since=
//!   GET  /v1/health

use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use axum::extract::{Query, State};
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use libsql::{params, Builder, Connection};
use serde::{Deserialize, Serialize};
use tower_http::cors::{Any, CorsLayer};
use tracing::{info, warn};

#[derive(Clone)]
struct AppState {
    db: Arc<Connection>,
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "tuktuk_vps=info,tower_http=info".into()),
        )
        .init();

    let host = std::env::var("TUKTUK_HOST").unwrap_or_else(|_| "0.0.0.0".into());
    let port: u16 = std::env::var("TUKTUK_PORT")
        .ok()
        .and_then(|p| p.parse().ok())
        .unwrap_or(8080);
    let db_path = std::env::var("TUKTUK_DB").unwrap_or_else(|_| "tuktuk.db".into());

    let state = AppState {
        db: Arc::new(open_db(&db_path).await?),
    };
    init_schema(&state.db).await?;
    info!(%db_path, "libSQL ready");

    let cors = CorsLayer::new()
        .allow_origin(Any)
        .allow_methods(Any)
        .allow_headers(Any);

    let app = Router::new()
        .route("/v1/health", get(health))
        .route("/v1/directory", get(directory))
        .route("/v1/pull", get(pull))
        .route("/v1/register", post(register))
        .route("/v1/push", post(push))
        .layer(cors)
        .with_state(state);

    let addr: SocketAddr = format!("{host}:{port}").parse()?;
    info!(%addr, "TukTuk VPS listening");
    let listener = tokio::net::TcpListener::bind(addr).await?;
    axum::serve(listener, app).await?;
    Ok(())
}

async fn open_db(path: &str) -> Result<Connection, Box<dyn std::error::Error>> {
    if path != ":memory:" {
        if let Some(parent) = PathBuf::from(path).parent() {
            if !parent.as_os_str().is_empty() {
                std::fs::create_dir_all(parent)?;
            }
        }
    }
    let db = Builder::new_local(path).build().await?;
    Ok(db.connect()?)
}

/// Create tables on every start (idempotent).
async fn init_schema(conn: &Connection) -> Result<(), libsql::Error> {
    conn.execute_batch(
        r#"
        CREATE TABLE IF NOT EXISTS nodes (
            node_id  TEXT PRIMARY KEY NOT NULL,
            nick     TEXT NOT NULL DEFAULT '',
            pubkey   TEXT NOT NULL DEFAULT '',
            seen_at  INTEGER NOT NULL
        );

        CREATE TABLE IF NOT EXISTS envelopes (
            id           TEXT PRIMARY KEY NOT NULL,
            sender_id    TEXT NOT NULL,
            receiver_id  TEXT,
            payload      TEXT NOT NULL,
            kind         TEXT NOT NULL DEFAULT 'mesh_bytes',
            created_at   INTEGER NOT NULL
        );

        CREATE INDEX IF NOT EXISTS idx_envelopes_created_at
            ON envelopes(created_at);

        CREATE INDEX IF NOT EXISTS idx_envelopes_pull
            ON envelopes(created_at, receiver_id);
        "#,
    )
    .await?;
    Ok(())
}

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

// ── Wire types (camelCase, compatible with Android VpsBridge) ───────────────

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RegisterRequest {
    node_id: Option<String>,
    nick: Option<String>,
    pubkey: Option<String>,
}

#[derive(Debug, Serialize)]
struct HealthResponse {
    ok: bool,
    nodes: i64,
    envelopes: i64,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct DirectoryNode {
    node_id: String,
    nick: String,
    pubkey: String,
    seen_at: i64,
}

#[derive(Debug, Serialize)]
struct DirectoryResponse {
    nodes: Vec<DirectoryNode>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct EnvelopeIn {
    id: String,
    from: String,
    to: Option<String>,
    #[serde(alias = "payload")]
    payload_b64: String,
    ts: Option<i64>,
    kind: Option<String>,
}

#[derive(Debug, Deserialize)]
struct PushRequest {
    envelopes: Vec<EnvelopeIn>,
}

#[derive(Debug, Serialize)]
struct PushResponse {
    ok: bool,
    accepted: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct EnvelopeOut {
    id: String,
    from: String,
    to: String,
    payload_b64: String,
    ts: i64,
    kind: String,
}

#[derive(Debug, Serialize)]
struct PullResponse {
    envelopes: Vec<EnvelopeOut>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PullQuery {
    node_id: Option<String>,
    since: Option<i64>,
}

#[derive(Debug, Serialize)]
struct ErrorBody {
    error: String,
}

// ── Handlers ────────────────────────────────────────────────────────────────

async fn health(State(state): State<AppState>) -> Result<Json<HealthResponse>, AppError> {
    let node_count = scalar_i64(&state.db, "SELECT COUNT(*) FROM nodes").await?;
    let env_count = scalar_i64(&state.db, "SELECT COUNT(*) FROM envelopes").await?;
    Ok(Json(HealthResponse {
        ok: true,
        nodes: node_count,
        envelopes: env_count,
    }))
}

async fn scalar_i64(conn: &Connection, sql: &str) -> Result<i64, AppError> {
    let mut rows = conn.query(sql, ()).await?;
    let row = rows.next().await?.ok_or("empty scalar result")?;
    Ok(row.get::<i64>(0)?)
}

async fn directory(State(state): State<AppState>) -> Result<Json<DirectoryResponse>, AppError> {
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
            node_id: row.get::<String>(0)?,
            nick: row.get::<String>(1)?,
            pubkey: row.get::<String>(2)?,
            seen_at: row.get::<i64>(3)?,
        });
    }
    Ok(Json(DirectoryResponse { nodes }))
}

async fn register(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(body): Json<RegisterRequest>,
) -> Result<Response, AppError> {
    let header_id = headers
        .get("X-Node-Id")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .to_string();
    let node_id = body
        .node_id
        .filter(|s| !s.is_empty())
        .or_else(|| (!header_id.is_empty()).then_some(header_id));

    let Some(node_id) = node_id else {
        return Ok((
            StatusCode::BAD_REQUEST,
            Json(ErrorBody {
                error: "nodeId_required".into(),
            }),
        )
            .into_response());
    };

    let nick = body.nick.unwrap_or_default();
    let pubkey = body.pubkey.unwrap_or_default();
    let seen_at = now_ms();

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
            params![node_id, nick, pubkey, seen_at],
        )
        .await?;

    Ok(Json(serde_json::json!({ "ok": true })).into_response())
}

async fn push(
    State(state): State<AppState>,
    Json(body): Json<PushRequest>,
) -> Result<Json<PushResponse>, AppError> {
    let mut accepted = 0u32;
    for env in body.envelopes {
        if env.id.is_empty() {
            continue;
        }
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

        // Dedup by primary key.
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
                    env.from,
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

async fn pull(
    State(state): State<AppState>,
    Query(q): Query<PullQuery>,
) -> Result<Json<PullResponse>, AppError> {
    let node_id = q.node_id.unwrap_or_default();
    let since = q.since.unwrap_or(0);

    // Mirror Python MVP filter:
    //   created_at > since
    //   sender != requester
    //   receiver is NULL / '' / '*' / requester
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
            id: row.get::<String>(0)?,
            from: row.get::<String>(1)?,
            to: row.get::<String>(2)?,
            payload_b64: row.get::<String>(3)?,
            ts: row.get::<i64>(4)?,
            kind: row.get::<String>(5)?,
        });
    }

    Ok(Json(PullResponse { envelopes }))
}

// ── Errors ──────────────────────────────────────────────────────────────────

struct AppError(String);

impl From<libsql::Error> for AppError {
    fn from(e: libsql::Error) -> Self {
        warn!(error = %e, "libsql error");
        AppError(e.to_string())
    }
}

impl From<&'static str> for AppError {
    fn from(e: &'static str) -> Self {
        AppError(e.to_string())
    }
}

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(ErrorBody { error: self.0 }),
        )
            .into_response()
    }
}
