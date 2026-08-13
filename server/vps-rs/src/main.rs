//! TukTuk VPS: mesh S&F + email/TG auth + BLE contact handshake.

mod auth;
mod config;
mod contacts;
mod db;
mod jwt_util;
mod mail;
mod mesh;
mod moderation;
mod node_id;
mod oracle;
mod rate_limit;
mod state;
mod telemetry;

use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::Arc;

use axum::http::{header, HeaderValue, Method};
use axum::routing::{get, post};
use axum::Router;
use libsql::Builder;
use tower_http::cors::{AllowOrigin, CorsLayer};
use tracing::info;

use crate::config::Config;
use crate::state::AppState;

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
    let cfg = Arc::new(Config::from_env());

    let db = open_db(&db_path).await?;
    db::init_schema(&db).await?;
    oracle::store::init_schema(&db).await?;
    moderation::init_schema(&db).await?;
    info!(%db_path, "libSQL ready (mesh + oracle + moderation)");
    if !cfg.smtp_ready() {
        if cfg.otp_dev_log {
            info!("SMTP not configured — OTP will log/return via TUKTUK_OTP_DEV_LOG=true");
        } else {
            tracing::warn!("SMTP not configured and TUKTUK_OTP_DEV_LOG=false — email OTP will fail until SMTP is set");
        }
    }
    if cfg.telegram_bot_token.is_none() {
        info!("TUKTUK_TELEGRAM_BOT_TOKEN unset — /auth/telegram disabled until set");
    }

    let state = AppState {
        db: Arc::new(db),
        cfg,
        rate_limits: Arc::new(rate_limit::RateLimitState::new()),
    };

    // Oracle retention: prune edges older than 30 days (once at boot + daily).
    // Mesh envelopes: drop older than 7 days to bound disk DoS.
    {
        let prune_db = Arc::clone(&state.db);
        tokio::spawn(async move {
            loop {
                let now_secs = crate::state::now_ms() / 1000;
                match oracle::store::prune_stale_edges(
                    &prune_db,
                    now_secs,
                    oracle::store::ORACLE_EDGE_RETENTION_SECS,
                )
                .await
                {
                    Ok(n) if n > 0 => {
                        info!(deleted = n, "oracle_edges pruned (retention 30d)")
                    }
                    Ok(_) => {}
                    Err(e) => tracing::warn!(error = %e.message, "oracle prune failed"),
                }
                match mesh::prune_old_envelopes(&prune_db).await {
                    Ok(n) if n > 0 => info!(deleted = n, "envelopes pruned (transit retention)"),
                    Ok(_) => {}
                    Err(e) => tracing::warn!(error = %e.message, "envelope prune failed"),
                }
                match jwt_util::prune_revocations(&prune_db).await {
                    Ok(n) if n > 0 => info!(deleted = n, "expired revocations pruned"),
                    Ok(_) => {}
                    Err(e) => tracing::warn!(error = %e.message, "revocation prune failed"),
                }
                match mesh::prune_old_reports(&prune_db).await {
                    Ok(n) if n > 0 => info!(deleted = n, "reports pruned (retention 30d)"),
                    Ok(_) => {}
                    Err(e) => tracing::warn!(error = %e.message, "report prune failed"),
                }
                tokio::time::sleep(std::time::Duration::from_secs(3_600)).await;
            }
        });
    }

    let cors = build_cors(&state.cfg);
    info!(origins = ?state.cfg.cors_origins, "CORS lockdown active");

    let app = Router::new()
        // Mesh (Android VpsBridge)
        .route("/v1/health", get(mesh::health))
        .route("/v1/directory", get(mesh::directory))
        .route("/v1/pull", get(mesh::pull))
        .route("/v1/register", post(mesh::register))
        .route("/v1/push", post(mesh::push))
        .route("/v1/ack", post(mesh::ack))
        // Auth
        .route("/auth/email/send", post(auth::email_send))
        .route("/auth/email/verify", post(auth::email_verify))
        .route("/auth/telegram", post(auth::telegram_auth))
        .route("/auth/refresh", post(auth::refresh))
        .route("/auth/logout", post(auth::logout))
        // Contacts / hidden BLE handshake
        .route("/contacts/add", post(contacts::add_contact))
        // Oracle — social-orbit ingest + courier hints
        .route("/v1/oracle/sync", post(oracle::api::sync))
        .route("/v1/oracle/hint", post(oracle::api::hint))
        // Moderation — reports + JWT-gated ban list
        .route("/v1/moderation/report", post(moderation::report))
        .route("/v1/moderation/blacklist", get(moderation::blacklist))
        .merge(telemetry::router())
        .layer(cors)
        .with_state(state);

    let addr: SocketAddr = format!("{host}:{port}").parse()?;
    info!(%addr, "TukTuk VPS listening");
    let listener = tokio::net::TcpListener::bind(addr).await?;
    axum::serve(listener, app).await?;
    Ok(())
}

fn build_cors(cfg: &Config) -> CorsLayer {
    let methods = [
        Method::GET,
        Method::POST,
        Method::OPTIONS,
    ];
    let headers = [
        header::AUTHORIZATION,
        header::CONTENT_TYPE,
        header::HeaderName::from_static("x-node-id"),
    ];
    let layer = CorsLayer::new()
        .allow_methods(methods)
        .allow_headers(headers);
    let origins: Vec<HeaderValue> = cfg
        .cors_origins
        .iter()
        .filter_map(|o| HeaderValue::from_str(o).ok())
        .collect();
    if origins.is_empty() {
        // No browser origins: do not reflect Allow-Origin (OkHttp unaffected).
        layer
    } else {
        layer.allow_origin(AllowOrigin::list(origins))
    }
}

async fn open_db(path: &str) -> Result<libsql::Connection, Box<dyn std::error::Error>> {
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
