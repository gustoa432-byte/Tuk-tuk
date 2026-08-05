//! User reports + global ban list (humanitarian moderation).

use axum::extract::State;
use axum::http::HeaderMap;
use axum::Json;
use libsql::params;
use serde::{Deserialize, Serialize};

use crate::jwt_util::{bearer_from_header, verify_token};
use crate::oracle::auth::require_node;
use crate::state::{now_ms, AppError, AppState};

#[derive(Debug, Deserialize)]
pub struct ReportRequest {
    pub reported_node_id: String,
    /// Decrypted plaintext the reporter chose to submit (E2E stays on-device until they report).
    pub decrypted_message_content: String,
}

#[derive(Debug, Serialize)]
pub struct ReportResponse {
    pub ok: bool,
    pub id: i64,
}

/// Persist report tables (idempotent).
pub async fn init_schema(conn: &libsql::Connection) -> Result<(), libsql::Error> {
    conn.execute_batch(
        r#"
        CREATE TABLE IF NOT EXISTS banned_nodes (
            node_id    TEXT PRIMARY KEY NOT NULL,
            reason     TEXT NOT NULL DEFAULT '',
            banned_at  INTEGER NOT NULL
        );

        CREATE TABLE IF NOT EXISTS reports (
            id                 INTEGER PRIMARY KEY AUTOINCREMENT,
            reporter_jwt       TEXT NOT NULL,
            reported_node_id   TEXT NOT NULL,
            message_content    TEXT NOT NULL,
            created_at         INTEGER NOT NULL
        );

        CREATE INDEX IF NOT EXISTS idx_reports_reported
            ON reports(reported_node_id, created_at);
        "#,
    )
    .await?;
    Ok(())
}

pub async fn is_banned(conn: &libsql::Connection, node_id: &str) -> Result<bool, AppError> {
    let id = node_id.trim();
    if id.is_empty() {
        return Ok(false);
    }
    let mut rows = conn
        .query(
            "SELECT 1 FROM banned_nodes WHERE node_id = ?1 LIMIT 1",
            params![id],
        )
        .await?;
    Ok(rows.next().await?.is_some())
}

/// 403 when [node_id] is on the global ban list.
pub async fn reject_if_banned(conn: &libsql::Connection, node_id: &str) -> Result<(), AppError> {
    if is_banned(conn, node_id).await? {
        return Err(AppError::forbidden("node_banned"));
    }
    Ok(())
}

/// `POST /v1/moderation/report` — JWT required; stores reporter token + content.
pub async fn report(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(body): Json<ReportRequest>,
) -> Result<Json<ReportResponse>, AppError> {
    let principal = require_node(&state, &headers)?;
    reject_if_banned(&state.db, &principal.node_id).await?;

    let reported = body.reported_node_id.trim().to_string();
    if reported.is_empty() {
        return Err(AppError::bad("reported_node_id_required"));
    }
    if reported == principal.node_id {
        return Err(AppError::bad("cannot_report_self"));
    }
    let content = body.decrypted_message_content;
    if content.len() > 8_192 {
        return Err(AppError::bad("message_content_too_long"));
    }

    let auth = headers
        .get(axum::http::header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok());
    let token = bearer_from_header(auth)?.to_string();
    let _ = verify_token(&state.cfg.jwt_secret, &token)?;

    let created_at = now_ms();
    state
        .db
        .execute(
            r#"
            INSERT INTO reports (reporter_jwt, reported_node_id, message_content, created_at)
            VALUES (?1, ?2, ?3, ?4)
            "#,
            params![token, reported, content, created_at],
        )
        .await?;

    let mut rows = state.db.query("SELECT last_insert_rowid()", ()).await?;
    let id: i64 = rows
        .next()
        .await?
        .map(|r| r.get::<i64>(0))
        .transpose()?
        .unwrap_or(0);

    Ok(Json(ReportResponse { ok: true, id }))
}

/// `GET /v1/moderation/blacklist` — JSON array of banned mesh node ids.
pub async fn blacklist(State(state): State<AppState>) -> Result<Json<Vec<String>>, AppError> {
    let mut rows = state
        .db
        .query(
            "SELECT node_id FROM banned_nodes ORDER BY banned_at DESC",
            (),
        )
        .await?;
    let mut banned = Vec::new();
    while let Some(row) = rows.next().await? {
        banned.push(row.get::<String>(0)?);
    }
    Ok(Json(banned))
}
