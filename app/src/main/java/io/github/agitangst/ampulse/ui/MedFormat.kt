package io.github.agitangst.ampulse.ui

import android.content.res.Resources
import android.text.format.DateFormat
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.domain.FoodRule
import io.github.agitangst.ampulse.domain.MedForm
import io.github.agitangst.ampulse.domain.Plan
import io.github.agitangst.ampulse.domain.PlanKind
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Тексты экранов лекарств: числа, время и даты по языку программы и настройке телефона (12 или 24 ч), склонения
 * через `plurals`, сводка схемы словами. Создаётся [rememberMedTexts] и пересоздаётся при смене языка.
 */
internal class MedTexts(private val res: Resources, val locale: Locale, val is24: Boolean) {
    private val timeFormat = DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "h:mm a", locale)
    private val dateFormat = DateTimeFormatter.ofPattern(res.getString(R.string.meds_date_pattern), locale)
    private val shortDateFormat = DateTimeFormatter.ofPattern(res.getString(R.string.meds_date_short_pattern), locale)
    private val numberFormat = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 1
    }

    fun string(@StringRes id: Int, vararg args: Any): String = res.getString(id, *args)

    fun plural(@PluralsRes id: Int, n: Int, vararg args: Any): String = res.getQuantityString(id, n, *args)

    fun time(t: LocalTime): String = t.format(timeFormat)

    /** Дата словами цифр по языку: RU `15.10.2026`, EN `Oct 15, 2026`. */
    fun date(d: LocalDate): String = d.format(dateFormat)

    /** Короткая дата без года: RU `8 окт.`, EN `Oct 8`. */
    fun shortDate(d: LocalDate): String = d.format(shortDateFormat)

    /** Число без лишнего нуля: 1 → «1», 0,5 → «0,5» (в EN «0.5»). */
    fun number(v: Double): String = numberFormat.format(v)

    fun dose(amount: Double, unit: String): String = "${number(amount)} $unit".trim()

    fun weekday(d: DayOfWeek): String = d.getDisplayName(TextStyle.SHORT, locale).replaceFirstChar { it.titlecase(locale) }

    fun days(n: Int): String = plural(R.plurals.meds_days, n, n)

    fun hours(n: Int): String = string(R.string.meds_hours_short, n)

    fun times(n: Int): String = plural(R.plurals.meds_times_count, n, n)

    /** Промежуток словами: «2 ч 15 мин», «45 мин», «1 ч»; секунды округляются вверх до минуты. */
    fun duration(d: Duration): String {
        val minutes = ((d.seconds + 59) / 60).coerceAtLeast(0)
        val h = (minutes / 60).toInt()
        val m = (minutes % 60).toInt()
        return when {
            h > 0 && m > 0 -> string(R.string.meds_dur_h_min, h, m)
            h > 0 -> string(R.string.meds_dur_h, h)
            else -> string(R.string.meds_dur_min, m)
        }
    }

    /** «после 14:30», «завтра после 08:00» или с датой, если это не сегодня и не завтра. */
    fun after(next: LocalDateTime, now: LocalDateTime): String = when (next.toLocalDate()) {
        now.toLocalDate() -> string(R.string.meds_after_today, time(next.toLocalTime()))
        now.toLocalDate().plusDays(1) -> string(R.string.meds_after_tomorrow, time(next.toLocalTime()))
        else -> string(R.string.meds_after_date, shortDate(next.toLocalDate()) + ", " + time(next.toLocalTime()))
    }

    /** «сегодня, 08:15», «вчера, 22:00» или дата со временем. */
    fun dayAndTime(at: LocalDateTime, now: LocalDateTime): String = when (at.toLocalDate()) {
        now.toLocalDate() -> string(R.string.meds_day_today, time(at.toLocalTime()))
        now.toLocalDate().minusDays(1) -> string(R.string.meds_day_yesterday, time(at.toLocalTime()))
        else -> shortDate(at.toLocalDate()) + ", " + time(at.toLocalTime())
    }

    fun formName(form: MedForm): String = string(
        when (form) {
            MedForm.Tablet -> R.string.meds_form_tablet
            MedForm.Capsule -> R.string.meds_form_capsule
            MedForm.Injection -> R.string.meds_form_injection
            MedForm.Drops -> R.string.meds_form_drops
            MedForm.Liquid -> R.string.meds_form_liquid
            MedForm.Other -> R.string.meds_form_other
        },
    )

    /** Единица дозы, подставляемая при выборе формы: «табл.», «капс.», «ЕД», «кап.», «мл». */
    fun unitFor(form: MedForm): String = string(
        when (form) {
            MedForm.Tablet -> R.string.meds_unit_tablet
            MedForm.Capsule -> R.string.meds_unit_capsule
            MedForm.Injection -> R.string.meds_unit_injection
            MedForm.Drops -> R.string.meds_unit_drops
            MedForm.Liquid -> R.string.meds_unit_liquid
            MedForm.Other -> R.string.meds_unit_other
        },
    )

    fun foodName(food: FoodRule): String = string(
        when (food) {
            FoodRule.None -> R.string.meds_food_none
            FoodRule.EmptyStomach -> R.string.meds_food_empty
            FoodRule.Before -> R.string.meds_food_before
            FoodRule.With -> R.string.meds_food_with
            FoodRule.After -> R.string.meds_food_after
        },
    )

    fun kindName(kind: PlanKind): String = string(
        when (kind) {
            PlanKind.Daily -> R.string.meds_kind_daily
            PlanKind.Weekly -> R.string.meds_kind_weekly
            PlanKind.EveryNDays -> R.string.meds_kind_every_n
            PlanKind.Cycle -> R.string.meds_kind_cycle
            PlanKind.Taper -> R.string.meds_kind_taper
            PlanKind.AsNeeded -> R.string.meds_kind_prn
        },
    )

    /**
     * Сводка схемы словами для строки списка: «2 раза в день · 08:30, 14:00», «Пн, Ср, Пт · 09:00», «Через день»,
     * «21 день приём / 7 перерыв», «Ступени: 4 → 1 табл. до 8 окт.», «По необходимости · не чаще раза в 6 ч, до 3 в
     * сутки», «Пауза». Нет схемы — «Без схемы».
     */
    fun summary(plan: Plan?, unit: String): String {
        if (plan == null) return string(R.string.meds_sum_none)
        if (plan.paused) return string(R.string.meds_sum_paused)
        val at = plan.times.map { it.time }.sorted().joinToString(", ") { time(it) }
        fun withTimes(head: String) = if (at.isEmpty()) head else "$head · $at"
        return when (plan.kind) {
            PlanKind.Daily -> withTimes(plural(R.plurals.meds_sum_per_day, plan.times.size, plan.times.size))
            PlanKind.Weekly -> withTimes(
                plan.weekdays.sorted().joinToString(", ") { weekday(it) }.ifEmpty { string(R.string.meds_sum_none) },
            )
            PlanKind.EveryNDays -> withTimes(
                if (plan.everyN == 2) string(R.string.meds_sum_every_other) else plural(R.plurals.meds_sum_every_n, plan.everyN, plan.everyN),
            )
            PlanKind.Cycle -> plural(R.plurals.meds_sum_cycle_on, plan.cycleOn, plan.cycleOn) + " / " +
                string(R.string.meds_sum_cycle_off, plan.cycleOff)
            PlanKind.Taper -> {
                val first = plan.steps.firstOrNull()
                val last = plan.steps.lastOrNull()
                if (first == null || last == null) {
                    string(R.string.meds_sum_none)
                } else {
                    val range = number(first.amount) + " → " + dose(last.amount, unit)
                    val until = plan.lastDay?.let { " " + string(R.string.meds_sum_until, shortDate(it)) }.orEmpty()
                    string(R.string.meds_sum_taper, range) + until
                }
            }
            PlanKind.AsNeeded -> {
                val parts = buildList {
                    if (plan.prnIntervalHours > 0) add(string(R.string.meds_sum_prn_interval, plan.prnIntervalHours))
                    if (plan.prnMaxPerDay > 0) add(string(R.string.meds_sum_prn_max, plan.prnMaxPerDay))
                }
                string(R.string.meds_kind_prn) + if (parts.isEmpty()) "" else " · " + parts.joinToString(", ")
            }
        }
    }
}

/** [MedTexts] для текущего языка и формата времени телефона. */
@Composable
internal fun rememberMedTexts(): MedTexts {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(configuration) {
        MedTexts(context.resources, configuration.locales[0], DateFormat.is24HourFormat(context))
    }
}
