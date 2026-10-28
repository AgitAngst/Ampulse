package io.github.agitangst.ampulse.domain

import io.github.agitangst.ampulse.data.Codec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class DayTest {
    private val day = LocalDate.of(2026, 9, 26)
    private val metformin = Medicine(1, "Метформин", "850 мг", MedForm.Tablet, "табл.")
    private val vitD = Medicine(2, "Витамин D3", "2000 МЕ", MedForm.Capsule, "капс.")
    private val schedules = listOf(
        Schedule(10, 1, Plan(PlanKind.Daily, listOf(DoseTime(LocalTime.of(8, 30), 1.0), DoseTime(LocalTime.of(14, 0), 1.0)), day)),
        Schedule(11, 2, Plan(PlanKind.Daily, listOf(DoseTime(LocalTime.of(13, 0), 1.0)), day)),
    )

    private fun at(h: Int, m: Int = 0) = day.atTime(h, m)

    @Test
    fun statesAlongTheDay() {
        val taken = DoseEvent(100, 1, 10, at(8, 30), at(8, 41), EventStatus.Taken, 1.0)
        val items = dayItems(day, listOf(metformin, vitD), schedules, listOf(taken), now = at(13, 52))
        assertEquals(listOf(at(8, 30), at(13), at(14)), items.map { it.planned.at })
        assertEquals(listOf(DoseState.Taken, DoseState.Due, DoseState.Due), items.map { it.state })
        assertEquals(DoseState.Overdue, dayItems(day, listOf(metformin, vitD), schedules, emptyList(), at(15, 1))[1].state)
        assertEquals(DoseState.Later, dayItems(day, listOf(metformin, vitD), schedules, emptyList(), at(12, 29))[1].state)
        assertEquals(1 to 3, progress(items))
    }

    @Test
    fun doubleDoseGuard() {
        val planned = PlannedDose(1, 10, at(14), 1.0)
        val taken = DoseEvent(1, 1, 10, at(14), at(14, 3), EventStatus.Taken, 1.0)
        assertEquals(MarkCheck.Ok, checkMark(planned, emptyList()))
        assertEquals(MarkCheck.AlreadyTaken(at(14, 3)), checkMark(planned, listOf(taken)))
    }

    @Test
    fun asNeededIntervalAndLimit() {
        val plan = Plan(PlanKind.AsNeeded, emptyList(), day, prnIntervalHours = 6, prnMaxPerDay = 3)
        fun took(h: Int) = DoseEvent(h.toLong(), 5, null, null, at(h), EventStatus.Taken, 1.0)
        assertEquals(PrnCheck.Allowed, checkPrn(plan, 5, emptyList(), at(9)))
        assertEquals(PrnCheck.TooSoon(at(14), 1), checkPrn(plan, 5, listOf(took(8)), at(10)))
        assertEquals(PrnCheck.Allowed, checkPrn(plan, 5, listOf(took(8)), at(14)))
        val limit = checkPrn(plan, 5, listOf(took(2), took(8), took(14)), at(21))
        assertTrue(limit is PrnCheck.LimitReached && limit.nextAllowed == LocalDateTime.of(day.plusDays(1), LocalTime.of(2, 0)))
    }

    @Test
    fun codecRoundTrips() {
        val times = listOf(DoseTime(LocalTime.of(8, 0), 1.0), DoseTime(LocalTime.of(20, 30), 0.5))
        assertEquals("08:00=1;20:30=0.5", Codec.times(times))
        assertEquals(times, Codec.times(Codec.times(times)))
        val steps = listOf(TaperStep(4.0, 3), TaperStep(2.5, 7))
        assertEquals(steps, Codec.steps(Codec.steps(steps)))
        val days = setOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY)
        assertEquals(1 or 64, Codec.weekdays(days))
        assertEquals(days, Codec.weekdays(Codec.weekdays(days)))
        val plan = Plan(PlanKind.Taper, times, day, steps = steps, weekdays = days)
        assertEquals(plan, Codec.plan(Codec.entity(7, plan)))
    }
}
