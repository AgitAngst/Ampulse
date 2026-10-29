//! Конфигурация: пути, настройки установки (`settings.json`) и параметры запуска `serve`.

use std::net::SocketAddr;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::cli::ServeArgs;
use crate::store::Limits;

/// Имя службы, пользователя и программы в журнале.
pub const NAME: &str = "ampulse";
/// Порт по умолчанию.
pub const DEFAULT_PORT: u16 = 8740;
/// Файл базы в каталоге данных.
pub const DB_FILE: &str = "ampulse.db";
/// Настройки установки в каталоге данных.
pub const SETTINGS_FILE: &str = "settings.json";

/// Каталог данных по умолчанию: на Linux — системный, на остальных системах (разработка) — рядом.
#[cfg(target_os = "linux")]
pub const DEFAULT_DATA_DIR: &str = "/var/lib/ampulse";
#[cfg(not(target_os = "linux"))]
pub const DEFAULT_DATA_DIR: &str = "ampulse-data";

/// Что `install` запомнил: переустановка без флагов оставляет это как есть.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Settings {
    pub port: u16,
    /// Адрес, по которому сервер видят клиенты, вместе с портом: `203.0.113.7:8740`.
    pub address: String,
}

impl Settings {
    pub fn load(data: &Path) -> Option<Self> {
        let text = std::fs::read_to_string(data.join(SETTINGS_FILE)).ok()?;
        serde_json::from_str(&text).ok()
    }

    pub fn save(&self, data: &Path) -> std::io::Result<()> {
        let text = serde_json::to_string_pretty(self).map_err(std::io::Error::other)?;
        crate::fsutil::write_secret(&data.join(SETTINGS_FILE), text.as_bytes())
    }

    /// Имя или IP из адреса, без порта и скобок.
    pub fn host(&self) -> &str {
        host_of(&self.address)
    }
}

/// Хост из `host:port`, `[v6]:port` или просто хоста.
pub fn host_of(address: &str) -> &str {
    if let Some(rest) = address.strip_prefix('[') {
        return rest.split(']').next().unwrap_or(rest);
    }
    match address.rsplit_once(':') {
        Some((host, port)) if !host.contains(':') && port.chars().all(|c| c.is_ascii_digit()) => host,
        _ => address,
    }
}

/// Параметры запуска `serve`.
#[derive(Debug, Clone)]
pub struct ServeConfig {
    pub data_dir: PathBuf,
    pub addr: SocketAddr,
    pub limits: Limits,
}

impl ServeConfig {
    pub fn from_args(args: &ServeArgs) -> Self {
        let mut limits = Limits::default();
        if let Some(n) = args.max_boxes {
            limits.max_boxes = n;
        }
        if let Some(mib) = args.max_total_mib {
            limits.max_total_bytes = mib.saturating_mul(1024 * 1024);
        }
        Self {
            data_dir: args.data.clone().unwrap_or_else(|| PathBuf::from(DEFAULT_DATA_DIR)),
            addr: args
                .addr
                .unwrap_or_else(|| SocketAddr::from(([0, 0, 0, 0], DEFAULT_PORT))),
            limits,
        }
    }

    pub fn db_path(&self) -> PathBuf {
        self.data_dir.join(DB_FILE)
    }
}

/// Каталог данных из флага или умолчание.
pub fn data_dir(flag: &Option<PathBuf>) -> PathBuf {
    flag.clone().unwrap_or_else(|| PathBuf::from(DEFAULT_DATA_DIR))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn host_of_all_shapes() {
        assert_eq!(host_of("203.0.113.7:8740"), "203.0.113.7");
        assert_eq!(host_of("relay.example.org:443"), "relay.example.org");
        assert_eq!(host_of("[2001:db8::1]:8740"), "2001:db8::1");
        assert_eq!(host_of("relay.example.org"), "relay.example.org");
        assert_eq!(host_of("2001:db8::1"), "2001:db8::1");
    }

    #[test]
    fn serve_config_defaults_and_overrides() {
        let cfg = ServeConfig::from_args(&ServeArgs::default());
        assert_eq!(cfg.addr.port(), DEFAULT_PORT);
        assert_eq!(cfg.limits.max_box_messages, 200);
        let cfg = ServeConfig::from_args(&ServeArgs {
            max_boxes: Some(3),
            max_total_mib: Some(2),
            ..Default::default()
        });
        assert_eq!(cfg.limits.max_boxes, 3);
        assert_eq!(cfg.limits.max_total_bytes, 2 * 1024 * 1024);
        assert!(cfg.db_path().ends_with(DB_FILE));
    }

    #[test]
    fn settings_round_trip() {
        let dir = std::env::temp_dir().join(format!(
            "ampulse-cfg-{}",
            crate::codec::b64_encode(&crate::codec::random_bytes::<6>())
        ));
        std::fs::create_dir_all(&dir).unwrap();
        assert!(Settings::load(&dir).is_none());
        let s = Settings {
            port: 9001,
            address: "203.0.113.7:9001".into(),
        };
        s.save(&dir).unwrap();
        assert_eq!(Settings::load(&dir), Some(s.clone()));
        assert_eq!(s.host(), "203.0.113.7");
        let _ = std::fs::remove_dir_all(&dir);
    }
}
