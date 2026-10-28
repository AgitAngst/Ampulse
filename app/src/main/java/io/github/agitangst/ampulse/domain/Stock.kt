package io.github.agitangst.ampulse.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Упаковка лекарства: сколько осталось (в единицах дозы), срок годности, вскрытие. У лекарства может быть несколько
 * упаковок; расходуется сначала та, у которой срок раньше (FEFO), вскрытая — прежде новой.
 *
 * @param expiry последний день срока; если на упаковке только месяц — последний день месяца ([expiryHasDay] = false).
 * @param afterOpenDays сколько дней годно после вскрытия (инсулин — 28); 0 — срок после вскрытия не меняется.
 */
data class Pack(
    val id: Long,
    val medicineId: Long,
    val left: Double,
    val expiry: LocalDate?,
    val expiryHasDay: Boolean = false,
    val openedAt: LocalDate? = null,
    val afterOpenDays: Int = 0,
    val batch: String = "",
    val serial: String = "",
    val gtin: String = "",
)

/** До какого дня годна упаковка с учётом вскрытия. */
fun Pack.goodUntil(): LocalDate? {
    // «Годна 28 дней после вскрытия»: вскрыта 05.09 — годна до 03.10 включительно (как в макете, экран 40).
    val opened = if (openedAt != null && afterOpenDays > 0) openedAt.plusDays(afterOpenDays.toLong()) else null
    return listOfNotNull(expiry, opened).minOrNull()
}

/** Порядок расхода: вскрытые, затем по сроку, затем по номеру. */
fun fefo(packs: List<Pack>): List<Pack> =
    packs.filter { it.left > 0 }.sortedWith(compareBy<Pack>({ it.openedAt == null }, { it.goodUntil() ?: LocalDate.MAX }, { it.id }))

/** Списание дозы: из какой упаковки и сколько. Не хватает в одной — переходит к следующей. */
fun consume(packs: List<Pack>, amount: Double): List<Pair<Long, Double>> {
    var rest = amount
    val out = mutableListOf<Pair<Long, Double>>()
    for (p in fefo(packs)) {
        if (rest <= 0) break
        val take = minOf(p.left, rest)
        out += p.id to take
        rest -= take
    }
    return out
}

/** Расход в день по схеме — среднее за 28 дней вперёд (учитывает дни недели, циклы, ступени). */
fun dailyNeed(plan: Plan, from: LocalDate): Double {
    if (plan.kind == PlanKind.AsNeeded || plan.paused) return 0.0
    val days = 28
    val total = (0 until days).sumOf { d -> plan.dosesOn(from.plusDays(d.toLong())).sumOf { it.amount } }
    return total / days
}

/** На сколько дней хватит запаса по схеме; `null` — расход неизвестен (по необходимости, пауза). */
fun daysLeft(packs: List<Pack>, plan: Plan?, today: LocalDate): Int? {
    val need = plan?.let { dailyNeed(it, today) } ?: return null
    if (need <= 0.0) return null
    return floor(packs.sumOf { it.left } / need).toInt()
}

/** Мало запаса — 7 дней и меньше (макет, экран 5). */
const val LOW_SUPPLY_DAYS = 7

/** Состояние срока (макет, экраны 40–41). */
enum class ExpiryState { Expired, Opened, Soon, Ok, Unknown }

/** Окно «скоро истекает»: 1, 3 или 6 месяцев (выбор в «Сроках годности»). */
fun expiryState(pack: Pack, today: LocalDate, windowMonths: Int): ExpiryState {
    val until = pack.goodUntil() ?: return ExpiryState.Unknown
    return when {
        until < today -> ExpiryState.Expired
        pack.openedAt != null && pack.afterOpenDays > 0 -> ExpiryState.Opened
        until <= today.plusMonths(windowMonths.toLong()) -> ExpiryState.Soon
        else -> ExpiryState.Ok
    }
}

/** Прогноз «успеете ли допить до конца срока». */
sealed interface ExpiryForecast {
    /** Закончится [runsOut] — раньше срока. */
    data class Fits(val runsOut: LocalDate) : ExpiryForecast
    /** К концу срока останется [left] единиц. */
    data class Leftover(val left: Double) : ExpiryForecast
    data object Unknown : ExpiryForecast
}

fun forecast(pack: Pack, need: Double, today: LocalDate): ExpiryForecast {
    val until = pack.goodUntil() ?: return ExpiryForecast.Unknown
    if (need <= 0.0) return ExpiryForecast.Unknown
    val days = ChronoUnit.DAYS.between(today, until) + 1
    val runsOutIn = ceil(pack.left / need).toLong()
    return if (runsOutIn <= days) ExpiryForecast.Fits(today.plusDays(runsOutIn)) else ExpiryForecast.Leftover(pack.left - need * days)
}

/** Последний день срока, если на упаковке только месяц и год. */
fun endOfMonth(year: Int, month: Int): LocalDate = YearMonth.of(year, month).atEndOfMonth()

/**
 * Код «Честного знака» на лекарстве (GS1 DataMatrix): GTIN (01), серийный номер (21), срок (17, ГГММДД; ДД = 00 —
 * конец месяца), серия (10). Переменные поля кончаются символом GS (29) или концом строки. Без сети: подлинность и
 * отзыв партии проверить нельзя.
 */
data class PackCode(val gtin: String, val serial: String, val expiry: LocalDate?, val expiryHasDay: Boolean, val batch: String)

fun parsePackCode(raw: String): PackCode? {
    val s = raw.removePrefix("]d2").removePrefix("è").trim('\u001D')
    var i = 0
    var gtin = ""
    var serial = ""
    var batch = ""
    var expiry: LocalDate? = null
    var hasDay = false
    fun variable(): String {
        val end = s.indexOf('\u001D', i).let { if (it < 0) s.length else it }
        return s.substring(i, end).also { i = if (end < s.length) end + 1 else end }
    }
    while (i + 2 <= s.length) {
        when (s.substring(i, i + 2)) {
            "01" -> { if (i + 16 > s.length) return null; gtin = s.substring(i + 2, i + 16); i += 16 }
            "17" -> {
                if (i + 8 > s.length) return null
                val v = s.substring(i + 2, i + 8); i += 8
                val y = 2000 + v.substring(0, 2).toInt()
                val m = v.substring(2, 4).toInt()
                val d = v.substring(4, 6).toInt()
                if (m !in 1..12) return null
                hasDay = d != 0
                expiry = if (hasDay) LocalDate.of(y, m, d) else endOfMonth(y, m)
            }
            "21" -> { i += 2; serial = variable() }
            "10" -> { i += 2; batch = variable() }
            "91", "92", "93" -> { i += 2; variable() }
            else -> { i += 2; variable() }
        }
    }
    return if (gtin.length == 14 && gtin.all(Char::isDigit)) PackCode(gtin, serial, expiry, hasDay, batch) else null
}

/** Остаток срока для показа: до 45 дней — днями, до года — месяцами, дальше — годами (docs/ui-guideline.md, §6). */
sealed interface TimeLeft {
    data class Days(val n: Int) : TimeLeft
    data class Months(val n: Int) : TimeLeft
    data class Years(val n: Int) : TimeLeft
}

fun timeLeft(until: LocalDate, today: LocalDate): TimeLeft {
    val days = ChronoUnit.DAYS.between(today, until).toInt()
    return when {
        days < 45 -> TimeLeft.Days(maxOf(days, 0))
        days < 365 -> TimeLeft.Months((days / 30.44).toInt())
        else -> TimeLeft.Years((days / 365.25).toInt())
    }
}

/** Момент «сейчас» для расчётов списания (тесты подменяют). */
fun interface Clock {
    fun now(): LocalDateTime
}
