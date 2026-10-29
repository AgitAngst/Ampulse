package io.github.agitangst.ampulse.sync

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Отказ при разговоре с сервером. [kind] — что делать приложению, [code] — код сервера (`bad_credentials`, `conflict`…)
 * или подробность связи (`tls`, `host`), [retryAfterSecs] — сколько ждать при [Kind.RateLimited].
 */
class SyncFailure(
    val kind: Kind,
    val code: String? = null,
    val retryAfterSecs: Int? = null,
    message: String? = null,
) : Exception(message ?: "${kind.name}${code?.let { ": $it" }.orEmpty()}") {
    enum class Kind {
        /** Адрес не разобрать. */
        BadAddress,

        /** Адрес начинается с `http://` — а нужен HTTPS. */
        Insecure,

        /** Нет связи, сервер не ответил, не тот сертификат. */
        NoConnection,

        /** Сервер не узнал токен (или логин и пароль при входе): 401. */
        Unauthorized,

        /** Слишком много попыток: 429. */
        RateLimited,

        /** Сервер отказал с кодом ошибки (400, 403, 409…). */
        Rejected,

        /** Сервер ответил неожиданно: 5xx или не JSON. */
        Server,
    }
}

/** Вход выполнен: токен для всех дальнейших запросов. */
data class Session(val accountId: String, val token: String)

/** Итог одной записи выгрузки по ответу сервера. */
data class MirrorReply(val kind: String, val id: String, val ok: Boolean, val error: String?)

/**
 * Клиент сервера Ampulse: вход, выход и выгрузка («зеркало»). `java.net.HttpURLConnection` и `org.json`, без
 * зависимостей. HTTPS обязателен; [allowHttp] — только для тестов на JVM. Вызовы блокирующие — звать на `Dispatchers.IO`.
 * Пароль нигде не хранится и не пишется в журнал.
 */
class MirrorClient(
    address: String,
    allowHttp: Boolean = false,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000,
) {
    private val base: String = normalizeAddress(address, allowHttp)

    fun login(login: String, password: String): Session = credentials("/api/v1/login", login, password)

    /** Создать аккаунт (если сервер позволяет): после этого сразу выполнен вход. */
    fun register(login: String, password: String): Session = credentials("/api/v1/register", login, password)

    /** Отозвать токен на сервере. */
    fun logout(token: String) {
        call("/api/v1/logout", token, JSONObject())
    }

    /** Одна выгрузка (до 500 записей каждого вида); ответ — по записи в порядке обработки. */
    fun mirror(token: String, body: JSONObject): List<MirrorReply> {
        val reply = call("/api/v1/mirror", token, body)
        val results = reply.optJSONArray("results") ?: throw SyncFailure(SyncFailure.Kind.Server, "bad_reply")
        return (0 until results.length()).map { i ->
            val r = results.getJSONObject(i)
            MirrorReply(r.optString("kind"), r.optString("id"), r.optBoolean("ok"), r.optString("error").takeIf { it.isNotEmpty() })
        }
    }

    private fun credentials(path: String, login: String, password: String): Session {
        val reply = call(path, null, JSONObject().put("login", login).put("password", password))
        val token = reply.optString("token")
        if (token.isEmpty()) throw SyncFailure(SyncFailure.Kind.Server, "bad_reply")
        return Session(reply.optString("account_id"), token)
    }

    /** POST с JSON; успех — тело ответа как объект (пустое тело — пустой объект). */
    private fun call(path: String, token: String?, body: JSONObject): JSONObject {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val conn = try {
            URL(base + path).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw SyncFailure(SyncFailure.Kind.BadAddress, message = e.message)
        }
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            // Перенаправление унесло бы токен на другой адрес (а то и на http) — не следуем.
            conn.instanceFollowRedirects = false
            conn.doOutput = true
            conn.useCaches = false
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            // Тело не потоковое (буфер): иначе на 401 HttpURLConnection отдаёт IOException вместо тела с кодом ошибки.
            conn.outputStream.use { it.write(bytes) }
            val status = conn.responseCode
            val text = (if (status >= 400) conn.errorStream else conn.inputStream)?.use { readLimited(it) }.orEmpty()
            if (status in 200..299) return parseObject(text)
            throw failure(status, text, conn.getHeaderField("Retry-After"))
        } catch (e: SyncFailure) {
            throw e
        } catch (e: UnknownHostException) {
            throw SyncFailure(SyncFailure.Kind.NoConnection, "host")
        } catch (e: SSLException) {
            throw SyncFailure(SyncFailure.Kind.NoConnection, "tls")
        } catch (e: IOException) {
            throw SyncFailure(SyncFailure.Kind.NoConnection, "io")
        } finally {
            conn.disconnect()
        }
    }

    private fun failure(status: Int, text: String, retryAfterHeader: String?): SyncFailure {
        val json = runCatching { JSONObject(text) }.getOrNull()
        val code = json?.optString("error")?.takeIf { it.isNotEmpty() }
        return when {
            status == 429 -> SyncFailure(
                SyncFailure.Kind.RateLimited, code,
                json?.optInt("retry_after_secs", 0)?.takeIf { it > 0 } ?: retryAfterHeader?.trim()?.toIntOrNull(),
            )
            status == 401 -> SyncFailure(SyncFailure.Kind.Unauthorized, code)
            status in 400..499 -> SyncFailure(SyncFailure.Kind.Rejected, code ?: "http_$status")
            else -> SyncFailure(SyncFailure.Kind.Server, code ?: "http_$status")
        }
    }

    private fun parseObject(text: String): JSONObject {
        if (text.isBlank()) return JSONObject()
        return try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw SyncFailure(SyncFailure.Kind.Server, "bad_reply")
        }
    }

    private fun readLimited(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_RESPONSE) throw SyncFailure(SyncFailure.Kind.Server, "too_large")
        }
        return out.toString(Charsets.UTF_8.name())
    }

    companion object {
        /** Ответ больше этого — не наш сервер: 500 записей × 6 видов занимают на порядок меньше. */
        private const val MAX_RESPONSE = 4 * 1024 * 1024

        /**
         * Привести адрес к виду `https://хост[:порт][/путь]` без хвостового `/`. Без схемы подставляется `https://`;
         * `http://` — [SyncFailure.Kind.Insecure] (кроме тестов), всё прочее — [SyncFailure.Kind.BadAddress].
         */
        fun normalizeAddress(text: String, allowHttp: Boolean = false): String {
            val typed = text.trim()
            if (typed.isEmpty()) throw SyncFailure(SyncFailure.Kind.BadAddress)
            val withScheme = if ("://" in typed) typed else "https://$typed"
            val uri = try {
                URI(withScheme)
            } catch (e: URISyntaxException) {
                throw SyncFailure(SyncFailure.Kind.BadAddress)
            }
            val scheme = uri.scheme?.lowercase()
            if (scheme == "http" && !allowHttp) throw SyncFailure(SyncFailure.Kind.Insecure)
            if (scheme != "https" && !(scheme == "http" && allowHttp)) throw SyncFailure(SyncFailure.Kind.BadAddress)
            if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null) {
                throw SyncFailure(SyncFailure.Kind.BadAddress)
            }
            val port = if (uri.port >= 0) ":${uri.port}" else ""
            return "$scheme://${uri.host.lowercase()}$port${uri.rawPath.orEmpty().trimEnd('/')}"
        }
    }
}
