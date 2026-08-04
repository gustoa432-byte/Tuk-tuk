//! Pure graph scoring math (no HTTP, no DB).

/// Seconds in one full calendar day for decay steps.
pub const DAY_SECS: i64 = 86_400;

/// Per-full-day idle penalty multiplier (10% loss → ×0.9).
pub const DECAY_PER_DAY: f64 = 0.9;

/// Weight of an orbit edge for courier ranking.
///
/// - Base = `meet_count` as f64
/// - For each **full** day of idle time between `last_meet_at` and `current_time`,
///   multiply by 0.9 ([`DECAY_PER_DAY`]).
///
/// Timestamps are Unix **seconds** (not milliseconds).
///
/// Example: `meet_count = 10`, idle = 7 full days → `10 * (0.9 ^ 7)`.
pub fn calculate_weight(meet_count: i64, last_meet_at: i64, current_time: i64) -> f64 {
    if meet_count <= 0 {
        return 0.0;
    }
    let base = meet_count as f64;
    let elapsed = (current_time - last_meet_at).max(0);
    let full_days = elapsed / DAY_SECS;
    // Cap exponent to avoid needless powi on huge idle gaps (weight → ~0 anyway).
    let days = full_days.min(i32::MAX as i64) as i32;
    base * DECAY_PER_DAY.powi(days)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn zero_or_negative_meet_count_is_zero() {
        assert_eq!(calculate_weight(0, 0, 86_400), 0.0);
        assert_eq!(calculate_weight(-3, 0, 86_400), 0.0);
    }

    #[test]
    fn no_idle_keeps_base_weight() {
        let w = calculate_weight(10, 1_000_000, 1_000_000);
        assert!((w - 10.0).abs() < 1e-12);
    }

    #[test]
    fn partial_day_does_not_decay() {
        // 86399 seconds < 1 full day
        let w = calculate_weight(10, 0, DAY_SECS - 1);
        assert!((w - 10.0).abs() < 1e-12);
    }

    #[test]
    fn seven_full_days_matches_spec_example() {
        // meet_count = 10, 7 full days → 10 * (0.9 ^ 7)
        let expected = 10.0 * 0.9_f64.powi(7);
        let w = calculate_weight(10, 0, 7 * DAY_SECS);
        assert!(
            (w - expected).abs() < 1e-12,
            "got {w}, expected {expected}"
        );
    }

    #[test]
    fn one_full_day_decays_10_percent() {
        let w = calculate_weight(10, 0, DAY_SECS);
        assert!((w - 9.0).abs() < 1e-12);
    }

    #[test]
    fn future_last_meet_does_not_inflate() {
        // last_meet_at > current_time → elapsed clamped to 0
        let w = calculate_weight(5, 10_000, 1_000);
        assert!((w - 5.0).abs() < 1e-12);
    }
}
