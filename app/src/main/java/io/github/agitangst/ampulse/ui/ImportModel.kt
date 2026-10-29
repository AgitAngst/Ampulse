package io.github.agitangst.ampulse.ui

import io.github.agitangst.ampulse.domain.FoodRule
import io.github.agitangst.ampulse.domain.MedForm
import io.github.agitangst.ampulse.domain.Medicine
import io.github.agitangst.ampulse.domain.Parsed
import io.github.agitangst.ampulse.domain.Plan
import io.github.agitangst.ampulse.domain.Prescription
import io.github.agitangst.ampulse.domain.Routine
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

// Чистая логика экрана «Назначение → план» (макет, экраны 7–9): без Android, поэтому проверяется юнит-тестом.

/** Файл с назначением больше этого — не назначение, читать не будем. */
internal const val IMPORT_MAX_FILE_BYTES = 256 * 1024

/** Сколько раз в день можно выбрать ответом на вопрос «Сколько раз в день?». */
internal val IMPORT_ANSWERS = 1..4

/** Название для сравнения: без регистра, «ё» = «е», пробелы схлопнуты. */
internal fun normName(s: String): String = s.trim().lowercase().replace('ё', 'е').replace(Regex("\\s+"), " ")

/**
 * Лекарство из списка, чью схему заменит строка назначения, — с тем же названием без учёта регистра. Нет такого —
 * `null`, строка станет новым лекарством. [taken] — уже занятые другой строкой: две строки об одном лекарстве не
 * затирают друг друга.
 */
internal fun matchExisting(name: String, medicines: List<Medicine>, taken: Set<Long> = emptySet()): Medicine? {
    val key = normName(name)
    if (key.isEmpty()) return null
    return medicines.firstOrNull { it.id !in taken && normName(it.name) == key }
}

/** Режим дня годится для плана: подъём раньше сна в одних сутках (иначе приёмы «поровну днём» считаются неверно). */
internal fun Routine.isValid(): Boolean = wake < sleep

/** Строка назначения на экранах проверки и плана. */
internal data class ImportRow(
    val parsed: Parsed,
    /** Сколько раз в день: из текста или ответ человека; `null` — ещё не отвечено. */
    val perDay: Int?,
    /** Лекарство из списка, чью схему заменит строка; `null` — новое. */
    val existing: Medicine?,
) {
    /** Частота ещё не выбрана — «Дальше» недоступно. */
    val isOpen: Boolean get() = perDay == null

    /** В тексте частоты нет (или она нелепая: «0 р/д») — под карточкой вопрос «Сколько раз в день?». */
    val asked: Boolean get() = (parsed.timesPerDay ?: 0) < 1
}

/**
 * Строки проверки: разобранное без убранных ([removed] — исходные строки текста). Частота — из текста (нуль и
 * меньше — «нет»), иначе ответ из [answers] по исходной строке.
 */
internal fun reviewRows(
    parsed: List<Parsed>,
    removed: Set<String>,
    answers: Map<String, Int>,
    medicines: List<Medicine>,
): List<ImportRow> {
    val taken = mutableSetOf<Long>()
    return parsed.filter { it.source !in removed }.map { p ->
        val match = matchExisting(p.name, medicines, taken)?.also { taken += it.id }
        ImportRow(p, p.timesPerDay?.takeIf { it >= 1 } ?: answers[p.source], match)
    }
}

/**
 * Лекарство из разобранной строки. Единица — по форме из строк программы ([unitFor]); у укола с дозировкой
 * («14 ЕД», «2 мл», «125 мг») — та единица, что в дозировке: доза укола в схеме берётся из дозировки, и единица
 * обязана быть её же (`Prescription.medicine` для «125 мг» ставит «мл»).
 */
internal fun medicineOf(p: Parsed, unitFor: (MedForm) -> String): Medicine {
    val unit = if (p.form == MedForm.Injection) {
        p.strength?.substringAfter(' ', "")?.takeIf { it.isNotBlank() } ?: unitFor(MedForm.Other)
    } else {
        unitFor(p.form)
    }
    return Medicine(0, p.name, p.strength.orEmpty(), p.form, unit, food = p.food)
}

/** Лекарство и схема, которые уйдут в базу из одной строки. */
internal data class ImportPlan(val row: ImportRow, val medicine: Medicine, val plan: Plan)

/**
 * Схема строки по режиму дня. Строка совпала с лекарством из списка — сохраняется с его id и заменяет его схему;
 * название, важность и заметка остаются прежними, дозировка и еда — из назначения, если они там есть, форма и
 * единица — если в тексте нашлась форма. `null` — частота ещё не выбрана.
 */
internal fun planFor(row: ImportRow, start: LocalDate, routine: Routine, unitFor: (MedForm) -> String): ImportPlan? {
    val n = row.perDay ?: return null
    val p = row.parsed
    val fresh = medicineOf(p, unitFor)
    val old = row.existing
    val medicine = if (old == null) {
        fresh
    } else {
        // Формы в тексте нет («Метформин 500 мг 2 р/д») — остаётся прежняя форма и единица.
        val keepShape = fresh.form == MedForm.Other
        old.copy(
            strength = fresh.strength.ifBlank { old.strength },
            form = if (keepShape) old.form else fresh.form,
            unit = if (keepShape) old.unit else fresh.unit,
            food = if (p.found.food) p.food else old.food,
        )
    }
    val plan = Prescription.plan(p, n, start, routine)
    // Одно время дважды (режим дня с совпадающими приёмами пищи) — один приём.
    return ImportPlan(row, medicine, plan.copy(times = plan.times.distinctBy { it.time }))
}

/** В назначении другая дозировка, чем у лекарства в списке (или в списке её не было, а в назначении есть — не считается). */
internal fun doseDiffers(old: Medicine, p: Parsed): Boolean {
    val now = p.strength ?: return false
    return old.strength.isNotBlank() && normName(old.strength) != normName(now)
}

/** Приёмы дня: время и что принять в это время; по возрастанию времени. */
internal data class Slot(val time: LocalTime, val entries: List<ImportPlan>)

internal fun daySlots(plans: List<ImportPlan>): List<Slot> =
    plans.flatMap { ip -> ip.plan.times.map { it.time to ip } }
        .groupBy({ it.first }, { it.second })
        .toSortedMap()
        .map { (time, list) -> Slot(time, list) }

/** Приёмов за день по всем схемам (для сводки «N приёмов в день»). */
internal fun dosesPerDay(plans: List<ImportPlan>): Int = plans.sumOf { it.plan.times.size }

/**
 * Сколько понадобится на курс: число приёмов и сумма доз в единицах лекарства — по самой схеме (через день, ступени).
 * Курса без конца считается на [CourseTotal.days] = 30 дней и помечается [CourseTotal.monthly].
 */
internal data class CourseTotal(val doses: Int, val amount: Double, val days: Int, val monthly: Boolean)

internal fun courseTotal(plan: Plan): CourseTotal {
    val last = plan.lastDay
    val days = if (last == null) 30 else (ChronoUnit.DAYS.between(plan.start, last).toInt() + 1).coerceAtLeast(0)
    var doses = 0
    var amount = 0.0
    for (d in 0 until days) {
        for (dose in plan.dosesOn(plan.start.plusDays(d.toLong()))) {
            doses++
            amount += dose.amount
        }
    }
    return CourseTotal(doses, amount, days, monthly = last == null)
}

/**
 * Промежуток между приёмами в часах, если разбор поставил их поровну сам (ни еды, ни «утром/вечером» в тексте нет);
 * иначе 0 — на карточке этот промежуток показывается пунктиром как додуманный.
 */
internal fun spreadHours(p: Parsed, times: List<LocalTime>): Int {
    if (p.food != FoodRule.None || p.hint != null || times.size < 2) return 0
    val sorted = times.sorted()
    return Math.round(Duration.between(sorted[0], sorted[1]).toMinutes() / 60.0).toInt()
}

/** Дописать прочитанный или распознанный текст в поле: с новой строки; пустое поле — просто текст. */
internal fun appendText(current: String, add: String): String {
    val extra = add.trim()
    if (extra.isEmpty()) return current
    return if (current.isBlank()) extra else current.trimEnd() + "\n" + extra
}

/**
 * Текст файла: UTF-8 (в том числе с меткой BOM) и UTF-16 с меткой; если не UTF-8 — windows-1251 (русские .txt из
 * «Блокнота» старых версий).
 */
internal fun decodeText(bytes: ByteArray): String {
    fun starts(vararg head: Int) = bytes.size >= head.size && head.indices.all { bytes[it] == head[it].toByte() }
    return when {
        starts(0xEF, 0xBB, 0xBF) -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        starts(0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        starts(0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        else -> strictUtf8(bytes) ?: String(bytes, Charset.forName("windows-1251"))
    }
}

private fun strictUtf8(bytes: ByteArray): String? = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (e: CharacterCodingException) {
    null
}
