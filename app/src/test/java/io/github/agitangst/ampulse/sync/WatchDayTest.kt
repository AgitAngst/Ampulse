package io.github.agitangst.ampulse.sync

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** «Сегодня» чужого профиля: день в поясе профиля, окно «Пора», «сейчас» по времени данных; коды приглашений; разбор страниц. */
class WatchDayTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun at(zone: ZoneId, date: String, hh: Int, mm: Int): Long =
        ZonedDateTime.of(LocalDate.parse(date).atTime(hh, mm), zone).toEpochSecond()

    private fun cache(tz: String = "Europe/Berlin", vararg intakes: WatchIntake): WatchCache = WatchCache(
        profiles = mapOf("p1" to WatchProfile("p1", "Мама", "person", "", "viewer", tz)),
        meds = mapOf(
            "m1" to WatchMed("m1", "p1", "Метформин", "pill", "850 мг", "с едой", false),
            "m2" to WatchMed("m2", "p1", "Левотироксин", "pill", "50 мкг", "", false),
        ),
        intakes = intakes.associateBy { it.id },
    )

    private fun intake(id: String, med: String, planned: Long, status: String = "pending", taken: Long? = null) =
        WatchIntake(id, med, "p1", planned, status, taken, "")

    @Test
    fun stateWindowIsThirtyMinutesBeforeAndAnHourAfter() {
        val planned = at(berlin, "2027-01-15", 14, 0)
        val i = intake("i", "m1", planned)
        fun state(deltaMin: Long) = WatchToday.stateOf(i, Instant.ofEpochSecond(planned + deltaMin * 60))
        assertEquals(WatchState.Later, state(-31))
        assertEquals(WatchState.Due, state(-30))
        assertEquals(WatchState.Due, state(0))
        assertEquals(WatchState.Due, state(60))
        assertEquals(WatchState.Overdue, state(61))
        assertEquals(WatchState.Taken, WatchToday.stateOf(i.copy(status = "taken", takenAt = planned), Instant.ofEpochSecond(planned + 9999)))
        assertEquals(WatchState.Skipped, WatchToday.stateOf(i.copy(status = "skipped"), Instant.ofEpochSecond(planned - 9999)))
    }

    @Test
    fun dayIsCutInTheProfilesZoneNotThePhones() {
        // 23:30 в Берлине 15 января — в Токио уже 16-е. Профиль в Берлине: «сегодня» — 15-е.
        val now = at(berlin, "2027-01-15", 23, 30) * 1000
        val late = intake("late", "m1", at(berlin, "2027-01-15", 23, 0))
        val next = intake("next", "m1", at(berlin, "2027-01-16", 0, 30))
        val early = intake("early", "m2", at(berlin, "2027-01-15", 0, 5))
        val prev = intake("prev", "m2", at(berlin, "2027-01-14", 23, 55))
        val day = WatchToday.build(cache("Europe/Berlin", late, next, early, prev), "p1", now)!!
        assertEquals(LocalDate.parse("2027-01-15"), day.date)
        assertEquals(listOf("early", "late"), day.rows.map { it.intake.id })
        val tokyo = WatchToday.build(cache("Asia/Tokyo", late, next, early, prev), "p1", now)!!
        assertEquals(LocalDate.parse("2027-01-16"), tokyo.date)
        assertEquals(listOf("late", "next"), tokyo.rows.map { it.intake.id })
        // Незнакомый пояс — UTC, а не падение.
        assertEquals(ZoneId.of("UTC"), WatchToday.zoneOf(WatchProfile("p", "n", "person", "", "viewer", "Mars/Olympus")))
    }

    @Test
    fun rowsAreOrderedAndCountedForTheRing() {
        val now = at(berlin, "2027-01-15", 13, 52) * 1000
        val c = cache(
            "Europe/Berlin",
            intake("a", "m2", at(berlin, "2027-01-15", 7, 0), "taken", at(berlin, "2027-01-15", 7, 4)),
            intake("b", "m1", at(berlin, "2027-01-15", 8, 30), "skipped"),
            intake("c", "m1", at(berlin, "2027-01-15", 12, 0)),
            intake("d", "m1", at(berlin, "2027-01-15", 14, 0)),
            intake("e", "m2", at(berlin, "2027-01-15", 21, 0)),
        )
        val day = WatchToday.build(c, "p1", now)!!
        assertEquals(listOf("a", "b", "c", "d", "e"), day.rows.map { it.intake.id })
        assertEquals(
            listOf(WatchState.Taken, WatchState.Skipped, WatchState.Overdue, WatchState.Due, WatchState.Later),
            day.rows.map { it.state },
        )
        assertEquals(1, day.taken)
        assertEquals(5, day.total)
        assertEquals("c", day.overdue!!.intake.id)
        assertEquals("d", day.next!!.intake.id)
    }

    @Test
    fun intakeWithoutItsMedicineOrFromAnotherProfileIsSkipped() {
        val now = at(berlin, "2027-01-15", 10, 0) * 1000
        val c = cache("Europe/Berlin", intake("ok", "m1", at(berlin, "2027-01-15", 9, 0)), intake("nomed", "mX", at(berlin, "2027-01-15", 9, 0)))
        assertEquals(listOf("ok"), WatchToday.build(c, "p1", now)!!.rows.map { it.intake.id })
        assertNull(WatchToday.build(c, "missing", now))
    }

    @Test
    fun nowIsPhoneClockCorrectedByServerOffsetOrDataTimeWhenOffline() {
        val serverTime = at(berlin, "2027-01-15", 13, 10)
        val phoneAt = serverTime * 1000 + 120_000 // часы телефона убежали на 2 минуты
        val fresh = WatchCache(serverTime = serverTime, phoneAt = phoneAt)
        // Через 42 минуты по часам телефона «сейчас» по серверу — 13:52.
        val phoneNow = phoneAt + 42 * 60_000
        assertEquals((serverTime + 42 * 60) * 1000, WatchToday.nowMillis(fresh, phoneNow))
        // Нет связи: время данных, часы телефона не двигают «просрочено».
        assertEquals(serverTime * 1000, WatchToday.nowMillis(fresh.failed("NoConnection:io"), phoneNow))
        // Обновлений не было: часы телефона.
        assertEquals(phoneNow, WatchToday.nowMillis(WatchCache(), phoneNow))
    }

    @Test
    fun staleDayDoesNotBecomeOverdueByTheClock() {
        val serverTime = at(berlin, "2027-01-15", 13, 10)
        val c = cache("Europe/Berlin", intake("d", "m1", at(berlin, "2027-01-15", 14, 0))).copy(serverTime = serverTime, phoneAt = serverTime * 1000)
        val phoneLater = serverTime * 1000 + 6 * 3600_000
        val stale = c.failed("NoConnection:io")
        assertEquals(WatchState.Later, WatchToday.build(stale, "p1", WatchToday.nowMillis(stale, phoneLater))!!.rows.single().state)
        assertEquals(WatchState.Overdue, WatchToday.build(c, "p1", WatchToday.nowMillis(c, phoneLater))!!.rows.single().state)
    }

    @Test
    fun codeIsNormalisedAndMustBeTwelveSigns() {
        assertEquals("ABCDEFGHJKMN", WatchCodes.normalize(" abcd-efgh jkmn "))
        assertEquals("ABCDEFGHJKMN", WatchCodes.normalize("abcd–efgh_jkmn"))
        assertEquals("ABCDEFGHJKMN", WatchCodes.normalize("ABCD-EFGH-JKMN-EXTRA"))
        assertEquals("", WatchCodes.normalize("--  --"))
        assertEquals("ABCD-EFG", WatchCodes.pretty("abcdefg"))
        assertEquals("ABCD-EFGH-JKMN", WatchCodes.pretty("abcdefghjkmn"))
        assertTrue(WatchCodes.isComplete("abcd-efgh-jkmn"))
        assertFalse(WatchCodes.isComplete("abcd-efgh-jkm"))
        assertEquals("", WatchCodes.normalize("кириллица"))
    }

    @Test
    fun pageParsingReadsWhatWeNeedAndIgnoresTheRest() {
        val page = WatchPage.parse(
            JSONObject(
                """{"server_time":1800000000,"cursor":12,"more":true,"resync":true,
                  "profiles":[{"id":"p1","name":"Мама","kind":"person","note":"","created_at":1,"role":"viewer","timezone":"Europe/Berlin"},{"name":"без id"}],
                  "meds":[{"id":"m1","profile_id":"p1","name":"Метформин","form":"pill","dose":"850 мг","note":"","archived":false},{"id":"bad"}],
                  "schedules":[{"id":"s1"}],
                  "intakes":[{"id":"i1","med_id":"m1","profile_id":"p1","schedule_id":null,"planned_at":1800000100,"status":"taken","taken_at":1800000200,"note":"","overdue":false},
                             {"id":"i2","med_id":"m1","profile_id":"p1","planned_at":1800000300,"status":"pending","taken_at":null}],
                  "removed":{"meds":["m9"],"schedules":["s9"],"intakes":["i9","i8"]}}""",
            ),
        )
        assertEquals(12, page.cursor)
        assertTrue(page.more)
        assertTrue(page.resync)
        assertEquals(listOf("p1"), page.profiles.map { it.id })
        assertEquals(listOf("m1"), page.meds.map { it.id })
        assertEquals(1_800_000_200L, page.intakes[0].takenAt)
        assertNull(page.intakes[1].takenAt)
        assertEquals(listOf("m9"), page.removedMeds)
        assertEquals(listOf("i9", "i8"), page.removedIntakes)
        assertEquals("viewer", page.roles["p1"])
    }

    @Test
    fun pageWithoutCursorIsNotOurServer() {
        val failure = org.junit.jupiter.api.Assertions.assertThrows(SyncFailure::class.java) { WatchPage.parse(JSONObject("""{"ok":true}""")) }
        assertEquals("bad_reply", failure.code)
    }

    @Test
    fun oldIntakesArePrunedAndOwnProfilesNeverStored() {
        val old = intake("old", "m1", 1_000)
        val fresh = intake("new", "m1", 1_800_000_000)
        val c = cache("UTC", old, fresh).pruned(1_700_000_000)
        assertEquals(setOf("new"), c.intakes.keys)
        val page = WatchPage.parse(
            JSONObject(
                """{"cursor":1,"profiles":[{"id":"own","name":"Я","role":"owner"},{"id":"p1","name":"Мама","role":"viewer"}],
                  "meds":[{"id":"mo","profile_id":"own","name":"Своё"},{"id":"m1","profile_id":"p1","name":"Чужое"}]}""",
            ),
        )
        val applied = WatchCache().apply(page, null)
        assertEquals(setOf("p1"), applied.profiles.keys)
        assertEquals(setOf("m1"), applied.meds.keys)
    }
}
