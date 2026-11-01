package io.github.agitangst.ampulse.relay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Живой прогон против настоящего ретранслятора (`server/`, ampulse-server 0.1.0) с самоподписанным
 * сертификатом — проверяет и протокол, и закрепление отпечатка. Запуск (сервер — `ampulse-server serve`):
 * `RELAY_LIVE_ADDR=https://127.0.0.1:8491 RELAY_LIVE_FP=<отпечаток из журнала сервера>`.
 */
@EnabledIfEnvironmentVariable(named = "RELAY_LIVE_ADDR", matches = ".+")
class RelayLiveServerTest {
    private val address = System.getenv("RELAY_LIVE_ADDR")!!
    private val fingerprint = System.getenv("RELAY_LIVE_FP") ?: error("Set RELAY_LIVE_FP")

    private fun client() = RelayClient(address, fingerprint)

    @Test
    fun pairSendReceiveAckAgainstTheRealServer() {
        val engine = RelayEngine(client())
        assertTrue(engine.pair(RelayState()).boxTo.isEmpty(), "пустое состояние остаётся пустым")

        val key = RelayCrypto.newPairKey()
        val ownerTo = RelayCrypto.newBoxId()
        val ownerFrom = RelayCrypto.newBoxId()
        engine.pair(RelayState(address = address, fingerprint = fingerprint, boxTo = ownerTo, boxFrom = ownerFrom, pairKey = RelayBase64.url(key), role = RelayState.ROLE_OWNER))

        var owner = RelayState(address = address, fingerprint = fingerprint, boxTo = ownerTo, boxFrom = ownerFrom, pairKey = RelayBase64.url(key), role = RelayState.ROLE_OWNER)
        var listener = RelayState(address = address, fingerprint = fingerprint, boxTo = ownerFrom, boxFrom = ownerTo, pairKey = RelayBase64.url(key), role = RelayState.ROLE_LISTENER)

        val ts = System.currentTimeMillis() / 1000
        owner = engine.tick(owner.enqueued(RelayState.missedMessage("live-1", ts, "Vitamin X (demo)", "Galina (demo)"), ts))
        assertTrue(owner.queue.isEmpty(), "сообщение ушло на настоящий сервер")

        listener = engine.tick(listener)
        assertEquals(listOf("live-1"), listener.inbox.map { it.id }, "уведомление пришло и расшифровалось")
        assertEquals(1, listener.cursor)

        // подтверждение стёрло сообщение: повторный шаг ничего не приносит
        val again = engine.tick(listener)
        assertTrue(again.inbox.size == 1 && again.cursor == 1L)

        // живость отвечает версией настоящего сервера
        assertTrue(client().health()!!.startsWith("0."))
    }

    @Test
    fun wrongFingerprintIsRefused() {
        val client = RelayClient(address, "ab".repeat(32))
        var failure: RelayFailure? = null
        try {
            client.health()
        } catch (e: RelayFailure) {
            failure = e
        }
        assertEquals(RelayFailure.Kind.CertChanged, failure?.kind, "чужой отпечаток — отказ до разговора")
    }
}
