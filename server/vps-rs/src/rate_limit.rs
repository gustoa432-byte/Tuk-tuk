//! In-memory sliding-window rate limits (OTP + push + refresh + oracle + report).
//!
//! Keys are opaque strings (email / node_id / client IP). Process-local only —
//! fine for single-VPS TukTuk; multi-instance would need Redis later.

use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use axum::http::HeaderMap;

use crate::state::AppError;

#[derive(Clone, Copy)]
struct Window {
    /// Max events inside [now - period, now].
    max: u32,
    period: Duration,
}

const OTP_SEND_PER_EMAIL: Window = Window {
    max: 5,
    period: Duration::from_secs(15 * 60),
};
const OTP_SEND_PER_IP: Window = Window {
    max: 30,
    period: Duration::from_secs(60 * 60),
};
const OTP_VERIFY_PER_EMAIL: Window = Window {
    max: 10,
    period: Duration::from_secs(15 * 60),
};
const OTP_VERIFY_PER_IP: Window = Window {
    max: 60,
    period: Duration::from_secs(15 * 60),
};
const PUSH_PER_NODE: Window = Window {
    max: 60,
    period: Duration::from_secs(60),
};
const PUSH_PER_IP: Window = Window {
    max: 120,
    period: Duration::from_secs(60),
};
const TELEMETRY_PER_NODE: Window = Window {
    max: 3,
    period: Duration::from_secs(60 * 60),
};
const TELEMETRY_PER_IP: Window = Window {
    max: 6,
    period: Duration::from_secs(60 * 60),
};
const REFRESH_PER_USER: Window = Window {
    max: 30,
    period: Duration::from_secs(60 * 60),
};
const REFRESH_PER_IP: Window = Window {
    max: 60,
    period: Duration::from_secs(60 * 60),
};
const ORACLE_PER_NODE: Window = Window {
    max: 30,
    period: Duration::from_secs(60),
};
const ORACLE_PER_IP: Window = Window {
    max: 60,
    period: Duration::from_secs(60),
};
const REPORT_PER_NODE: Window = Window {
    max: 10,
    period: Duration::from_secs(60 * 60),
};
/// Deliberately loose: clients ≤ 0.1.116 re-register on every 12s sync tick
/// (≈300/h). The endpoint cannot create foreign rows, so this only bounds
/// request volume; newer builds register every 10 minutes.
const REGISTER_PER_NODE: Window = Window {
    max: 600,
    period: Duration::from_secs(60 * 60),
};
const REGISTER_PER_IP: Window = Window {
    max: 1_200,
    period: Duration::from_secs(60 * 60),
};
/// Contact lookups are the only "who is this UUID" surface — keep them scarce.
const CONTACTS_PER_USER: Window = Window {
    max: 60,
    period: Duration::from_secs(60 * 60),
};
const CONTACTS_PER_IP: Window = Window {
    max: 120,
    period: Duration::from_secs(60 * 60),
};
const TELEGRAM_AUTH_PER_IP: Window = Window {
    max: 30,
    period: Duration::from_secs(15 * 60),
};
/// Exact username lookup is the only internet find — bound guessing.
const LOOKUP_PER_USER: Window = Window {
    max: 30,
    period: Duration::from_secs(60 * 60),
};
const LOOKUP_PER_IP: Window = Window {
    max: 60,
    period: Duration::from_secs(60 * 60),
};
const USERNAME_CLAIM_PER_USER: Window = Window {
    max: 8,
    period: Duration::from_secs(60 * 60),
};
const USERNAME_CLAIM_PER_IP: Window = Window {
    max: 20,
    period: Duration::from_secs(60 * 60),
};
/// Phone-hash lookup is batched (up to 200 hashes) — stricter than username.
const PHONE_LOOKUP_PER_USER: Window = Window {
    max: 10,
    period: Duration::from_secs(60 * 60),
};
const PHONE_LOOKUP_PER_IP: Window = Window {
    max: 20,
    period: Duration::from_secs(60 * 60),
};
const PHONE_CLAIM_PER_USER: Window = Window {
    max: 8,
    period: Duration::from_secs(60 * 60),
};
const PHONE_CLAIM_PER_IP: Window = Window {
    max: 20,
    period: Duration::from_secs(60 * 60),
};

#[derive(Default)]
struct Bucket {
    hits: Vec<Instant>,
}

impl Bucket {
    fn allow(&mut self, now: Instant, window: Window) -> bool {
        let cutoff = now.checked_sub(window.period).unwrap_or(now);
        self.hits.retain(|t| *t > cutoff);
        if self.hits.len() as u32 >= window.max {
            return false;
        }
        self.hits.push(now);
        true
    }

    fn is_empty_at(&self, now: Instant) -> bool {
        let cutoff = now.checked_sub(Duration::from_secs(2 * 60 * 60)).unwrap_or(now);
        !self.hits.iter().any(|t| *t > cutoff)
    }
}

#[derive(Default)]
pub struct RateLimitState {
    inner: Mutex<HashMap<String, Bucket>>,
}

impl RateLimitState {
    pub fn new() -> Self {
        Self::default()
    }

    fn check(&self, key: &str, window: Window) -> Result<(), AppError> {
        let now = Instant::now();
        let mut map = self
            .inner
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        // Opportunistic GC of stale buckets.
        if map.len() > 10_000 {
            map.retain(|_, b| !b.is_empty_at(now));
        }
        let bucket = map.entry(key.to_string()).or_default();
        if bucket.allow(now, window) {
            Ok(())
        } else {
            Err(AppError::too_many("rate_limited"))
        }
    }

    pub fn check_otp_send(&self, email: &str, ip: &str) -> Result<(), AppError> {
        self.check(&format!("otp_send:email:{email}"), OTP_SEND_PER_EMAIL)?;
        self.check(&format!("otp_send:ip:{ip}"), OTP_SEND_PER_IP)?;
        Ok(())
    }

    pub fn check_otp_verify(&self, email: &str, ip: &str) -> Result<(), AppError> {
        self.check(&format!("otp_verify:email:{email}"), OTP_VERIFY_PER_EMAIL)?;
        self.check(&format!("otp_verify:ip:{ip}"), OTP_VERIFY_PER_IP)?;
        Ok(())
    }

    pub fn check_push(&self, node_id: &str, ip: &str) -> Result<(), AppError> {
        self.check(&format!("push:node:{node_id}"), PUSH_PER_NODE)?;
        self.check(&format!("push:ip:{ip}"), PUSH_PER_IP)?;
        Ok(())
    }

    pub fn check_telemetry(&self, node_id: &str, ip: &str) -> Result<(), AppError> {
        self.check(&format!("telemetry:node:{node_id}"), TELEMETRY_PER_NODE)?;
        self.check(&format!("telemetry:ip:{ip}"), TELEMETRY_PER_IP)?;
        Ok(())
    }

    pub fn check_refresh(&self, user_id: &str, ip: &str) -> Result<(), AppError> {
        self.check(&format!("refresh:user:{user_id}"), REFRESH_PER_USER)?;
        self.check(&format!("refresh:ip:{ip}"), REFRESH_PER_IP)?;
        Ok(())
    }

    pub fn check_oracle(&self, node_id: &str, ip: &str) -> Result<(), AppError> {
        self.check(&format!("oracle:node:{node_id}"), ORACLE_PER_NODE)?;
        self.check(&format!("oracle:ip:{ip}"), ORACLE_PER_IP)?;
        Ok(())
    }

    pub fn check_report(&self, node_id: &str) -> Result<(), AppError> {
        self.check(&format!("report:node:{node_id}"), REPORT_PER_NODE)
    }

    pub fn check_register(&self, node_id: &str, ip: &str) -> Result<(), AppError> {
        self.check(&format!("register:node:{node_id}"), REGISTER_PER_NODE)?;
        self.check(&format!("register:ip:{ip}"), REGISTER_PER_IP)?;
        Ok(())
    }

    pub fn check_contacts(&self, user_id: &str, ip: &str) -> Result<(), AppError> {
        self.check(&format!("contacts:user:{user_id}"), CONTACTS_PER_USER)?;
        self.check(&format!("contacts:ip:{ip}"), CONTACTS_PER_IP)?;
        Ok(())
    }

    pub fn check_telegram_auth(&self, ip: &str) -> Result<(), AppError> {
        self.check(&format!("tg_auth:ip:{ip}"), TELEGRAM_AUTH_PER_IP)
    }

    pub fn check_lookup(&self, user_id: &str, ip: &str) -> Result<(), AppError> {
        self.check(&format!("lookup:user:{user_id}"), LOOKUP_PER_USER)?;
        self.check(&format!("lookup:ip:{ip}"), LOOKUP_PER_IP)?;
        Ok(())
    }

    pub fn check_username_claim(&self, user_id: &str, ip: &str) -> Result<(), AppError> {
        self.check(&format!("username:user:{user_id}"), USERNAME_CLAIM_PER_USER)?;
        self.check(&format!("username:ip:{ip}"), USERNAME_CLAIM_PER_IP)?;
        Ok(())
    }

    pub fn check_phone_lookup(&self, user_id: &str, ip: &str) -> Result<(), AppError> {
        self.check(&format!("phone_lookup:user:{user_id}"), PHONE_LOOKUP_PER_USER)?;
        self.check(&format!("phone_lookup:ip:{ip}"), PHONE_LOOKUP_PER_IP)?;
        Ok(())
    }

    pub fn check_phone_claim(&self, user_id: &str, ip: &str) -> Result<(), AppError> {
        self.check(&format!("phone_claim:user:{user_id}"), PHONE_CLAIM_PER_USER)?;
        self.check(&format!("phone_claim:ip:{ip}"), PHONE_CLAIM_PER_IP)?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn window_blocks_after_max_and_is_key_scoped() {
        let rl = RateLimitState::new();
        let w = Window {
            max: 2,
            period: Duration::from_secs(60),
        };
        assert!(rl.check("k1", w).is_ok());
        assert!(rl.check("k1", w).is_ok());
        assert!(rl.check("k1", w).is_err(), "third hit must be limited");
        assert!(rl.check("k2", w).is_ok(), "other keys are independent");
    }

    #[test]
    fn lookup_is_bounded_per_account() {
        let rl = RateLimitState::new();
        for _ in 0..30 {
            assert!(rl.check_lookup("u1", "10.0.0.1").is_ok());
        }
        assert!(
            rl.check_lookup("u1", "10.0.0.1").is_err(),
            "lookup must not be a full-table walk"
        );
        assert!(rl.check_lookup("u2", "10.0.0.1").is_ok());
    }

    #[test]
    fn phone_lookup_is_stricter_than_username() {
        let rl = RateLimitState::new();
        for _ in 0..10 {
            assert!(rl.check_phone_lookup("u1", "10.0.0.1").is_ok());
        }
        assert!(
            rl.check_phone_lookup("u1", "10.0.0.1").is_err(),
            "phone lookup must be scarcer than username lookup"
        );
        assert!(rl.check_lookup("u1", "10.0.0.1").is_ok());
        assert!(rl.check_phone_lookup("u2", "10.0.0.1").is_ok());
    }
}

/// Prefer nginx `X-Forwarded-For` only when `TUKTUK_TRUST_PROXY=1|true`.
pub fn client_ip(headers: &HeaderMap) -> String {
    let trust = std::env::var("TUKTUK_TRUST_PROXY")
        .map(|v| v == "1" || v.eq_ignore_ascii_case("true"))
        .unwrap_or(false);
    if trust {
        if let Some(xff) = headers
            .get("x-forwarded-for")
            .and_then(|v| v.to_str().ok())
        {
            let first = xff.split(',').next().unwrap_or("").trim();
            if !first.is_empty() {
                return first.to_string();
            }
        }
        if let Some(real) = headers
            .get("x-real-ip")
            .and_then(|v| v.to_str().ok())
            .map(str::trim)
            .filter(|s| !s.is_empty())
        {
            return real.to_string();
        }
    }
    "unknown".into()
}
