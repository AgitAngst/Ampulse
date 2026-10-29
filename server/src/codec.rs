//! Мелкие общие вещи: Base64URL, хэш токена, случайные байты, сравнение за постоянное время.

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use sha2::{Digest, Sha256};

/// Длина хэша SHA-256.
pub const HASH_LEN: usize = 32;
/// Длина идентификатора ящика в байтах (до Base64URL).
pub const BOX_ID_BYTES: usize = 16;

/// Хэш токена, как его хранит сервер.
pub type TokenHash = [u8; HASH_LEN];

/// Base64URL без дополнения.
pub fn b64_encode(bytes: &[u8]) -> String {
    URL_SAFE_NO_PAD.encode(bytes)
}

/// Обратное к [`b64_encode`]; чужой алфавит и дополнение `=` — ошибка.
pub fn b64_decode(text: &str) -> Option<Vec<u8>> {
    URL_SAFE_NO_PAD.decode(text.as_bytes()).ok()
}

/// SHA-256 произвольных байт.
pub fn sha256(bytes: &[u8]) -> [u8; HASH_LEN] {
    let mut out = [0u8; HASH_LEN];
    out.copy_from_slice(&Sha256::digest(bytes));
    out
}

/// Хэш токена: SHA-256 от байт, которые лежат в Base64URL-токене клиента.
pub fn hash_token(token: &[u8]) -> TokenHash {
    sha256(token)
}

/// Шестнадцатеричная строка строчными буквами.
pub fn hex(bytes: &[u8]) -> String {
    use std::fmt::Write;
    bytes.iter().fold(String::with_capacity(bytes.len() * 2), |mut out, b| {
        let _ = write!(out, "{b:02x}");
        out
    })
}

/// Сравнение за постоянное время: по времени ответа нельзя понять, сколько байт совпало.
pub fn ct_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut diff = 0u8;
    for (x, y) in a.iter().zip(b) {
        diff |= x ^ y;
    }
    // `black_box` не даёт оптимизатору заменить цикл на ранний выход.
    std::hint::black_box(diff) == 0
}

/// Случайные байты от системы.
pub fn random_bytes<const N: usize>() -> [u8; N] {
    let mut out = [0u8; N];
    getrandom::fill(&mut out).expect("system random source is unavailable");
    out
}

/// Идентификатор ящика правильной формы: Base64URL от 16 байт.
pub fn valid_box_id(id: &str) -> bool {
    id.len() == 22 && b64_decode(id).is_some_and(|raw| raw.len() == BOX_ID_BYTES)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn base64_round_trip_and_rejects_padding() {
        let raw = [1u8, 2, 250, 251, 252];
        let text = b64_encode(&raw);
        assert_eq!(b64_decode(&text).unwrap(), raw);
        assert!(b64_decode(&format!("{text}=")).is_none());
        assert!(b64_decode("a+b/").is_none());
    }

    #[test]
    fn sha256_known_vector() {
        assert_eq!(
            hex(&sha256(b"abc")),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
    }

    #[test]
    fn ct_eq_checks_content_and_length() {
        assert!(ct_eq(b"abc", b"abc"));
        assert!(!ct_eq(b"abc", b"abd"));
        assert!(!ct_eq(b"abc", b"ab"));
    }

    #[test]
    fn box_id_shape() {
        assert!(valid_box_id(&b64_encode(&[7u8; BOX_ID_BYTES])));
        assert!(!valid_box_id(&b64_encode(&[7u8; 15])));
        assert!(!valid_box_id("short"));
        assert!(!valid_box_id(&"=".repeat(22)));
    }
}
