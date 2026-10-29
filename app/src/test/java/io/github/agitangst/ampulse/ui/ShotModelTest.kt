package io.github.agitangst.ampulse.ui

import io.github.agitangst.ampulse.domain.DoseEvent
import io.github.agitangst.ampulse.domain.DoseTime
import io.github.agitangst.ampulse.domain.EventStatus
import io.github.agitangst.ampulse.domain.Plan
import io.github.agitangst.ampulse.domain.PlanKind
import io.github.agitangst.ampulse.domain.Site
import io.github.agitangst.ampulse.domain.TaperStep
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class ShotModelTest {
    private val start = LocalDate.of(2026, 9, 24)
    private fun daily(vararg times: Pair<LocalTime, Double>, end: LocalDate? = null, paused: Boolean = false) =
        Plan(PlanKind.Daily, times.map { DoseTime(it.first, it.second) }, start, end, paused = paused)

    @Test
    fun kindFollowsSiteThenUnit() {
        // Место, выбранное на «Месте укола», решает само.
        assertEquals(ShotKindGuess(ShotKind.Muscle, true), guessShotKind(Site.ButtockLeft, "ЕД"))
        assertEquals(ShotKindGuess(ShotKind.Muscle, true), guessShotKind(Site.OuterThighRight, ""))
        assertEquals(ShotKindGuess(ShotKind.Pen, true), guessShotKind(Site.AbdomenRight, "мл"))
        assertEquals(ShotKindGuess(ShotKind.Pen, true), guessShotKind(Site.Scruff, "мл"))
        // Без места — по единице дозы, в обоих языках и с точкой.
        assertEquals(ShotKindGuess(ShotKind.Pen, true), guessShotKind(null, "ЕД"))
        assertEquals(ShotKindGuess(ShotKind.Pen, true), guessShotKind(null, "U"))
        assertEquals(ShotKindGuess(ShotKind.Muscle, true), guessShotKind(null, "мл"))
        assertEquals(ShotKindGuess(ShotKind.Muscle, true), guessShotKind(null, " ml. "))
        // Прочее неоднозначно: ручка по умолчанию, но с переключателем.
        assertEquals(ShotKindGuess(ShotKind.Pen, false), guessShotKind(null, "амп."))
        assertEquals(ShotKindGuess(ShotKind.Pen, false), guessShotKind(null, "табл."))
    }

    @Test
    fun stepsPerKind() {
        val pen = shotSteps(ShotKind.Pen)
        val muscle = shotSteps(ShotKind.Muscle)
        assertEquals(8, pen.size)
        assertEquals(7, muscle.size)
        assertTrue(pen.all { it.kind == ShotKind.Pen })
        assertTrue(muscle.all { it.kind == ShotKind.Muscle })
        // Отсчёт — только на шаге «Укол», перед «После укола».
        assertEquals(listOf(ShotStep.InjectPen), pen.filter { it.timed })
        assertEquals(listOf(ShotStep.InjectMuscle), muscle.filter { it.timed })
        assertEquals(ShotStep.InjectPen, pen[pen.size - 2])
        assertEquals(ShotStep.InjectMuscle, muscle[muscle.size - 2])
    }

    @Test
    fun timerSeconds() {
        // Ручка — всегда 10 секунд; в мышцу — около 10 секунд на 1 мл, не меньше 10.
        assertEquals(10, shotTimerSeconds(ShotKind.Pen, "ЕД", 12.0))
        assertEquals(20, shotTimerSeconds(ShotKind.Muscle, "мл", 2.0))
        assertEquals(10, shotTimerSeconds(ShotKind.Muscle, "ml", 0.5))
        assertEquals(35, shotTimerSeconds(ShotKind.Muscle, "ml", 3.5))
        // Объём неизвестен (доза в ампулах) — минимум, а не выдуманный.
        assertEquals(10, shotTimerSeconds(ShotKind.Muscle, "амп.", 3.0))
    }

    @Test
    fun amountComesFromPlanNeverInvented() {
        val day = LocalDate.of(2026, 9, 26)
        assertEquals(12.0, shotAmount(daily(LocalTime.of(22, 0) to 12.0), day), 1e-9)
        assertEquals(0.0, shotAmount(null, day), 1e-9)
        // Ступенчатая схема: доза ступени дня, а не первая из списка.
        val taper = Plan(PlanKind.Taper, listOf(DoseTime(LocalTime.of(9, 0), 1.0)), start, steps = listOf(TaperStep(4.0, 3), TaperStep(2.0, 3)))
        assertEquals(4.0, shotAmount(taper, LocalDate.of(2026, 9, 25)), 1e-9)
        assertEquals(2.0, shotAmount(taper, LocalDate.of(2026, 9, 28)), 1e-9)
        // День вне схемы — первая доза схемы.
        assertEquals(1.0, shotAmount(taper, LocalDate.of(2026, 10, 20)), 1e-9)
    }

    @Test
    fun courseCounting() {
        val ten = daily(LocalTime.of(9, 0) to 2.0, end = LocalDate.of(2026, 10, 3))
        assertEquals(10, courseDoses(ten))
        assertEquals(20, courseDoses(daily(LocalTime.of(9, 0) to 2.0, LocalTime.of(21, 0) to 2.0, end = LocalDate.of(2026, 10, 3))))
        // Пауза курс не обнуляет.
        assertEquals(10, courseDoses(daily(LocalTime.of(9, 0) to 2.0, end = LocalDate.of(2026, 10, 3), paused = true)))
        // Срока нет — счёта нет.
        assertNull(courseDoses(daily(LocalTime.of(9, 0) to 2.0)))
        assertNull(courseDoses(Plan(PlanKind.AsNeeded, emptyList(), start, end = LocalDate.of(2026, 10, 3))))

        assertEquals(3 to 10, courseDay(ten, LocalDate.of(2026, 9, 26)))
        assertEquals(1 to 10, courseDay(ten, LocalDate.of(2026, 9, 1)))
        assertEquals(10 to 10, courseDay(ten, LocalDate.of(2026, 11, 1)))
        assertNull(courseDay(daily(LocalTime.of(9, 0) to 2.0), LocalDate.of(2026, 9, 26)))
    }

    @Test
    fun courseDoneCountsTakenAndExtraSinceStart() {
        val plan = daily(LocalTime.of(9, 0) to 2.0, end = LocalDate.of(2026, 10, 3))
        fun e(id: Long, med: Long, day: Int, status: EventStatus) =
            DoseEvent(id, med, null, null, LocalDateTime.of(2026, 9, day, 9, 0), status, 2.0)
        val events = listOf(
            e(1, 7, 24, EventStatus.Taken),
            e(2, 7, 25, EventStatus.Taken),
            e(3, 7, 26, EventStatus.Extra),
            e(4, 7, 27, EventStatus.Skipped),
            e(5, 8, 25, EventStatus.Taken),
            e(6, 7, 20, EventStatus.Taken),
        )
        assertEquals(3, courseDone(events, 7, plan))
    }

    @Test
    fun nextDoseAfter() {
        val plan = daily(LocalTime.of(9, 0) to 2.0, end = LocalDate.of(2026, 9, 27))
        assertEquals(LocalDateTime.of(2026, 9, 26, 9, 0), nextPlanned(plan, LocalDateTime.of(2026, 9, 26, 8, 0)))
        assertEquals(LocalDateTime.of(2026, 9, 27, 9, 0), nextPlanned(plan, LocalDateTime.of(2026, 9, 26, 10, 0)))
        // Курс кончился — следующего нет; схемы нет — тоже.
        assertNull(nextPlanned(plan, LocalDateTime.of(2026, 9, 27, 10, 0)))
        assertNull(nextPlanned(null, LocalDateTime.of(2026, 9, 26, 8, 0)))
    }

    @Test
    fun siteHelpers() {
        assertTrue(isAbdomen(Site.AbdomenLeft))
        assertFalse(isAbdomen(Site.ThighLeft))
        assertTrue(isButtock(Site.ButtockRight))
        assertFalse(isButtock(Site.OuterThighRight))
        assertTrue(isLeftSide(Site.ButtockLeft))
        assertFalse(isLeftSide(Site.ButtockRight))
    }
}
