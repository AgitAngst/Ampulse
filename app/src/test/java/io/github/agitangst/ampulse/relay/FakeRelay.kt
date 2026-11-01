package io.github.agitangst.ampulse.relay

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Заглушка ретранслятора для JVM-тестов: ящики, сообщения, подтверждение — как `server/src/api.rs`
 * (сами шифртексты заглушка не разбирает). Ставится на 127.0.0.1 со случайным портом; для кода клиента
 * это HTTP — в тестах TLS не гоняется, крипта проверена вектором отдельно.
 */
class FakeRelay : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val boxes = ConcurrentHashMap<String, Pair<String, String>>() // id → write_hash, read_hash
    private val messages = ConcurrentHashMap<String, MutableList<Msg>>()
    private val seqs = ConcurrentHashMap<String, AtomicLong>()
    private val acked = ConcurrentHashMap<String, Long>()

    data class Msg(val seq: Long, val at: Long, val data: ByteArray)
    data class Request(val method: String, val path: String, val auth: String?, val body: ByteArray)

    val requests = mutableListOf<Request>()
    val address: String = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { ex -> handle(ex) }
        server.start()
    }

    private fun handle(ex: HttpExchange) {
        val body = ex.requestBody.readBytes()
        synchronized(requests) { requests.add(Request(ex.requestMethod, ex.requestURI.path + "?" + ex.requestURI.query, ex.getRequestHeaders().getFirst("Authorization"), body)) }
        val path = ex.requestURI.path
        when {
            path == "/v1/health" -> reply(ex, 200, """{"ok":true,"version":"fake"}""")
            path.startsWith("/v1/box/") -> box(ex, path.removePrefix("/v1/box/"), body)
            else -> reply(ex, 404, """{"error":"not_found"}""")
        }
    }

    private fun box(ex: HttpExchange, id: String, body: ByteArray) {
        val auth = ex.getRequestHeaders().getFirst("Authorization")?.removePrefix("Bearer ")
        val known = boxes[id]
        when (ex.requestMethod) {
            "PUT" -> {
                val o = JSONObject(String(body, Charsets.UTF_8))
                if (known == null) {
                    boxes[id] = o.getString("write_hash") to o.getString("read_hash")
                    reply(ex, 201, "{}")
                } else if (known == (o.getString("write_hash") to o.getString("read_hash"))) reply(ex, 204, "")
                else reply(ex, 409, """{"error":"conflict"}""")
            }
            "POST" -> {
                if (known == null || !hash(auth!!).contentEquals(b64urlDecode(known.first))) return reply(ex, 404, """{"error":"not_found"}""")
                if (body.size > 8192) return reply(ex, 413, """{"error":"too_large"}""")
                val seq = seqs.getOrPut(id) { AtomicLong() }.incrementAndGet()
                messages.getOrPut(id) { mutableListOf() }.add(Msg(seq, 1800000000, body))
                reply(ex, 201, """{"seq":$seq}""")
            }
            "GET" -> {
                if (known == null || !hash(auth!!).contentEquals(b64urlDecode(known.second))) return reply(ex, 404, """{"error":"not_found"}""")
                val after = ex.requestURI.query?.split("after=")?.get(1)?.split("&")?.first()?.toLongOrNull() ?: 0
                val upto = acked[id] ?: 0
                val list = (messages[id] ?: emptyList()).filter { it.seq > maxOf(after, upto) }
                val more = false
                val arr = list.joinToString(",") {
                    """{"seq":${it.seq},"at":${it.at},"data":"${Base64.getUrlEncoder().withoutPadding().encodeToString(it.data)}"}"""
                }
                reply(ex, 200, """{"messages":[$arr],"more":$more}""")
            }
            "DELETE" -> {
                if (known == null || !hash(auth!!).contentEquals(b64urlDecode(known.second))) return reply(ex, 404, """{"error":"not_found"}""")
                val upto = ex.requestURI.query?.split("upto=")?.get(1)?.toLongOrNull() ?: 0
                acked[id] = maxOf(acked[id] ?: 0, upto)
                reply(ex, 204, "")
            }
            else -> reply(ex, 400, """{"error":"bad"}""")
        }
    }

    private fun hash(token: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(Base64.getUrlDecoder().decode(token))

    private fun b64urlDecode(text: String): ByteArray = Base64.getUrlDecoder().decode(text)

    private fun reply(ex: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
        ex.close()
    }

    override fun close() {
        server.stop(0)
    }
}

class IOExceptionSink : IOException()
