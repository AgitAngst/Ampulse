package io.github.agitangst.ampulse.relay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Полный цикл первой версии против заглушки: знакомство, отправка, приём, подтверждение, повторы. */
class RelayEngineTest {
    private fun client(server: FakeRelay) = RelayClient(server.address, allowHttp = true)

    /** Одна пара на обоих: владелец пишет в ownerTo и читает ownerFrom, близкий — наоборот. */
    private fun pair(server: FakeRelay, role: String, shared: Triple<String, String, String>? = null): RelayState {
        val (ownerTo, ownerFrom, key) = shared ?: Triple(RelayCrypto.newBoxId(), RelayCrypto.newBoxId(), RelayBase64.url(RelayCrypto.newPairKey()))
        val state = if (role == RelayState.ROLE_OWNER) {
            RelayState(address = server.address, boxTo = ownerTo, boxFrom = ownerFrom, pairKey = key, role = role)
        } else {
            RelayState(address = server.address, boxTo = ownerFrom, boxFrom = ownerTo, pairKey = key, role = role)
        }
        RelayEngine(client(server)).pair(state)
        return state
    }

    @Test
    fun ownerSendsAndListenerReceivesTheMissedDose() = FakeRelay().use { server ->
        val engine = RelayEngine(client(server))
        val owner = pair(server, RelayState.ROLE_OWNER)
        val listener = pair(server, RelayState.ROLE_LISTENER, Triple(owner.boxTo, owner.boxFrom, owner.pairKey))
        // пропущенный приём встал в очередь владельца
        val queued = owner.enqueued(RelayState.missedMessage("dose-17", 1800000500, "Vitamin X (demo)", "Galina (demo)"), nowSec = 1_800_000_000)
        assertEquals(1, queued.queue.size)

        var ownerState = engine.tick(queued)
        assertTrue(ownerState.queue.isEmpty(), "очередь пуста после шага")

        val listenerState = engine.tick(listener)
        assertEquals(1, listenerState.inbox.size)
        val notice = listenerState.inbox.single()
        assertEquals("dose-17", notice.id)
        assertEquals("Vitamin X (demo)", notice.med)
        assertEquals("Galina (demo)", notice.who)
        assertEquals(1, listenerState.cursor)
        // повторный шаг ничего не приносит: сообщения подтверждены и стёрты
        val again = engine.tick(listenerState)
        assertEquals(1, again.inbox.size)
        assertEquals(1L, again.cursor)
        // и то же сообщение вторично не всплывает (сервер стёр после ack)
        assertTrue(server.requests.none { it.method == "DELETE" && it.path.endsWith("upto=0") })
    }

    @Test
    fun oneMessagePerTickSavesQuotas() = FakeRelay().use { server ->
        val engine = RelayEngine(client(server))
        var owner = pair(server, RelayState.ROLE_OWNER)
        val now = System.currentTimeMillis() / 1000
        owner = owner.enqueued(RelayState.missedMessage("a", now, "A (demo)", "Me (demo)"), now)
        owner = owner.enqueued(RelayState.missedMessage("b", now, "B (demo)", "Me (demo)"), now)
        owner = engine.tick(owner)
        assertEquals(1, owner.queue.size)
        owner = engine.tick(owner)
        assertTrue(owner.queue.isEmpty())
    }

    @Test
    fun foreignKeyAndGarbageDoNotBreakTheListener() = FakeRelay().use { server ->
        // чужой ключ у слушателя: сервер отдаёт, расшифровка не проходит — шаг не падает
        val engine = RelayEngine(client(server))
        val owner = pair(server, RelayState.ROLE_OWNER)
        val listener = pair(server, RelayState.ROLE_LISTENER, Triple(owner.boxTo, owner.boxFrom, owner.pairKey))
        val foreign = listener.copy(pairKey = RelayBase64.url(RelayCrypto.newPairKey()))
        val t = System.currentTimeMillis() / 1000
        engine.tick(owner.enqueued(RelayState.missedMessage("x", t, "M (demo)", "Who (demo)"), t))
        val state = engine.tick(foreign)
        // расшифровка не прошла — уведомлений нет; подтверждение чужим ключом отклонено, ошибка записана
        assertTrue(state.inbox.isEmpty())
        assertEquals(RelayFailure.Kind.NotFound.name, state.lastError)
    }

    @Test
    fun oldMessagesAreDroppedAfterAWeek() = FakeRelay().use { server ->
        val engine = RelayEngine(client(server), now = { 1_800_100_000 })
        var owner = pair(server, RelayState.ROLE_OWNER)
        owner = owner.enqueued(RelayState.missedMessage("old", 1_700_000_000, "Old (demo)", "Me (demo)"), 1_700_000_000)
        val afterWeek = engine.tick(owner)
        assertTrue(afterWeek.queue.isEmpty(), "просроченное не отправляется")
    }

    @Test
    fun stateSurvivesJsonRoundTrip() = FakeRelay().use { server ->
        var state = pair(server, RelayState.ROLE_OWNER)
        state = state.enqueued(RelayState.missedMessage("j-1", 7, "J (demo)", "Me (demo)"), 7)
        state = state.received(state.boxFrom, RelayClient.Page(listOf(RelayMessage(3, 5, RelayCrypto.seal(RelayBase64.url(state.pairKey), state.boxFrom, RelayState.missedMessage("n-1", 5, "N (demo)", "She (demo)")))), false), 9)
        val loaded = RelayState.fromJson(state.toJson())
        // Queued держит ByteArray — сравниваем поле за полем, а не весь data class
        assertEquals(state.address, loaded.address)
        assertEquals(state.boxTo, loaded.boxTo)
        assertEquals(state.boxFrom, loaded.boxFrom)
        assertEquals(state.pairKey, loaded.pairKey)
        assertEquals(state.role, loaded.role)
        assertEquals(1, loaded.queue.size)
        assertTrue(loaded.queue.single().sealed.contentEquals(state.queue.single().sealed))
        assertEquals(state.queue.single().at, loaded.queue.single().at)
        assertEquals(state.inbox, loaded.inbox)
        assertEquals(3, loaded.cursor)
    }

    @Test
    fun pairingIsIdempotentAndConflictsOnOtherKeys() = FakeRelay().use { server ->
        val client = client(server)
        val key = RelayBase64.url(RelayCrypto.newPairKey())
        val box = RelayCrypto.newBoxId()
        val a = RelayState(address = server.address, boxTo = box, boxFrom = RelayCrypto.newBoxId(), pairKey = key)
        RelayEngine(client).pair(a)
        RelayEngine(client).pair(a) // те же ключи — молча
        val other = a.copy(pairKey = RelayBase64.url(RelayCrypto.newPairKey()))
        var conflict: RelayFailure? = null
        try {
            RelayEngine(client).pair(other)
        } catch (e: RelayFailure) {
            conflict = e
        }
        assertNotNull(conflict)
        assertEquals(RelayFailure.Kind.Rejected, conflict!!.kind)
    }

    @Test
    fun healthAnswersVersion() = FakeRelay().use { server ->
        assertEquals("fake", client(server).health())
    }

    @Test
    fun pairingQrRoundTripsAndSwapsTheBoxes() {
        val owner = RelayState(
            address = "https://relay.example.org:8740", fingerprint = "ab".repeat(32),
            boxTo = RelayCrypto.newBoxId(), boxFrom = RelayCrypto.newBoxId(),
            pairKey = RelayBase64.url(RelayCrypto.newPairKey()), role = RelayState.ROLE_OWNER,
        )
        val qr = PairingQr.encode(owner)
        assertTrue(qr != null && qr.startsWith("ampulse:relay:1:"))
        val listener = PairingQr.decode(qr!!)
        assertNotNull(listener)
        assertEquals(RelayState.ROLE_LISTENER, listener!!.role)
        // близкий пишет в обратный ящик и читает прямой
        assertEquals(owner.boxTo, listener.boxFrom)
        assertEquals(owner.boxFrom, listener.boxTo)
        assertEquals(owner.address, listener.address)
        assertEquals(owner.pairKey, listener.pairKey)
        // чужие коды не разбираются
        assertNull(PairingQr.decode("ampulse:1:whatever"))
        assertNull(PairingQr.decode("https://example.org"))
    }

    @Test
    fun unpairedStateIsLeftAlone() {
        val engine = RelayEngine(RelayClient("https://relay.invalid"))
        val state = engine.tick(RelayState())
        assertNull(state.lastError)
        assertTrue(state.inbox.isEmpty())
    }
}
