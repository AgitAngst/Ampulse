//! Вектор взаимодействия для клиента в приложении (openspec: relay-client): фиксированные ключ пары, ящик и
//! сообщение, зашифрованные теми же функциями, что образец клиента. Приложение проверяет по нему свой Tink-слой.
//! Запуск: `cargo test -p ampulse-server vector -- --nocapture` — печатает пару строк для Kotlin-теста.

use ampulse_server::codec::{b64_encode, sha256};
use chacha20poly1305::aead::{Aead, KeyInit};
use chacha20poly1305::{XChaCha20Poly1305, XNonce};
use hkdf::Hkdf;
use sha2::Sha256;

fn derive(pair_key: &[u8; 32], info: &str) -> [u8; 32] {
    let mut out = [0u8; 32];
    Hkdf::<Sha256>::new(None, pair_key)
        .expand(info.as_bytes(), &mut out)
        .unwrap();
    out
}

fn pad(plain: &[u8]) -> Vec<u8> {
    let need = plain.len() + 2;
    let size = [256usize, 1024, 4096, 8192].into_iter().find(|s| *s >= need).unwrap();
    let mut out = Vec::with_capacity(size);
    out.extend_from_slice(&(plain.len() as u16).to_be_bytes());
    out.extend_from_slice(plain);
    out.resize(size, 0);
    out
}

#[test]
fn print_vector_for_the_app() {
    let pair_key: [u8; 32] = core::array::from_fn(|i| (i as u8).wrapping_mul(7).wrapping_add(3));
    let box_id = b64_encode(&[0xAB; 16]);
    let plain = br#"{"t":"missed","id":"vec-1","ts":1800000000,"p":{"med":"Vitamin X (demo)","who":"Galina (demo)"}}"#;
    let write = b64_encode(&derive(&pair_key, &format!("ampulse/v1/token/{box_id}/write")));
    let read = b64_encode(&derive(&pair_key, &format!("ampulse/v1/token/{box_id}/read")));
    let key = derive(&pair_key, &format!("ampulse/v1/msg/{box_id}"));
    let nonce_bytes: [u8; 24] = core::array::from_fn(|i| (i as u8).wrapping_mul(11).wrapping_add(5));
    let cipher = XChaCha20Poly1305::new_from_slice(&key).unwrap();
    let nonce = XNonce::from(nonce_bytes);
    let mut sealed = nonce_bytes.to_vec();
    sealed.extend(cipher.encrypt(&nonce, pad(plain).as_slice()).unwrap());
    println!("VECTOR pair_key_hex={:02x?}", pair_key);
    println!("VECTOR box_id={box_id}");
    println!("VECTOR write_token={write} write_hash={}", b64_encode(&sha256(&derive(&pair_key, &format!("ampulse/v1/token/{box_id}/write")))));
    println!("VECTOR read_token={read} read_hash={}", b64_encode(&sha256(&derive(&pair_key, &format!("ampulse/v1/token/{box_id}/read")))));
    println!("VECTOR plain={}", String::from_utf8_lossy(plain));
    println!("VECTOR sealed_b64={}", b64_encode(&sealed));
    println!("VECTOR sealed_len={}", sealed.len());
}
