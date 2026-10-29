package io.github.agitangst.ampulse.ui

import io.github.agitangst.ampulse.domain.Insight
import io.github.agitangst.ampulse.domain.PressureState
import io.github.agitangst.ampulse.domain.Reading
import io.github.agitangst.ampulse.domain.Target
import io.github.agitangst.ampulse.domain.average
import io.github.agitangst.ampulse.domain.classify
import io.github.agitangst.ampulse.domain.isEvening
import io.github.agitangst.ampulse.domain.insight as domainInsight
import io.github.agitangst.ampulse.domain.isMorning
import java.time.LocalDate

// Чистая логика экрана «История давления» (макет, экран 27): периоды, средние утром и вечером, столбики графика по
// дням, шкала и линии цели, подсказка, цель врача. Без Compose и Android — проверяется тестом.

/** Периоды истории, дней: сегменты «7 / 14 / 30 дней». */
internal val PRESSURE_PERIODS = listOf(7, 14, 30)

/** Сколько замеров в списке «Последние замеры» до «Показать все». */
internal const val PRESSURE_LATEST_SHOWN = 8

/** Границы цели врача на экране «Цель врача»: верхнее 100–180, нижнее 60–110, шаг 5. */
internal val TARGET_SYS_RANGE = 100..180
internal val TARGET_DIA_RANGE = 60..110
internal const val TARGET_STEP = 5

/** Цель можно сохранить: числа в границах и верхнее больше нижнего. */
internal fun isValidTarget(sys: Int, dia: Int): Boolean = sys in TARGET_SYS_RANGE && dia in TARGET_DIA_RANGE && sys > dia

/** Шаг цели вверх или вниз: кратно [TARGET_STEP], в границах [range]. */
internal fun stepTarget(value: Int, up: Boolean, range: IntRange): Int {
    val next = if (up) (value / TARGET_STEP + 1) * TARGET_STEP else ((value + TARGET_STEP - 1) / TARGET_STEP - 1) * TARGET_STEP
    return next.coerceIn(range.first, range.last)
}

/** Часть суток замера: утро — до 12:00, вечер — с 17:00 (domain/Pressure.kt), между ними — днём. */
internal enum class DayPart { Morning, Midday, Evening }

internal fun dayPart(r: Reading): DayPart = when {
    isMorning(r) -> DayPart.Morning
    isEvening(r) -> DayPart.Evening
    else -> DayPart.Midday
}

/** Столбик одной части суток за день: среднее верхнее и нижнее, оценка относительно цели. */
internal data class DaySpan(val sys: Int, val dia: Int, val state: PressureState) {
    val inTarget: Boolean get() = state == PressureState.Within
}

/** День на графике: пара столбиков — утро и вечер (замеров днём на графике нет). */
internal data class DayColumn(val date: LocalDate, val morning: DaySpan?, val evening: DaySpan?)

/** Среднее за период по части суток: давление, пульс (если был), число замеров и оценка. */
internal data class PartStat(val sys: Int, val dia: Int, val pulse: Int?, val count: Int, val state: PressureState)

/** Шкала графика: от [min] до [max] мм рт. ст., сверху вниз. */
internal data class ChartScale(val min: Int, val max: Int) {
    /** Доля высоты графика сверху: 0 — [max], 1 — [min]. */
    fun fraction(value: Int): Float = (max - value).toFloat() / (max - min)
}

/** Линия сетки: [target] — линия цели (пунктир цвета внимания), иначе граница шкалы. */
internal data class GridLine(val value: Int, val target: Boolean)

/** Шкала по умолчанию — 60–160, как в макете; шире, если замеры или цель выходят за неё; границы кратны 10. */
internal fun chartScale(columns: List<DayColumn>, target: Target): ChartScale {
    val spans = columns.flatMap { listOfNotNull(it.morning, it.evening) }
    val hi = maxOf(160, target.sys, spans.maxOfOrNull { it.sys } ?: 0)
    val lo = minOf(60, target.dia, spans.minOfOrNull { it.dia } ?: 60)
    return ChartScale(Math.floorDiv(lo, 10) * 10, (hi + 9) / 10 * 10)
}

/**
 * Линии сетки: две линии цели (верхнее и нижнее) и границы шкалы. Граница ближе 10 мм рт. ст. к линии цели не
 * рисуется — подписи не налезают друг на друга.
 */
internal fun gridLines(scale: ChartScale, target: Target): List<GridLine> {
    val lines = mutableListOf(GridLine(target.sys, true), GridLine(target.dia, true))
    for (bound in listOf(scale.max, scale.min)) {
        if (lines.none { Math.abs(it.value - bound) < 10 }) lines += GridLine(bound, false)
    }
    return lines.sortedByDescending { it.value }
}

/** Подписи дней под графиком: через [labelStep] столбцов, считая от последнего (сегодня). */
internal fun labelStep(days: Int): Int = if (days <= 14) 1 else 5

internal fun showDayLabel(index: Int, count: Int, step: Int): Boolean = (count - 1 - index) % step == 0

/** До недели подпись с днём недели («Сб 26»), дальше — только число: столбцы узкие. */
internal fun labelWithWeekday(days: Int): Boolean = days <= 7

/** Самое высокое и самое низкое среднее (по верхнему) для описания графика диктору. */
internal data class SpanPoint(val date: LocalDate, val part: DayPart, val span: DaySpan)

internal data class ChartExtremes(val daysWithData: Int, val highest: SpanPoint, val lowest: SpanPoint)

internal fun chartExtremes(columns: List<DayColumn>): ChartExtremes? {
    val points = columns.flatMap { c ->
        listOfNotNull(
            c.morning?.let { SpanPoint(c.date, DayPart.Morning, it) },
            c.evening?.let { SpanPoint(c.date, DayPart.Evening, it) },
        )
    }
    if (points.isEmpty()) return null
    val order = compareBy<SpanPoint>({ it.span.sys }, { it.span.dia })
    return ChartExtremes(
        daysWithData = columns.count { it.morning != null || it.evening != null },
        highest = points.maxWith(order),
        lowest = points.minWith(order),
    )
}

/** Что показать в подсказке под графиком: пусто, «всё в пределах цели» или «выше цели в N из M дней». */
internal sealed interface InsightSummary {
    data object None : InsightSummary

    data object AllWithin : InsightSummary

    /** Пары «дней выше цели» и «дней с замерами»; `null` — по этой части суток выше цели не было. */
    data class Above(val evening: Pair<Int, Int>?, val morning: Pair<Int, Int>?) : InsightSummary
}

internal fun summarize(i: Insight): InsightSummary = when {
    i.eveningDays == 0 && i.morningDays == 0 -> InsightSummary.None
    i.eveningAbove == 0 && i.morningAbove == 0 -> InsightSummary.AllWithin
    else -> InsightSummary.Above(
        evening = if (i.eveningAbove > 0) i.eveningAbove to i.eveningDays else null,
        morning = if (i.morningAbove > 0) i.morningAbove to i.morningDays else null,
    )
}

/** Всё, что экран показывает за период: средние, столбики, шкала, подсказка и список (новые сверху). */
internal data class PressureHistory(
    val from: LocalDate,
    val to: LocalDate,
    val morning: PartStat?,
    val evening: PartStat?,
    val columns: List<DayColumn>,
    val scale: ChartScale,
    val grid: List<GridLine>,
    val insight: InsightSummary,
    val latest: List<Reading>,
) {
    val isEmpty: Boolean get() = latest.isEmpty()
}

/** Первый день периода из [days] дней, считая сегодняшний. */
internal fun periodFrom(today: LocalDate, days: Int): LocalDate = today.minusDays(days - 1L)

/** Среднее по части суток: все замеры части идут в одно среднее; `null` — замеров нет. */
internal fun partStat(readings: List<Reading>, target: Target): PartStat? {
    val avg = average(readings) ?: return null
    return PartStat(avg.first, avg.second, avg.third, readings.size, classify(avg.first, avg.second, target))
}

private fun span(readings: List<Reading>, target: Target): DaySpan? =
    average(readings)?.let { DaySpan(it.first, it.second, classify(it.first, it.second, target)) }

/**
 * Сводка по замерам за [days] дней до [today] включительно. Замеры вне периода отбрасываются. Серия из нескольких
 * замеров одной части суток в день — на графике одним столбиком (среднее серии).
 */
internal fun pressureHistory(readings: List<Reading>, target: Target, today: LocalDate, days: Int): PressureHistory {
    val from = periodFrom(today, days)
    val inPeriod = readings.filter { it.at.toLocalDate() in from..today }.sortedByDescending { it.at }
    val byDay = inPeriod.groupBy { it.at.toLocalDate() }
    val columns = (0 until days).map { i ->
        val date = from.plusDays(i.toLong())
        val day = byDay[date].orEmpty()
        DayColumn(
            date,
            span(day.filter { dayPart(it) == DayPart.Morning }, target),
            span(day.filter { dayPart(it) == DayPart.Evening }, target),
        )
    }
    val scale = chartScale(columns, target)
    return PressureHistory(
        from = from,
        to = today,
        morning = partStat(inPeriod.filter { dayPart(it) == DayPart.Morning }, target),
        evening = partStat(inPeriod.filter { dayPart(it) == DayPart.Evening }, target),
        columns = columns,
        scale = scale,
        grid = gridLines(scale, target),
        insight = summarize(domainInsight(inPeriod, target)),
        latest = inPeriod,
    )
}
