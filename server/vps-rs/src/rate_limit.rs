//! In-memory sliding-window rate limits (OTP + push).
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
    max: 120,
    period: Duration::from_secs(60),
};
const PUSH_PER_IP: Window = Window {
    max: 240,
    period: Duration::from_secs(60),
};

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
}

/// Prefer nginx `X-Forwarded-For` (first hop); else `X-Real-IP`; else unknown.
pub fn client_ip(headers: &HeaderMap) -> String {
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
    "unknown".into()
}
