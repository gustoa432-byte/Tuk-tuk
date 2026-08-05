//! User reports + global ban list (humanitarian moderation).
//!
//! Account bans (`banned_accounts.user_id` = JWT `sub`) survive BLE key rotation.
//! Node bans remain for mesh-local blacklist sync.

use axum::extract::State;
use axum::http::HeaderMap;
use axum::Json;
use libsql::params;
use serde::{Deserialize, Serialize};

use crate::node_id::derive_node_id;
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

/// Persist report / ban tables (idempotent).
pub async fn init_schema(conn: &libsql::Connection) -> Result<(), libsql::Error> {
    conn.execute_batch(
        r#"
        CREATE TABLE IF NOT EXISTS banned_nodes (
            node_id    TEXT PRIMARY KEY NOT NULL,
            reason     TEXT NOT NULL DEFAULT '',
            banned_at  INTEGER NOT NULL
        );

        CREATE TABLE IF NOT EXISTS banned_accounts (
            user_id    TEXT PRIMARY KEY NOT NULL,
            reason     TEXT NOT NULL DEFAULT '',
            banned_at  INTEGER NOT NULL
        );

        CREATE TABLE IF NOT EXISTS reports (
            id                 INTEGER PRIMARY KEY AUTOINCREMENT,
            reporter_jwt       TEXT NOT NULL DEFAULT '',
            reported_node_id   TEXT NOT NULL,
            message_content    TEXT NOT NULL,
            created_at         INTEGER NOT NULL
        );

        CREATE INDEX IF NOT EXISTS idx_reports_reported
            ON reports(reported_node_id, created_at);
        "#,
    )
    .await?;

    // Additive columns for existing DBs (ignore "duplicate column" failures).
    let _ = conn
        .execute(
            "ALTER TABLE reports ADD COLUMN reporter_user_id TEXT NOT NULL DEFAULT ''",
            (),
        )
        .await;
    let _ = conn
        .execute(
            "ALTER TABLE reports ADD COLUMN reporter_node_id TEXT NOT NULL DEFAULT ''",
            (),
        )
        .await;
    Ok(())
}

pub async fn is_node_banned(conn: &libsql::Connection, node_id: &str) -> Result<bool, AppError> {
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

pub async fn is_account_banned(conn: &libsql::Connection, user_id: &str) -> Result<bool, AppError> {
    let id = user_id.trim();
    if id.is_empty() {
        return Ok(false);
    }
    let mut rows = conn
        .query(
            "SELECT 1 FROM banned_accounts WHERE user_id = ?1 LIMIT 1",
            params![id],
        )
        .await?;
    Ok(rows.next().await?.is_some())
}

/// 403 when the account (`sub`) or mesh `node_id` is banned.
pub async fn reject_if_banned(
    conn: &libsql::Connection,
    user_id: &str,
    node_id: &str,
) -> Result<(), AppError> {
    if is_account_banned(conn, user_id).await? {
        return Err(AppError::forbidden("account_banned"));
    }
    if is_node_banned(conn, node_id).await? {
        return Err(AppError::forbidden("node_banned"));
    }
    Ok(())
}

/// `POST /v1/moderation/report` — JWT required; stores reporter ids only (never raw JWT).
pub async fn report(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(body): Json<ReportRequest>,
) -> Result<Json<ReportResponse>, AppError> {
    let principal = require_node(&state, &headers)?;
    reject_if_banned(&state.db, &principal.user_id, &principal.node_id).await?;

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

    let created_at = now_ms();
    state
        .db
        .execute(
            r#"
            INSERT INTO reports (
                reporter_jwt, reporter_user_id, reporter_node_id,
                reported_node_id, message_content, created_at
            )
            VALUES ('', ?1, ?2, ?3, ?4, ?5)
            "#,
            params![
                principal.user_id,
                principal.node_id,
                reported,
                content,
                created_at
            ],
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

/// `GET /v1/moderation/blacklist` — JSON array of banned mesh node ids
/// (`banned_nodes` ∪ derived ids from banned accounts' current BLE keys).
pub async fn blacklist(State(state): State<AppState>) -> Result<Json<Vec<String>>, AppError> {
    use std::collections::BTreeSet;

    let mut banned: BTreeSet<String> = BTreeSet::new();

    let mut rows = state
        .db
        .query(
            "SELECT node_id FROM banned_nodes ORDER BY banned_at DESC",
            (),
        )
        .await?;
    while let Some(row) = rows.next().await? {
        let id: String = row.get(0)?;
        if !id.is_empty() {
            banned.insert(id);
        }
    }

    let mut acc = state
        .db
        .query(
            r#"
            SELECT u.public_ble_key
            FROM banned_accounts b
            JOIN users u ON u.id = b.user_id
            "#,
            (),
        )
        .await?;
    while let Some(row) = acc.next().await? {
        let pk: String = row.get(0)?;
        if let Ok(nid) = derive_node_id(&pk) {
            banned.insert(nid);
        }
    }

    Ok(Json(banned.into_iter().collect()))
}
