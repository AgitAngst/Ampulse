package io.github.agitangst.ampulse.ui

import io.github.agitangst.ampulse.domain.DoseEvent
import io.github.agitangst.ampulse.domain.DoseTime
import io.github.agitangst.ampulse.domain.EventStatus
import io.github.agitangst.ampulse.domain.MedForm
import io.github.agitangst.ampulse.domain.Medicine
import io.github.agitangst.ampulse.domain.Plan
import io.github.agitangst.ampulse.domain.PlanKind
import io.github.agitangst.ampulse.domain.Reading
import io.github.agitangst.ampulse.domain.Schedule
import io.github.agitangst.ampulse.domain.Target
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class ReportModelTest {
    private val start = LocalDate.of(2026, 9, 26)
    private val metformin = Medicine(7, "Метформин", "850 мг", MedForm.Tablet, "табл.")
    private val prnMed = Medicine(9, "Ибупрофен", "200 мг", MedForm.Tablet, "табл.")
    private val twiceDaily = Plan(PlanKind.Daily, listOf(DoseTime(LocalTime.of(8, 0), 1.0), DoseTime(LocalTime.of(20, 0), 1.0)), start)
    private val asNeeded = Plan(PlanKind.AsNeeded, emptyList(), start, prnIntervalHours = 6, prnMaxPerDay = 3)
    private val schedules = listOf(Schedule(1, 7, twiceDaily), Schedule(2, 9, asNeeded))

    private fun build(
        events: List<DoseEvent> = emptyList(),
        readings: List<Reading>? = null,
        doses: Boolean = true,
    ) = buildReport(
        profileName = "Мама",
        from = start,
        to = start.plusDays(1),
        medicines = listOf(metformin, prnMed),
        schedules = schedules,
        events = events,
        readings = readings,
        target = Target(),
        // Сейчас — 27.09, 12:00: 26-е целиком прошло, 27-го прошёл только утренний приём.
        now = start.plusDays(1).atTime(12, 0),
        doses = doses,
    )

    @Test
    fun periodEndsToday() {
        val today = LocalDate.of(2026, 10, 29)
        assertEquals(LocalDate.of(2026, 10, 16) to today, reportRange(today, 14))
        assertEquals(LocalDate.of(2026, 9, 30) to today, reportRange(today, 30))
        assertEquals(LocalDate.of(2026, 7, 31) to today, reportRange(today, 90))
    }

    @Test
    fun dosesSkipsExtrasAndAsNeeded() {
        val events = listOf(
            DoseEvent(1, 7, 1, start.atTime(8, 0), start.atTime(8, 5), EventStatus.Taken, 1.0),
            DoseEvent(2, 7, 1, start.atTime(20, 0), start.atTime(20, 1), EventStatus.Skipped, 1.0, note = "  забыла дома "),
            DoseEvent(3, 7, null, null, start.atTime(21, 0), EventStatus.Extra, 1.0),
            DoseEvent(4, 9, null, null, start.atTime(15, 0), EventStatus.Taken, 1.0),
            // До начала периода и чужое лекарство — в отчёт не входят.
            DoseEvent(5, 7, null, null, start.minusDays(1).atTime(21, 0), EventStatus.Extra, 1.0),
            DoseEvent(6, 99, null, null, start.atTime(22, 0), EventStatus.Extra, 1.0),
        )
        val r = build(events)

        // «По необходимости» без лишних приёмов в соблюдение не попадает — только в свой раздел.
        val med = r.meds.single()
        assertEquals(metformin, med.stats.medicine)
        assertEquals(twiceDaily, med.plan)
        assertEquals(3, r.planned)
        assertEquals(1, r.taken)
        assertEquals(1, r.skipped)
        assertEquals(1, r.missed)
        assertEquals(1, r.extra)
        assertEquals(33, r.percent)

        // Причина без пробелов по краям, пропуск с датой.
        assertEquals(listOf(ReportSkip(start.atTime(20, 0), metformin, "забыла дома")), r.skips)
        assertEquals(listOf(ReportExtra(start.atTime(21, 0), metformin, 1.0)), r.extras)
        assertEquals(listOf(ReportPrn(prnMed, 1)), r.prn)
        assertTrue(r.hasContent)
        assertNull(r.pressure)
    }

    @Test
    fun futureDosesAreNotMisses() {
        // Ничего не отмечено, но 27.09 вечерний приём ещё впереди: всего 3 прошедших приёма (26-е и утро 27-го).
        val r = build()
        assertEquals(3, r.planned)
        assertEquals(0, r.taken)
        assertEquals(3, r.missed)
        assertEquals(0, r.percent)
    }

    @Test
    fun pressureAveragesByDay() {
        val readings = listOf(
            Reading(1, 130, 80, 70, start.atTime(7, 30)),
            Reading(2, 126, 84, 72, start.atTime(8, 0)),
            Reading(3, 120, 78, null, start.atTime(13, 0)),
            Reading(4, 140, 90, 80, start.atTime(20, 0)),
            Reading(5, 136, 86, null, start.plusDays(1).atTime(21, 0)),
            // Вне периода.
            Reading(6, 150, 95, null, start.minusDays(1).atTime(8, 0)),
        )
        val bp = build(readings = readings, doses = false).pressure ?: error("pressure section is missing")

        assertEquals(5, bp.total)
        assertEquals(BpAverage(128, 82, 71, 2), bp.morning)
        assertEquals(BpAverage(138, 88, 80, 2), bp.evening)
        assertEquals(listOf(start, start.plusDays(1)), bp.days.map { it.date })
        val first = bp.days[0]
        assertEquals(BpAverage(128, 82, 71, 2), first.morning)
        assertEquals(BpAverage(120, 78, null, 1), first.daytime)
        assertEquals(BpAverage(140, 90, 80, 1), first.evening)
        val second = bp.days[1]
        assertNull(second.morning)
        assertNull(second.daytime)
        assertEquals(BpAverage(136, 86, null, 1), second.evening)
    }

    @Test
    fun sectionsSwitchOffIndependently() {
        val readings = listOf(Reading(1, 130, 80, null, start.atTime(8, 0)))
        val onlyPressure = build(readings = readings, doses = false)
        assertTrue(onlyPressure.meds.isEmpty())
        assertTrue(onlyPressure.skips.isEmpty())
        assertTrue(onlyPressure.extras.isEmpty())
        assertTrue(onlyPressure.prn.isEmpty())
        assertNull(onlyPressure.percent)
        assertTrue(onlyPressure.hasContent)

        // Давление включено, замеров нет, приёмы выключены — отчёту нечего показывать.
        val empty = build(readings = emptyList(), doses = false)
        assertEquals(0, empty.pressure?.total)
        assertFalse(empty.hasContent)
    }
}
