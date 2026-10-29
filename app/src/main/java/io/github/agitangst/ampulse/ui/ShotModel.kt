package io.github.agitangst.ampulse.ui

import io.github.agitangst.ampulse.domain.DoseEvent
import io.github.agitangst.ampulse.domain.EventStatus
import io.github.agitangst.ampulse.domain.Plan
import io.github.agitangst.ampulse.domain.PlanKind
import io.github.agitangst.ampulse.domain.Site
import io.github.agitangst.ampulse.domain.injectSeconds
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale

// Чистая логика «Укола пошагово» (макет, экраны 44–46): вид укола, список шагов, отсчёт, счёт курса. Без Android.

/** Вид укола: подкожно шприц-ручкой или в мышцу из ампулы шприцем. */
enum class ShotKind { Pen, Muscle }

/** Вид укола и уверенность в нём: если [sure] ложно, наверху экрана показывается переключатель. */
data class ShotKindGuess(val kind: ShotKind, val sure: Boolean)

private fun normUnit(unit: String): String = unit.trim().trimEnd('.').lowercase(Locale.ROOT)

/** Единица действия («ЕД», «U») — у шприц-ручки. */
fun isUnitsUnit(unit: String): Boolean = normUnit(unit) in setOf("ед", "u", "iu", "ме")

/** Единица объёма («мл», «ml») — у ампулы. */
fun isMillilitres(unit: String): Boolean = normUnit(unit) in setOf("мл", "ml")

/**
 * Вид укола по месту и единице дозы: место, выбранное на «Месте укола», решает само (в мышцу — ягодицы и бёдра);
 * иначе «ЕД» / «U» — ручка, «мл» / «ml» — ампула; прочее неоднозначно — по умолчанию ручка, но с переключателем.
 */
fun guessShotKind(site: Site?, unit: String): ShotKindGuess = when {
    site != null -> ShotKindGuess(if (site.intramuscular) ShotKind.Muscle else ShotKind.Pen, true)
    isUnitsUnit(unit) -> ShotKindGuess(ShotKind.Pen, true)
    isMillilitres(unit) -> ShotKindGuess(ShotKind.Muscle, true)
    else -> ShotKindGuess(ShotKind.Pen, false)
}

/** Шаги обоих видов. Заголовки и тексты — в `strings_shots.xml` (`ShotTexts.kt`). */
enum class ShotStep(val kind: ShotKind, val timed: Boolean = false) {
    Hands(ShotKind.Pen), CheckPen(ShotKind.Pen), Needle(ShotKind.Pen), Prime(ShotKind.Pen), Dial(ShotKind.Pen),
    SitePen(ShotKind.Pen), InjectPen(ShotKind.Pen, timed = true), AfterPen(ShotKind.Pen),

    Prepare(ShotKind.Muscle), CheckAmpoule(ShotKind.Muscle), Draw(ShotKind.Muscle), SiteMuscle(ShotKind.Muscle),
    Skin(ShotKind.Muscle), InjectMuscle(ShotKind.Muscle, timed = true), AfterMuscle(ShotKind.Muscle),
}

/** Шаги вида по порядку: подкожно ручкой — 8, в мышцу — 7. */
fun shotSteps(kind: ShotKind): List<ShotStep> = ShotStep.entries.filter { it.kind == kind }

/**
 * Отсчёт шага «Укол», секунды: подкожно — держать кнопку 10 с; в мышцу — около 10 с на 1 мл, не меньше 10
 * (`injectSeconds`). Объём известен, только если доза в «мл» / «ml»; иначе — минимум.
 */
fun shotTimerSeconds(kind: ShotKind, unit: String, amount: Double): Int =
    if (kind == ShotKind.Pen) injectSeconds(false, amount) else injectSeconds(true, if (isMillilitres(unit)) amount else 0.0)

/** Доза укола: по схеме на этот день, иначе первая доза схемы; схемы нет — 0 (дозу приложение не придумывает). */
fun shotAmount(plan: Plan?, date: LocalDate): Double =
    plan?.let { it.dosesOn(date).firstOrNull()?.amount ?: it.times.firstOrNull()?.amount } ?: 0.0

fun isAbdomen(site: Site): Boolean = site == Site.AbdomenLeft || site == Site.AbdomenRight

fun isButtock(site: Site): Boolean = site == Site.ButtockLeft || site == Site.ButtockRight

/** В схеме ягодиц отмечается левая сторона (вид сзади — слева); у правой ягодицы — зеркально. */
fun isLeftSide(site: Site): Boolean = site.name.endsWith("Left")

/** Объём в мышцу в плечо не больше, мл (памятка, экран 46); ягодица и бедро — `maxIntramuscularMl`. */
const val ARM_MAX_ML = 1.0

/** Всего приёмов за курс по схеме; `null` — у схемы нет конца («по необходимости», без срока). Паузу не считает. */
fun courseTotal(plan: Plan): Int? {
    val last = plan.lastDay ?: return null
    if (plan.kind == PlanKind.AsNeeded || last < plan.start || ChronoUnit.DAYS.between(plan.start, last) > 3660) return null
    val open = plan.copy(paused = false)
    var n = 0
    var day = plan.start
    while (!day.isAfter(last)) {
        n += open.dosesOn(day).size
        day = day.plusDays(1)
    }
    return n.takeIf { it > 0 }
}

/** День курса «3 из 10» на дату [today]; `null` — срока нет. День вне курса прижимается к краю. */
fun courseDay(plan: Plan, today: LocalDate): Pair<Int, Int>? {
    val last = plan.lastDay ?: return null
    if (plan.kind == PlanKind.AsNeeded || last < plan.start) return null
    val total = ChronoUnit.DAYS.between(plan.start, last).toInt() + 1
    val day = (ChronoUnit.DAYS.between(plan.start, today).toInt() + 1).coerceIn(1, total)
    return day to total
}

/** Сколько уколов лекарства сделано с начала курса (принятые и записанные сверх схемы). */
fun courseDone(events: List<DoseEvent>, medicineId: Long, plan: Plan): Int =
    events.count {
        it.medicineId == medicineId && (it.status == EventStatus.Taken || it.status == EventStatus.Extra) &&
            !it.at.toLocalDate().isBefore(plan.start)
    }

/** Ближайший приём по схеме после [after] (до 60 дней вперёд); `null` — приёмов больше нет. */
fun nextPlanned(plan: Plan?, after: LocalDateTime): LocalDateTime? {
    if (plan == null) return null
    for (d in 0L..60L) {
        val date = after.toLocalDate().plusDays(d)
        val next = plan.dosesOn(date).map { date.atTime(it.time) }.filter { it > after }.minOrNull()
        if (next != null) return next
    }
    return null
}
