//! Ограничение частоты по адресу: ведро жетонов на каждого клиента.
//!
//! Обычный запрос стоит один жетон, неверный токен или неизвестный ящик — несколько: так
//! перебор идентификаторов упирается в ограничение быстрее обычной работы. Время берётся из
//! [`Clock`], поэтому тесты могут «подождать», не ожидая.

use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::{Arc, Mutex};

use crate::clock::Clock;

/// Настройки ведра.
#[derive(Debug, Clone, Copy)]
pub struct RateConfig {
    /// Сколько запросов можно подряд.
    pub burst: f64,
    /// Сколько жетонов возвращается за секунду.
    pub per_sec: f64,
    /// Во сколько жетонов обходится неверный токен (сверх самого запроса).
    pub failure_cost: f64,
}

impl Default for RateConfig {
    fn default() -> Self {
        Self {
            burst: 60.0,
            per_sec: 1.0,
            failure_cost: 4.0,
        }
    }
}

/// Ключ клиента: адрес IPv4 целиком, у IPv6 — сеть /64 (одному клиенту выдают её целиком).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
struct Key([u8; 16]);

impl Key {
    fn of(ip: IpAddr) -> Self {
        match ip {
            IpAddr::V4(v4) => {
                let mut raw = [0u8; 16];
                raw[..4].copy_from_slice(&v4.octets());
                raw[15] = 4;
                Self(raw)
            }
            IpAddr::V6(v6) => {
                let mut raw = v6.octets();
                raw[8..].fill(0);
                Self(raw)
            }
        }
    }
}

#[derive(Debug, Clone, Copy)]
struct Bucket {
    tokens: f64,
    at_ms: i64,
}

pub struct RateLimiter {
    config: RateConfig,
    clock: Arc<dyn Clock>,
    buckets: Mutex<HashMap<Key, Bucket>>,
}

/// Столько адресов помним; больше — убираем тех, чьё ведро уже полно.
const MAX_TRACKED: usize = 50_000;

impl RateLimiter {
    pub fn new(config: RateConfig, clock: Arc<dyn Clock>) -> Self {
        Self {
            config,
            clock,
            buckets: Mutex::new(HashMap::new()),
        }
    }

    fn refilled(&self, bucket: Bucket, now: i64) -> Bucket {
        let elapsed = (now - bucket.at_ms).max(0) as f64 / 1000.0;
        Bucket {
            tokens: (bucket.tokens + elapsed * self.config.per_sec).min(self.config.burst),
            at_ms: now,
        }
    }

    /// Взять один жетон. Нет жетонов — через сколько секунд повторить.
    pub fn check(&self, ip: IpAddr) -> Result<(), u64> {
        let now = self.clock.now_ms();
        let mut buckets = self.buckets.lock().unwrap_or_else(|p| p.into_inner());
        if buckets.len() >= MAX_TRACKED {
            let full = self.config.burst;
            let cfg = self.config;
            buckets.retain(|_, b| {
                let elapsed = (now - b.at_ms).max(0) as f64 / 1000.0;
                (b.tokens + elapsed * cfg.per_sec) < full
            });
        }
        let start = Bucket {
            tokens: self.config.burst,
            at_ms: now,
        };
        let bucket = self.refilled(*buckets.get(&Key::of(ip)).unwrap_or(&start), now);
        if bucket.tokens >= 1.0 {
            buckets.insert(
                Key::of(ip),
                Bucket {
                    tokens: bucket.tokens - 1.0,
                    at_ms: now,
                },
            );
            Ok(())
        } else {
            buckets.insert(Key::of(ip), bucket);
            let wait = (1.0 - bucket.tokens) / self.config.per_sec;
            Err(wait.ceil().max(1.0) as u64)
        }
    }

    /// Наказать за неверный токен: списать жетоны (можно уйти в минус до `-burst`).
    pub fn penalize(&self, ip: IpAddr) {
        let now = self.clock.now_ms();
        let mut buckets = self.buckets.lock().unwrap_or_else(|p| p.into_inner());
        let start = Bucket {
            tokens: self.config.burst,
            at_ms: now,
        };
        let bucket = self.refilled(*buckets.get(&Key::of(ip)).unwrap_or(&start), now);
        let tokens = (bucket.tokens - self.config.failure_cost).max(-self.config.burst);
        buckets.insert(Key::of(ip), Bucket { tokens, at_ms: now });
    }

    /// Забыть адреса, у которых ведро снова полно.
    pub fn prune(&self) {
        let now = self.clock.now_ms();
        let mut buckets = self.buckets.lock().unwrap_or_else(|p| p.into_inner());
        let cfg = self.config;
        buckets.retain(|_, b| {
            let elapsed = (now - b.at_ms).max(0) as f64 / 1000.0;
            (b.tokens + elapsed * cfg.per_sec) < cfg.burst
        });
    }

    #[cfg(test)]
    fn tracked(&self) -> usize {
        self.buckets.lock().unwrap().len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::clock::ManualClock;

    fn limiter(burst: f64, per_sec: f64) -> (RateLimiter, Arc<ManualClock>) {
        let clock = Arc::new(ManualClock::new(1_000));
        let cfg = RateConfig {
            burst,
            per_sec,
            failure_cost: 4.0,
        };
        (RateLimiter::new(cfg, clock.clone()), clock)
    }

    fn ip(last: u8) -> IpAddr {
        IpAddr::from([192, 0, 2, last])
    }

    #[test]
    fn burst_then_refuse_then_refill() {
        let (limiter, clock) = limiter(3.0, 1.0);
        for _ in 0..3 {
            assert!(limiter.check(ip(1)).is_ok());
        }
        assert_eq!(limiter.check(ip(1)), Err(1));
        clock.advance_secs(1);
        assert!(limiter.check(ip(1)).is_ok());
        assert!(limiter.check(ip(1)).is_err());
        clock.advance_secs(100);
        for _ in 0..3 {
            assert!(limiter.check(ip(1)).is_ok());
        }
        assert!(limiter.check(ip(1)).is_err());
    }

    #[test]
    fn clients_are_independent() {
        let (limiter, _) = limiter(1.0, 0.1);
        assert!(limiter.check(ip(1)).is_ok());
        assert!(limiter.check(ip(1)).is_err());
        assert!(limiter.check(ip(2)).is_ok());
    }

    #[test]
    fn failures_cost_more_than_requests() {
        let (limiter, clock) = limiter(10.0, 1.0);
        assert!(limiter.check(ip(1)).is_ok());
        limiter.penalize(ip(1));
        limiter.penalize(ip(1));
        // 10 - 1 - 4 - 4 = 1 жетон остался.
        assert!(limiter.check(ip(1)).is_ok());
        let Err(wait) = limiter.check(ip(1)) else {
            panic!("must be limited")
        };
        assert_eq!(wait, 1);
        limiter.penalize(ip(1));
        limiter.penalize(ip(1));
        limiter.penalize(ip(1));
        clock.advance_secs(1);
        let Err(wait) = limiter.check(ip(1)) else {
            panic!("must be limited")
        };
        assert!(wait > 1, "penalties lengthen the wait, got {wait}");
    }

    #[test]
    fn ipv6_clients_share_a_64_network() {
        let (limiter, _) = limiter(1.0, 0.1);
        let a: IpAddr = "2001:db8:1:2::1".parse().unwrap();
        let b: IpAddr = "2001:db8:1:2:ffff::9".parse().unwrap();
        let other: IpAddr = "2001:db8:1:3::1".parse().unwrap();
        assert!(limiter.check(a).is_ok());
        assert!(limiter.check(b).is_err());
        assert!(limiter.check(other).is_ok());
    }

    #[test]
    fn prune_forgets_idle_clients() {
        let (limiter, clock) = limiter(5.0, 1.0);
        assert!(limiter.check(ip(1)).is_ok());
        limiter.prune();
        assert_eq!(limiter.tracked(), 1);
        clock.advance_secs(10);
        limiter.prune();
        assert_eq!(limiter.tracked(), 0);
    }
}
