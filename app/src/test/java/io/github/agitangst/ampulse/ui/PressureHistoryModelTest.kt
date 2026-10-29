package io.github.agitangst.ampulse.ui

import io.github.agitangst.ampulse.domain.Insight
import io.github.agitangst.ampulse.domain.PressureState
import io.github.agitangst.ampulse.domain.Reading
import io.github.agitangst.ampulse.domain.Target
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime

class PressureHistoryModelTest {
    private val today = LocalDate.of(2026, 9, 26)
    private val target = Target()

    private fun at(day: Int, hour: Int, minute: Int = 0, month: Int = 9) = LocalDateTime.of(2026, month, day, hour, minute)

    private var nextId = 1L
    private fun reading(day: Int, hour: Int, sys: Int, dia: Int, pulse: Int? = null, minute: Int = 0, month: Int = 9) =
        Reading(nextId++, sys, dia, pulse, at(day, hour, minute, month))

    /** Неделя 20–26 сентября: утро и вечер, один замер днём и один старый вне периода. */
    private fun week() = listOf(
        reading(26, 8, 128, 82, 71, 12),
        reading(25, 20, 136, 87, 73, 30),
        reading(25, 8, 127, 82, 71, 15),
        reading(24, 20, 132, 84, 70, 35),
        reading(24, 13, 140, 90),
        reading(24, 8, 125, 80, 68),
        reading(10, 8, 150, 95),
    )

    @Test
    fun averagesCountOnlyMorningAndEveningInPeriod() {
        val h = pressureHistory(week(), target, today, 7)
        // Утро: 128/82, 127/82, 125/80 → 127/81, пульс 70; замер днём и старый замер не учитываются.
        assertEquals(PartStat(127, 81, 70, 3, PressureState.Within), h.morning)
        // Вечер: 136/87 и 132/84 → 134/86 (85,5 округляется вверх), пульс 72 — выше цели по нижнему.
        assertEquals(PartStat(134, 86, 72, 2, PressureState.Above), h.evening)
    }

    @Test
    fun columnsAreOnePerDayWithMorningAndEveningBars() {
        val h = pressureHistory(week(), target, today, 7)
        assertEquals(7, h.columns.size)
        assertEquals(LocalDate.of(2026, 9, 20), h.columns.first().date)
        assertEquals(today, h.columns.last().date)
        val sat = h.columns.last()
        assertEquals(DaySpan(128, 82, PressureState.Within), sat.morning)
        assertNull(sat.evening)
        val fri = h.columns[5]
        assertEquals(DaySpan(127, 82, PressureState.Within), fri.morning)
        assertEquals(DaySpan(136, 87, PressureState.Above), fri.evening)
        assertFalse(fri.evening!!.inTarget)
        // Днём на графике ничего нет: 24-е — только утро 125/80 и вечер 132/84.
        val thu = h.columns[4]
        assertEquals(DaySpan(125, 80, PressureState.Within), thu.morning)
        assertEquals(DaySpan(132, 84, PressureState.Within), thu.evening)
        // Дни без замеров — пустые столбцы.
        assertNull(h.columns.first().morning)
        assertNull(h.columns.first().evening)
    }

    @Test
    fun latestIsNewestFirstAndInPeriodOnly() {
        val h = pressureHistory(week().shuffled(java.util.Random(7)), target, today, 7)
        assertEquals(6, h.latest.size)
        assertEquals(at(26, 8, 12), h.latest.first().at)
        assertEquals(h.latest.sortedByDescending { it.at }, h.latest)
        // Замер днём в списке есть, хотя в средних и на графике его нет.
        assertTrue(h.latest.any { dayPart(it) == DayPart.Midday })
        // Тот же набор за 30 дней включает и старый замер.
        assertEquals(7, pressureHistory(week(), target, today, 30).latest.size)
    }

    @Test
    fun seriesOfTwoIsOneBar() {
        val series = listOf(reading(26, 7, 130, 80, 66), reading(26, 7, 134, 85, 68, 5))
        val h = pressureHistory(series, target, today, 7)
        assertEquals(DaySpan(132, 83, PressureState.Within), h.columns.last().morning)
        assertEquals(PartStat(132, 83, 67, 2, PressureState.Within), h.morning)
    }

    @Test
    fun lowAverageIsNotInTarget() {
        val h = pressureHistory(listOf(reading(26, 8, 85, 55)), target, today, 7)
        val span = h.columns.last().morning!!
        assertEquals(PressureState.Low, span.state)
        assertFalse(span.inTarget)
    }

    @Test
    fun emptyPeriod() {
        val h = pressureHistory(emptyList(), target, today, 14)
        assertTrue(h.isEmpty)
        assertNull(h.morning)
        assertNull(h.evening)
        assertEquals(14, h.columns.size)
        assertNull(chartExtremes(h.columns))
        assertEquals(InsightSummary.None, h.insight)
        assertEquals(ChartScale(60, 160), h.scale)
    }

    @Test
    fun insightCountsEveningDaysAboveTarget() {
        val h = pressureHistory(week(), target, today, 7)
        // Вечера: 24-е в пределах цели, 25-е выше → 1 из 2; утром выше цели не было.
        assertEquals(InsightSummary.Above(evening = 1 to 2, morning = null), h.insight)
    }

    @Test
    fun summarizeVariants() {
        assertEquals(InsightSummary.None, summarize(Insight(0, 0, 0, 0)))
        assertEquals(InsightSummary.AllWithin, summarize(Insight(0, 3, 0, 4)))
        assertEquals(InsightSummary.Above(evening = 3 to 6, morning = 1 to 7), summarize(Insight(3, 6, 1, 7)))
        // Есть только утро, и оно выше цели.
        assertEquals(InsightSummary.Above(evening = null, morning = 2 to 5), summarize(Insight(0, 0, 2, 5)))
    }

    @Test
    fun extremesAndDaysWithData() {
        val ex = chartExtremes(pressureHistory(week(), target, today, 7).columns)!!
        assertEquals(3, ex.daysWithData)
        assertEquals(SpanPoint(LocalDate.of(2026, 9, 25), DayPart.Evening, DaySpan(136, 87, PressureState.Above)), ex.highest)
        assertEquals(SpanPoint(LocalDate.of(2026, 9, 24), DayPart.Morning, DaySpan(125, 80, PressureState.Within)), ex.lowest)
    }

    @Test
    fun scaleIsSixtyToOneSixtyByDefault() {
        val h = pressureHistory(week(), target, today, 7)
        assertEquals(ChartScale(60, 160), h.scale)
        assertEquals(0f, h.scale.fraction(160), 1e-6f)
        assertEquals(1f, h.scale.fraction(60), 1e-6f)
        assertEquals(0.5f, h.scale.fraction(110), 1e-6f)
    }

    @Test
    fun scaleGrowsForExtremeValuesAndTarget() {
        val high = DayColumn(today, null, DaySpan(185, 118, PressureState.Crisis))
        val low = DayColumn(today.minusDays(1), DaySpan(85, 52, PressureState.Low), null)
        assertEquals(ChartScale(50, 190), chartScale(listOf(high, low), target))
        // Цель врача 170/100 выше 160: шкала тянется до 170.
        assertEquals(ChartScale(60, 170), chartScale(emptyList(), Target(170, 100)))
        // Цель ниже границы не бывает (60), шкала остаётся обычной.
        assertEquals(ChartScale(60, 160), chartScale(emptyList(), Target(120, 60)))
    }

    @Test
    fun gridHasTwoTargetLinesAndBounds() {
        val lines = gridLines(ChartScale(60, 160), target)
        assertEquals(listOf(160, 135, 85, 60), lines.map { it.value })
        assertEquals(listOf(false, true, true, false), lines.map { it.target })
    }

    @Test
    fun gridDropsBoundCloseToTargetLine() {
        // Цель 155/95: граница 160 ближе 10 к линии цели — её подпись налезла бы на подпись цели.
        val lines = gridLines(ChartScale(60, 160), Target(155, 95))
        assertEquals(listOf(155, 95, 60), lines.map { it.value })
        // Цель 120/65: нижняя граница 60 тоже не рисуется.
        assertEquals(listOf(160, 120, 65), gridLines(ChartScale(60, 160), Target(120, 65)).map { it.value })
    }

    @Test
    fun dayLabelsFitTheColumns() {
        assertEquals(1, labelStep(7))
        assertEquals(1, labelStep(14))
        assertEquals(5, labelStep(30))
        assertTrue(labelWithWeekday(7))
        assertFalse(labelWithWeekday(14))
        // 30 дней: подписаны сегодня и каждый пятый день назад от него.
        val shown = (0 until 30).filter { showDayLabel(it, 30, 5) }
        assertEquals(listOf(4, 9, 14, 19, 24, 29), shown)
        assertEquals(7, (0 until 7).count { showDayLabel(it, 7, 1) })
    }

    @Test
    fun periodStartCountsTodayAsFirstDay() {
        assertEquals(LocalDate.of(2026, 9, 20), periodFrom(today, 7))
        assertEquals(LocalDate.of(2026, 9, 13), periodFrom(today, 14))
        assertEquals(LocalDate.of(2026, 8, 28), periodFrom(today, 30))
    }

    @Test
    fun dayPartBoundaries() {
        assertEquals(DayPart.Morning, dayPart(reading(26, 11, 120, 80, minute = 59)))
        assertEquals(DayPart.Midday, dayPart(reading(26, 12, 120, 80)))
        assertEquals(DayPart.Midday, dayPart(reading(26, 16, 120, 80, minute = 59)))
        assertEquals(DayPart.Evening, dayPart(reading(26, 17, 120, 80)))
    }

    @Test
    fun targetValidation() {
        assertTrue(isValidTarget(135, 85))
        assertTrue(isValidTarget(180, 60))
        assertTrue(isValidTarget(100, 60))
        assertFalse(isValidTarget(100, 110)) // верхнее не больше нижнего
        assertFalse(isValidTarget(181, 80))
        assertFalse(isValidTarget(99, 60))
        assertFalse(isValidTarget(130, 55))
        assertFalse(isValidTarget(130, 111))
    }

    @Test
    fun targetStepsByFiveAndStaysInRange() {
        assertEquals(140, stepTarget(135, true, TARGET_SYS_RANGE))
        assertEquals(130, stepTarget(135, false, TARGET_SYS_RANGE))
        // Число не кратно 5 (цель, сохранённая раньше): шаг выравнивает.
        assertEquals(140, stepTarget(137, true, TARGET_SYS_RANGE))
        assertEquals(135, stepTarget(137, false, TARGET_SYS_RANGE))
        assertEquals(180, stepTarget(180, true, TARGET_SYS_RANGE))
        assertEquals(100, stepTarget(100, false, TARGET_SYS_RANGE))
        assertEquals(100, stepTarget(102, false, TARGET_SYS_RANGE))
        assertEquals(110, stepTarget(110, true, TARGET_DIA_RANGE))
        assertEquals(60, stepTarget(60, false, TARGET_DIA_RANGE))
        assertEquals(90, stepTarget(85, true, TARGET_DIA_RANGE))
    }

    @Test
    fun targetChangesTheRating() {
        val strict = Target(125, 80)
        val h = pressureHistory(week(), strict, today, 7)
        // При цели 125/80 утреннее среднее 127/81 уже выше цели.
        assertEquals(PressureState.Above, h.morning!!.state)
    }
}
