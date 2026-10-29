//! Самоподписанный сертификат сервера и его отпечаток.
//!
//! Клиент не доверяет центрам сертификации: отпечаток SHA-256 приходит к нему в QR знакомства
//! и сверяется при каждом соединении. Поэтому имя в сертификате второстепенно, а сам
//! сертификат создаётся один раз при установке на 10 лет и дальше не меняется.

use std::net::IpAddr;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use rcgen::{date_time_ymd, CertificateParams, DistinguishedName, DnType, KeyPair, SanType};
use rustls::pki_types::pem::PemObject;
use rustls::pki_types::{CertificateDer, PrivateKeyDer};

use crate::codec::{hex, sha256};
use crate::fsutil;

pub const CERT_FILE: &str = "cert.pem";
pub const KEY_FILE: &str = "key.pem";
/// Сертификат действует столько лет.
pub const VALID_YEARS: i32 = 10;

/// Сертификат с закрытым ключом.
pub struct Identity {
    cert_der: CertificateDer<'static>,
    cert_pem: String,
    key_pem: String,
}

impl Identity {
    /// Прочитать сертификат из каталога данных или создать новый. Есть только один из двух
    /// файлов — ошибка: молча выпустить новый значило бы сменить отпечаток, который уже у клиентов.
    ///
    /// `host` — адрес или имя сервера для записи в сертификат (кроме `localhost`).
    pub fn load_or_create(dir: &Path, host: Option<&str>, now_secs: i64) -> Result<Self, String> {
        let (cert_path, key_path) = (dir.join(CERT_FILE), dir.join(KEY_FILE));
        match (cert_path.exists(), key_path.exists()) {
            (true, true) => Self::load(&cert_path, &key_path),
            (false, false) => {
                let identity = Self::generate(host, now_secs)?;
                fsutil::write_secret(&key_path, identity.key_pem.as_bytes())
                    .map_err(|e| format!("cannot write {}: {e}", key_path.display()))?;
                fsutil::write_secret(&cert_path, identity.cert_pem.as_bytes())
                    .map_err(|e| format!("cannot write {}: {e}", cert_path.display()))?;
                Ok(identity)
            }
            _ => Err(format!(
                "{} and {} must exist together; restore the missing one from a backup or remove both to issue a new certificate",
                cert_path.display(),
                key_path.display()
            )),
        }
    }

    fn load(cert_path: &Path, key_path: &Path) -> Result<Self, String> {
        for path in [cert_path, key_path] {
            if fsutil::is_symlink(path) {
                return Err(format!("{} is a symbolic link; refusing to read it", path.display()));
            }
        }
        let cert_pem =
            std::fs::read_to_string(cert_path).map_err(|e| format!("cannot read {}: {e}", cert_path.display()))?;
        let key_pem =
            std::fs::read_to_string(key_path).map_err(|e| format!("cannot read {}: {e}", key_path.display()))?;
        let cert_der = CertificateDer::from_pem_slice(cert_pem.as_bytes())
            .map_err(|e| format!("{} is not a certificate: {e}", cert_path.display()))?;
        PrivateKeyDer::from_pem_slice(key_pem.as_bytes())
            .map_err(|e| format!("{} is not a key: {e}", key_path.display()))?;
        Ok(Self {
            cert_der,
            cert_pem,
            key_pem,
        })
    }

    fn generate(host: Option<&str>, now_secs: i64) -> Result<Self, String> {
        let err = |e: rcgen::Error| format!("cannot create certificate: {e}");
        let key = KeyPair::generate().map_err(err)?;
        let mut params = CertificateParams::default();
        let dns = |name: &str| -> Result<SanType, String> {
            Ok(SanType::DnsName(
                name.to_owned()
                    .try_into()
                    .map_err(|_| format!("bad host name: {name}"))?,
            ))
        };
        let mut names = vec![dns("localhost")?, SanType::IpAddress(IpAddr::from([127, 0, 0, 1]))];
        if let Some(host) = host
            .map(|h| h.trim_matches(|c| c == '[' || c == ']'))
            .filter(|h| !h.is_empty())
        {
            names.push(match host.parse::<IpAddr>() {
                Ok(ip) => SanType::IpAddress(ip),
                Err(_) => dns(host)?,
            });
        }
        params.subject_alt_names = names;
        let mut name = DistinguishedName::new();
        name.push(DnType::CommonName, "Ampulse relay");
        params.distinguished_name = name;
        let ((y0, m0, d0), (y1, m1, d1)) = validity(now_secs);
        params.not_before = date_time_ymd(y0, m0, d0);
        params.not_after = date_time_ymd(y1, m1, d1);
        let cert = params.self_signed(&key).map_err(err)?;
        Ok(Self {
            cert_der: cert.der().clone(),
            cert_pem: cert.pem(),
            key_pem: key.serialize_pem(),
        })
    }

    /// SHA-256 сертификата (DER), строчные шестнадцатеричные знаки без разделителей.
    pub fn fingerprint(&self) -> String {
        hex(&sha256(self.cert_der.as_ref()))
    }

    /// Настройки TLS: только 1.3, провайдер `ring`.
    pub fn server_config(&self) -> Result<Arc<rustls::ServerConfig>, String> {
        let key = PrivateKeyDer::from_pem_slice(self.key_pem.as_bytes()).map_err(|e| format!("bad key: {e}"))?;
        let provider = Arc::new(rustls::crypto::ring::default_provider());
        let mut config = rustls::ServerConfig::builder_with_provider(provider)
            .with_protocol_versions(&[&rustls::version::TLS13])
            .map_err(|e| format!("TLS setup failed: {e}"))?
            .with_no_client_auth()
            .with_single_cert(vec![self.cert_der.clone()], key)
            .map_err(|e| format!("certificate and key do not match: {e}"))?;
        config.alpn_protocols = vec![b"http/1.1".to_vec()];
        Ok(Arc::new(config))
    }
}

/// Отпечаток сертификата из файла данных (для `status`), без чтения ключа.
pub fn fingerprint_of_dir(dir: &Path) -> Option<String> {
    let path: PathBuf = dir.join(CERT_FILE);
    let pem = std::fs::read(path).ok()?;
    let der = CertificateDer::from_pem_slice(&pem).ok()?;
    Some(hex(&sha256(der.as_ref())))
}

/// Начало и конец срока: со вчерашнего дня (запас на расхождение часов) и ровно на 10 лет вперёд.
fn validity(now_secs: i64) -> ((i32, u8, u8), (i32, u8, u8)) {
    let today = now_secs.div_euclid(86_400);
    let (y0, m0, d0) = civil_from_days(today - 1);
    let (y1, m1, d1) = civil_from_days(today);
    // 29 февраля через 10 лет может не существовать.
    let d1 = if m1 == 2 && d1 == 29 { 28 } else { d1 };
    ((y0, m0, d0), (y1 + VALID_YEARS, m1, d1))
}

/// Дата по числу дней от 1970-01-01 (григорианский календарь).
fn civil_from_days(days: i64) -> (i32, u8, u8) {
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u8;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u8;
    let y = (yoe + era * 400) as i32 + i32::from(m <= 2);
    (y, m, d)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_dir() -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "ampulse-tls-{}",
            crate::codec::b64_encode(&crate::codec::random_bytes::<6>())
        ));
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn civil_dates() {
        assert_eq!(civil_from_days(0), (1970, 1, 1));
        assert_eq!(civil_from_days(19_782), (2024, 2, 29));
        assert_eq!(civil_from_days(20_755), (2026, 10, 29));
    }

    #[test]
    fn validity_is_ten_years() {
        // 2026-10-29 12:00 UTC
        let now = 20_755 * 86_400 + 43_200;
        assert_eq!(validity(now), ((2026, 10, 28), (2036, 10, 29)));
        // 29 февраля 2024 -> 28 февраля 2034
        let feb29 = 19_782 * 86_400;
        assert_eq!(validity(feb29), ((2024, 2, 28), (2034, 2, 28)));
    }

    #[test]
    fn certificate_is_created_once_and_fingerprint_is_stable() {
        let dir = temp_dir();
        let now = 1_800_000_000;
        let first = Identity::load_or_create(&dir, Some("203.0.113.7"), now).unwrap();
        let second = Identity::load_or_create(&dir, Some("other.example"), now + 1_000_000).unwrap();
        assert_eq!(first.fingerprint(), second.fingerprint());
        assert_eq!(first.fingerprint().len(), 64);
        assert_eq!(fingerprint_of_dir(&dir).unwrap(), first.fingerprint());
        first.server_config().unwrap();
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn half_a_certificate_is_an_error() {
        let dir = temp_dir();
        Identity::load_or_create(&dir, None, 1_800_000_000).unwrap();
        std::fs::remove_file(dir.join(KEY_FILE)).unwrap();
        assert!(Identity::load_or_create(&dir, None, 1_800_000_000).is_err());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn garbage_certificate_is_an_error() {
        let dir = temp_dir();
        std::fs::write(dir.join(CERT_FILE), "nope").unwrap();
        std::fs::write(dir.join(KEY_FILE), "nope").unwrap();
        assert!(Identity::load_or_create(&dir, None, 1_800_000_000).is_err());
        assert!(fingerprint_of_dir(&dir).is_none());
        let _ = std::fs::remove_dir_all(&dir);
    }
}
