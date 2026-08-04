//! Oracle persistence: encounter graph tables on the shared libSQL file.

use libsql::Connection;

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
