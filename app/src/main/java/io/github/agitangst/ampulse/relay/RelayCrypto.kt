package io.github.agitangst.ampulse.relay

import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.XChaCha20Poly1305
import java.security.SecureRandom

/**
 * Шифрование сообщений ретранслятора (openspec: relay-client, docs/dev/relay.md). Взаимодействие с серверным
 * образцом `server/tests/client_sample.rs`: ключ пары (32 байта, создаёт телефон владельца, близкому — по QR
 * знакомства) разворачивается HKDF-SHA256 в токены ящика и ключ сообщения; само сообщение — XChaCha20-Poly1305
 * с дополнением до набора размеров, чтобы снаружи были видны только размеры из набора.
 *
 * Проводной вид у Tink и Rust-образца один: `nonce(24) ‖ шифртекст ‖ метка(16)` — Tink кладёт своё случайное
 * одноразовое число в начало вывода, серверный образец делает то же вручную; Additional Data пустое.
 *
 * Чистая часть без Android, покрыта тестами JVM. Сервер ключей не знает и хранит только шифртекст.
 */
object RelayCrypto {
    /** Допустимые размеры дополненного текста: 2 байта длины, текст, нули до размера. */
    private val SIZES = intArrayOf(256, 1024, 4096, 8192)

    /** Одноразовое число (24) и метка подлинности (16) — в начале и конце шифртекста. */
    const val NONCE = 24
    const val TAG = 16

    /** Новый ключ пары: 32 случайных байта. */
    fun newPairKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    /** Развёртка ключа пары: HKDF-SHA256 без соли, 32 байта. */
    fun derive(pairKey: ByteArray, info: String): ByteArray = Hkdf.computeHkdf("HMACSHA256", pairKey, ByteArray(0), info.toByteArray(Charsets.UTF_8), 32)

    /** Идентификатор ящика: Base64URL от 16 случайных байт. */
    fun newBoxId(): String = RelayBase64.url(ByteArray(16).also { SecureRandom().nextBytes(it) })

    /** Токены ящика из ключа пары — сервер хранит только их SHA-256. */
    fun writeToken(pairKey: ByteArray, boxId: String): String = RelayBase64.url(derive(pairKey, "ampulse/v1/token/$boxId/write"))

    fun readToken(pairKey: ByteArray, boxId: String): String = RelayBase64.url(derive(pairKey, "ampulse/v1/token/$boxId/read"))

    /** Зашифровать сообщение для ящика: случайное одноразовое число уже в начале вывода Tink. */
    fun seal(pairKey: ByteArray, boxId: String, plain: ByteArray): ByteArray =
        XChaCha20Poly1305(derive(pairKey, "ampulse/v1/msg/$boxId")).encrypt(pad(plain), ByteArray(0))

    /** Расшифровать; чужой ключ или повреждённый текст — null. */
    fun open(pairKey: ByteArray, boxId: String, sealed: ByteArray): ByteArray? {
        if (sealed.size <= NONCE + TAG) return null
        val plain = runCatching {
            XChaCha20Poly1305(derive(pairKey, "ampulse/v1/msg/$boxId")).decrypt(sealed, ByteArray(0))
        }.getOrNull() ?: return null
        return unpad(plain)
    }

    /** Размер из набора, который займёт это сообщение (с одноразовым числом и меткой). */
    fun wireSize(plain: ByteArray): Int = SIZES.first { it >= plain.size + 2 } + NONCE + TAG

    /** Дополнить до ближайшего размера из набора: 2 байта длины, текст, нули. */
    internal fun pad(plain: ByteArray): ByteArray {
        val size = SIZES.first { it >= plain.size + 2 }
        return ByteArray(size).also {
            it[0] = (plain.size shr 8).toByte()
            it[1] = plain.size.toByte()
            plain.copyInto(it, 2)
        }
    }

    internal fun unpad(padded: ByteArray): ByteArray? {
        val len = ((padded[0].toInt() and 0xFF) shl 8) or (padded[1].toInt() and 0xFF)
        if (len > padded.size - 2) return null
        return padded.copyOfRange(2, 2 + len)
    }
}

/** Base64URL без заполнения (`java.util.Base64` без Android доступен и в JVM-тестах). */
object RelayBase64 {
    fun url(bytes: ByteArray): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun url(text: String): ByteArray = java.util.Base64.getUrlDecoder().decode(text)
}
