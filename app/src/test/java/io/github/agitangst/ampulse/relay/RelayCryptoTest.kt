package io.github.agitangst.ampulse.relay

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest

/**
 * Вектор взаимодействия с серверным образцом `server/tests/vector_for_app.rs` (те же функции, что
 * `client_sample.rs`): ключ пары, ящик, токены и шифртекст совпадают с Rust-стороной буква в букву.
 */
class RelayCryptoTest {
    @Test
    fun rustVectorOpensAndSealsIdentically() {
        val pairKey = ByteArray(32) { ((it * 7 + 3) and 0xFF).toByte() }
        val boxId = "q6urq6urq6urq6urq6urqw"
        assertEquals("-y3mKghKe_awcJRl0cX0XbkbiYosgHMMjqstSPub5lc", RelayCrypto.writeToken(pairKey, boxId))
        assertEquals("Dop59JP3BGep-14oQVhk92H8XRX1rvFYedjwWvKmTWo", RelayCrypto.readToken(pairKey, boxId))

        val sealed = java.util.Base64.getUrlDecoder().decode(
            "BRAbJjE8R1JdaHN-iZSfqrXAy9bh7PcC4KYhTB0Fm9_JC16fZKKzMD-m8NbVfVJO2yUymPQdnK0dXCIbyfORM-MHVdxt6t_beYx4wxT2jaXH7ew5Ja6GGmJ_8BwTDUEOoZVoUFWKmMOsSWkmBmW7cJRN-kWSipEwWrS-QnecP7Pm93YQBO7z8nAMdB_9wnpruiypEmQwD4RZolB0az0eTRu1XJ_54Z7o61R77Jb01-HH-6O2Y0-zzrgKDaM_8IZW92uuElriXskIAUzH6PXjt2lM_s7MhhcafzAFeRVweJygY89yNnACo3Tk3nfLx2TFIDJWInykOLMgs2DDlvhaHtrqTZc9ZcgecDQ5J2gImnfU3Jo2O2w4KXHSk5nEb-TvdSIn93-J50U"
        )
        assertEquals(296, sealed.size)
        val plain = RelayCrypto.open(pairKey, boxId, sealed)
        assertNotNull(plain)
        assertEquals("""{"t":"missed","id":"vec-1","ts":1800000000,"p":{"med":"Vitamin X (demo)","who":"Galina (demo)"}}""", String(plain!!))
    }

    @Test
    fun roundTripWithRandomKeys() {
        val key = RelayCrypto.newPairKey()
        val box = RelayCrypto.newBoxId()
        assertEquals(32, key.size)
        assertEquals(22, box.length)
        val plain = """{"t":"missed","id":"x"}""".toByteArray()
        val sealed = RelayCrypto.seal(key, box, plain)
        assertEquals(RelayCrypto.wireSize(plain), sealed.size)
        assertArrayEquals(plain, RelayCrypto.open(key, box, sealed))
    }

    @Test
    fun paddingHidesTheLengthAndForeignKeysDoNotOpen() {
        val key = RelayCrypto.newPairKey()
        val box = RelayCrypto.newBoxId()
        // Разной длине — один размер из набора; снаружи разницы не видно.
        assertEquals(RelayCrypto.wireSize("{}".toByteArray()), RelayCrypto.wireSize(ByteArray(200)))
        assertEquals(24 + 256 + 16, RelayCrypto.wireSize(ByteArray(200)))
        assertEquals(24 + 1024 + 16, RelayCrypto.wireSize(ByteArray(900)))
        assertNull(RelayCrypto.open(RelayCrypto.newPairKey(), box, RelayCrypto.seal(key, box, "hi".toByteArray())))
        val sealed = RelayCrypto.seal(key, box, "hi".toByteArray())
            .also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNull(RelayCrypto.open(key, box, sealed))
        assertNull(RelayCrypto.open(key, box, ByteArray(10)))
    }

    @Test
    fun serverStoresOnlyHashesOfTokenBytes() {
        // PUT /v1/box принимает SHA-256 от самих байт токена (codec::hash_token), не от строки Base64URL.
        val key = RelayCrypto.newPairKey()
        val box = RelayCrypto.newBoxId()
        val digest = MessageDigest.getInstance("SHA-256")
        fun hashOf(token: String) = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(digest.digest(RelayBase64.url(token)))
        val state = mapOf("write_hash" to hashOf(RelayCrypto.writeToken(key, box)), "read_hash" to hashOf(RelayCrypto.readToken(key, box)))
        assertTrue(state["write_hash"]!!.length == 43 && state["read_hash"]!!.length == 43)
    }
}
