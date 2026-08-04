//! SMTP helper for OTP emails.

use lettre::message::Mailbox;
use lettre::transport::smtp::authentication::Credentials;
use lettre::{AsyncSmtpTransport, AsyncTransport, Message, Tokio1Executor};

use crate::config::Config;
use crate::state::AppError;

pub async fn send_otp(cfg: &Config, to_email: &str, code: &str) -> Result<(), AppError> {
    let host = cfg
        .smtp_host
        .as_ref()
        .ok_or_else(|| AppError::internal("smtp_host_missing"))?;
    let from_raw = cfg
        .smtp_from
        .as_ref()
        .ok_or_else(|| AppError::internal("smtp_from_missing"))?;

    let from: Mailbox = from_raw
        .parse()
        .map_err(|_| AppError::internal("smtp_from_invalid"))?;
    let to: Mailbox = to_email
        .parse()
        .map_err(|_| AppError::bad("invalid_email"))?;

    let email = Message::builder()
        .from(from)
        .to(to)
        .subject("TukTuk — код входа")
        .body(format!(
            "Ваш код: {code}\nДействует 5 минут.\n\nЕсли это не вы — проигнорируйте письмо."
        ))
        .map_err(|e| AppError::internal(e.to_string()))?;

    let mut builder = AsyncSmtpTransport::<Tokio1Executor>::starttls_relay(host)
        .map_err(|e| AppError::internal(e.to_string()))?
        .port(cfg.smtp_port);

    if let (Some(user), Some(pass)) = (&cfg.smtp_user, &cfg.smtp_pass) {
        builder = builder.credentials(Credentials::new(user.clone(), pass.clone()));
    }

    let mailer = builder.build();
    mailer
        .send(email)
        .await
        .map_err(|e| AppError::internal(e.to_string()))?;
    Ok(())
}
