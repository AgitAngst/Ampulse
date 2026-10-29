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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Просмотр чужих профилей против настоящего сервера (ручной прогон, в обычный `test` не входит). Включается
 * `AMPULSE_LIVE_URL` (например `http://127.0.0.1:8499`, сервер запущен с `--open-registration`); `AMPULSE_LIVE_LOGIN` и
 * `AMPULSE_LIVE_PASSWORD` — придумайте на один прогон. Заводятся два аккаунта: владелец (логин) и зритель (логин + `b`).
 * Данные выдуманные; в конце всё стирается.
 *
 * Ход: владелец выкладывает зеркалом профиль «Мама» и создаёт приглашение → зритель принимает код и получает профиль →
 * владелец отмечает приём → зритель видит изменение → владелец убирает зрителя → профиль у зрителя пропадает.
 */
@EnabledIfEnvironmentVariable(named = "AMPULSE_LIVE_URL", matches = ".+")
class WatchLiveServerTest {
    private val url = System.getenv("AMPULSE_LIVE_URL")!!
    private val ownerLogin = System.getenv("AMPULSE_LIVE_LOGIN") ?: error("Set AMPULSE_LIVE_LOGIN")
    private val password = System.getenv("AMPULSE_LIVE_PASSWORD") ?: error("Set AMPULSE_LIVE_PASSWORD")
    private val viewerLogin = ownerLogin + "b"

    private val salt = ByteArray(16) { (it * 7 + 3).toByte() }
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now(zone)
    private val builder = MirrorBuilder(salt, zone, "Me")

    private fun call(method: String, path: String, token: String, body: JSONObject? = null): Pair<Int, JSONObject> {
        val c = URL(url + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val status = c.responseCode
        val text = (if (status >= 400) c.errorStream else c.inputStream)?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
        c.disconnect()
        return status to (if (text.isBlank()) JSONObject() else JSONObject(text))
    }

    private fun snapshot(taken: Boolean): SyncSnapshot {
        val medicines = listOf(
            SyncMedicine(Medicine(1, "Метформин (демо)", "850 мг", MedForm.Tablet, "табл.", note = "с едой", profileId = 5), false),
            SyncMedicine(Medicine(2, "Витамин D3 (демо)", "2000 МЕ", MedForm.Capsule, "капс.", profileId = 5), false),
        )
        val schedules = listOf(
            Schedule(10, 1, Plan(PlanKind.Daily, listOf(DoseTime(LocalTime.of(8, 0), 1.0), DoseTime(LocalTime.of(20, 0), 1.0)), today.minusDays(5))),
            Schedule(11, 2, Plan(PlanKind.Daily, listOf(DoseTime(LocalTime.of(12, 0), 1.0)), today.minusDays(5))),
        )
        val morning = today.minusDays(1).atTime(8, 0)
        val events = if (taken) listOf(DoseEvent(100, 1, 10, morning, morning.plusMinutes(7), EventStatus.Taken, 1.0)) else emptyList()
        return SyncSnapshot(listOf(Profile(5, "Мама (демо)", ProfileKind.Person)), medicines, schedules, events)
    }

    @Test
    fun invitationViewUpdateAndRevoke() {
        val admin = MirrorClient(url, allowHttp = true)
        fun enter(login: String) = try {
            admin.register(login, password)
        } catch (e: SyncFailure) {
            admin.login(login, password)
        }
        val owner = enter(ownerLogin)
        val viewer = enter(viewerLogin)

        // 1. Владелец выкладывает зеркалом профиль «Мама» и создаёт приглашение наблюдателя.
        val engine = SyncEngine(admin)
        var state = SyncState()
        val window = builder.window(today)
        fun upload(s: SyncSnapshot) = engine.sync(owner.token, builder.build(s, today), window, state, 0) { state = it }
        val first = upload(snapshot(false))
        assertTrue(first.rejected.isEmpty(), first.rejected.toString())
        val momId = SyncIds.profile(salt, 5)
        val (inviteStatus, invite) = call("POST", "/api/v1/profiles/$momId/invites", owner.token, JSONObject().put("role", "viewer"))
        assertEquals(201, inviteStatus, invite.toString())
        val code = invite.getString("code")

        // 2. Зритель: до кода чужих профилей нет; неверный код — not_found.
        val watch = WatchEngine(admin)
        var cache = watch.refresh(viewer.token, WatchCache(accountKey = "live")) {}
        assertTrue(cache.profiles.isEmpty(), cache.profiles.keys.toString())
        val wrong = assertThrows(SyncFailure::class.java) { admin.acceptInvite(viewer.token, "ZZZZ-ZZZZ-ZZZZ") }
        assertEquals("not_found", wrong.code)

        // 3. Код вводят как придётся (регистр, дефисы, пробелы): профиль приходит с ролью и поясом.
        val profile = admin.acceptInvite(viewer.token, " " + code.lowercase().replace("-", " - ") + " ")
        assertEquals("Мама (демо)", profile.name)
        assertEquals("viewer", profile.role)
        assertTrue(profile.timezone.isNotEmpty())
        // Второй раз тем же кодом — не подходит (использован); своё приглашение владелец принять не может.
        assertEquals("not_found", assertThrows(SyncFailure::class.java) { admin.acceptInvite(viewer.token, code) }.code)
        cache = cache.accepted(profile)
        assertTrue(profile.id in cache.pending)
        cache = watch.refresh(viewer.token, cache) {}

        // 4. Кэш: чужой профиль, его лекарства и приёмы; выложенный зеркалом профиль зрителя (если был) не мешает.
        assertEquals(setOf(momId), cache.profiles.keys)
        assertEquals(2, cache.meds.size)
        val perDay = 3
        val sinceWindow = cache.intakes.values.filter { it.profileId == momId }
        assertTrue(sinceWindow.size >= perDay * 15, "intakes: ${sinceWindow.size}")
        assertTrue(cache.pending.isEmpty())
        assertNull(cache.error)
        assertTrue(cache.serverTime > 0)
        val day = WatchToday.build(cache, momId, WatchToday.nowMillis(cache, System.currentTimeMillis()))
        assertNotNull(day)
        assertTrue(day!!.rows.isNotEmpty(), "today rows")
        val yesterdayMorning = sinceWindow.first { it.plannedAt == today.minusDays(1).atTime(8, 0).atZone(zone).toEpochSecond() }
        assertEquals(WatchStatus.PENDING, yesterdayMorning.status)
        val cursor = cache.cursor

        // 5. Владелец отмечает приём → зритель получает только изменение.
        upload(snapshot(true))
        cache = watch.refresh(viewer.token, cache) {}
        assertTrue(cache.cursor >= cursor)
        val changed = cache.intakes.getValue(yesterdayMorning.id)
        assertEquals(WatchStatus.TAKEN, changed.status)
        assertNotNull(changed.takenAt)
        // Тот же ответ без изменений ничего не меняет.
        val again = watch.refresh(viewer.token, cache) {}
        assertEquals(cache.intakes, again.intakes)
        assertEquals(cache.cursor, again.cursor)

        // 6. «Убрать» на телефоне: пока доступ есть, профиль не возвращается; новый код снимает пометку.
        val hidden = again.removed(momId)
        val afterHide = watch.refresh(viewer.token, hidden) {}
        assertTrue(afterHide.shown.isEmpty())
        assertTrue(afterHide.intakes.isEmpty())
        assertTrue(momId in afterHide.hidden)
        val (inv2Status, invite2) = call("POST", "/api/v1/profiles/$momId/invites", owner.token, JSONObject().put("role", "viewer"))
        assertEquals(201, inv2Status)
        // Профиль у зрителя уже есть — повторный код отвечает conflict.
        assertEquals("conflict", assertThrows(SyncFailure::class.java) { admin.acceptInvite(viewer.token, invite2.getString("code")) }.code)
        val shownAgain = watch.refresh(viewer.token, afterHide.accepted(profile)) {}
        assertEquals(setOf(momId), shownAgain.shown.map { it.id }.toSet())
        assertTrue(shownAgain.intakes.values.any { it.profileId == momId })

        // 7. Владелец убирает зрителя → при следующем обновлении профиль пропадает у зрителя со всем, что к нему относится.
        val (delStatus, _) = call("DELETE", "/api/v1/profiles/$momId/members/${viewer.accountId}", owner.token)
        assertEquals(204, delStatus)
        val gone = watch.refresh(viewer.token, shownAgain) {}
        assertTrue(gone.profiles.isEmpty())
        assertTrue(gone.meds.isEmpty())
        assertTrue(gone.intakes.isEmpty())
        assertFalse(momId in gone.hidden)

        if (System.getenv("AMPULSE_LIVE_KEEP") != null) return
        // 8. Убрать за собой.
        engine.erase(owner.token, state) { state = it }
        admin.logout(viewer.token)
        admin.logout(owner.token)
    }
}
