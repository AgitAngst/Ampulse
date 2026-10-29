//! Образец клиента по design.md: ключ пары → токены (HKDF-SHA256) и шифрование сообщения
//! (XChaCha20-Poly1305 с дополнением до набора размеров). Сервер ключей не знает; тест показывает,
//! что весь путь работает через него и что на сервере остаётся только шифртекст. Это не код
//! приложения (клиент — отдельное изменение `relay-client`), а проверка, что протокол для него годится.

mod common;

use ampulse_server::codec::{b64_encode, random_bytes, sha256};
use chacha20poly1305::aead::{Aead, KeyInit};
use chacha20poly1305::{XChaCha20Poly1305, XNonce};
use common::{Env, Pair};
use hkdf::Hkdf;
use sha2::Sha256;

const SIZES: [usize; 4] = [256, 1024, 4096, 8192];
/// Одноразовое число и метка подлинности добавляются к дополненному тексту.
const OVERHEAD: usize = 24 + 16;

fn derive(pair_key: &[u8; 32], info: &str) -> [u8; 32] {
    let mut out = [0u8; 32];
    Hkdf::<Sha256>::new(None, pair_key)
        .expand(info.as_bytes(), &mut out)
        .expect("32 bytes is a valid length");
    out
}

/// Дополнить до ближайшего размера из набора: 2 байта длины, текст, нули.
fn pad(plain: &[u8]) -> Vec<u8> {
    let need = plain.len() + 2;
    let size = SIZES
        .into_iter()
        .find(|s| *s >= need)
        .expect("message fits the biggest size");
    let mut out = Vec::with_capacity(size);
    out.extend_from_slice(&(plain.len() as u16).to_be_bytes());
    out.extend_from_slice(plain);
    out.resize(size, 0);
    out
}

fn unpad(padded: &[u8]) -> Vec<u8> {
    let len = u16::from_be_bytes([padded[0], padded[1]]) as usize;
    padded[2..2 + len].to_vec()
}

fn seal(pair_key: &[u8; 32], box_id: &str, plain: &[u8]) -> Vec<u8> {
    let cipher = XChaCha20Poly1305::new_from_slice(&derive(pair_key, &format!("ampulse/v1/msg/{box_id}"))).unwrap();
    let nonce_bytes = random_bytes::<24>();
    let nonce = XNonce::from(nonce_bytes);
    let mut out = nonce_bytes.to_vec();
    out.extend(cipher.encrypt(&nonce, pad(plain).as_slice()).unwrap());
    out
}

fn open(pair_key: &[u8; 32], box_id: &str, sealed: &[u8]) -> Option<Vec<u8>> {
    let cipher = XChaCha20Poly1305::new_from_slice(&derive(pair_key, &format!("ampulse/v1/msg/{box_id}"))).ok()?;
    let nonce = XNonce::try_from(&sealed[..24]).ok()?;
    cipher.decrypt(&nonce, &sealed[24..]).ok().map(|padded| unpad(&padded))
}

fn pair_from_key(pair_key: &[u8; 32]) -> Pair {
    let id = b64_encode(&random_bytes::<16>());
    Pair {
        write: b64_encode(&derive(pair_key, &format!("ampulse/v1/token/{id}/write"))),
        read: b64_encode(&derive(pair_key, &format!("ampulse/v1/token/{id}/read"))),
        id,
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn sample_client_exchanges_encrypted_messages_through_the_relay() {
    let env = Env::start().await;
    let key = random_bytes::<32>();
    let pair = pair_from_key(&key);
    assert_eq!(env.create(&pair).await.status, 201);

    // Два сообщения разного вида и размера: снаружи видны только размеры из набора.
    let missed = br#"{"t":"missed","id":"m-1","ts":1800000000,"p":{"med":"Vitamin X","who":"Galina"}}"#;
    let card = format!(
        r#"{{"t":"card","id":"c-1","ts":1800000100,"p":"{}"}}"#,
        "x".repeat(2000)
    );
    let (sealed_missed, sealed_card) = (seal(&key, &pair.id, missed), seal(&key, &pair.id, card.as_bytes()));
    assert_eq!(sealed_missed.len(), SIZES[0] + OVERHEAD);
    assert_eq!(sealed_card.len(), SIZES[2] + OVERHEAD);
    assert_eq!(env.post(&pair, &sealed_missed).await.status, 201);
    assert_eq!(env.post(&pair, &sealed_card).await.status, 201);

    // Получатель знает только ключ пары: сам выводит токен чтения и расшифровывает.
    let got = env.read(&pair, 0, 0).await.messages();
    assert_eq!(got.len(), 2);
    assert_eq!(open(&key, &pair.id, &got[0].1).unwrap(), missed);
    assert_eq!(open(&key, &pair.id, &got[1].1).unwrap(), card.as_bytes());
    // Чужой ключ сообщение не открывает.
    assert!(open(&random_bytes::<32>(), &pair.id, &got[0].1).is_none());

    // На сервере нет открытого текста: ни имён, ни названий.
    let bytes = std::fs::read(env.dir.join("ampulse.db")).unwrap();
    for marker in [b"Galina".as_slice(), b"Vitamin X", b"missed"] {
        assert!(
            !bytes.windows(marker.len()).any(|w| w == marker),
            "plaintext leaked to the server database"
        );
    }
    // Ключа пары сервер тоже не получал: в базе нет ни его, ни его хэша.
    assert!(!bytes.windows(32).any(|w| w == key));
    assert!(!bytes.windows(32).any(|w| w == sha256(&key)));

    assert_eq!(env.ack(&pair, got[1].0).await.status, 204);
    assert!(env.read(&pair, 0, 0).await.seqs().is_empty());
    env.stop().await;
}
