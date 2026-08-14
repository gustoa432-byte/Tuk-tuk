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
            created_at   INTEGER NOT NULL,
            -- ms when the addressee pulled it; 0 = still undelivered.
            -- Delivered rows are dropped quickly (see mesh::prune_old_envelopes).
            delivered_at INTEGER NOT NULL DEFAULT 0
        );

        CREATE INDEX IF NOT EXISTS idx_envelopes_created_at
            ON envelopes(created_at);

        CREATE INDEX IF NOT EXISTS idx_envelopes_pull
            ON envelopes(created_at, receiver_id);

        CREATE INDEX IF NOT EXISTS idx_envelopes_mailbox
            ON envelopes(receiver_id, created_at);

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

        -- Every BLE key that ever authenticated for an account.
        -- Re-auth adds a device row; it never silently rewrites
        -- users.public_ble_key (that would be an identity takeover path).
        CREATE TABLE IF NOT EXISTS user_devices (
            user_id         TEXT NOT NULL,
            public_ble_key  TEXT NOT NULL,
            node_id         TEXT NOT NULL DEFAULT '',
            first_seen      INTEGER NOT NULL,
            last_seen       INTEGER NOT NULL,
            PRIMARY KEY (user_id, public_ble_key)
        );

        -- Revocation hook for issued JWTs (single token by jti).
        CREATE TABLE IF NOT EXISTS revoked_tokens (
            jti         TEXT PRIMARY KEY NOT NULL,
            user_id     TEXT NOT NULL DEFAULT '',
            revoked_at  INTEGER NOT NULL
        );

        -- "Log out everywhere": rejects any token issued before not_before.
        -- Covers legacy tokens that carry no jti at all.
        CREATE TABLE IF NOT EXISTS token_epochs (
            user_id     TEXT PRIMARY KEY NOT NULL,
            not_before  INTEGER NOT NULL
        );
        "#,
    )
    .await?;

    // Additive columns for databases created before these fields existed.
    // libSQL has no "ADD COLUMN IF NOT EXISTS" — a duplicate column error is
    // the expected no-op on an already-migrated deployment.
    let _ = conn
        .execute(
            "ALTER TABLE envelopes ADD COLUMN delivered_at INTEGER NOT NULL DEFAULT 0",
            (),
        )
        .await;
    let _ = conn
        .execute(
            "ALTER TABLE envelopes ADD COLUMN sender_pub_key TEXT NOT NULL DEFAULT ''",
            (),
        )
        .await;
    let _ = conn
        .execute("ALTER TABLE users ADD COLUMN username TEXT", ())
        .await;
    let _ = conn
        .execute(
            "ALTER TABLE users ADD COLUMN username_changed_at INTEGER NOT NULL DEFAULT 0",
            (),
        )
        .await;
    let _ = conn
        .execute(
            r#"
            CREATE UNIQUE INDEX IF NOT EXISTS idx_users_username
            ON users(username)
            WHERE username IS NOT NULL AND username != ''
            "#,
            (),
        )
        .await;
    // SHA-256 hex of E.164 only — never a raw phone number.
    let _ = conn
        .execute(
            "ALTER TABLE users ADD COLUMN phone_hash TEXT NOT NULL DEFAULT ''",
            (),
        )
        .await;
    let _ = conn
        .execute(
            r#"
            CREATE UNIQUE INDEX IF NOT EXISTS idx_users_phone_hash
            ON users(phone_hash)
            WHERE phone_hash IS NOT NULL AND phone_hash != ''
            "#,
            (),
        )
        .await;

    Ok(())
}
