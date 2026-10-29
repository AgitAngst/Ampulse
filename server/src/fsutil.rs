//! Файлы и права. Каталог данных — 0700, файлы в нём — 0600 (на Linux); на других системах
//! права не трогаем: сервер там только запускается для разработки.

use std::io;
use std::path::Path;

/// Создать каталог (с родителями) и закрыть его от посторонних.
pub fn secure_dir(path: &Path) -> io::Result<()> {
    std::fs::create_dir_all(path)?;
    set_mode(path, 0o700);
    Ok(())
}

/// Закрыть файл от посторонних; если его нет — ничего.
pub fn secure_file(path: &Path) {
    if path.exists() {
        set_mode(path, 0o600);
    }
}

/// Записать секрет (ключ, база) в файл, который с самого начала доступен только хозяину.
pub fn write_secret(path: &Path, bytes: &[u8]) -> io::Result<()> {
    use std::io::Write;
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create(true).truncate(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let mut file = options.open(path)?;
    file.write_all(bytes)?;
    file.sync_all()?;
    drop(file);
    set_mode(path, 0o600);
    Ok(())
}

/// Символическая ссылка там, где root откроет файл службы? Каталог данных принадлежит службе,
/// и тот, кто её взломал, мог бы подложить ссылку на системный файл.
pub fn is_symlink(path: &Path) -> bool {
    std::fs::symlink_metadata(path).is_ok_and(|m| m.file_type().is_symlink())
}

#[cfg(unix)]
fn set_mode(path: &Path, mode: u32) {
    use std::os::unix::fs::PermissionsExt;
    if let Err(e) = std::fs::set_permissions(path, std::fs::Permissions::from_mode(mode)) {
        tracing::warn!("cannot set permissions on {}: {e}", path.display());
    }
}

#[cfg(not(unix))]
fn set_mode(_path: &Path, _mode: u32) {}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn write_secret_replaces_content() {
        let dir = std::env::temp_dir().join(format!(
            "ampulse-fs-{}",
            crate::codec::b64_encode(&crate::codec::random_bytes::<6>())
        ));
        secure_dir(&dir).unwrap();
        let file = dir.join("secret");
        write_secret(&file, b"one").unwrap();
        write_secret(&file, b"2").unwrap();
        assert_eq!(std::fs::read(&file).unwrap(), b"2");
        assert!(!is_symlink(&file));
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(std::fs::metadata(&file).unwrap().permissions().mode() & 0o777, 0o600);
            assert_eq!(std::fs::metadata(&dir).unwrap().permissions().mode() & 0o777, 0o700);
        }
        let _ = std::fs::remove_dir_all(&dir);
    }
}
