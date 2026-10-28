package io.github.agitangst.ampulse.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class PlanTest {
    private val d0 = LocalDate.of(2026, 9, 26) // суббота
    private val morning = DoseTime(LocalTime.of(8, 0), 1.0)
    private val evening = DoseTime(LocalTime.of(20, 0), 1.0)

    @Test
    fun dailyWithEnd() {
        val p = Plan(PlanKind.Daily, listOf(evening, morning), d0, end = d0.plusDays(6))
        assertEquals(listOf(morning, evening), p.dosesOn(d0))
        assertEquals(2, p.dosesOn(d0.plusDays(6)).size)
        assertTrue(p.dosesOn(d0.plusDays(7)).isEmpty())
        assertTrue(p.dosesOn(d0.minusDays(1)).isEmpty())
    }

    @Test
    fun weeklyEveryNAndCycle() {
        val weekly = Plan(PlanKind.Weekly, listOf(morning), d0, weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.SATURDAY))
        assertEquals(1, weekly.dosesOn(d0).size)
        assertTrue(weekly.dosesOn(d0.plusDays(1)).isEmpty())
        assertEquals(1, weekly.dosesOn(d0.plusDays(2)).size)

        val alt = Plan(PlanKind.EveryNDays, listOf(morning), d0, everyN = 2)
        assertEquals(listOf(1, 0, 1, 0), (0..3).map { alt.dosesOn(d0.plusDays(it.toLong())).size })

        val cycle = Plan(PlanKind.Cycle, listOf(morning), d0, cycleOn = 21, cycleOff = 7)
        assertEquals(1, cycle.dosesOn(d0.plusDays(20)).size)
        assertTrue(cycle.dosesOn(d0.plusDays(21)).isEmpty())
        assertTrue(cycle.dosesOn(d0.plusDays(27)).isEmpty())
        assertEquals(1, cycle.dosesOn(d0.plusDays(28)).size)
    }

    @Test
    fun taperStepsAndEnd() {
        val p = Plan(PlanKind.Taper, listOf(morning), d0, steps = listOf(TaperStep(4.0, 3), TaperStep(3.0, 3), TaperStep(2.0, 3), TaperStep(1.0, 3)))
        assertEquals(d0.plusDays(11), p.lastDay)
        assertEquals(4.0, p.dosesOn(d0).single().amount)
        assertEquals(3.0, p.dosesOn(d0.plusDays(3)).single().amount)
        assertEquals(1.0, p.dosesOn(d0.plusDays(11)).single().amount)
        assertTrue(p.dosesOn(d0.plusDays(12)).isEmpty())
        assertEquals(d0.plusDays(3), p.nextStepChange(d0))
        assertEquals(d0.plusDays(9), p.nextStepChange(d0.plusDays(6)))
        assertNull(p.nextStepChange(d0.plusDays(9)))
    }

    @Test
    fun asNeededAndPausedHaveNoSchedule() {
        assertTrue(Plan(PlanKind.AsNeeded, listOf(morning), d0, prnIntervalHours = 6, prnMaxPerDay = 4).dosesOn(d0).isEmpty())
        assertTrue(Plan(PlanKind.Daily, listOf(morning), d0, paused = true).dosesOn(d0).isEmpty())
    }
}
