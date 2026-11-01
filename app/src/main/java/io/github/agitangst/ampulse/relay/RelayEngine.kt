package io.github.agitangst.ampulse.relay

/**
 * Один шаг ретранслятора (openspec: relay-client): отправить одно сообщение из очереди, забрать новые из
 * своего ящика, подтвердить их серверу. Чистая часть без Android — сеть идёт через [RelayClient], состояние
 * считается здесь и покрыто тестами JVM. Отказ сети не выбрасывает: он ложится в [RelayState.lastError], а
 * сделанное до него сохраняется через [onSave] — оборванный шаг продолжится со следующего.
 *
 * Вызывается каждые 15 минут (WorkManager) и при открытии приложения; только сборка `github`.
 */
class RelayEngine(
    private val client: RelayClient,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    /** Шаг: вернуть новое состояние; бросает только ошибки адреса (невосстановимые). */
    fun tick(start: RelayState, onSave: (RelayState) -> Unit = {}): RelayState {
        var state = start.pruned(now())
        if (!state.paired) return state
        try {
            state = sendQueue(state, onSave)
            state = receive(state, onSave)
            onSave(state.copy(lastError = null, lastCheckAt = now()))
            return state.copy(lastError = null, lastCheckAt = now())
        } catch (e: RelayFailure) {
            val failed = state.copy(lastError = e.kind.name)
            onSave(failed)
            return failed
        }
    }

    /** Одно сообщение за шаг: экономия батареи и квот важнее скорости. */
    private fun sendQueue(state: RelayState, onSave: (RelayState) -> Unit): RelayState {
        val queued = state.pending ?: return state
        client.post(state.boxTo, RelayBase64.url(state.pairKey), queued.sealed)
        val sent = state.sent()
        onSave(sent)
        return sent
    }

    private fun receive(state: RelayState, onSave: (RelayState) -> Unit): RelayState {
        var latest = state
        while (true) {
            val page = client.fetch(state.boxFrom, RelayBase64.url(state.pairKey), latest.cursor)
            if (page.messages.isEmpty()) return latest
            latest = latest.received(state.boxFrom, page, now())
            client.ack(state.boxFrom, RelayBase64.url(state.pairKey), latest.cursor)
            onSave(latest)
            if (!page.hasMore) return latest
        }
    }

    /** Знакомство владельца: создать оба ящика (идемпотентно — повтор теми же ключами молчит). */
    fun pair(state: RelayState): RelayState {
        if (!state.paired) return state
        client.createBox(state.boxTo, RelayBase64.url(state.pairKey))
        client.createBox(state.boxFrom, RelayBase64.url(state.pairKey))
        return state
    }
}
