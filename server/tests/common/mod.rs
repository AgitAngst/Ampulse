//! Общее для интеграционных тестов: настоящий HTTPS-сервер на порту 0 и клиент, который проверяет
//! отпечаток сертификата так же, как это будет делать приложение. Все данные выдуманные.

#![allow(dead_code)]

use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use ampulse_server::api::{self, ApiOptions, RunningServer};
use ampulse_server::clock::ManualClock;
use ampulse_server::codec::{b64_encode, hex, random_bytes, sha256};
use ampulse_server::ratelimit::RateConfig;
use ampulse_server::store::{Limits, Store};
use ampulse_server::tls::Identity;
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::{verify_tls12_signature, verify_tls13_signature, CryptoProvider};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{DigitallySignedStruct, SignatureScheme};
use tokio::io::{AsyncReadExt, AsyncWriteExt};

/// Время в тестах стартует отсюда (октябрь 2027) и двигается руками.
pub const START: i64 = 1_800_000_000;
pub const DAY: i64 = 86_400;

/// Свой каталог под тест, на диске сборки (E:).
pub fn temp_dir(tag: &str) -> PathBuf {
    let dir = PathBuf::from(env!("CARGO_TARGET_TMPDIR")).join(format!("{tag}-{}", b64_encode(&random_bytes::<6>())));
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

/// Ограничение частоты, которое тестам не мешает.
pub fn quiet_rate() -> RateConfig {
    RateConfig {
        burst: 100_000.0,
        per_sec: 100_000.0,
        failure_cost: 1.0,
    }
}

pub struct Env {
    pub server: RunningServer,
    pub clock: Arc<ManualClock>,
    pub store: Arc<Store>,
    pub addr: SocketAddr,
    pub fingerprint: String,
    pub dir: PathBuf,
}

impl Env {
    pub async fn start() -> Self {
        Self::with(Limits::default(), quiet_rate(), ApiOptions::default()).await
    }

    pub async fn with(limits: Limits, rate: RateConfig, options: ApiOptions) -> Self {
        let dir = temp_dir("relay");
        let clock = Arc::new(ManualClock::new(START));
        let store = Arc::new(Store::open(&dir.join("ampulse.db"), clock.clone(), limits).unwrap());
        let identity = Identity::load_or_create(&dir, Some("127.0.0.1"), START).unwrap();
        let addr = SocketAddr::from(([127, 0, 0, 1], 0));
        let server = api::start(addr, store.clone(), &identity, clock.clone(), rate, options)
            .await
            .unwrap();
        Self {
            addr: server.addr,
            fingerprint: server.fingerprint.clone(),
            server,
            clock,
            store,
            dir,
        }
    }

    pub async fn stop(self) {
        self.server.shutdown().await;
        let _ = std::fs::remove_dir_all(&self.dir);
    }

    /// Запрос с настоящим отпечатком.
    pub async fn call(&self, method: &str, path: &str, token: Option<&str>, body: &[u8]) -> Reply {
        request(self.addr, &self.fingerprint, method, path, token, body)
            .await
            .expect("request must succeed")
    }

    pub async fn create(&self, pair: &Pair) -> Reply {
        let body = serde_json::json!({ "write_hash": pair.write_hash(), "read_hash": pair.read_hash() });
        self.call("PUT", &pair.path(), None, body.to_string().as_bytes()).await
    }

    pub async fn post(&self, pair: &Pair, data: &[u8]) -> Reply {
        self.call("POST", &pair.path(), Some(&pair.write), data).await
    }

    pub async fn read(&self, pair: &Pair, after: u64, wait: u64) -> Reply {
        self.call(
            "GET",
            &format!("{}?after={after}&wait={wait}", pair.path()),
            Some(&pair.read),
            b"",
        )
        .await
    }

    pub async fn ack(&self, pair: &Pair, upto: u64) -> Reply {
        self.call("DELETE", &format!("{}?upto={upto}", pair.path()), Some(&pair.read), b"")
            .await
    }

    /// Создать ящик и убедиться, что он создан.
    pub async fn new_box(&self) -> Pair {
        let pair = Pair::random();
        assert_eq!(self.create(&pair).await.status, 201);
        pair
    }
}

/// Идентификатор ящика и два токена, как их держит клиент.
#[derive(Clone)]
pub struct Pair {
    pub id: String,
    /// Токены в Base64URL, как в заголовке `Authorization`.
    pub write: String,
    pub read: String,
}

impl Pair {
    pub fn random() -> Self {
        Self {
            id: b64_encode(&random_bytes::<16>()),
            write: b64_encode(&random_bytes::<32>()),
            read: b64_encode(&random_bytes::<32>()),
        }
    }

    pub fn path(&self) -> String {
        format!("/v1/box/{}", self.id)
    }

    fn hash_of(token: &str) -> String {
        b64_encode(&sha256(&ampulse_server::codec::b64_decode(token).unwrap()))
    }

    pub fn write_hash(&self) -> String {
        Self::hash_of(&self.write)
    }

    pub fn read_hash(&self) -> String {
        Self::hash_of(&self.read)
    }
}

pub struct Reply {
    pub status: u16,
    pub headers: Vec<(String, String)>,
    pub body: Vec<u8>,
}

impl Reply {
    pub fn json(&self) -> serde_json::Value {
        serde_json::from_slice(&self.body)
            .unwrap_or_else(|e| panic!("not JSON ({e}): {:?}", String::from_utf8_lossy(&self.body)))
    }

    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers
            .iter()
            .find(|(k, _)| k.eq_ignore_ascii_case(name))
            .map(|(_, v)| v.as_str())
    }

    /// Сообщения из ответа на чтение: (номер, данные).
    pub fn messages(&self) -> Vec<(u64, Vec<u8>)> {
        self.json()["messages"]
            .as_array()
            .expect("messages array")
            .iter()
            .map(|m| {
                (
                    m["seq"].as_u64().unwrap(),
                    ampulse_server::codec::b64_decode(m["data"].as_str().unwrap()).unwrap(),
                )
            })
            .collect()
    }

    pub fn seqs(&self) -> Vec<u64> {
        self.messages().into_iter().map(|(seq, _)| seq).collect()
    }
}

/// Проверка по отпечатку: сертификат принимается, только если SHA-256 его DER равен ожидаемому.
#[derive(Debug)]
struct PinVerifier {
    fingerprint: String,
    provider: Arc<CryptoProvider>,
}

impl ServerCertVerifier for PinVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        if hex(&sha256(end_entity.as_ref())) == self.fingerprint {
            Ok(ServerCertVerified::assertion())
        } else {
            Err(rustls::Error::General("certificate fingerprint does not match".into()))
        }
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        verify_tls12_signature(message, cert, dss, &self.provider.signature_verification_algorithms)
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        verify_tls13_signature(message, cert, dss, &self.provider.signature_verification_algorithms)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider.signature_verification_algorithms.supported_schemes()
    }
}

/// Клиентские настройки TLS 1.3 с проверкой отпечатка.
pub fn client_config(fingerprint: &str) -> Arc<rustls::ClientConfig> {
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let verifier = Arc::new(PinVerifier {
        fingerprint: fingerprint.to_owned(),
        provider: provider.clone(),
    });
    let config = rustls::ClientConfig::builder_with_provider(provider)
        .with_protocol_versions(&[&rustls::version::TLS13])
        .unwrap()
        .dangerous()
        .with_custom_certificate_verifier(verifier)
        .with_no_client_auth();
    Arc::new(config)
}

/// Один HTTP/1.1-запрос по TLS. Ошибка — не удалось соединиться или не сошёлся отпечаток.
pub async fn request(
    addr: SocketAddr,
    fingerprint: &str,
    method: &str,
    path: &str,
    token: Option<&str>,
    body: &[u8],
) -> Result<Reply, String> {
    let connector = tokio_rustls::TlsConnector::from(client_config(fingerprint));
    let tcp = tokio::net::TcpStream::connect(addr)
        .await
        .map_err(|e| format!("connect: {e}"))?;
    let name = ServerName::IpAddress(addr.ip().into());
    let mut tls = connector.connect(name, tcp).await.map_err(|e| format!("tls: {e}"))?;

    let mut head = format!("{method} {path} HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n");
    if let Some(token) = token {
        head.push_str(&format!("Authorization: Bearer {token}\r\n"));
    }
    if !body.is_empty() || method == "POST" || method == "PUT" {
        head.push_str(&format!(
            "Content-Length: {}\r\nContent-Type: application/json\r\n",
            body.len()
        ));
    }
    head.push_str("\r\n");
    tls.write_all(head.as_bytes())
        .await
        .map_err(|e| format!("write: {e}"))?;
    tls.write_all(body).await.map_err(|e| format!("write: {e}"))?;
    tls.flush().await.map_err(|e| format!("flush: {e}"))?;

    // Сервер закрывает соединение после ответа; обрыв без close_notify после заголовков не считаем ошибкой.
    let mut raw = Vec::new();
    let mut chunk = [0u8; 4096];
    loop {
        match tokio::time::timeout(Duration::from_secs(60), tls.read(&mut chunk)).await {
            Ok(Ok(0)) | Ok(Err(_)) => break,
            Ok(Ok(n)) => raw.extend_from_slice(&chunk[..n]),
            Err(_) => return Err("timeout waiting for the response".into()),
        }
    }
    parse_response(&raw)
}

fn parse_response(raw: &[u8]) -> Result<Reply, String> {
    let split = raw
        .windows(4)
        .position(|w| w == b"\r\n\r\n")
        .ok_or("no response headers")?;
    let head = std::str::from_utf8(&raw[..split]).map_err(|_| "headers are not UTF-8")?;
    let mut lines = head.split("\r\n");
    let status = lines
        .next()
        .and_then(|l| l.split_whitespace().nth(1))
        .and_then(|s| s.parse().ok())
        .ok_or("bad status line")?;
    let headers: Vec<(String, String)> = lines
        .filter_map(|l| {
            l.split_once(':')
                .map(|(k, v)| (k.trim().to_owned(), v.trim().to_owned()))
        })
        .collect();
    let rest = &raw[split + 4..];
    let chunked = headers
        .iter()
        .any(|(k, v)| k.eq_ignore_ascii_case("transfer-encoding") && v.contains("chunked"));
    let body = if chunked { dechunk(rest)? } else { rest.to_vec() };
    Ok(Reply { status, headers, body })
}

fn dechunk(mut data: &[u8]) -> Result<Vec<u8>, String> {
    let mut out = Vec::new();
    loop {
        let eol = data.windows(2).position(|w| w == b"\r\n").ok_or("bad chunk")?;
        let size = usize::from_str_radix(std::str::from_utf8(&data[..eol]).map_err(|_| "bad chunk")?.trim(), 16)
            .map_err(|_| "bad chunk size")?;
        data = &data[eol + 2..];
        if size == 0 {
            return Ok(out);
        }
        if data.len() < size + 2 {
            return Err("short chunk".into());
        }
        out.extend_from_slice(&data[..size]);
        data = &data[size + 2..];
    }
}
