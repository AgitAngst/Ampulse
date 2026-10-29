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
import io.github.agitangst.ampulse.domain.TaperStep
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Построение выгрузки из схем и отметок. Данные выдуманные; сегодня — 29.10.2026, пояс Берлина. */
class MirrorBuilderTest {
    private val salt = ByteArray(16) { (it * 7 + 3).toByte() }
    private val zone = ZoneId.of("Europe/Berlin")
    private val today = LocalDate.of(2026, 10, 29)
    private val builder = MirrorBuilder(salt, zone, "Me")

    private fun at(day: String, h: Int, m: Int = 0): LocalDateTime = LocalDate.parse(day).atTime(h, m)

    private fun sec(t: LocalDateTime) = t.atZone(zone).toEpochSecond()

    private fun med(id: Long, name: String, form: MedForm = MedForm.Tablet, profile: Long = 0, strength: String = "", note: String = "") =
        Medicine(id, name, strength, form, "табл.", note = note, profileId = profile)

    private fun times(vararg hm: Pair<Int, Int>) = hm.map { DoseTime(LocalTime.of(it.first, it.second), 1.0) }

    private fun plan(kind: PlanKind, start: String, vararg hm: Pair<Int, Int>, paused: Boolean = false, build: (Plan) -> Plan = { it }) =
        build(Plan(kind, times(*hm), LocalDate.parse(start), paused = paused))

    private val medicines = listOf(
        SyncMedicine(med(1, "Метформин", strength = "850 мг", note = "после еды"), false),
        SyncMedicine(med(2, "Инсулин (демо)", MedForm.Injection), false),
        SyncMedicine(med(3, "Цинк", MedForm.Capsule), false),
        SyncMedicine(med(4, "Курс витамина", MedForm.Drops), false),
        SyncMedicine(med(5, "Преднизолон"), false),
        SyncMedicine(med(6, "Ибупрофен"), false),
        SyncMedicine(med(7, "Корвалол", MedForm.Liquid), true),
        SyncMedicine(med(8, "Добавка для кота", profile = 2), false),
        SyncMedicine(med(9, "Чужой профиль", profile = 99), false),
        SyncMedicine(med(10, "Из копии расписания", profile = 3), false),
    )

    private val schedules = listOf(
        Schedule(10, 1, plan(PlanKind.Daily, "2026-01-01", 8 to 0, 20 to 0)),
        Schedule(11, 2, plan(PlanKind.Weekly, "2026-01-01", 9 to 0) { it.copy(weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)) }),
        Schedule(12, 3, plan(PlanKind.EveryNDays, "2026-09-01", 10 to 0) { it.copy(everyN = 3) }),
        Schedule(13, 4, plan(PlanKind.Cycle, "2026-10-01", 18 to 0) { it.copy(cycleOn = 3, cycleOff = 2) }),
        Schedule(14, 5, plan(PlanKind.Taper, "2026-10-27", 7 to 0) { it.copy(steps = listOf(TaperStep(2.0, 3), TaperStep(1.0, 3))) }),
        Schedule(15, 6, plan(PlanKind.AsNeeded, "2026-01-01") { it.copy(prnIntervalHours = 6, prnMaxPerDay = 3) }),
        Schedule(16, 7, plan(PlanKind.Daily, "2026-01-01", 9 to 0)),
        Schedule(17, 8, plan(PlanKind.Daily, "2026-01-01", 8 to 0)),
        Schedule(18, 10, plan(PlanKind.Daily, "2026-01-01", 8 to 0)),
    )

    private val profiles = listOf(
        Profile(2, "Барсик", ProfileKind.Cat),
        Profile(3, "Копия: Галина", ProfileKind.Person, watchOnly = true),
    )

    private fun event(id: Long, med: Long, planned: String?, plannedH: Int, at: LocalDateTime, status: EventStatus, schedule: Long? = null, note: String = "") =
        DoseEvent(id, med, schedule, planned?.let { at(it, plannedH) }, at, status, 1.0, note)

    private val events = listOf(
        event(100, 1, "2026-10-28", 8, at("2026-10-28", 8, 5), EventStatus.Taken, 10),
        event(101, 1, "2026-10-28", 20, at("2026-10-28", 20, 10), EventStatus.Skipped, 10, note = "тошнило"),
        event(102, 6, null, 0, at("2026-10-29", 10), EventStatus.Taken),
        event(103, 6, null, 0, at("2026-10-29", 11), EventStatus.Extra),
        event(104, 1, null, 0, at("2026-10-28", 13), EventStatus.Extra),
        event(105, 3, "2026-10-20", 14, at("2026-10-20", 14, 40), EventStatus.Taken, 12),
        event(106, 1, "2026-08-01", 8, at("2026-08-01", 8, 2), EventStatus.Taken, 10),
        event(107, 7, "2026-10-01", 9, at("2026-10-01", 9, 20), EventStatus.Taken, 16),
        event(108, 10, "2026-10-28", 8, at("2026-10-28", 8, 1), EventStatus.Taken, 18),
        event(109, 6, null, 0, at("2026-10-27", 22), EventStatus.Skipped, note = "прошло"),
    )

    private val snapshot = SyncSnapshot(profiles, medicines, schedules, events)
    private val set = builder.build(snapshot, today)

    private fun medId(n: Long) = SyncIds.medicine(salt, n)

    private fun intakesOf(n: Long) = set.intakes.values.filter { it.medId == medId(n) }

    @Test
    fun windowIsThirtyDaysBackAndFourteenAhead() {
        val w = builder.window(today)
        assertEquals(sec(at("2026-09-29", 0)), w.from)
        assertEquals(sec(at("2026-11-13", 0)), w.to)
    }

    @Test
    fun profilesMeAndOwnOnly() {
        assertEquals(2, set.profiles.size)
        val me = set.profiles.getValue(SyncIds.profile(salt, 0))
        assertEquals("Me", me.name)
        assertEquals("person", me.kind)
        assertEquals("Europe/Berlin", me.timezone)
        assertEquals("", me.note)
        val cat = set.profiles.getValue(SyncIds.profile(salt, 2))
        assertEquals("Барсик", cat.name)
        assertEquals("animal", cat.kind)
        // Копия чужого расписания (watchOnly) не выкладывается.
        assertNull(set.profiles[SyncIds.profile(salt, 3)])
        assertEquals("Я", MirrorBuilder(salt, zone, "Я").build(snapshot, today).profiles.getValue(SyncIds.profile(salt, 0)).name)
    }

    @Test
    fun zoneWithoutRegionFallsBackToUtc() {
        val b = MirrorBuilder(salt, ZoneId.of("GMT+03:00"), "Me")
        assertEquals("UTC", b.build(snapshot, today).profiles.values.first().timezone)
    }

    @Test
    fun medicinesMapFormsAndSkipForeignProfiles() {
        assertEquals(8, set.meds.size)
        val metformin = set.meds.getValue(medId(1))
        assertEquals(SyncIds.profile(salt, 0), metformin.profileId)
        assertEquals("pill", metformin.form)
        assertEquals("850 мг", metformin.dose)
        assertEquals("после еды", metformin.note)
        assertFalse(metformin.archived)
        assertEquals("injection", set.meds.getValue(medId(2)).form)
        listOf(3L, 4L, 5L, 6L).forEach { assertEquals("pill", set.meds.getValue(medId(it)).form) }
        assertTrue(set.meds.getValue(medId(7)).archived)
        assertEquals(SyncIds.profile(salt, 2), set.meds.getValue(medId(8)).profileId)
        assertNull(set.meds[medId(9)])
        assertNull(set.meds[medId(10)])
    }

    @Test
    fun dailyPlanIsLaidOutOverTheWholeWindow() {
        val list = intakesOf(1).filter { it.planned }
        // 45 дней (29.09–12.11) по два приёма, плюс «лишний» вне плана.
        assertEquals(90, list.size)
        assertEquals(sec(at("2026-09-29", 8)), list.first().plannedAt)
        assertEquals(sec(at("2026-11-12", 20)), list.last().plannedAt)
        assertTrue(list.all { it.plannedAt in builder.window(today) })
        assertEquals(list.sortedBy { it.plannedAt }, list)
    }

    @Test
    fun weeklyEveryNDaysCycleAndTaper() {
        // Пн и Чт за 45 дней, считаем независимо от Plan.
        val days = generateSequence(today.minusDays(30)) { it.plusDays(1) }.take(45).toList()
        val weekly = days.count { it.dayOfWeek == DayOfWeek.MONDAY || it.dayOfWeek == DayOfWeek.THURSDAY }
        assertEquals(weekly, intakesOf(2).size)
        val everyThree = days.count { java.time.temporal.ChronoUnit.DAYS.between(LocalDate.of(2026, 9, 1), it) % 3 == 0L }
        // К плановым добавляется отметка времени, которого уже нет в схеме (событие 105, см. ниже).
        assertEquals(everyThree + 1, intakesOf(3).filter { it.planned }.size)
        val cycle = days.count { it >= LocalDate.of(2026, 10, 1) && java.time.temporal.ChronoUnit.DAYS.between(LocalDate.of(2026, 10, 1), it) % 5 < 3 }
        assertEquals(cycle, intakesOf(4).size)
        // Ступени: 3 дня + 3 дня, с 27.10 по 01.11.
        assertEquals(6, intakesOf(5).size)
        assertEquals(sec(at("2026-10-27", 7)), intakesOf(5).first().plannedAt)
        assertEquals(sec(at("2026-11-01", 7)), intakesOf(5).last().plannedAt)
        // Правило схемы на сервер не уходит: у приёма только время.
        assertTrue(intakesOf(5).all { it.status == "pending" && it.planned })
    }

    @Test
    fun marksTakenAndSkippedMergeIntoPlannedDoses() {
        val morning = set.intakes.getValue(SyncIds.planned(salt, 10, sec(at("2026-10-28", 8))))
        assertEquals("taken", morning.status)
        assertEquals(sec(at("2026-10-28", 8, 5)), morning.takenAt)
        assertTrue(morning.planned)
        assertEquals(sec(at("2026-10-28", 8)), morning.plannedAt)
        val evening = set.intakes.getValue(SyncIds.planned(salt, 10, sec(at("2026-10-28", 20))))
        assertEquals("skipped", evening.status)
        assertNull(evening.takenAt)
        assertEquals("тошнило", evening.note)
        // Без отметки — ожидается.
        val next = set.intakes.getValue(SyncIds.planned(salt, 10, sec(at("2026-10-29", 8))))
        assertEquals("pending", next.status)
        assertNull(next.takenAt)
        // Каждая плановая отметка слита с приёмом: «лишних» копий нет (90 плановых + 1 «лишний»).
        assertEquals(91, intakesOf(1).size)
    }

    @Test
    fun unplannedDosesAreTakenAndNotPlanned() {
        val extra = set.intakes.getValue(SyncIds.event(salt, 104))
        assertFalse(extra.planned)
        assertEquals("taken", extra.status)
        assertEquals(sec(at("2026-10-28", 13)), extra.plannedAt)
        assertEquals(extra.plannedAt, extra.takenAt)
        // «По необходимости»: плановых приёмов нет, есть отметки; Extra и Taken без плана — оба внеплановые.
        val prn = intakesOf(6)
        assertEquals(3, prn.size)
        assertTrue(prn.none { it.planned })
        assertEquals(listOf("skipped", "taken", "taken"), prn.sortedBy { it.plannedAt }.map { it.status })
        // Пропуск без плана остаётся пропуском (приём не принят) и без taken_at.
        val skipped = set.intakes.getValue(SyncIds.event(salt, 109))
        assertEquals("skipped", skipped.status)
        assertNull(skipped.takenAt)
        assertEquals("прошло", skipped.note)
    }

    @Test
    fun marksOfChangedScheduleAndArchivedMedicineStay() {
        // Время, которого уже нет в схеме, всё равно выложено, под тем же id, что дал бы приём по схеме.
        val orphan = set.intakes.getValue(SyncIds.planned(salt, 12, sec(at("2026-10-20", 14))))
        assertEquals("taken", orphan.status)
        assertTrue(orphan.planned)
        // Архивное лекарство: новых плановых приёмов нет, прошлая отметка осталась.
        val archived = intakesOf(7)
        assertEquals(1, archived.size)
        assertEquals("taken", archived.single().status)
    }

    @Test
    fun pausedScheduleKeepsMarksButHasNoPendingDoses() {
        val paused = snapshot.copy(schedules = schedules.map { if (it.id == 10L) it.copy(plan = it.plan.copy(paused = true)) else it })
        val built = builder.build(paused, today)
        val list = built.intakes.values.filter { it.medId == medId(1) }
        assertTrue(list.none { it.status == "pending" })
        assertEquals(setOf("skipped", "taken"), list.filter { it.planned }.map { it.status }.toSet())
    }

    @Test
    fun outOfWindowAndForeignMarksAreIgnored() {
        assertNull(set.intakes[SyncIds.planned(salt, 10, sec(at("2026-08-01", 8)))])
        // Отметка лекарства из копии расписания (watchOnly) не выкладывается.
        assertTrue(intakesOf(10).isEmpty())
        assertTrue(set.intakes.values.none { it.medId !in set.meds })
    }

    @Test
    fun catGetsItsOwnDoses() {
        assertEquals(45, intakesOf(8).size)
    }

    @Test
    fun deterministicAndHashesFollowContent() {
        val again = builder.build(snapshot, today)
        assertEquals(set, again)
        assertEquals(set.intakes.values.map { it.hash }, again.intakes.values.map { it.hash })
        val edited = builder.build(snapshot.copy(medicines = medicines.map { if (it.medicine.id == 1L) it.copy(medicine = it.medicine.copy(note = "до еды")) else it }), today)
        assertNotEquals(set.meds.getValue(medId(1)).hash, edited.meds.getValue(medId(1)).hash)
        assertEquals(set.meds.getValue(medId(2)).hash, edited.meds.getValue(medId(2)).hash)
        assertEquals(set.intakes, edited.intakes)
    }

    @Test
    fun longTextsAreCutToServerLimits() {
        val long = snapshot.copy(medicines = listOf(SyncMedicine(med(1, "Я".repeat(100), strength = "д".repeat(1200), note = "  " + "н".repeat(1500)), false)))
        val m = builder.build(long, today).meds.values.single()
        assertEquals(80, m.name.length)
        assertEquals(1000, m.dose.length)
        assertEquals(1000, m.note.length)
    }

    @Test
    fun intakeJsonCarriesTakenAtOnlyWhenTaken() {
        val taken = set.intakes.getValue(SyncIds.planned(salt, 10, sec(at("2026-10-28", 8)))).toJson()
        assertEquals("taken", taken.getString("status"))
        assertTrue(taken.has("taken_at"))
        assertEquals(true, taken.getBoolean("planned"))
        assertNotNull(taken.getString("med_id"))
        val pending = set.intakes.getValue(SyncIds.planned(salt, 10, sec(at("2026-10-29", 8)))).toJson()
        assertFalse(pending.has("taken_at"))
        val med = set.meds.getValue(medId(1)).toJson()
        assertEquals(setOf("id", "profile_id", "name", "form", "dose", "note", "archived"), med.keys().asSequence().toSet())
        val profile = set.profiles.values.first().toJson()
        assertEquals(setOf("id", "name", "kind", "note", "timezone"), profile.keys().asSequence().toSet())
    }
}
