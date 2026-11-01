package io.github.agitangst.ampulse.relay

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Клиент ретранслятора (openspec: relay-client, docs/dev/relay.md): ящики, сообщения, подтверждение.
 * HTTPS с самоподписанным сертификатом сервера — доверие только по отпечатку SHA-256 из QR знакомства
 * (сменился сертификат — [RelayFailure.Kind.CertChanged], экран «Сервер сменился»).
 *
 * Чистая часть без Android (кроме `org.json`), покрыта тестами JVM на заглушке [FakeRelay].
 */
class RelayClient(
    private val base: String,
    fingerprintHex: String? = null,
    private val connectTimeoutMs: Int = 10_000,
    /** Только для JVM-тестов с заглушкой на 127.0.0.1; в приложении ретранслятор всегда HTTPS. */
    private val allowHttp: Boolean = false,
) {
    /** Ответ `GET`: новые сообщения после курсора. */
    data class Page(val messages: List<RelayMessage>, val hasMore: Boolean)

    private var pinned: String? = fingerprintHex?.lowercase()?.replace(":", "")

    init {
        if (base.isNotEmpty() && !base.startsWith("https://") && !allowHttp) throw RelayFailure(RelayFailure.Kind.BadAddress)
    }

    /** Создать ящик: 201 — новый, 204 — тот же; другие хэши для этого id — [RelayFailure.Kind.Rejected]. */
    fun createBox(boxId: String, pairKey: ByteArray) {
        val body = JSONObject()
            .put("write_hash", hashOf(RelayCrypto.writeToken(pairKey, boxId)))
            .put("read_hash", hashOf(RelayCrypto.readToken(pairKey, boxId)))
        val reply = call("PUT", "/v1/box/$boxId", null, body.toString().toByteArray())
        if (reply.status != 201 && reply.status != 204) throw failure(reply.status, reply.text)
    }

    /** Положить зашифрованное сообщение; ответ — номер по порядку. */
    fun post(boxId: String, pairKey: ByteArray, sealed: ByteArray): Long {
        val reply = call("POST", "/v1/box/$boxId", RelayCrypto.writeToken(pairKey, boxId), sealed)
        if (reply.status != 201) throw failure(reply.status, reply.text)
        return parseObject(reply.text).optLong("seq", -1)
    }

    /** Новые сообщения после [after]; [waitSec] — длинный опрос (до 25). Пустой ответ — тоже нормально. */
    fun fetch(boxId: String, pairKey: ByteArray, after: Long, waitSec: Int = 0): Page {
        val wait = if (waitSec in 0..25) waitSec else 0
        val token = RelayCrypto.readToken(pairKey, boxId)
        val reply = call("GET", "/v1/box/$boxId?after=$after&wait=$wait", token, null)
        if (reply.status != 200) throw failure(reply.status, reply.text)
        val json = parseObject(reply.text)
        val array = json.optJSONArray("messages") ?: JSONArray()
        val messages = (0 until array.length()).mapNotNull { i ->
            val m = array.optJSONObject(i) ?: return@mapNotNull null
            // Сервер кодирует шифртекст Base64URL без заполнения (codec::b64_encode).
            val data = runCatching { RelayBase64.url(m.optString("data")) }.getOrNull() ?: return@mapNotNull null
            RelayMessage(m.optLong("seq"), m.optLong("at"), data)
        }
        return Page(messages, json.optBoolean("more"))
    }

    /** Подтвердить и стереть сообщения до [upto] включительно. */
    fun ack(boxId: String, pairKey: ByteArray, upto: Long) {
        val reply = call("DELETE", "/v1/box/$boxId?upto=$upto", RelayCrypto.readToken(pairKey, boxId), null)
        if (reply.status != 204) throw failure(reply.status, reply.text)
    }

    /** Живость сервера: не касается ящиков; ответ — версия или отказ. */
    fun health(): String? {
        val reply = call("GET", "/v1/health", null, null)
        if (reply.status != 200) throw failure(reply.status, reply.text)
        return parseObject(reply.text).optString("version").takeIf { it.isNotEmpty() }
    }

    // --- транспорт ---

    private class Reply(val status: Int, val text: String)

    private fun hashOf(token: String): String = RelayBase64.url(
        MessageDigest.getInstance("SHA-256").digest(RelayBase64.url(token))
    )

    private fun call(method: String, path: String, token: String?, body: ByteArray?): Reply {
        val conn = try {
            URL(base + path).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw RelayFailure(RelayFailure.Kind.BadAddress, message = e.message)
        }
        try {
            if (conn is HttpsURLConnection) pin(conn)
            conn.requestMethod = method
            conn.connectTimeout = connectTimeoutMs
            // Длинный опрос до 25 секунд: чтение ждёт дольше соединения.
            conn.readTimeout = connectTimeoutMs + 30_000
            conn.instanceFollowRedirects = false
            conn.doOutput = body != null && method != "GET"
            conn.useCaches = false
            conn.setRequestProperty("Accept", "application/json")
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null && method != "GET") {
                conn.setRequestProperty("Content-Type", "application/octet-stream")
                conn.outputStream.use { it.write(body) }
            }
            val status = conn.responseCode
            val text = (if (status >= 400) conn.errorStream else conn.inputStream)?.use { readLimited(it) }.orEmpty()
            return Reply(status, text)
        } catch (e: RelayFailure) {
            throw e
        } catch (e: SSLException) {
            throw RelayFailure(RelayFailure.Kind.CertChanged, "tls")
        } catch (e: UnknownHostException) {
            throw RelayFailure(RelayFailure.Kind.NoConnection, "host")
        } catch (e: IOException) {
            throw RelayFailure(RelayFailure.Kind.NoConnection, "io")
        } finally {
            conn.disconnect()
        }
    }

    /** Доверие только сертификату с отпечатком из QR; самоподписанный без имени узла — имя не проверяем. */
    private fun pin(conn: HttpsURLConnection) {
        val expected = pinned ?: return
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val leaf = chain.firstOrNull() ?: throw CertificateException("no certificate")
                val got = MessageDigest.getInstance("SHA-256").digest(leaf.encoded).joinToString("") { "%02x".format(it) }
                if (got != expected) throw CertificateException("fingerprint mismatch")
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        conn.sslSocketFactory = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), null) }.socketFactory
        conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
    }

    private fun failure(status: Int, text: String): RelayFailure {
        val code = runCatching { JSONObject(text).optString("error") }.getOrNull()?.takeIf { it.isNotEmpty() }
        return when {
            status == 429 -> RelayFailure(RelayFailure.Kind.RateLimited, code)
            status == 404 -> RelayFailure(RelayFailure.Kind.NotFound, code ?: "not_found")
            status == 413 -> RelayFailure(RelayFailure.Kind.TooLarge, "too_large")
            status in 400..499 -> RelayFailure(RelayFailure.Kind.Rejected, code ?: "http_$status")
            else -> RelayFailure(RelayFailure.Kind.Server, code ?: "http_$status")
        }
    }

    private fun parseObject(text: String): JSONObject =
        if (text.isBlank()) JSONObject() else try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw RelayFailure(RelayFailure.Kind.Server, "bad_reply")
        }

    private fun readLimited(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_RESPONSE) throw RelayFailure(RelayFailure.Kind.Server, "too_large")
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val MAX_RESPONSE = 4 * 1024 * 1024
    }
}

/** Сообщение из ящика: номер по порядку, время сервера и шифртекст. */
data class RelayMessage(val seq: Long, val at: Long, val sealed: ByteArray)

/** Отказ ретранслятора: [kind] — что делать приложению. */
class RelayFailure(
    val kind: Kind,
    val code: String? = null,
    val retryAfterSecs: Int? = null,
    message: String? = null,
) : Exception(message ?: "$kind ${code.orEmpty()}") {
    enum class Kind { BadAddress, NoConnection, CertChanged, NotFound, Rejected, RateLimited, TooLarge, Server }
}
