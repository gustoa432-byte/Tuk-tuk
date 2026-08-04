//! Schema init for mesh + auth + contacts.
//! Oracle tables live in [`crate::oracle::store`].

use libsql::Connection;

pub async fn init_schema(conn: &Connection) -> Result<(), libsql::Error> {
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

        CREATE TABLE IF NOT EXISTS users (
            id              TEXT PRIMARY KEY NOT NULL,
            auth_method     TEXT NOT NULL,
            auth_id         TEXT NOT NULL,
            public_ble_key  TEXT NOT NULL DEFAULT '',
            created_at      INTEGER NOT NULL,
            UNIQUE(auth_method, auth_id)
        );

        CREATE INDEX IF NOT EXISTS idx_users_ble
            ON users(public_ble_key);

        CREATE TABLE IF NOT EXISTS contacts (
            user_id_1   TEXT NOT NULL,
            user_id_2   TEXT NOT NULL,
            created_at  INTEGER NOT NULL,
            PRIMARY KEY (user_id_1, user_id_2)
        );

        CREATE TABLE IF NOT EXISTS email_otps (
            email       TEXT PRIMARY KEY NOT NULL,
            code        TEXT NOT NULL,
            expires_at  INTEGER NOT NULL
        );
        "#,
    )
    .await?;
    Ok(())
}
