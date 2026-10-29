//! `backup --to DIR`: согласованная копия базы и файлов установки. Службу останавливать не надо.
//!
//! В копии лежат шифртекст и метаданные, а также закрытый ключ сертификата: с ним сервер
//! поднимается на другой машине с прежним отпечатком. Ключа, которым читается сообщение, в
//! копии нет и быть не может. Каталог копии закрыт (0700 на Linux).

use std::path::{Path, PathBuf};

use crate::config::{DB_FILE, SETTINGS_FILE};
use crate::fsutil;
use crate::store::copy_database;
use crate::tls::{CERT_FILE, KEY_FILE};

/// Сделать копию; возвращает записанные файлы.
pub fn run(data: &Path, to: &Path) -> Result<Vec<PathBuf>, String> {
    let db = data.join(DB_FILE);
    if !db.is_file() {
        return Err(format!("{} not found: nothing to back up", db.display()));
    }
    if fsutil::is_symlink(&db) {
        return Err(format!("{} is a symbolic link; refusing to read it", db.display()));
    }
    fsutil::secure_dir(to).map_err(|e| format!("cannot create {}: {e}", to.display()))?;
    let target = to.join(DB_FILE);
    if target.exists() {
        return Err(format!("{} already exists; choose another directory", target.display()));
    }
    copy_database(&db, &target).map_err(|e| format!("cannot copy the database: {e}"))?;
    fsutil::secure_file(&target);
    let mut written = vec![target];
    for name in [CERT_FILE, KEY_FILE, SETTINGS_FILE] {
        let from = data.join(name);
        if !from.is_file() {
            continue;
        }
        if fsutil::is_symlink(&from) {
            return Err(format!("{} is a symbolic link; refusing to read it", from.display()));
        }
        let bytes = std::fs::read(&from).map_err(|e| format!("cannot read {}: {e}", from.display()))?;
        let dest = to.join(name);
        fsutil::write_secret(&dest, &bytes).map_err(|e| format!("cannot write {}: {e}", dest.display()))?;
        written.push(dest);
    }
    Ok(written)
}

/// Куда класть копию, если `--to` не указан.
pub fn default_target(now_secs: i64) -> PathBuf {
    PathBuf::from(format!("/var/backups/ampulse/backup-{now_secs}"))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::clock::ManualClock;
    use crate::codec::hash_token;
    use crate::store::{read_stats, Limits, Stats, Store};
    use std::sync::Arc;

    fn temp_dir() -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "ampulse-backup-{}",
            crate::codec::b64_encode(&crate::codec::random_bytes::<6>())
        ));
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn backup_copies_database_and_install_files() {
        let root = temp_dir();
        let data = root.join("data");
        fsutil::secure_dir(&data).unwrap();
        let clock = Arc::new(ManualClock::new(1_800_000_000));
        let identity = crate::tls::Identity::load_or_create(&data, None, 1_800_000_000).unwrap();
        crate::config::Settings {
            port: 8740,
            address: "203.0.113.7:8740".into(),
        }
        .save(&data)
        .unwrap();
        let store = Store::open(&data.join(DB_FILE), clock, Limits::default()).unwrap();
        let (w, r) = (hash_token(b"w"), hash_token(b"r"));
        store.create_box("a", &w, &r).unwrap();
        store.post("a", &w, b"ciphertext").unwrap().unwrap();

        // Служба «работает»: соединение с базой открыто, копия всё равно получается.
        let to = root.join("copy");
        let files = run(&data, &to).unwrap();
        assert_eq!(files.len(), 4);
        assert_eq!(
            read_stats(&to.join(DB_FILE)).unwrap(),
            Stats {
                boxes: 1,
                messages: 1,
                bytes: 10
            }
        );
        assert_eq!(crate::tls::fingerprint_of_dir(&to).unwrap(), identity.fingerprint());
        assert!(to.join(SETTINGS_FILE).is_file());

        // Повторная копия в тот же каталог не затирает прежнюю.
        assert!(run(&data, &to).is_err());
        drop(store);
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn backup_without_database_is_an_error() {
        let root = temp_dir();
        assert!(run(&root, &root.join("copy")).is_err());
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn default_target_is_under_var_backups() {
        assert_eq!(default_target(5), PathBuf::from("/var/backups/ampulse/backup-5"));
    }
}
