package io.github.agitangst.ampulse.sync

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Крошечный «сервер» на `com.sun.net.httpserver` для тестов клиента: вход, выход и выгрузка с теми же правилами, что у
 * настоящего (имя 1–80 знаков, лекарство — в чужом профиле нельзя, удаление уносит вложенное). Режимы сбоев —
 * переменная [mode]. Данные выдуманные.
 */
class FakeServer : AutoCloseable {
    class Request(val path: String, val auth: String?, val body: JSONObject, val query: String? = null, val method: String = "POST")

    private val http: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val requests = CopyOnWriteArrayList<Request>()

    val profiles = LinkedHashMap<String, JSONObject>()
    val meds = LinkedHashMap<String, JSONObject>()
    val intakes = LinkedHashMap<String, JSONObject>()

    /** `ok`, `401`, `429`, `500`, `drop` (оборвать связь), `garbage` (не JSON), `short` (меньше ответов, чем записей). */
    @Volatile var mode = "ok"

    /** Для /login и /register: статус и код ошибки вместо успеха. */
    @Volatile var credentialsError: Pair<Int, String>? = null

    val address: String get() = "http://127.0.0.1:${http.address.port}"

    init {
        http.createContext("/") { x -> handle(x) }
        http.start()
    }

    // --- Общий доступ и чтение (просмотр чужих профилей): журнал изменений с номерами, как у настоящего сервера ---

    private class Entry(val seq: Long, val kind: String, val id: String, val profileId: String, val json: JSONObject?)

    /** Профили, доступные аккаунту (с ролью), в том числе свои (`owner`). */
    val shared = LinkedHashMap<String, JSONObject>()
    private val journal = ArrayList<Entry>()
    private var seq = 0L

    /** Коды приглашений → профиль. */
    val invites = LinkedHashMap<String, String>()

    /** Профили, которыми ещё не поделились: появляются в [shared] после принятия кода. */
    val pending = LinkedHashMap<String, JSONObject>()

    /** `ok`, `401`, `429`, `500`, `drop`, `garbage` — режим сбоя чтения (/sync и /invites/accept). */
    @Volatile var readMode = "ok"

    /** Сервер «забыл» удаления до этого номера: запрос с `0 < since < forgetBelow` получает `resync`. */
    @Volatile var forgetBelow = 0L

    @Volatile var serverTime = 1_800_000_000L

    fun share(id: String, name: String, role: String, kind: String = "person", tz: String = "Europe/Berlin") {
        shared[id] = JSONObject().put("id", id).put("name", name).put("kind", kind).put("note", "").put("created_at", 1_700_000_000L)
            .put("role", role).put("timezone", tz)
    }

    fun unshare(id: String) {
        shared.remove(id)
    }

    fun offer(id: String, name: String, code: String, kind: String = "person", tz: String = "Europe/Berlin") {
        pending[id] = JSONObject().put("id", id).put("name", name).put("kind", kind).put("note", "").put("created_at", 1_700_000_000L)
            .put("role", "viewer").put("timezone", tz)
        invites[code] = id
    }

    fun putMed(id: String, profileId: String, name: String, dose: String = "", note: String = "", form: String = "pill") {
        val o = JSONObject().put("id", id).put("profile_id", profileId).put("name", name).put("form", form).put("dose", dose)
            .put("note", note).put("archived", false).put("created_at", 1_700_000_000L)
        journal += Entry(++seq, "med", id, profileId, o)
    }

    fun putIntake(id: String, medId: String, profileId: String, plannedAt: Long, status: String = "pending", takenAt: Long? = null) {
        val o = JSONObject().put("id", id).put("med_id", medId).put("profile_id", profileId).put("schedule_id", JSONObject.NULL)
            .put("planned_at", plannedAt).put("status", status).put("taken_at", takenAt ?: JSONObject.NULL).put("note", "").put("overdue", false)
        journal += Entry(++seq, "intake", id, profileId, o)
    }

    fun removeIntake(id: String, profileId: String) {
        journal += Entry(++seq, "rm_intake", id, profileId, null)
    }

    fun removeMed(id: String, profileId: String) {
        journal += Entry(++seq, "rm_med", id, profileId, null)
    }

    /** Удаление «забыто» сервером: следа в журнале нет (так бывает за пределами срока хранения). */
    fun forget(id: String) {
        journal.removeAll { it.id == id }
    }

    val lastSeq: Long get() = seq

    fun syncRequests() = requests.filter { it.path == "/api/v1/sync" }

    private fun query(x: HttpExchange): Map<String, String> =
        (x.requestURI.rawQuery ?: "").split('&').filter { it.contains('=') }
            .associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    /** Ответить сбоем, если включён [readMode]; `true` — ответ уже отправлен. */
    private fun readFailure(x: HttpExchange): Boolean {
        when (readMode) {
            "401" -> reply(x, 401, """{"error":"unauthorized"}""")
            "429" -> {
                x.responseHeaders.add("Retry-After", "7")
                reply(x, 429, """{"error":"too_many_attempts","retry_after_secs":7}""")
            }
            "500" -> reply(x, 500, """{"error":"internal"}""")
            "drop" -> x.close()
            "garbage" -> reply(x, 200, "<html>not json</html>")
            else -> return false
        }
        return true
    }

    private fun syncPage(x: HttpExchange) {
        if (readFailure(x)) return
        val q = query(x)
        val since = q["since"]?.toLongOrNull() ?: 0L
        val limit = q["limit"]?.toIntOrNull() ?: 500
        val only = q["profile"]
        if (only != null && only !in shared) return reply(x, 404, """{"error":"not_found"}""")
        val resync = since > 0 && since < forgetBelow
        val from = if (resync) 0L else since
        // Вещь, менявшаяся несколько раз, приходит один раз; удалённая — только в removed.
        val latest = journal.groupBy { it.kind.removePrefix("rm_") + "/" + it.id }.mapValues { it.value.last() }.values.toSet()
        val matching = journal.filter { it in latest && it.seq > from && it.profileId in shared && (only == null || it.profileId == only) }
        val page = matching.take(limit)
        val cursor = page.lastOrNull()?.seq ?: from
        val out = JSONObject()
            .put("server_time", serverTime)
            .put("profiles", JSONArray().also { a -> shared.values.forEach { a.put(it) } })
            .put("cursor", cursor)
            .put("more", matching.size > page.size)
            .put("meds", JSONArray().also { a -> page.filter { it.kind == "med" }.forEach { a.put(it.json) } })
            .put("schedules", JSONArray())
            .put("intakes", JSONArray().also { a -> page.filter { it.kind == "intake" }.forEach { a.put(it.json) } })
            .put(
                "removed",
                JSONObject()
                    .put("meds", JSONArray(page.filter { it.kind == "rm_med" }.map { it.id }))
                    .put("schedules", JSONArray())
                    .put("intakes", JSONArray(page.filter { it.kind == "rm_intake" }.map { it.id })),
            )
        if (resync) out.put("resync", true)
        reply(x, 200, out.toString())
    }

    private fun accept(x: HttpExchange, body: JSONObject) {
        if (readFailure(x)) return
        val code = body.optString("code").filter { it.isLetterOrDigit() }.uppercase()
        val profile = invites[code] ?: return reply(x, 404, """{"error":"not_found"}""")
        if (profile in shared) return reply(x, 409, """{"error":"conflict"}""")
        invites.remove(code)
        shared[profile] = pending.getValue(profile)
        reply(x, 200, shared.getValue(profile).toString())
    }

    fun mirrorRequests() = requests.filter { it.path == "/api/v1/mirror" }

    override fun close() = http.stop(0)

    private fun handle(x: HttpExchange) {
        try {
            val text = x.requestBody.readBytes().toString(Charsets.UTF_8)
            val body = if (text.isBlank()) JSONObject() else JSONObject(text)
            val path = x.requestURI.path
            requests += Request(path, x.requestHeaders.getFirst("Authorization"), body, x.requestURI.rawQuery, x.requestMethod)
            when (path) {
                "/api/v1/sync" -> syncPage(x)
                "/api/v1/invites/accept" -> accept(x, body)
                "/api/v1/login", "/api/v1/register" -> credentials(x, path)
                "/api/v1/logout" -> reply(x, 204, "")
                "/api/v1/mirror" -> mirror(x, body)
                else -> reply(x, 404, """{"error":"not_found"}""")
            }
        } catch (e: Exception) {
            reply(x, 500, """{"error":"internal"}""")
        }
    }

    private fun credentials(x: HttpExchange, path: String) {
        credentialsError?.let { (status, code) ->
            if (status == 429) {
                x.responseHeaders.add("Retry-After", "42")
                return reply(x, 429, """{"error":"$code","retry_after_secs":42}""")
            }
            return reply(x, status, """{"error":"$code"}""")
        }
        reply(x, if (path.endsWith("register")) 201 else 200, """{"account_id":"acc-demo-0001","token":"tok-demo-0001"}""")
    }

    private fun mirror(x: HttpExchange, body: JSONObject) {
        when (mode) {
            "401" -> return reply(x, 401, """{"error":"unauthorized"}""")
            "429" -> {
                x.responseHeaders.add("Retry-After", "42")
                return reply(x, 429, """{"error":"too_many_attempts","retry_after_secs":42}""")
            }
            "500" -> return reply(x, 500, """{"error":"internal"}""")
            "drop" -> return x.close()
            "garbage" -> return reply(x, 200, "<html>not json</html>")
        }
        val results = JSONArray()
        fun result(kind: String, id: String, error: String?) {
            results.put(JSONObject().put("kind", kind).put("id", id).put("ok", error == null).also { if (error != null) it.put("error", error) })
        }
        fun items(name: String) = body.optJSONArray(name) ?: JSONArray()
        for (o in items("profiles").objects()) {
            val name = o.optString("name")
            if (name.isBlank() || name.length > 80) result("profile", o.getString("id"), "bad_name")
            else { profiles[o.getString("id")] = o; result("profile", o.getString("id"), null) }
        }
        for (o in items("meds").objects()) {
            val name = o.optString("name")
            when {
                name.isBlank() || name.length > 80 -> result("med", o.getString("id"), "bad_name")
                o.getString("profile_id") !in profiles -> result("med", o.getString("id"), "not_found")
                else -> { meds[o.getString("id")] = o; result("med", o.getString("id"), null) }
            }
        }
        for (o in items("intakes").objects()) {
            if (o.getString("med_id") !in meds) result("intake", o.getString("id"), "not_found")
            else { intakes[o.getString("id")] = o; result("intake", o.getString("id"), null) }
        }
        val delete = body.optJSONObject("delete") ?: JSONObject()
        for (id in delete.optJSONArray("intakes").strings()) { intakes.remove(id); result("intake", id, null) }
        for (id in delete.optJSONArray("meds").strings()) {
            meds.remove(id)
            intakes.values.removeIf { it.getString("med_id") == id }
            result("med", id, null)
        }
        for (id in delete.optJSONArray("profiles").strings()) {
            profiles.remove(id)
            val gone = meds.filterValues { it.getString("profile_id") == id }.keys
            meds.keys.removeAll(gone)
            intakes.values.removeIf { it.getString("med_id") in gone }
            result("profile", id, null)
        }
        if (mode == "short" && results.length() > 0) results.remove(results.length() - 1)
        reply(x, 200, JSONObject().put("results", results).toString())
    }

    private fun reply(x: HttpExchange, status: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        x.responseHeaders.add("Content-Type", "application/json")
        x.sendResponseHeaders(status, if (status == 204) -1 else bytes.size.toLong())
        if (status != 204) x.responseBody.use { it.write(bytes) }
        x.close()
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
}
