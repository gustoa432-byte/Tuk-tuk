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
