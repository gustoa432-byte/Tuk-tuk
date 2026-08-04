//! Oracle persistence: encounter graph tables on the shared libSQL file.

use libsql::{params, Connection};

use crate::state::AppError;

/// Create Oracle tables (idempotent). Safe to call on every boot.
pub async fn init_schema(conn: &Connection) -> Result<(), libsql::Error> {
    conn.execute_batch(
        r#"
        CREATE TABLE IF NOT EXISTS oracle_nodes (
            node_id       TEXT PRIMARY KEY NOT NULL,
            last_seen_at  INTEGER NOT NULL
        );

        CREATE TABLE IF NOT EXISTS oracle_edges (
            source_node   TEXT NOT NULL,
            target_node   TEXT NOT NULL,
            meet_count    INTEGER NOT NULL DEFAULT 0,
            last_meet_at  INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (source_node, target_node)
        );

        CREATE INDEX IF NOT EXISTS idx_oracle_edges_source
            ON oracle_edges(source_node);

        CREATE INDEX IF NOT EXISTS idx_oracle_edges_target
            ON oracle_edges(target_node);
        "#,
    )
    .await?;
    Ok(())
}

#[derive(Debug, Clone)]
pub struct OrbitIngestRow {
    pub target_node: String,
    pub meet_count: i64,
    pub last_meet_at: i64,
}

#[derive(Debug, Clone)]
pub struct CourierCandidate {
    pub courier_node: String,
    pub meet_count: i64,
    pub last_meet_at: i64,
}

/// Upsert Journal A orbits for `source_node`.
/// Edge meet_count / last_meet_at update only when incoming meet_count is **greater**.
pub async fn upsert_orbits(
    conn: &Connection,
    source_node: &str,
    orbits: &[OrbitIngestRow],
    now_secs: i64,
) -> Result<usize, AppError> {
    conn.execute(
        r#"
        INSERT INTO oracle_nodes (node_id, last_seen_at)
        VALUES (?1, ?2)
        ON CONFLICT(node_id) DO UPDATE SET last_seen_at = excluded.last_seen_at
        "#,
        params![source_node, now_secs],
    )
    .await?;

    let mut accepted = 0usize;
    for row in orbits {
        conn.execute(
            r#"
            INSERT INTO oracle_edges (source_node, target_node, meet_count, last_meet_at)
            VALUES (?1, ?2, ?3, ?4)
            ON CONFLICT(source_node, target_node) DO UPDATE SET
                meet_count = CASE
                    WHEN excluded.meet_count > oracle_edges.meet_count
                    THEN excluded.meet_count
                    ELSE oracle_edges.meet_count
                END,
                last_meet_at = CASE
                    WHEN excluded.meet_count > oracle_edges.meet_count
                    THEN excluded.last_meet_at
                    ELSE oracle_edges.last_meet_at
                END
            "#,
            params![
                source_node,
                row.target_node.as_str(),
                row.meet_count,
                row.last_meet_at
            ],
        )
        .await?;
        accepted += 1;
    }
    Ok(accepted)
}

/// 1st-degree couriers: neighbors of `sender` who have an edge to `target`.
pub async fn courier_candidates(
    conn: &Connection,
    sender_node: &str,
    target_node: &str,
) -> Result<Vec<CourierCandidate>, AppError> {
    let mut rows = conn
        .query(
            r#"
            SELECT e1.target_node, e2.meet_count, e2.last_meet_at
            FROM oracle_edges e1
            INNER JOIN oracle_edges e2
                ON e2.source_node = e1.target_node
               AND e2.target_node = ?2
            WHERE e1.source_node = ?1
              AND e1.target_node != ?2
            "#,
            params![sender_node, target_node],
        )
        .await?;

    let mut out = Vec::new();
    while let Some(row) = rows.next().await? {
        let courier_node: String = row.get(0)?;
        let meet_count: i64 = row.get(1)?;
        let last_meet_at: i64 = row.get(2)?;
        out.push(CourierCandidate {
            courier_node,
            meet_count,
            last_meet_at,
        });
    }
    Ok(out)
}
