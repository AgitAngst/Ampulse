//! `status --json`: что центр показывает владельцу. Поля `name`, `version`, `running`, `address`,
//! `fingerprint`, `boxes`, `messages`, `disk_free` есть всегда; чего узнать не удалось — `null`.

use std::path::Path;

use serde::Serialize;

use crate::config::{Settings, DB_FILE, NAME};
use crate::{store, tls};

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Status {
    pub name: &'static str,
    pub version: &'static str,
    pub running: bool,
    pub address: Option<String>,
    /// SHA-256 сертификата, строчный hex.
    pub fingerprint: Option<String>,
    pub boxes: Option<u64>,
    pub messages: Option<u64>,
    /// Свободно на диске с данными, байт.
    pub disk_free: Option<u64>,
}

/// Собрать состояние из каталога данных. `running` и `disk_free` знает только система.
pub fn gather(data: &Path, running: bool, disk_free: Option<u64>) -> Status {
    let db = data.join(DB_FILE);
    let counts = if db.exists() { store::read_stats(&db).ok() } else { None };
    Status {
        name: NAME,
        version: env!("CARGO_PKG_VERSION"),
        running,
        address: Settings::load(data).map(|s| s.address),
        fingerprint: tls::fingerprint_of_dir(data),
        boxes: counts.map(|c| c.boxes),
        messages: counts.map(|c| c.messages),
        disk_free,
    }
}

impl Status {
    pub fn to_json(&self) -> String {
        serde_json::to_string(self).expect("status always serializes")
    }

    /// Те же сведения для человека.
    pub fn to_text(&self) -> String {
        let show = |v: Option<String>| v.unwrap_or_else(|| "unknown".to_owned());
        format!(
            "{} {}\nrunning:     {}\naddress:     {}\nfingerprint: {}\nboxes:       {}\nmessages:    {}\ndisk free:   {}\n",
            self.name,
            self.version,
            if self.running { "yes" } else { "no" },
            show(self.address.clone()),
            show(self.fingerprint.clone()),
            show(self.boxes.map(|n| n.to_string())),
            show(self.messages.map(|n| n.to_string())),
            show(self.disk_free.map(|n| format!("{} MiB", n / (1024 * 1024)))),
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_dir() -> std::path::PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "ampulse-status-{}",
            crate::codec::b64_encode(&crate::codec::random_bytes::<6>())
        ));
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn empty_directory_gives_nulls_but_all_keys() {
        let dir = temp_dir();
        let json: serde_json::Value = serde_json::from_str(&gather(&dir, false, None).to_json()).unwrap();
        for key in [
            "name",
            "version",
            "running",
            "address",
            "fingerprint",
            "boxes",
            "messages",
            "disk_free",
        ] {
            assert!(json.get(key).is_some(), "missing key {key}");
        }
        assert_eq!(json["name"], "ampulse");
        assert_eq!(json["running"], false);
        assert!(json["fingerprint"].is_null() && json["boxes"].is_null());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn installed_directory_reports_everything() {
        use crate::clock::ManualClock;
        use crate::codec::hash_token;
        use std::sync::Arc;

        let dir = temp_dir();
        let clock = Arc::new(ManualClock::new(1_800_000_000));
        let identity = tls::Identity::load_or_create(&dir, Some("203.0.113.7"), 1_800_000_000).unwrap();
        Settings {
            port: 8740,
            address: "203.0.113.7:8740".into(),
        }
        .save(&dir)
        .unwrap();
        let store = store::Store::open(&dir.join(DB_FILE), clock, store::Limits::default()).unwrap();
        let (w, r) = (hash_token(b"w"), hash_token(b"r"));
        store.create_box("a", &w, &r).unwrap();
        store.post("a", &w, b"x").unwrap().unwrap();
        drop(store);

        let status = gather(&dir, true, Some(12_345));
        assert_eq!(status.address.as_deref(), Some("203.0.113.7:8740"));
        assert_eq!(status.fingerprint, Some(identity.fingerprint()));
        assert_eq!((status.boxes, status.messages), (Some(1), Some(1)));
        assert_eq!(status.disk_free, Some(12_345));
        assert!(status.running);
        assert!(status.to_text().contains("203.0.113.7:8740"));
        let _ = std::fs::remove_dir_all(&dir);
    }
}
