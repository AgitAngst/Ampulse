package io.github.agitangst.ampulse.relay

import org.json.JSONArray
import org.json.JSONObject

/**
 * Состояние пары «владелец ↔ близкий» на телефоне (openspec: relay-client). Хранится в JSON-файле `filesDir`
 * (как `server_watch.json`): адрес ретранслятора и отпечаток его сертификата из QR знакомства, идентификаторы
 * обоих ящиков и ключ пары. Роль определяет направление: владелец пишет в ящик «владелец → близкий» и слушает
 * обратный, близкий — наоборот.
 *
 * Первая версия: владелец отправляет только `missed`, близкий только слушает (тип `ok` — потом).
 */
data class RelayState(
    val address: String = "",
    val fingerprint: String = "",
    /** Куда этот телефон пишет. */
    val boxTo: String = "",
    /** Откуда этот телефон читает. */
    val boxFrom: String = "",
    /** Base64URL ключа пары: секрет есть только на двух телефонах. */
    val pairKey: String = "",
    val role: String = ROLE_OWNER,
    /** Подтверждённый курсор ящика [boxFrom]: всё до него на сервере стёрто. */
    val cursor: Long = 0,
    /** Очередь отправки: шифртекст и время постановки; старше 7 дней — выбрасывается. */
    val queue: List<Queued> = emptyList(),
    /** Последние расшифрованные уведомления для экрана «Близкие». */
    val inbox: List<Notice> = emptyList(),
    val lastCheckAt: Long = 0,
    val lastError: String? = null,
) {
    val paired: Boolean get() = address.isNotEmpty() && boxTo.isNotEmpty() && boxFrom.isNotEmpty() && pairKey.isNotEmpty()

    /** Есть ли что отправить (у близкого очереди нет). */
    val pending: Queued? get() = queue.firstOrNull()

    fun toJson(): String = JSONObject()
        .put("v", VERSION)
        .put("address", address)
        .put("fp", fingerprint)
        .put("to", boxTo)
        .put("from", boxFrom)
        .put("key", pairKey)
        .put("role", role)
        .put("cursor", cursor)
        .put("queue", JSONArray().apply { queue.forEach { put(JSONObject().put("sealed", RelayBase64Std.encode(it.sealed)).put("at", it.at)) } })
        .put("inbox", JSONArray().apply { inbox.forEach { put(it.toJson()) } })
        .put("checked", lastCheckAt)
        .putOpt("error", lastError)
        .toString()

    /** Поставить сообщение в очередь: зашифровать для ящика [boxTo] и запомнить время. */
    fun enqueued(plain: ByteArray, nowSec: Long): RelayState =
        copy(queue = queue + Queued(RelayCrypto.seal(RelayBase64.url(pairKey), boxTo, plain), nowSec))

    /** Убрать отправленное из очереди. */
    fun sent(): RelayState = copy(queue = queue.drop(1))

    /** Принять страницу: расшифровать, разобрать, добавить к [inbox], сдвинуть курсор. */
    fun received(boxId: String, page: RelayClient.Page, nowSec: Long): RelayState {
        val key = RelayBase64.url(pairKey)
        var latest = cursor
        val fresh = page.messages.mapNotNull { m ->
            latest = maxOf(latest, m.seq)
            val plain = RelayCrypto.open(key, boxId, m.sealed) ?: return@mapNotNull null
            Notice.parse(plain, m.at)
        }
        val deduped = (fresh.filter { n -> inbox.none { it.id == n.id } } + inbox)
            .sortedByDescending { it.ts }
            .take(MAX_INBOX)
        return copy(inbox = deduped, cursor = latest)
    }

    /** Забыть просроченное: очередь и уведомления старше 7 дней. */
    fun pruned(nowSec: Long): RelayState {
        val keep = nowSec - KEEP_SECS
        return copy(queue = queue.filter { it.at >= keep }, inbox = inbox.filter { it.at >= keep })
    }

    companion object {
        const val ROLE_OWNER = "owner"
        const val ROLE_LISTENER = "listener"
        const val VERSION = 1
        const val MAX_INBOX = 20
        const val KEEP_SECS = 7L * 24 * 3600

        fun fromJson(text: String?): RelayState {
            val o = text?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return RelayState()
            return RelayState(
                address = o.optString("address"),
                fingerprint = o.optString("fp"),
                boxTo = o.optString("to"),
                boxFrom = o.optString("from"),
                pairKey = o.optString("key"),
                role = o.optString("role", ROLE_OWNER),
                cursor = o.optLong("cursor"),
                queue = (0 until o.optJSONArray("queue").length()).mapNotNull { i ->
                    val q = o.optJSONArray("queue").optJSONObject(i) ?: return@mapNotNull null
                    val sealed = runCatching { RelayBase64Std.decode(q.optString("sealed")) }.getOrNull() ?: return@mapNotNull null
                    Queued(sealed, q.optLong("at"))
                },
                inbox = (0 until o.optJSONArray("inbox").length()).mapNotNull { i ->
                    Notice.fromJson(o.optJSONArray("inbox").optJSONObject(i) ?: return@mapNotNull null)
                },
                lastCheckAt = o.optLong("checked"),
                lastError = o.optString("error").takeIf { it.isNotEmpty() },
            )
        }

        /** Сообщение о пропуске приёма: тот же вид, что в серверном образце. */
        fun missedMessage(id: String, ts: Long, med: String, who: String): ByteArray =
            JSONObject().put("t", "missed").put("id", id).put("ts", ts)
                .put("p", JSONObject().put("med", med).put("who", who))
                .toString()
                .toByteArray(Charsets.UTF_8)
    }
}

/** Ждущее отправки сообщение: шифртекст для ящика и время постановки в очередь. */
data class Queued(val sealed: ByteArray, val at: Long)

/** Расшифрованное уведомление для экрана «Близкие». */
data class Notice(val id: String, val ts: Long, val at: Long, val med: String, val who: String) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("ts", ts).put("at", at).put("med", med).put("who", who)

    companion object {
        fun parse(plain: ByteArray, at: Long): Notice? {
            val o = runCatching { JSONObject(String(plain, Charsets.UTF_8)) }.getOrNull() ?: return null
            if (o.optString("t") != "missed") return null
            val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
            val p = o.optJSONObject("p") ?: return null
            return Notice(id, o.optLong("ts"), at, p.optString("med"), p.optString("who"))
        }

        fun fromJson(o: JSONObject): Notice? {
            val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
            return Notice(id, o.optLong("ts"), o.optLong("at"), o.optString("med"), o.optString("who"))
        }
    }
}

/** Стандартный Base64 для файла состояния (шифртекст в JSON). */
object RelayBase64Std {
    fun encode(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    fun decode(text: String): ByteArray = java.util.Base64.getDecoder().decode(text)
}
