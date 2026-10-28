package io.github.agitangst.ampulse.domain

import java.time.LocalDate
import java.time.LocalTime

/**
 * Разбор назначения врача без сети (макет, экраны 7–9): правила для русского и английского текста. Каждая строка —
 * лекарство; что взято из текста — [Parsed.found], что додумано — показывается пунктиром, чего нет — спрашивается.
 * Дозы приложение не меняет: только раскладывает то, что написал врач.
 */
enum class TimeHint { Night, Morning, Evening, Both }

enum class Route { Subcutaneous, Intramuscular }

/** Что взято из текста, а что додумано (на экране проверки — сплошная рамка или пунктир). */
data class Found(val count: Boolean, val frequency: Boolean, val duration: Boolean, val food: Boolean)

data class Parsed(
    val source: String,
    val name: String,
    /** Дозировка как в тексте: «500 мг», «14 ЕД». */
    val strength: String?,
    /** Сколько единиц на приём: «по 2 таб.» → 2. */
    val perDose: Double,
    val form: MedForm,
    val route: Route?,
    val hint: TimeHint?,
    /** Сколько раз в день; `null` — в тексте нет, надо спросить. */
    val timesPerDay: Int?,
    val everyOtherDay: Boolean,
    val food: FoodRule,
    /** Длительность курса в днях; `null` — постоянно. */
    val days: Int?,
    val steps: List<TaperStep>?,
    val found: Found,
)

/** Режим дня человека: подъём, завтрак, обед, ужин, сон. */
data class Routine(
    val wake: LocalTime = LocalTime.of(7, 0),
    val breakfast: LocalTime = LocalTime.of(7, 30),
    val lunch: LocalTime = LocalTime.of(13, 0),
    val dinner: LocalTime = LocalTime.of(19, 0),
    val sleep: LocalTime = LocalTime.of(23, 0),
)

object Prescription {
    private val CYRILLIC = Regex("[а-яё]", RegexOption.IGNORE_CASE)

    fun parseAll(text: String): List<Parsed> = text.lines().mapNotNull(::parse)

    fun parse(raw: String): Parsed? {
        val line = raw.replace(Regex("""^\s*\d+\s*[.)]\s*"""), "").trim()
        return if (CYRILLIC.containsMatchIn(line)) Ru.parse(line) else En.parse(line)
    }

    private fun num(s: String) = s.replace(',', '.').toDouble()

    private fun nameOf(line: String, cuts: List<Int>): String {
        val cut = cuts.filter { it >= 0 }.minOrNull() ?: line.length
        return line.substring(0, cut).trim().trimEnd('.', ',', ';', ':', '–', '—', '-').trim().ifEmpty { line.split(Regex("\\s+"))[0] }
    }

    private object Ru {
        val UNIT = mapOf("мкг" to "мкг", "мг" to "мг", "г" to "г", "ме" to "МЕ", "ед" to "ЕД", "мл" to "мл")
        val word = Regex("[а-яёa-z]{3,}", RegexOption.IGNORE_CASE)
        val dose = Regex("""(\d+(?:[.,]\d+)?)\s*(мкг|мг|г|ме|ед|мл)(?![а-яёa-z])""", RegexOption.IGNORE_CASE)
        val nameCut = Regex("""\s(?:по\s|утром|вечером|на ночь|перед сном|натощак|\d\s*р\s*/|\d\s*раза?\s)|,""", RegexOption.IGNORE_CASE)
        val count = Regex("""(\d+(?:[.,]\d+)?)\s*(капс|таб|шт|доз)""", RegexOption.IGNORE_CASE)
        val freq = Regex("""(\d)\s*(?:р\s*/\s*д|р\.?\s*в\s*(?:д|сут)|раза?\s*в\s*(?:день|сутки|д))""", RegexOption.IGNORE_CASE)
        val dayDur = Regex("""(\d+)\s*(?:дн|день|дня|дней|сут)""", RegexOption.IGNORE_CASE)
        val weekDur = Regex("""(\d+)\s*нед""", RegexOption.IGNORE_CASE)
        val monthDur = Regex("""(\d+)\s*мес""", RegexOption.IGNORE_CASE)
        val then = Regex(""",?\s*(?:затем|потом|далее)\s+""", RegexOption.IGNORE_CASE)

        fun has(line: String, re: String) = Regex(re, RegexOption.IGNORE_CASE).containsMatchIn(line)

        fun parse(line: String): Parsed? {
            if (!word.containsMatchIn(line)) return null
            val d = dose.find(line)
            val name = nameOf(line, listOf(d?.range?.first ?: -1, nameCut.find(line)?.range?.first ?: -1))
            val c = count.find(line)
            val route = when {
                has(line, """п\s*/\s*к|подкож""") -> Route.Subcutaneous
                has(line, """в\s*/\s*м|внутримыш""") -> Route.Intramuscular
                else -> null
            }
            val form = when {
                route != null -> MedForm.Injection
                has(line, "капс") -> MedForm.Capsule
                has(line, "таб") -> MedForm.Tablet
                has(line, "капл") -> MedForm.Drops
                else -> MedForm.Other
            }
            val hint = when {
                has(line, "на ночь|перед сном") -> TimeHint.Night
                has(line, "утром и вечером") -> TimeHint.Both
                has(line, "утром") -> TimeHint.Morning
                has(line, "вечером") -> TimeHint.Evening
                else -> null
            }
            val everyOther = has(line, "через день")
            val n = freq.find(line)?.groupValues?.get(1)?.toInt()
                ?: when {
                    hint == TimeHint.Both -> 2
                    hint != null -> 1
                    has(line, "ежедневно|раз в день") || everyOther -> 1
                    else -> null
                }
            val food = when {
                has(line, "натощак") -> FoodRule.EmptyStomach
                has(line, """до\s+еды""") -> FoodRule.Before
                has(line, "во время еды|с едой|во время при[её]ма пищи") -> FoodRule.With
                has(line, """после\s+еды""") -> FoodRule.After
                else -> FoodRule.None
            }
            var days = dayDur.find(line)?.groupValues?.get(1)?.toInt()
                ?: weekDur.find(line)?.groupValues?.get(1)?.toInt()?.times(7)
                ?: monthDur.find(line)?.groupValues?.get(1)?.toInt()?.times(30)
            var durationFound = days != null || has(line, "постоянно|длительно|пожизненно")
            val steps = line.split(then).takeIf { it.size > 1 }?.map { seg ->
                val per = count.find(seg)?.groupValues?.get(1)?.let(::num)
                val dd = dayDur.find(seg)?.groupValues?.get(1)?.toInt()
                if (per == null || dd == null) null else TaperStep(per, dd)
            }?.takeIf { s -> s.all { it != null } }?.filterNotNull()
            if (steps != null) { days = steps.sumOf { it.days }; durationFound = true }
            return Parsed(
                source = line, name = name, strength = d?.let { "${it.groupValues[1]} ${UNIT[it.groupValues[2].lowercase()]}" },
                perDose = c?.groupValues?.get(1)?.let(::num) ?: 1.0, form = form, route = route, hint = hint,
                timesPerDay = n, everyOtherDay = everyOther, food = food, days = days, steps = steps,
                found = Found(count = c != null, frequency = n != null, duration = durationFound, food = food != FoodRule.None),
            )
        }
    }

    private object En {
        val UNIT = mapOf("mcg" to "mcg", "µg" to "mcg", "mg" to "mg", "g" to "g", "iu" to "IU", "units" to "U", "unit" to "U", "u" to "U", "ml" to "ml")
        val word = Regex("[a-z]{3,}", RegexOption.IGNORE_CASE)
        val dose = Regex("""(\d+(?:[.,]\d+)?)\s*(mcg|µg|mg|g|iu|units?|u|ml)(?![a-z])""", RegexOption.IGNORE_CASE)
        val nameCut = Regex(
            """\s(?:\d+\s*(?:caps?|capsules?|tabs?|tablets?)\b|in the morning|in the evening|at bedtime|at night|on an empty stomach|once\b|twice\b|\d\s*(?:times|x)\b|[bt]\.?i\.?d\b|q\.?i\.?d\b)|,""",
            RegexOption.IGNORE_CASE,
        )
        val count = Regex("""(\d+(?:[.,]\d+)?)\s*(caps?|capsules?|tabs?|tablets?|pcs|doses?)\b""", RegexOption.IGNORE_CASE)
        val freq = Regex("""(\d)\s*(?:times?|x)\s*(?:a|per|/)?\s*day""", RegexOption.IGNORE_CASE)
        val dayDur = Regex("""(\d+)\s*days?\b""", RegexOption.IGNORE_CASE)
        val weekDur = Regex("""(\d+)\s*weeks?\b""", RegexOption.IGNORE_CASE)
        val monthDur = Regex("""(\d+)\s*months?\b""", RegexOption.IGNORE_CASE)
        val then = Regex(""",?\s*then\s+""", RegexOption.IGNORE_CASE)

        fun has(line: String, re: String) = Regex(re, RegexOption.IGNORE_CASE).containsMatchIn(line)

        fun parse(line: String): Parsed? {
            if (!word.containsMatchIn(line)) return null
            val d = dose.find(line)
            val name = nameOf(line, listOf(d?.range?.first ?: -1, nameCut.find(line)?.range?.first ?: -1))
            val c = count.find(line)
            val route = when {
                has(line, """\b(?:sc|s/c|subcut|subcutaneous(?:ly)?)\b""") -> Route.Subcutaneous
                has(line, """\b(?:im|i/m|intramuscular(?:ly)?)\b""") -> Route.Intramuscular
                else -> null
            }
            val form = when {
                route != null -> MedForm.Injection
                has(line, """\bcap""") -> MedForm.Capsule
                has(line, """\btab""") -> MedForm.Tablet
                has(line, """\bdrops?\b""") -> MedForm.Drops
                else -> MedForm.Other
            }
            val hint = when {
                has(line, "at bedtime|at night|before bed") -> TimeHint.Night
                has(line, "morning and evening") -> TimeHint.Both
                has(line, "in the morning|every morning") -> TimeHint.Morning
                has(line, "in the evening|every evening") -> TimeHint.Evening
                else -> null
            }
            val everyOther = has(line, "every other day")
            val n = freq.find(line)?.groupValues?.get(1)?.toInt()
                ?: when {
                    has(line, """\bq\.?i\.?d\b|four times""") -> 4
                    has(line, """\bt\.?i\.?d\b|three times""") -> 3
                    has(line, """\bb\.?i\.?d\b|twice""") -> 2
                    hint == TimeHint.Both -> 2
                    hint != null -> 1
                    has(line, """once (?:a|per) day|\bdaily\b|\bq\.?d\b|\bo\.?d\b""") || everyOther -> 1
                    else -> null
                }
            val food = when {
                has(line, "empty stomach") -> FoodRule.EmptyStomach
                has(line, "before (?:meals?|food|eating)") -> FoodRule.Before
                has(line, "with (?:meals?|food)|during meals?") -> FoodRule.With
                has(line, "after (?:meals?|food|eating)") -> FoodRule.After
                else -> FoodRule.None
            }
            var days = dayDur.find(line)?.groupValues?.get(1)?.toInt()
                ?: weekDur.find(line)?.groupValues?.get(1)?.toInt()?.times(7)
                ?: monthDur.find(line)?.groupValues?.get(1)?.toInt()?.times(30)
            var durationFound = days != null || has(line, "ongoing|long[- ]term|indefinitely|continuously")
            val steps = line.split(then).takeIf { it.size > 1 }?.map { seg ->
                val per = count.find(seg)?.groupValues?.get(1)?.let(::num)
                val dd = dayDur.find(seg)?.groupValues?.get(1)?.toInt()
                if (per == null || dd == null) null else TaperStep(per, dd)
            }?.takeIf { s -> s.all { it != null } }?.filterNotNull()
            if (steps != null) { days = steps.sumOf { it.days }; durationFound = true }
            return Parsed(
                source = line, name = name, strength = d?.let { "${it.groupValues[1]} ${UNIT[it.groupValues[2].lowercase()]}" },
                perDose = c?.groupValues?.get(1)?.let(::num) ?: 1.0, form = form, route = route, hint = hint,
                timesPerDay = n, everyOtherDay = everyOther, food = food, days = days, steps = steps,
                found = Found(count = c != null, frequency = n != null, duration = durationFound, food = food != FoodRule.None),
            )
        }
    }

    /** Время приёмов под режим дня (как в макете): еда — от завтрака/обеда/ужина, на ночь — за час до сна, иначе — ровно днём. */
    fun timesFor(p: Parsed, n: Int, r: Routine = Routine()): List<LocalTime> {
        fun min(t: LocalTime) = t.hour * 60 + t.minute
        fun at(m: Int) = LocalTime.of(((m % 1440) + 1440) % 1440 / 60, ((m % 1440) + 1440) % 1440 % 60)
        if (p.hint == TimeHint.Night) return listOf(at(min(r.sleep) - 60))
        if (p.food != FoodRule.None) {
            val off = when (p.food) { FoodRule.EmptyStomach, FoodRule.Before -> -30; FoodRule.After -> 30; else -> 0 }
            val base = when {
                n == 1 -> listOf(if (p.hint == TimeHint.Evening) r.dinner else r.breakfast)
                n == 2 -> listOf(r.breakfast, r.dinner)
                else -> listOf(r.breakfast, r.lunch, r.dinner)
            }.map { min(it) + off }.toMutableList()
            if (n >= 4) base += min(r.sleep) - 60
            return base.map(::at)
        }
        if (n == 1) return listOf(when (p.hint) { TimeHint.Evening -> LocalTime.of(20, 0); TimeHint.Morning -> r.breakfast; else -> LocalTime.of(9, 0) })
        if (p.hint == TimeHint.Both) return listOf(r.breakfast, LocalTime.of(20, 0))
        val span = min(r.sleep) - min(r.wake)
        val step = minOf(1440.0 / n, span.toDouble() / (n - 1))
        val start = min(r.wake) + (span - step * (n - 1)) / 2
        return (0 until n).map { i -> at((Math.round((start + step * i) / 30) * 30).toInt()) }
    }

    /** Лекарство из разобранной строки. Единица — по форме; у уколов — ЕД, если доза в ЕД, иначе мл. */
    fun medicine(p: Parsed, ru: Boolean): Medicine {
        val unit = when (p.form) {
            MedForm.Capsule -> if (ru) "капс." else "caps"
            MedForm.Tablet -> if (ru) "табл." else "tabs"
            MedForm.Drops -> if (ru) "кап." else "drops"
            MedForm.Injection -> if (p.strength?.endsWith("ЕД") == true || p.strength?.endsWith("U") == true) (if (ru) "ЕД" else "U") else (if (ru) "мл" else "ml")
            else -> if (ru) "шт." else "pcs"
        }
        return Medicine(0, p.name, p.strength.orEmpty(), p.form, unit, food = p.food)
    }

    /**
     * Схема из разобранной строки; [n] — сколько раз в день (из текста или ответ человека). Укол «14 ЕД» — доза 14 в
     * единицах ЕД; ступени — по шагам; «через день» — раз в 2 дня.
     */
    fun plan(p: Parsed, n: Int, start: LocalDate, r: Routine = Routine()): Plan {
        val amount = if (p.form == MedForm.Injection) p.strength?.substringBefore(' ')?.replace(',', '.')?.toDoubleOrNull() ?: p.perDose else p.perDose
        val times = timesFor(p, n, r).map { DoseTime(it, amount) }
        val end = p.days?.let { start.plusDays(it.toLong() - 1) }
        return when {
            p.steps != null -> Plan(PlanKind.Taper, times, start, steps = p.steps)
            p.everyOtherDay -> Plan(PlanKind.EveryNDays, times, start, end = end, everyN = 2)
            else -> Plan(PlanKind.Daily, times, start, end = end)
        }
    }
}
