package io.github.agitangst.ampulse.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** Форма лекарства: от неё — значок, единица дозы, правила уколов. */
enum class MedForm { Tablet, Capsule, Injection, Drops, Liquid, Other }

/** Приём и еда. */
enum class FoodRule { None, EmptyStomach, Before, With, After }

/**
 * Вид схемы (макет, экран 10): каждый день, по дням недели, раз в N дней, циклы «приём/перерыв», ступени (снижение
 * или повышение дозы) и «по необходимости» — без расписания, с интервалом и лимитом за сутки.
 */
enum class PlanKind { Daily, Weekly, EveryNDays, Cycle, Taper, AsNeeded }

/** Время приёма и доза в единицах лекарства (табл., ЕД, мл…). */
data class DoseTime(val time: LocalTime, val amount: Double)

/** Ступень схемы: доза на каждый приём и сколько дней она держится. */
data class TaperStep(val amount: Double, val days: Int)

/**
 * Схема приёма. Дозы назначает врач — приложение их только раскладывает и никогда не меняет само.
 *
 * @param end последний день курса (включительно); `null` — без конца. У ступеней конец — по сумме дней.
 */
data class Plan(
    val kind: PlanKind,
    val times: List<DoseTime>,
    val start: LocalDate,
    val end: LocalDate? = null,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val everyN: Int = 1,
    val cycleOn: Int = 0,
    val cycleOff: Int = 0,
    val steps: List<TaperStep> = emptyList(),
    val prnIntervalHours: Int = 0,
    val prnMaxPerDay: Int = 0,
    val paused: Boolean = false,
) {
    /** Последний день курса с учётом ступеней. */
    val lastDay: LocalDate?
        get() = when {
            kind == PlanKind.Taper && steps.isNotEmpty() -> start.plusDays(steps.sumOf { it.days }.toLong() - 1)
            else -> end
        }

    /** Приёмы в этот день по времени. «По необходимости» и пауза — пусто. */
    fun dosesOn(date: LocalDate): List<DoseTime> {
        if (paused || kind == PlanKind.AsNeeded || date < start) return emptyList()
        lastDay?.let { if (date > it) return emptyList() }
        val day = ChronoUnit.DAYS.between(start, date)
        val active = when (kind) {
            PlanKind.Daily -> true
            PlanKind.Weekly -> date.dayOfWeek in weekdays
            PlanKind.EveryNDays -> everyN > 0 && day % everyN == 0L
            PlanKind.Cycle -> cycleOn > 0 && day % (cycleOn + cycleOff) < cycleOn
            PlanKind.Taper -> true
            PlanKind.AsNeeded -> false
        }
        if (!active) return emptyList()
        val amount = if (kind == PlanKind.Taper) stepOn(date)?.amount ?: return emptyList() else null
        return times.sortedBy { it.time }.map { if (amount != null) it.copy(amount = amount) else it }
    }

    /** Ступень, действующая в этот день; `null` — вне ступеней. */
    fun stepOn(date: LocalDate): TaperStep? {
        var from = start
        for (step in steps) {
            val to = from.plusDays(step.days.toLong())
            if (date >= from && date < to) return step
            from = to
        }
        return null
    }

    /** День смены дозы в ступенчатой схеме — накануне приложение напоминает (макет, экран 10). */
    fun nextStepChange(after: LocalDate): LocalDate? {
        if (kind != PlanKind.Taper) return null
        var from = start
        for (step in steps.dropLast(1)) {
            from = from.plusDays(step.days.toLong())
            if (from > after) return from
        }
        return null
    }
}
