package io.github.agitangst.ampulse.sync

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Клиент против крошечного HTTP-сервера в JVM: вход, выгрузка, отказы. Данные выдуманные. */
class MirrorClientTest {
    private fun failure(block: () -> Unit): SyncFailure = assertThrows(SyncFailure::class.java, block)

    @Test
    fun addressIsNormalisedAndHttpsIsRequired() {
        assertEquals("https://example.org", MirrorClient.normalizeAddress("example.org"))
        assertEquals("https://example.org", MirrorClient.normalizeAddress("  Example.ORG/  "))
        assertEquals("https://example.org:8443/ampulse", MirrorClient.normalizeAddress("https://example.org:8443/ampulse/"))
        assertEquals("https://[::1]:8499", MirrorClient.normalizeAddress("[::1]:8499"))
        assertEquals(SyncFailure.Kind.Insecure, failure { MirrorClient.normalizeAddress("http://example.org") }.kind)
        assertEquals(SyncFailure.Kind.BadAddress, failure { MirrorClient.normalizeAddress("ftp://example.org") }.kind)
        assertEquals(SyncFailure.Kind.BadAddress, failure { MirrorClient.normalizeAddress("") }.kind)
        assertEquals(SyncFailure.Kind.BadAddress, failure { MirrorClient.normalizeAddress("https://") }.kind)
        assertEquals(SyncFailure.Kind.BadAddress, failure { MirrorClient.normalizeAddress("https://user:pw@example.org") }.kind)
        assertEquals(SyncFailure.Kind.BadAddress, failure { MirrorClient.normalizeAddress("https://exa mple.org") }.kind)
        // http — только для тестов на JVM.
        assertEquals("http://127.0.0.1:80", MirrorClient.normalizeAddress("http://127.0.0.1:80/", allowHttp = true))
        assertEquals(SyncFailure.Kind.Insecure, failure { MirrorClient("http://example.org") }.kind)
    }

    @Test
    fun loginSendsCredentialsAndReturnsToken() = FakeServer().use { server ->
        val session = MirrorClient(server.address, allowHttp = true).login("anna_demo", "demo-password-1")
        assertEquals("tok-demo-0001", session.token)
        assertEquals("acc-demo-0001", session.accountId)
        val request = server.requests.single()
        assertEquals("/api/v1/login", request.path)
        assertEquals("anna_demo", request.body.getString("login"))
        assertNull(request.auth)
    }

    @Test
    fun registerAcceptsCreated() = FakeServer().use { server ->
        val session = MirrorClient(server.address, allowHttp = true).register("anna_demo", "demo-password-1")
        assertEquals("tok-demo-0001", session.token)
        assertEquals("/api/v1/register", server.requests.single().path)
    }

    @Test
    fun loginFailuresAreTold() = FakeServer().use { server ->
        val client = MirrorClient(server.address, allowHttp = true)
        server.credentialsError = 401 to "bad_credentials"
        val wrong = failure { client.login("anna_demo", "wrong-password") }
        assertEquals(SyncFailure.Kind.Unauthorized, wrong.kind)
        assertEquals("bad_credentials", wrong.code)
        server.credentialsError = 429 to "too_many_attempts"
        val limited = failure { client.login("anna_demo", "x") }
        assertEquals(SyncFailure.Kind.RateLimited, limited.kind)
        assertEquals(42, limited.retryAfterSecs)
        server.credentialsError = 409 to "conflict"
        val taken = failure { client.register("anna_demo", "demo-password-1") }
        assertEquals(SyncFailure.Kind.Rejected, taken.kind)
        assertEquals("conflict", taken.code)
        server.credentialsError = 403 to "registration_closed"
        assertEquals("registration_closed", failure { client.register("anna_demo", "demo-password-1") }.code)
    }

    @Test
    fun mirrorSendsBearerTokenAndParsesResults() = FakeServer().use { server ->
        val client = MirrorClient(server.address, allowHttp = true)
        val body = JSONObject()
            .put("profiles", org.json.JSONArray().put(MirrorProfile("prof-aaaaaaaa", "Me", "person", "", "UTC").toJson()))
            .put("meds", org.json.JSONArray())
            .put("intakes", org.json.JSONArray())
        val replies = client.mirror("tok-demo-0001", body)
        assertEquals(listOf(MirrorReply("profile", "prof-aaaaaaaa", true, null)), replies)
        assertEquals("Bearer tok-demo-0001", server.requests.single().auth)
        assertTrue(server.profiles.containsKey("prof-aaaaaaaa"))
    }

    @Test
    fun mirrorReportsPerRecordRefusals() = FakeServer().use { server ->
        val body = JSONObject().put("profiles", org.json.JSONArray().put(MirrorProfile("prof-bbbbbbbb", "", "person", "", "UTC").toJson()))
        val replies = MirrorClient(server.address, allowHttp = true).mirror("t", body)
        assertEquals(listOf(MirrorReply("profile", "prof-bbbbbbbb", false, "bad_name")), replies)
    }

    @Test
    fun statusesMapToFailures() = FakeServer().use { server ->
        val client = MirrorClient(server.address, allowHttp = true)
        server.mode = "401"
        assertEquals(SyncFailure.Kind.Unauthorized, failure { client.mirror("t", JSONObject()) }.kind)
        server.mode = "429"
        val limited = failure { client.mirror("t", JSONObject()) }
        assertEquals(SyncFailure.Kind.RateLimited, limited.kind)
        assertEquals(42, limited.retryAfterSecs)
        server.mode = "500"
        val broken = failure { client.mirror("t", JSONObject()) }
        assertEquals(SyncFailure.Kind.Server, broken.kind)
        assertEquals("internal", broken.code)
        server.mode = "garbage"
        assertEquals("bad_reply", failure { client.mirror("t", JSONObject()) }.code)
    }

    @Test
    fun droppedConnectionIsNoConnection() = FakeServer().use { server ->
        server.mode = "drop"
        assertEquals(SyncFailure.Kind.NoConnection, failure { MirrorClient(server.address, allowHttp = true).mirror("t", JSONObject()) }.kind)
    }

    @Test
    fun closedPortIsNoConnection() {
        val address = FakeServer().use { it.address }
        assertEquals(SyncFailure.Kind.NoConnection, failure { MirrorClient(address, allowHttp = true, connectTimeoutMs = 2000).login("a", "b") }.kind)
    }

    @Test
    fun logoutAcceptsEmptyAnswer() = FakeServer().use { server ->
        MirrorClient(server.address, allowHttp = true).logout("tok-demo-0001")
        assertEquals("/api/v1/logout", server.requests.single().path)
        assertEquals("Bearer tok-demo-0001", server.requests.single().auth)
    }
}
