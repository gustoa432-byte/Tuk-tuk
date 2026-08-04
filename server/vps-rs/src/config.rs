//! Runtime config from environment.

#[derive(Clone, Debug)]
pub struct Config {
    pub jwt_secret: String,
    pub smtp_host: Option<String>,
    pub smtp_port: u16,
    pub smtp_user: Option<String>,
    pub smtp_pass: Option<String>,
    pub smtp_from: Option<String>,
    pub telegram_bot_token: Option<String>,
    pub otp_dev_log: bool,
}

impl Config {
    pub fn from_env() -> Self {
        let jwt_secret = std::env::var("TUKTUK_JWT_SECRET").unwrap_or_else(|_| {
            tracing::warn!("TUKTUK_JWT_SECRET unset — using ephemeral secret (tokens reset on restart)");
            uuid::Uuid::new_v4().to_string()
        });
        Self {
            jwt_secret,
            smtp_host: std::env::var("TUKTUK_SMTP_HOST").ok().filter(|s| !s.is_empty()),
            smtp_port: std::env::var("TUKTUK_SMTP_PORT")
                .ok()
                .and_then(|p| p.parse().ok())
                .unwrap_or(587),
            smtp_user: std::env::var("TUKTUK_SMTP_USER").ok().filter(|s| !s.is_empty()),
            smtp_pass: std::env::var("TUKTUK_SMTP_PASS").ok().filter(|s| !s.is_empty()),
            smtp_from: std::env::var("TUKTUK_SMTP_FROM").ok().filter(|s| !s.is_empty()),
            telegram_bot_token: std::env::var("TUKTUK_TELEGRAM_BOT_TOKEN")
                .ok()
                .filter(|s| !s.is_empty()),
            otp_dev_log: std::env::var("TUKTUK_OTP_DEV_LOG")
                .map(|v| v == "1" || v.eq_ignore_ascii_case("true"))
                .unwrap_or(true),
        }
    }

    pub fn smtp_ready(&self) -> bool {
        self.smtp_host.is_some() && self.smtp_from.is_some()
    }
}
