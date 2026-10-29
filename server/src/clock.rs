//! Часы сервера. Всё, что зависит от времени (сроки, ограничение частоты), читает их
//! через [`Clock`], чтобы тесты могли сдвигать время, не дожидаясь суток и недель.

use std::sync::atomic::{AtomicI64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

/// Источник времени: миллисекунды от 1970 года (UTC).
pub trait Clock: Send + Sync + 'static {
    fn now_ms(&self) -> i64;

    /// Целые секунды от 1970 года.
    fn now_secs(&self) -> i64 {
        self.now_ms().div_euclid(1000)
    }
}

/// Настоящие часы машины.
#[derive(Debug, Default, Clone, Copy)]
pub struct SystemClock;

impl Clock for SystemClock {
    fn now_ms(&self) -> i64 {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_millis() as i64)
            .unwrap_or(0)
    }
}

/// Часы, которые двигает тест.
#[derive(Debug)]
pub struct ManualClock(AtomicI64);

impl ManualClock {
    /// Часы, стоящие на `secs` секундах от 1970 года.
    pub fn new(secs: i64) -> Self {
        Self(AtomicI64::new(secs * 1000))
    }

    /// Сдвинуть вперёд.
    pub fn advance_secs(&self, secs: i64) {
        self.0.fetch_add(secs * 1000, Ordering::SeqCst);
    }

    pub fn advance_ms(&self, ms: i64) {
        self.0.fetch_add(ms, Ordering::SeqCst);
    }
}

impl Clock for ManualClock {
    fn now_ms(&self) -> i64 {
        self.0.load(Ordering::SeqCst)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn manual_clock_moves_only_when_told() {
        let clock = ManualClock::new(1_000);
        assert_eq!(clock.now_secs(), 1_000);
        clock.advance_secs(86_400);
        assert_eq!(clock.now_secs(), 87_400);
        clock.advance_ms(500);
        assert_eq!(clock.now_ms(), 87_400_500);
        assert_eq!(clock.now_secs(), 87_400);
    }

    #[test]
    fn system_clock_is_after_2026() {
        assert!(SystemClock.now_secs() > 1_780_000_000);
    }
}
