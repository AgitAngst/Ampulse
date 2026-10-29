package io.github.agitangst.ampulse.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Выгрузка целиком: разница, запросы, состояние — против крошечного сервера в JVM. Данные выдуманные. */
class SyncEngineTest {
    private val window = MirrorWindow(1_000_000, 2_000_000)
    private val profile = MirrorProfile("prof-aaaaaaaa", "Me", "person", "", "Europe/Berlin")
    private val cat = MirrorProfile("prof-bbbbbbbb", "Барсик", "animal", "", "Europe/Berlin")
    private val med = MirrorMed("med-aaaaaaaaaa", profile.id, "Метформин", "pill", "850 мг", "", false)
    private val catMed = MirrorMed("med-bbbbbbbbbb", cat.id, "Добавка", "pill", "", "", false)

    private fun intake(n: Int, at: Long = 1_100_000L + n, status: String = "pending", medId: String = med.id) =
        MirrorIntake("int-%08d".format(n), medId, at, status, if (status == "taken") at + 60 else null, "", true)

    private fun mirrorOf(intakes: List<MirrorIntake>, meds: List<MirrorMed> = listOf(med, catMed), profiles: List<MirrorProfile> = listOf(profile, cat)) =
        MirrorSet(profiles.associateBy { it.id }, meds.associateBy { it.id }, intakes.associateBy { it.id })

    private class Run(val engine: SyncEngine) {
        var state = SyncState()
        var saves = 0
        fun sync(set: MirrorSet, window: MirrorWindow): SyncReport =
            engine.sync("tok", set, window, state, 0) { state = it; saves++ }
    }

    private fun run(server: FakeServer, batch: Int = SyncEngine.BATCH) =
        Run(SyncEngine(MirrorClient(server.address, allowHttp = true), batch))

    @Test
    fun firstUploadThenNothingToDo() = FakeServer().use { server ->
        val r = run(server)
        val set = mirrorOf(listOf(intake(1), intake(2, status = "taken"), intake(3, medId = catMed.id)))
        val first = r.sync(set, window)
        assertEquals(2 + 2 + 3, first.sent)
        assertEquals(7, first.stored)
        assertTrue(first.rejected.isEmpty())
        assertEquals(setOf(profile.id, cat.id), server.profiles.keys)
        assertEquals(3, server.intakes.size)
        assertEquals("taken", server.intakes.getValue("int-00000002").getString("status"))
        assertEquals(1, server.mirrorRequests().size)
        // Повтор: изменений нет — запроса нет вовсе.
        val again = r.sync(set, window)
        assertEquals(0, again.sent)
        assertEquals(0, again.deleted)
        assertEquals(1, server.mirrorRequests().size)
    }

    @Test
    fun changedMarkIsTheOnlyThingResent() = FakeServer().use { server ->
        val r = run(server)
        r.sync(mirrorOf(listOf(intake(1), intake(2))), window)
        val report = r.sync(mirrorOf(listOf(intake(1), intake(2, status = "taken"))), window)
        assertEquals(1, report.sent)
        val body = server.mirrorRequests().last().body
        assertEquals(0, body.getJSONArray("profiles").length())
        assertEquals(0, body.getJSONArray("meds").length())
        assertEquals(1, body.getJSONArray("intakes").length())
        assertEquals("int-00000002", body.getJSONArray("intakes").getJSONObject(0).getString("id"))
        assertEquals("taken", server.intakes.getValue("int-00000002").getString("status"))
    }

    @Test
    fun deletedMedicineIsRemovedWithItsIntakes() = FakeServer().use { server ->
        val r = run(server)
        r.sync(mirrorOf(listOf(intake(1), intake(2, medId = catMed.id))), window)
        val report = r.sync(mirrorOf(listOf(intake(1)), meds = listOf(med)), window)
        assertEquals(2, report.deleted)
        assertFalse(server.meds.containsKey(catMed.id))
        assertEquals(setOf("int-00000001"), server.intakes.keys)
        assertFalse(r.state.meds.containsKey(catMed.id))
        assertFalse(r.state.intakes.containsKey("int-00000002"))
        val deletes = server.mirrorRequests().last().body.getJSONObject("delete")
        assertEquals(listOf(catMed.id), (0 until deletes.getJSONArray("meds").length()).map { deletes.getJSONArray("meds").getString(it) })
    }

    @Test
    fun deletedProfileCascadesInState() = FakeServer().use { server ->
        val r = run(server)
        r.sync(mirrorOf(listOf(intake(1), intake(2, medId = catMed.id))), window)
        // Профиль питомца удалён целиком: ни лекарства, ни приёмов в выгрузке нет.
        r.sync(mirrorOf(listOf(intake(1)), meds = listOf(med), profiles = listOf(profile)), window)
        assertEquals(setOf(profile.id), server.profiles.keys)
        assertEquals(setOf(profile.id), r.state.profiles.keys)
        assertEquals(setOf(med.id), r.state.meds.keys)
        assertEquals(setOf("int-00000001"), r.state.intakes.keys)
    }

    @Test
    fun intakesThatLeftTheWindowStayOnTheServer() = FakeServer().use { server ->
        val r = run(server)
        r.sync(mirrorOf(listOf(intake(1, at = 1_050_000), intake(2, at = 1_500_000))), window)
        // Окно сдвинулось: приём 1 теперь старше его начала и в выгрузке его нет — сервер хранит как есть.
        val moved = MirrorWindow(1_200_000, 2_200_000)
        val report = r.sync(mirrorOf(listOf(intake(2, at = 1_500_000))), moved)
        assertEquals(0, report.deleted)
        assertTrue(server.intakes.containsKey("int-00000001"))
    }

    @Test
    fun largeUploadIsSplitByFiveHundred() = FakeServer().use { server ->
        val r = run(server)
        val many = (1..1201).map { intake(it) }
        val report = r.sync(mirrorOf(many), window)
        assertEquals(1201 + 4, report.sent)
        assertEquals(3, server.mirrorRequests().size)
        assertEquals(listOf(500, 500, 201), server.mirrorRequests().map { it.body.getJSONArray("intakes").length() })
        assertEquals(1201, server.intakes.size)
        // Состояние сохранялось после каждого запроса.
        assertEquals(3, r.saves)
        assertEquals(1201 + 4, r.state.stored)
        // Удаление тоже режется: убрали все приёмы.
        val cleared = r.sync(mirrorOf(emptyList()), window)
        assertEquals(1201, cleared.deleted)
        assertEquals(0, server.intakes.size)
        assertEquals(listOf(500, 500, 201), server.mirrorRequests().drop(3).map { it.body.getJSONObject("delete").getJSONArray("intakes").length() })
    }

    @Test
    fun smallBatchSizeForOddCounts() = FakeServer().use { server ->
        val r = run(server, batch = 2)
        r.sync(mirrorOf((1..5).map { intake(it) }), window)
        assertEquals(3, server.mirrorRequests().size)
        assertEquals(5, server.intakes.size)
    }

    @Test
    fun unauthorizedStopsAndKeepsState() = FakeServer().use { server ->
        val r = run(server)
        val set = mirrorOf(listOf(intake(1)))
        r.sync(set, window)
        val saved = r.state
        server.mode = "401"
        val e = assertThrows(SyncFailure::class.java) { r.sync(mirrorOf(listOf(intake(1), intake(2))), window) }
        assertEquals(SyncFailure.Kind.Unauthorized, e.kind)
        assertEquals(saved, r.state)
    }

    @Test
    fun rateLimitAndNetworkErrorsArePassedUp() = FakeServer().use { server ->
        val r = run(server)
        server.mode = "429"
        val limited = assertThrows(SyncFailure::class.java) { r.sync(mirrorOf(listOf(intake(1))), window) }
        assertEquals(SyncFailure.Kind.RateLimited, limited.kind)
        assertEquals(42, limited.retryAfterSecs)
        server.mode = "drop"
        assertEquals(SyncFailure.Kind.NoConnection, assertThrows(SyncFailure::class.java) { r.sync(mirrorOf(listOf(intake(1))), window) }.kind)
        // Связь вернулась — выгрузка проходит с того места, где остановилась (ничего не потеряно и не задвоено).
        server.mode = "ok"
        val ok = r.sync(mirrorOf(listOf(intake(1))), window)
        assertEquals(2 + 2 + 1, ok.sent)
        assertEquals(1, server.intakes.size)
    }

    @Test
    fun brokenReplyIsAServerFailure() = FakeServer().use { server ->
        val r = run(server)
        server.mode = "short"
        val e = assertThrows(SyncFailure::class.java) { r.sync(mirrorOf(listOf(intake(1))), window) }
        assertEquals(SyncFailure.Kind.Server, e.kind)
        assertEquals("bad_reply", e.code)
        assertEquals(0, r.saves)
    }

    @Test
    fun refusedRecordIsReportedAndNotRetriedWhileUnchanged() = FakeServer().use { server ->
        val r = run(server)
        val bad = cat.copy(name = "")
        val set = mirrorOf(listOf(intake(1), intake(2, medId = catMed.id)), profiles = listOf(profile, bad))
        val first = r.sync(set, window)
        // Профиль с пустым именем сервер не принял; лекарство и приём этого профиля — «нет такого», но не навсегда.
        assertEquals(Rejected("profile", bad.id, "bad_name"), first.rejected.first())
        assertTrue(first.rejected.any { it.kind == "med" && it.code == "not_found" })
        assertFalse(r.state.profiles.containsKey(bad.id))
        assertEquals(listOf("bad_name"), r.state.refusedCodes)
        assertTrue(r.state.profiles.containsKey(profile.id))
        assertTrue(r.state.intakes.containsKey("int-00000001"))
        // Второй раз: отказанное содержимое не шлётся, и его дети — тоже; запроса нет.
        val count = server.mirrorRequests().size
        val second = r.sync(set, window)
        assertEquals(count, server.mirrorRequests().size)
        assertTrue(second.rejected.isEmpty())
        // Имя исправили — запись и вложенное уходят.
        val fixed = mirrorOf(listOf(intake(1), intake(2, medId = catMed.id)), profiles = listOf(profile, bad.copy(name = "Барсик")))
        val third = r.sync(fixed, window)
        assertTrue(third.rejected.isEmpty(), third.rejected.toString())
        assertTrue(server.profiles.containsKey(bad.id))
        assertTrue(r.state.rejected.isEmpty())
        assertEquals(2, server.intakes.size)
    }

    @Test
    fun eraseDeletesEverythingWeUploaded() = FakeServer().use { server ->
        val r = run(server)
        r.sync(mirrorOf(listOf(intake(1), intake(2, medId = catMed.id))), window)
        val engine = SyncEngine(MirrorClient(server.address, allowHttp = true))
        var state = r.state
        val report = engine.erase("tok", state) { state = it }
        assertEquals(6, report.deleted)
        assertEquals(0, report.stored)
        assertTrue(server.profiles.isEmpty() && server.meds.isEmpty() && server.intakes.isEmpty())
        assertEquals(SyncState(), state)
    }

    @Test
    fun uploadBodyUsesTheServerFieldNames() = FakeServer().use { server ->
        val r = run(server)
        r.sync(mirrorOf(listOf(intake(1, status = "taken"))), window)
        val body = server.mirrorRequests().single().body
        val p = body.getJSONArray("profiles").getJSONObject(0)
        assertEquals(setOf("id", "name", "kind", "note", "timezone"), p.keys().asSequence().toSet())
        val m = body.getJSONArray("meds").getJSONObject(0)
        assertEquals(setOf("id", "profile_id", "name", "form", "dose", "note", "archived"), m.keys().asSequence().toSet())
        val i = body.getJSONArray("intakes").getJSONObject(0)
        assertEquals(setOf("id", "med_id", "planned_at", "status", "taken_at", "note", "planned"), i.keys().asSequence().toSet())
        assertEquals(setOf("profiles", "meds", "intakes"), body.getJSONObject("delete").keys().asSequence().toSet())
        assertEquals("Bearer tok", server.mirrorRequests().single().auth)
    }
}
