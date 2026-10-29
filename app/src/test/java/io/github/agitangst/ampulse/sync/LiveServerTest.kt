package io.github.agitangst.ampulse.sync

import io.github.agitangst.ampulse.domain.DoseEvent
import io.github.agitangst.ampulse.domain.DoseTime
import io.github.agitangst.ampulse.domain.EventStatus
import io.github.agitangst.ampulse.domain.MedForm
import io.github.agitangst.ampulse.domain.Medicine
import io.github.agitangst.ampulse.domain.Plan
import io.github.agitangst.ampulse.domain.PlanKind
import io.github.agitangst.ampulse.domain.Profile
import io.github.agitangst.ampulse.domain.ProfileKind
import io.github.agitangst.ampulse.domain.Schedule
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.net.HttpURLConnection
import java.net.URL
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Проверка клиента против настоящего сервера (ручной прогон, в обычный `test` не входит). Включается переменной
 * окружения `AMPULSE_LIVE_URL` (например `http://127.0.0.1:8499`, сервер запущен с `--open-registration`); логин и пароль
 * — `AMPULSE_LIVE_LOGIN`, `AMPULSE_LIVE_PASSWORD` (обязательны; придумайте на один прогон). Данные выдуманные; в конце всё стирается
 * (кроме прогона с `AMPULSE_LIVE_KEEP`).
 */
@EnabledIfEnvironmentVariable(named = "AMPULSE_LIVE_URL", matches = ".+")
class LiveServerTest {
    private val url = System.getenv("AMPULSE_LIVE_URL")!!
    private val login = System.getenv("AMPULSE_LIVE_LOGIN") ?: error("Set AMPULSE_LIVE_LOGIN")
    private val password = System.getenv("AMPULSE_LIVE_PASSWORD") ?: error("Set AMPULSE_LIVE_PASSWORD")

    private val salt = ByteArray(16) { (it * 11 + 5).toByte() }
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now(zone)
    private val builder = MirrorBuilder(salt, zone, "Me")

    private fun get(path: String, token: String): JSONObject {
        val c = URL(url + path).openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", "Bearer $token")
        return JSONObject(c.inputStream.readBytes().toString(Charsets.UTF_8)).also { c.disconnect() }
    }

    private fun snapshot(takenMetformin: Boolean, withZinc: Boolean): SyncSnapshot {
        val medicines = mutableListOf(
            SyncMedicine(Medicine(1, "Метформин (демо)", "850 мг", MedForm.Tablet, "табл.", profileId = 0), false),
            SyncMedicine(Medicine(2, "Инсулин (демо)", "100 ЕД/мл", MedForm.Injection, "ЕД", profileId = 0), false),
            SyncMedicine(Medicine(3, "Добавка для кота (демо)", "", MedForm.Other, "шт.", profileId = 2), false),
        )
        val schedules = mutableListOf(
            Schedule(10, 1, Plan(PlanKind.Daily, listOf(DoseTime(LocalTime.of(8, 0), 1.0), DoseTime(LocalTime.of(20, 0), 1.0)), today.minusDays(5))),
            Schedule(11, 2, Plan(PlanKind.Weekly, listOf(DoseTime(LocalTime.of(9, 0), 10.0)), today.minusDays(40), weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY))),
            Schedule(12, 3, Plan(PlanKind.Daily, listOf(DoseTime(LocalTime.of(8, 30), 1.0)), today.minusDays(3))),
        )
        if (withZinc) {
            medicines += SyncMedicine(Medicine(4, "Цинк (демо)", "25 мг", MedForm.Capsule, "капс.", profileId = 0), false)
            schedules += Schedule(13, 4, Plan(PlanKind.EveryNDays, listOf(DoseTime(LocalTime.of(10, 0), 1.0)), today.minusDays(6), everyN = 2))
        }
        val morning = today.minusDays(1).atTime(8, 0)
        val events = mutableListOf(
            DoseEvent(100, 1, 10, today.minusDays(1).atTime(20, 0), today.minusDays(1).atTime(20, 12), EventStatus.Skipped, 1.0, "демо-причина"),
            DoseEvent(101, 1, null, null, today.minusDays(2).atTime(13, 0), EventStatus.Extra, 1.0),
        )
        if (takenMetformin) events += DoseEvent(102, 1, 10, morning, morning.plusMinutes(7), EventStatus.Taken, 1.0)
        return SyncSnapshot(listOf(Profile(2, "Барсик (демо)", ProfileKind.Cat)), medicines, schedules, events)
    }

    @Test
    fun uploadRepeatChangeDeleteAndErase() {
        val client = MirrorClient(url, allowHttp = true)
        val session = try {
            client.register(login, password)
        } catch (e: SyncFailure) {
            client.login(login, password)
        }
        val token = session.token
        val engine = SyncEngine(client)
        var state = SyncState()
        val window = builder.window(today)
        fun sync(s: SyncSnapshot): SyncReport {
            val set = builder.build(s, today)
            return engine.sync(token, set, window, state, 0) { state = it }
        }

        // 1. Первая выгрузка: всё, что построено, лежит на сервере.
        val first = builder.build(snapshot(false, true), today)
        val r1 = sync(snapshot(false, true))
        assertTrue(r1.rejected.isEmpty(), r1.rejected.toString())
        assertEquals(first.size, r1.sent)
        val all = get("/api/v1/sync", token)
        assertEquals(first.profiles.size, all.getJSONArray("profiles").length())
        assertEquals(first.meds.size, all.getJSONArray("meds").length())
        assertEquals(first.intakes.size, all.getJSONArray("intakes").length())
        val cursor = all.getLong("cursor")

        // 2. Повтор: ничего лишнего ни в клиенте, ни на сервере.
        val r2 = sync(snapshot(false, true))
        assertEquals(0, r2.sent)
        assertEquals(0, r2.deleted)
        val quiet = get("/api/v1/sync?since=$cursor", token)
        assertEquals(0, quiet.getJSONArray("intakes").length())
        assertEquals(0, quiet.getJSONArray("meds").length())

        // 3. Поменяли отметку (пропущенное утро стало принятым): уходит один приём.
        val r3 = sync(snapshot(true, true))
        assertEquals(1, r3.sent)
        val changed = get("/api/v1/sync?since=$cursor", token)
        assertEquals(1, changed.getJSONArray("intakes").length())
        assertEquals("taken", changed.getJSONArray("intakes").getJSONObject(0).getString("status"))
        val cursor2 = changed.getLong("cursor")

        // 4. Удалили лекарство: оно и его приёмы исчезают с сервера.
        val zincId = SyncIds.medicine(salt, 4)
        val r4 = sync(snapshot(true, false))
        assertTrue(r4.deleted >= 1)
        val removed = get("/api/v1/sync?since=$cursor2", token)
        val removedMeds = removed.getJSONObject("removed").getJSONArray("meds")
        assertTrue((0 until removedMeds.length()).any { removedMeds.getString(it) == zincId })
        val left = get("/api/v1/sync", token)
        assertEquals(first.meds.size - 1, left.getJSONArray("meds").length())
        assertFalse((0 until left.getJSONArray("intakes").length()).any { left.getJSONArray("intakes").getJSONObject(it).getString("med_id") == zincId })

        // `AMPULSE_LIVE_KEEP` — оставить выложенное на сервере для проверки руками (curl); потом прогнать без неё.
        if (System.getenv("AMPULSE_LIVE_KEEP") != null) return

        // 5. Стереть всё выложенное и выйти.
        val erased = engine.erase(token, state) { state = it }
        assertEquals(0, erased.stored)
        assertEquals(0, get("/api/v1/sync", token).getJSONArray("profiles").length())
        client.logout(token)
    }
}
