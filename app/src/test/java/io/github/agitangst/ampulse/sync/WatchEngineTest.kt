package io.github.agitangst.ampulse.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Обновление кэша чужих профилей против крошечного сервера в JVM: страницы, resync, новые профили, убранные. Данные выдуманные. */
class WatchEngineTest {
    private val t0 = 1_800_000_000L

    private fun client(server: FakeServer) = MirrorClient(server.address, allowHttp = true)

    private fun engine(server: FakeServer, limit: Int = 500) = WatchEngine(client(server), limit) { t0 * 1000 + 5_000 }

    private fun refresh(server: FakeServer, cache: WatchCache = WatchCache(), limit: Int = 500): WatchCache =
        engine(server, limit).refresh("tok-demo-0001", cache) {}

    private fun demo(server: FakeServer) {
        server.share("prof-own-0001", "Я (выложено)", "owner")
        server.share("prof-mama-0001", "Мама", "viewer")
        server.share("prof-cat-0001", "Барсик", "guardian", kind = "animal", tz = "Asia/Tokyo")
        server.putMed("med-own-0001", "prof-own-0001", "Своё лекарство")
        server.putIntake("int-own-0001", "med-own-0001", "prof-own-0001", t0)
        server.putMed("med-mama-01", "prof-mama-0001", "Метформин", dose = "850 мг", note = "с едой")
        server.putIntake("int-mama-01", "med-mama-01", "prof-mama-0001", t0 - 3600, "taken", t0 - 3500)
        server.putIntake("int-mama-02", "med-mama-01", "prof-mama-0001", t0 + 3600)
        server.putMed("med-cat-001", "prof-cat-0001", "Таурин")
        server.putIntake("int-cat-001", "med-cat-001", "prof-cat-0001", t0)
    }

    @Test
    fun firstRefreshStoresOnlyOthersAndNeedsNoSecondRequest() = FakeServer().use { server ->
        demo(server)
        val cache = refresh(server)
        assertEquals(setOf("prof-mama-0001", "prof-cat-0001"), cache.profiles.keys)
        assertEquals(setOf("med-mama-01", "med-cat-001"), cache.meds.keys)
        assertEquals(setOf("int-mama-01", "int-mama-02", "int-cat-001"), cache.intakes.keys)
        assertEquals("viewer", cache.profiles.getValue("prof-mama-0001").role)
        assertEquals("Asia/Tokyo", cache.profiles.getValue("prof-cat-0001").timezone)
        assertEquals(server.lastSeq, cache.cursor)
        assertEquals(t0, cache.serverTime)
        assertNull(cache.error)
        assertTrue(cache.pending.isEmpty())
        // С нуля общий журнал уже принёс всё: отдельных запросов по профилям нет.
        assertEquals(1, server.syncRequests().size)
        val request = server.syncRequests().single()
        assertEquals("GET", request.method)
        assertEquals("Bearer tok-demo-0001", request.auth)
        assertTrue(request.query!!.startsWith("since=0"))
    }

    @Test
    fun pagesAreFollowedWhileMore() = FakeServer().use { server ->
        demo(server)
        val cache = refresh(server, limit = 2)
        assertEquals(setOf("int-mama-01", "int-mama-02", "int-cat-001"), cache.intakes.keys)
        // 8 записей журнала (3 профиля × лекарства и приёмы), по 2 за страницу.
        assertTrue(server.syncRequests().size >= 3)
        assertEquals(server.lastSeq, cache.cursor)
    }

    @Test
    fun secondRefreshAsksOnlyForChanges() = FakeServer().use { server ->
        demo(server)
        val first = refresh(server)
        server.putIntake("int-mama-02", "med-mama-01", "prof-mama-0001", t0 + 3600, "taken", t0 + 3700)
        server.putIntake("int-own-0002", "med-own-0001", "prof-own-0001", t0 + 7200)
        val second = refresh(server, first)
        assertEquals("taken", second.intakes.getValue("int-mama-02").status)
        assertEquals(t0 + 3700, second.intakes.getValue("int-mama-02").takenAt)
        assertFalse("int-own-0002" in second.intakes)
        val last = server.syncRequests().last()
        assertTrue(last.query!!.startsWith("since=${first.cursor}"), last.query)
    }

    @Test
    fun removedIntakesAndMedsDisappear() = FakeServer().use { server ->
        demo(server)
        val first = refresh(server)
        server.removeIntake("int-mama-02", "prof-mama-0001")
        server.removeMed("med-cat-001", "prof-cat-0001")
        val second = refresh(server, first)
        assertFalse("int-mama-02" in second.intakes)
        assertFalse("med-cat-001" in second.meds)
        assertFalse("int-cat-001" in second.intakes)
        assertTrue("int-mama-01" in second.intakes)
    }

    @Test
    fun profileMissingFromTheListIsDroppedWithItsData() = FakeServer().use { server ->
        demo(server)
        val first = refresh(server)
        server.unshare("prof-mama-0001")
        val second = refresh(server, first)
        assertEquals(setOf("prof-cat-0001"), second.profiles.keys)
        assertTrue(second.meds.values.none { it.profileId == "prof-mama-0001" })
        assertTrue(second.intakes.values.none { it.profileId == "prof-mama-0001" })
    }

    @Test
    fun newProfileIsFetchedFromTheStartWithoutMovingTheCursor() = FakeServer().use { server ->
        demo(server)
        // Записи профиля, которым ещё не поделились, уже лежат в журнале: когда доступ откроют, они останутся позади курсора.
        server.putMed("med-dad-0001", "prof-dad-0001", "Аторвастатин")
        server.putIntake("int-dad-0001", "med-dad-0001", "prof-dad-0001", t0 + 100)
        server.putIntake("int-dad-0002", "med-dad-0001", "prof-dad-0001", t0 + 200)
        val first = refresh(server)
        server.share("prof-dad-0001", "Папа", "viewer")
        // Что-то новое у старого профиля: общий курсор уйдёт вперёд.
        server.putIntake("int-mama-03", "med-mama-01", "prof-mama-0001", t0 + 9000)
        val before = server.syncRequests().size
        val second = refresh(server, first)
        val asked = server.syncRequests().drop(before).map { it.query!! }
        assertTrue(asked.any { it.startsWith("since=${first.cursor}") && "profile=" !in it }, asked.toString())
        assertTrue(asked.any { it.startsWith("since=0") && "profile=prof-dad-0001" in it }, asked.toString())
        assertEquals(setOf("int-dad-0001", "int-dad-0002"), second.intakes.keys.filter { it.startsWith("int-dad") }.toSet())
        assertTrue(second.pending.isEmpty())
        assertEquals(server.lastSeq, second.cursor)
        assertTrue("int-mama-03" in second.intakes)
    }

    @Test
    fun newProfileFetchFollowsItsOwnPages() = FakeServer().use { server ->
        demo(server)
        server.putMed("med-dad-0001", "prof-dad-0001", "Аторвастатин")
        for (i in 1..5) server.putIntake("int-dad-000$i", "med-dad-0001", "prof-dad-0001", t0 + i)
        val first = refresh(server)
        server.share("prof-dad-0001", "Папа", "viewer")
        server.putIntake("int-mama-03", "med-mama-01", "prof-mama-0001", t0 + 9000)
        val second = refresh(server, first, limit = 2)
        assertEquals(5, second.intakes.keys.count { it.startsWith("int-dad") })
        val own = server.syncRequests().map { it.query!! }.filter { "profile=prof-dad-0001" in it }
        assertTrue(own.size >= 3, own.toString())
        assertTrue(own[1].startsWith("since=") && !own[1].startsWith("since=0&"), own.toString())
    }

    @Test
    fun resyncThrowsAwayWhatWasStored() = FakeServer().use { server ->
        demo(server)
        val first = refresh(server)
        // Сервер «забыл» удаления; клиент хранит приём, которого уже нет, — после resync его быть не должно.
        server.forget("int-mama-02")
        server.forgetBelow = first.cursor + 1
        server.putIntake("int-mama-09", "med-mama-01", "prof-mama-0001", t0 + 5000)
        val second = refresh(server, first.copy(cursor = 3))
        assertFalse("int-mama-02" in second.intakes)
        assertEquals(setOf("int-mama-01", "int-mama-09", "int-cat-001"), second.intakes.keys)
        assertTrue(second.pending.isEmpty())
    }

    @Test
    fun failureKeepsDataAndLetsTheCallerMarkTheError() = FakeServer().use { server ->
        demo(server)
        val first = refresh(server)
        server.readMode = "500"
        val failure = assertThrows(SyncFailure::class.java) { refresh(server, first) }
        assertEquals(SyncFailure.Kind.Server, failure.kind)
        assertEquals("Server:internal", WatchEngine.errorOf(failure))
        val marked = first.failed(WatchEngine.errorOf(failure))
        assertEquals(first.intakes, marked.intakes)
        assertEquals("Server:internal", marked.error)
        server.readMode = "ok"
        assertNull(refresh(server, marked).error)
    }

    @Test
    fun unauthorizedAndRateLimitAreTold() = FakeServer().use { server ->
        demo(server)
        server.readMode = "401"
        assertEquals(SyncFailure.Kind.Unauthorized, assertThrows(SyncFailure::class.java) { refresh(server) }.kind)
        server.readMode = "429"
        val limited = assertThrows(SyncFailure::class.java) { refresh(server) }
        assertEquals(SyncFailure.Kind.RateLimited, limited.kind)
        assertEquals(7, limited.retryAfterSecs)
        server.readMode = "garbage"
        assertEquals("bad_reply", assertThrows(SyncFailure::class.java) { refresh(server) }.code)
    }

    @Test
    fun noConnectionIsNoConnection() {
        val server = FakeServer()
        val address = server.address
        server.close()
        val failure = assertThrows(SyncFailure::class.java) { WatchEngine(MirrorClient(address, allowHttp = true)).refresh("t", WatchCache()) {} }
        assertEquals(SyncFailure.Kind.NoConnection, failure.kind)
    }

    @Test
    fun progressIsSavedAfterEachPage() = FakeServer().use { server ->
        demo(server)
        val saved = ArrayList<WatchCache>()
        engine(server, limit = 2).refresh("tok-demo-0001", WatchCache()) { saved += it }
        assertTrue(saved.size >= 3)
        assertTrue(saved.map { it.cursor }.zipWithNext().all { (a, b) -> a <= b })
    }

    @Test
    fun hiddenProfileStaysHiddenUntilAcceptedAgain() = FakeServer().use { server ->
        demo(server)
        val first = refresh(server)
        val hidden = first.removed("prof-mama-0001")
        assertTrue(hidden.intakes.values.none { it.profileId == "prof-mama-0001" })
        server.putIntake("int-mama-09", "med-mama-01", "prof-mama-0001", t0 + 5000)
        val second = refresh(server, hidden)
        assertEquals(listOf("prof-cat-0001"), second.shown.map { it.id })
        assertTrue(second.intakes.values.none { it.profileId == "prof-mama-0001" })
        assertTrue("prof-mama-0001" in second.hidden)
        // Доступ закрыли — пометка забывается; откроют снова — профиль появится.
        server.unshare("prof-mama-0001")
        val third = refresh(server, second)
        assertTrue("prof-mama-0001" !in third.hidden)
        server.share("prof-mama-0001", "Мама", "viewer")
        val fourth = refresh(server, third)
        assertTrue("prof-mama-0001" in fourth.profiles)
        assertTrue("int-mama-01" in fourth.intakes)
    }

    @Test
    fun acceptingACodeBringsTheProfileBackAndUnhidesIt() = FakeServer().use { server ->
        demo(server)
        server.offer("prof-dad-0001", "Папа", "ABCDEFGHJKMN")
        server.putMed("med-dad-0001", "prof-dad-0001", "Аторвастатин")
        server.putIntake("int-dad-0001", "med-dad-0001", "prof-dad-0001", t0 + 100)
        val first = refresh(server)
        // Код вводят как придётся: с дефисами, строчными, с пробелами.
        val profile = client(server).acceptInvite("tok-demo-0001", " abcd-efgh-jkmn ")
        assertEquals("Папа", profile.name)
        assertEquals("viewer", profile.role)
        assertEquals("ABCDEFGHJKMN", server.requests.last { it.path == "/api/v1/invites/accept" }.body.getString("code"))
        val after = first.removed("prof-dad-0001").accepted(profile)
        assertFalse("prof-dad-0001" in after.hidden)
        assertTrue("prof-dad-0001" in after.pending)
        val loaded = refresh(server, after)
        assertEquals(setOf("int-dad-0001"), loaded.intakes.keys.filter { it.startsWith("int-dad") }.toSet())
        assertTrue(loaded.pending.isEmpty())
    }

    @Test
    fun conflictWithHiddenProfilesRestoresThem() = FakeServer().use { server ->
        demo(server)
        val first = refresh(server)
        // Оба профиля убрали; код по «Маме» валиден, но доступ уже есть — конфликт.
        val hidden = first.removed("prof-mama-0001").removed("prof-cat-0001")
        val restoring = hidden.restoredHidden()
        assertTrue(restoring.hidden.isEmpty())
        assertEquals(setOf("prof-mama-0001", "prof-cat-0001"), restoring.pending)
        val loaded = refresh(server, restoring)
        assertEquals(setOf("prof-mama-0001", "prof-cat-0001"), loaded.shown.map { it.id }.toSet())
        assertTrue("int-mama-01" in loaded.intakes)
        assertTrue("int-cat-001" in loaded.intakes)
        assertTrue(loaded.pending.isEmpty())
        // У убранного без доступа пометку сняли, но профиль не вернулся.
        val revoked = first.removed("prof-mama-0001")
        server.unshare("prof-mama-0001")
        val afterRevoke = refresh(server, revoked.restoredHidden())
        assertTrue(afterRevoke.hidden.isEmpty())
        assertTrue("prof-mama-0001" !in afterRevoke.profiles)
    }

    @Test
    fun acceptFailuresAreTold() = FakeServer().use { server ->
        server.share("prof-mama-0001", "Мама", "viewer")
        server.offer("prof-mama-0001", "Мама", "AAAAAAAAAAAA")
        val c = client(server)
        val none = assertThrows(SyncFailure::class.java) { c.acceptInvite("t", "ZZZZ-ZZZZ-ZZZZ") }
        assertEquals(SyncFailure.Kind.Rejected, none.kind)
        assertEquals("not_found", none.code)
        val have = assertThrows(SyncFailure::class.java) { c.acceptInvite("t", "AAAA-AAAA-AAAA") }
        assertEquals("conflict", have.code)
        server.readMode = "429"
        val many = assertThrows(SyncFailure::class.java) { c.acceptInvite("t", "AAAA-AAAA-AAAA") }
        assertEquals(SyncFailure.Kind.RateLimited, many.kind)
        assertEquals(7, many.retryAfterSecs)
    }

    @Test
    fun cacheSurvivesAFileRoundTrip() = FakeServer().use { server ->
        demo(server)
        val cache = refresh(server).removed("prof-cat-0001").copy(error = "NoConnection:io")
        val back = WatchCache.fromJson(cache.toJson())
        assertEquals(cache, back)
        assertEquals(WatchCache(), WatchCache.fromJson("{ not json"))
        assertEquals(WatchCache(), WatchCache.fromJson(null))
        assertFalse(back.shown.any { it.id == "prof-cat-0001" })
    }
}
