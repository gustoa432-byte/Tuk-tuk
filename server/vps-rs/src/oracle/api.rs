//! Oracle HTTP handlers: orbit sync + courier hints.

use axum::extract::State;
use axum::http::HeaderMap;
use axum::Json;
use serde::{Deserialize, Serialize};

use crate::oracle::auth::require_node;
use crate::oracle::domain::calculate_weight;
use crate::oracle::store::{self, OrbitIngestRow};
use crate::state::{now_ms, AppError, AppState};

#[derive(Debug, Deserialize)]
pub struct SyncRequest {
    pub orbits: Vec<OrbitDto>,
}

#[derive(Debug, Deserialize)]
pub struct OrbitDto {
    pub target_node: String,
    pub meet_count: i64,
    pub last_meet_at: i64,
}

#[derive(Debug, Serialize)]
pub struct SyncResponse {
    pub ok: bool,
    pub accepted: usize,
}

#[derive(Debug, Deserialize)]
pub struct HintRequest {
    pub target_node: String,
}

#[derive(Debug, Serialize)]
pub struct HintResponse {
    pub recommended_couriers: Vec<CourierHint>,
}

#[derive(Debug, Serialize)]
pub struct CourierHint {
    pub node_id: String,
    pub score: f64,
}

/// `POST /v1/oracle/sync` — ingest Journal A orbits for the JWT device.
pub async fn sync(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(body): Json<SyncRequest>,
) -> Result<Json<SyncResponse>, AppError> {
    let principal = require_node(&state, &headers)?;
    let source = principal.node_id;
    let now_secs = now_ms() / 1000;

    let rows: Vec<OrbitIngestRow> = body
        .orbits
        .into_iter()
        .filter_map(|o| {
            let target = o.target_node.trim().to_string();
            if target.is_empty() || target == source {
                return None;
            }
            Some(OrbitIngestRow {
                target_node: target,
                meet_count: o.meet_count.max(0),
                last_meet_at: normalize_unix_secs(o.last_meet_at),
            })
        })
        .collect();

    let accepted = store::upsert_orbits(&state.db, &source, &rows, now_secs).await?;
    Ok(Json(SyncResponse {
        ok: true,
        accepted,
    }))
}

/// `POST /v1/oracle/hint` — top-3 1st-degree couriers toward `target_node`.
pub async fn hint(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(body): Json<HintRequest>,
) -> Result<Json<HintResponse>, AppError> {
    let principal = require_node(&state, &headers)?;
    let sender = principal.node_id;
    let target = body.target_node.trim().to_string();
    if target.is_empty() {
        return Err(AppError::bad("target_node_required"));
    }
    if target == sender {
        return Err(AppError::bad("cannot_hint_self"));
    }

    let now_secs = now_ms() / 1000;
    let candidates = store::courier_candidates(&state.db, &sender, &target).await?;

    let mut scored: Vec<CourierHint> = candidates
        .into_iter()
        .filter(|c| c.courier_node != sender && c.courier_node != target)
        .map(|c| CourierHint {
            node_id: c.courier_node,
            score: calculate_weight(c.meet_count, c.last_meet_at, now_secs),
        })
        .filter(|h| h.score > 0.0)
        .collect();

    scored.sort_by(|a, b| {
        b.score
            .partial_cmp(&a.score)
            .unwrap_or(std::cmp::Ordering::Equal)
    });
    scored.truncate(3);

    Ok(Json(HintResponse {
        recommended_couriers: scored,
    }))
}

/// Android Journal A uses ms; domain/store use Unix seconds.
fn normalize_unix_secs(ts: i64) -> i64 {
    if ts > 10_000_000_000 {
        ts / 1000
    } else {
        ts
    }
}
