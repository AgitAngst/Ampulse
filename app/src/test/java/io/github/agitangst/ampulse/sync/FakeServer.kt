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
    class Request(val path: String, val auth: String?, val body: JSONObject)

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

    fun mirrorRequests() = requests.filter { it.path == "/api/v1/mirror" }

    override fun close() = http.stop(0)

    private fun handle(x: HttpExchange) {
        try {
            val text = x.requestBody.readBytes().toString(Charsets.UTF_8)
            val body = if (text.isBlank()) JSONObject() else JSONObject(text)
            val path = x.requestURI.path
            requests += Request(path, x.requestHeaders.getFirst("Authorization"), body)
            when (path) {
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
