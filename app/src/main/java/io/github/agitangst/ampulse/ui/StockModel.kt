package io.github.agitangst.ampulse.ui

import android.content.Context
import io.github.agitangst.ampulse.domain.ExpiryForecast
import io.github.agitangst.ampulse.domain.LOW_SUPPLY_DAYS
import io.github.agitangst.ampulse.domain.MedForm
import io.github.agitangst.ampulse.domain.Medicine
import io.github.agitangst.ampulse.domain.Pack
import io.github.agitangst.ampulse.domain.Plan
import io.github.agitangst.ampulse.domain.Schedule
import io.github.agitangst.ampulse.domain.daysLeft
import io.github.agitangst.ampulse.domain.dailyNeed
import io.github.agitangst.ampulse.domain.fefo
import io.github.agitangst.ampulse.domain.forecast
import io.github.agitangst.ampulse.domain.goodUntil
import java.time.LocalDate

/** Расчёты экранов запаса и сроков годности поверх домена: что считается запасом, прогноз, настройки экранов. */

/** Срок упаковки вышел (с учётом вскрытия). Без срока — не просрочена. */
internal fun Pack.isExpired(today: LocalDate): Boolean = goodUntil()?.let { it < today } == true

/**
 * Лекарство со схемой и упаковками. Просроченные упаковки в запас не входят (макет, экран 41). Упаковок нет — запас не
 * ведётся, [days] = `null`; расход неизвестен («по необходимости», пауза) — тоже `null`.
 */
internal class MedStock(val med: Medicine, val plan: Plan?, val packs: List<Pack>, val today: LocalDate) {
    /** Упаковки, из которых можно принимать. */
    val usable: List<Pack> = packs.filter { !it.isExpired(today) }

    /** Сколько осталось в годных упаковках, в единицах дозы. */
    val left: Double = usable.sumOf { it.left }

    /** Расход в день по схеме. */
    val need: Double = plan?.let { dailyNeed(it, today) } ?: 0.0

    /** На сколько дней хватит. */
    val days: Int? = if (packs.isEmpty()) null else daysLeft(usable, plan, today)

    val hasExpired: Boolean = packs.any { it.left > 0 && it.isExpired(today) }

    val low: Boolean = days != null && days <= LOW_SUPPLY_DAYS
}

internal fun buildStock(meds: List<Medicine>, schedules: List<Schedule>, packs: List<Pack>, today: LocalDate): List<MedStock> {
    val planOf = schedules.associate { it.medicineId to it.plan }
    val packsOf = packs.groupBy { it.medicineId }
    return meds.map { MedStock(it, planOf[it.id], packsOf[it.id].orEmpty(), today) }
}

/**
 * Прогноз «успеете ли допить до конца срока» для упаковки с учётом того, что расходуется раньше неё (вскрытые и с
 * ранним сроком, порядок FEFO): их остаток считается впереди.
 */
internal fun packForecast(pack: Pack, others: List<Pack>, need: Double, today: LocalDate): ExpiryForecast {
    if (pack.left <= 0.0) return ExpiryForecast.Unknown
    val usable = others.filter { it.id != pack.id && it.left > 0 && !it.isExpired(today) }
    val order = fefo(usable + pack, today)
    val index = order.indexOfFirst { it === pack }
    val ahead = if (index > 0) order.take(index).sumOf { it.left } else 0.0
    return forecast(pack.copy(left = pack.left + ahead), need, today)
}

/** Шаг шагового поля остатка по форме лекарства: таблетки 1, ЕД 10, мл 1 (docs/ui-guideline.md, §6). */
internal fun leftStep(form: MedForm): Double = if (form == MedForm.Injection) 10.0 else 1.0

/** Остаток новой упаковки по умолчанию: упаковка таблеток, ручка 300 ЕД, флакон. */
internal fun defaultLeft(form: MedForm): Double = when (form) {
    MedForm.Tablet, MedForm.Capsule -> 30.0
    MedForm.Injection -> 300.0
    MedForm.Liquid -> 100.0
    MedForm.Drops, MedForm.Other -> 10.0
}

/** Срок после вскрытия по умолчанию: у уколов 28 дней, у остальных срок не меняется. */
internal fun defaultAfterOpen(form: MedForm): Int = if (form == MedForm.Injection) 28 else 0

/** Окно «скоро истекает»: 1, 3 или 6 месяцев; хранится в настройках программы. */
internal val EXPIRY_WINDOWS = listOf(1, 3, 6)

/** Настройки экранов запаса: окно сроков и отметка «в списке покупок» (файл `ampulse`). */
internal object StockPrefs {
    private const val FILE = "ampulse"
    private const val WINDOW = "expiry_window"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun expiryWindow(context: Context): Int =
        prefs(context).getInt(WINDOW, 3).let { if (it in EXPIRY_WINDOWS) it else 3 }

    fun setExpiryWindow(context: Context, months: Int) {
        prefs(context).edit().putInt(WINDOW, months).apply()
    }

    fun inShoppingList(context: Context, medicineId: Long): Boolean = prefs(context).getBoolean("shopping_$medicineId", false)

    fun setShoppingList(context: Context, medicineId: Long, on: Boolean) {
        prefs(context).edit().putBoolean("shopping_$medicineId", on).apply()
    }
}
