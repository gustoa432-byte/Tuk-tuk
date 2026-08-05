//! Telemetry ZIP upload → Telegram Bot API proxy (no disk persistence).

use axum::extract::{DefaultBodyLimit, Multipart, State};
use axum::http::HeaderMap;
use axum::routing::post;
use axum::{Json, Router};
use serde::Serialize;
use tracing::{info, warn};

use crate::oracle::auth::require_node;
use crate::rate_limit::client_ip;
use crate::state::{AppError, AppState};

const MAX_ZIP_BYTES: usize = 8 * 1024 * 1024;

#[derive(Debug, Serialize)]
pub struct UploadResponse {
    pub ok: bool,
    pub telegram_message_id: Option<i64>,
}

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/v1/telemetry/upload", post(upload))
        .layer(DefaultBodyLimit::max(MAX_ZIP_BYTES + 64 * 1024))
}

/// `POST /v1/telemetry/upload` — multipart fields:
/// - `file` / `document` / `zip` — ZIP bytes
/// - `nodeId` (optional; JWT wins)
/// - `appVersion` (optional caption)
pub async fn upload(
    State(state): State<AppState>,
    headers: HeaderMap,
    mut multipart: Multipart,
) -> Result<Json<UploadResponse>, AppError> {
    let principal = require_node(&state, &headers)?;
    let ip = client_ip(&headers);
    state
        .rate_limits
        .check_telemetry(&principal.node_id, &ip)?;

    let bot_token = state
        .cfg
        .telegram_bot_token
        .as_ref()
        .ok_or_else(|| AppError::internal("telegram_bot_token_not_configured"))?;
    let chat_id = state
        .cfg
        .telegram_feedback_chat_id
        .as_ref()
        .ok_or_else(|| AppError::internal("telegram_feedback_chat_id_not_configured"))?;

    let mut zip_bytes: Option<bytes::Bytes> = None;
    let mut zip_name = format!("tuktuk_{}.zip", principal.node_id);
    let mut app_version = String::new();
    let mut note = String::new();

    while let Some(field) = multipart
        .next_field()
        .await
        .map_err(|e| AppError::bad(format!("multipart: {e}")))?
    {
        let name = field.name().unwrap_or("").to_string();
        let file_name = field.file_name().map(|s| s.to_string());
        let data = field
            .bytes()
            .await
            .map_err(|e| AppError::bad(format!("multipart_read: {e}")))?;

        match name.as_str() {
            "file" | "document" | "zip" => {
                if data.len() > MAX_ZIP_BYTES {
                    return Err(AppError::bad("zip_too_large"));
                }
                if data.is_empty() {
                    return Err(AppError::bad("zip_empty"));
                }
                // ZIP local header magic
                if data.len() < 4 || &data[0..2] != b"PK" {
                    return Err(AppError::bad("not_a_zip"));
                }
                if let Some(fnm) = file_name {
                    let safe: String = fnm
                        .chars()
                        .filter(|c| c.is_ascii_alphanumeric() || *c == '.' || *c == '_' || *c == '-')
                        .take(80)
                        .collect();
                    if safe.ends_with(".zip") {
                        zip_name = safe;
                    }
                }
                zip_bytes = Some(data);
            }
            "appVersion" | "app_version" => {
                app_version = String::from_utf8_lossy(&data).trim().chars().take(64).collect();
            }
            "note" | "description" => {
                note = String::from_utf8_lossy(&data).trim().chars().take(500).collect();
            }
            _ => {}
        }
    }

    let zip = zip_bytes.ok_or_else(|| AppError::bad("zip_required"))?;
    let caption = format!(
        "TukTuk telemetry\nnodeId={}\nappVersion={}\n{}",
        principal.node_id,
        if app_version.is_empty() {
            "?"
        } else {
            app_version.as_str()
        },
        note
    )
    .trim()
    .chars()
    .take(1024)
    .collect::<String>();

    let message_id =
        forward_zip_to_telegram(bot_token, chat_id, &zip_name, zip, &caption).await?;
    info!(
        node_id = %principal.node_id,
        telegram_message_id = ?message_id,
        "telemetry zip proxied to Telegram"
    );
    Ok(Json(UploadResponse {
        ok: true,
        telegram_message_id: message_id,
    }))
}

async fn forward_zip_to_telegram(
    bot_token: &str,
    chat_id: &str,
    filename: &str,
    zip: bytes::Bytes,
    caption: &str,
) -> Result<Option<i64>, AppError> {
    let url = format!("https://api.telegram.org/bot{bot_token}/sendDocument");
    let part = reqwest::multipart::Part::bytes(zip.to_vec())
        .file_name(filename.to_string())
        .mime_str("application/zip")
        .map_err(|e| AppError::internal(format!("mime: {e}")))?;
    let form = reqwest::multipart::Form::new()
        .text("chat_id", chat_id.to_string())
        .text("caption", caption.to_string())
        .part("document", part);

    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(45))
        .build()
        .map_err(|e| AppError::internal(format!("http_client: {e}")))?;

    let resp = client
        .post(&url)
        .multipart(form)
        .send()
        .await
        .map_err(|e| {
            warn!(error = %e, "telegram sendDocument failed");
            AppError::internal("telegram_send_failed")
        })?;

    let status = resp.status();
    let body = resp
        .text()
        .await
        .unwrap_or_default();
    if !status.is_success() {
        warn!(%status, body = %body.chars().take(300).collect::<String>(), "telegram API error");
        return Err(AppError::internal("telegram_api_error"));
    }

    let parsed: serde_json::Value =
        serde_json::from_str(&body).unwrap_or_else(|_| serde_json::json!({}));
    let msg_id = parsed
        .pointer("/result/message_id")
        .and_then(|v| v.as_i64());
    Ok(msg_id)
}
