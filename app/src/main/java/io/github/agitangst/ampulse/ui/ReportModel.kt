package io.github.agitangst.ampulse.ui

import io.github.agitangst.ampulse.domain.DoseEvent
import io.github.agitangst.ampulse.domain.EventStatus
import io.github.agitangst.ampulse.domain.MedStats
import io.github.agitangst.ampulse.domain.Medicine
import io.github.agitangst.ampulse.domain.Plan
import io.github.agitangst.ampulse.domain.PlanKind
import io.github.agitangst.ampulse.domain.Reading
import io.github.agitangst.ampulse.domain.Schedule
import io.github.agitangst.ampulse.domain.Target
import io.github.agitangst.ampulse.domain.adherence
import io.github.agitangst.ampulse.domain.average
import io.github.agitangst.ampulse.domain.isEvening
import io.github.agitangst.ampulse.domain.isMorning
import java.time.LocalDate
import java.time.LocalDateTime

// Отчёт врачу (этап 0.8, задача 3.1): чистая логика без Android — что войдёт в сводку на экране и в PDF.
// Соблюдение считает `domain/Report.kt` (`adherence`), давление — `domain/Pressure.kt`; здесь они собираются в одну модель.

/** Периоды отчёта в днях — сегменты экрана. */
internal val REPORT_PERIODS = listOf(14, 30, 90)

/** Период отчёта: [days] дней, последний — сегодня. */
internal fun reportRange(today: LocalDate, days: Int): Pair<LocalDate, LocalDate> = today.minusDays(days - 1L) to today

/** Пропущенный приём с причиной; причина может быть пустой. */
internal data class ReportSkip(val at: LocalDateTime, val medicine: Medicine, val reason: String)

/** Лишний приём (вне схемы), записанный для врача. */
internal data class ReportExtra(val at: LocalDateTime, val medicine: Medicine, val amount: Double)

/** Лекарство «по необходимости» и сколько раз его принимали за период. */
internal data class ReportPrn(val medicine: Medicine, val count: Int)

/** Соблюдение по лекарству и его схема (для строки «схема словами»). */
internal data class ReportMed(val stats: MedStats, val plan: Plan?)

/** Среднее давление группы замеров и сколько замеров усреднено. */
internal data class BpAverage(val sys: Int, val dia: Int, val pulse: Int?, val count: Int)

/** Давление за день: среднее утром (до 12:00), днём (12:00–17:00) и вечером (с 17:00). */
internal data class BpDay(val date: LocalDate, val morning: BpAverage?, val daytime: BpAverage?, val evening: BpAverage?)

/** Давление за период; [total] — число замеров, [days] — по возрастанию даты. */
internal data class ReportPressure(
    val target: Target,
    val morning: BpAverage?,
    val evening: BpAverage?,
    val total: Int,
    val days: List<BpDay>,
)

/**
 * Всё, что видит врач. [doses] — раздел «Приёмы» включён; [pressure] — `null`, если раздел «Давление» выключен (включён,
 * но замеров нет — [ReportPressure.total] равно нулю).
 */
internal data class ReportModel(
    val profileName: String,
    val from: LocalDate,
    val to: LocalDate,
    val doses: Boolean,
    val meds: List<ReportMed>,
    val skips: List<ReportSkip>,
    val extras: List<ReportExtra>,
    val prn: List<ReportPrn>,
    val pressure: ReportPressure?,
) {
    /** Приёмов по схеме за период (прошедших). */
    val planned: Int get() = meds.sumOf { it.stats.planned }

    val taken: Int get() = meds.sumOf { it.stats.taken }

    /** Явно пропущено (с отметкой «пропущено»). */
    val skipped: Int get() = meds.sumOf { it.stats.skipped }

    /** Время прошло, отметки нет. */
    val missed: Int get() = meds.sumOf { it.stats.missed }

    val extra: Int get() = meds.sumOf { it.stats.extra }

    /** Общий процент принятого; `null` — приёмов по схеме не было. */
    val percent: Int? get() = if (planned == 0) null else Math.round(taken * 100.0 / planned).toInt()

    /** Есть что показать врачу: приёмы или замеры давления. */
    val hasContent: Boolean get() = (doses && (meds.isNotEmpty() || prn.isNotEmpty())) || (pressure?.total ?: 0) > 0
}

private fun inRange(day: LocalDate, from: LocalDate, to: LocalDate) = !day.isBefore(from) && !day.isAfter(to)

/**
 * Собирает отчёт. [medicines] — лекарства выбранного профиля; [events] — отметки с запасом по краям (приём мог быть
 * отмечен после полуночи); [readings] — замеры профиля или `null`, если давление не включено. Будущие приёмы пропусками
 * не считаются ([adherence]).
 */
internal fun buildReport(
    profileName: String,
    from: LocalDate,
    to: LocalDate,
    medicines: List<Medicine>,
    schedules: List<Schedule>,
    events: List<DoseEvent>,
    readings: List<Reading>?,
    target: Target,
    now: LocalDateTime,
    doses: Boolean,
): ReportModel {
    val byId = medicines.associateBy { it.id }
    val planOf = schedules.groupBy { it.medicineId }.mapValues { (_, list) -> list.first().plan }

    val stats = if (doses) adherence(from, to, medicines, schedules, events, now) else emptyList()
    val meds = stats.map { ReportMed(it, planOf[it.medicine.id]) }
    val skips = stats
        .flatMap { s -> s.skips.map { (at, note) -> ReportSkip(at, s.medicine, note.trim()) } }
        .sortedBy { it.at }
    val extras = if (!doses) {
        emptyList()
    } else {
        events
            .filter { it.status == EventStatus.Extra && it.medicineId in byId && inRange(it.at.toLocalDate(), from, to) }
            .sortedBy { it.at }
            .map { ReportExtra(it.at, byId.getValue(it.medicineId), it.amount) }
    }
    // «По необходимости» — приёмы без расписания: врачу важно, как часто ими пользовались.
    val prn = if (!doses) {
        emptyList()
    } else {
        medicines.filter { planOf[it.id]?.kind == PlanKind.AsNeeded }.mapNotNull { m ->
            val n = events.count {
                it.medicineId == m.id && it.status == EventStatus.Taken && it.planned == null && inRange(it.at.toLocalDate(), from, to)
            }
            if (n > 0) ReportPrn(m, n) else null
        }
    }

    return ReportModel(profileName, from, to, doses, meds, skips, extras, prn, readings?.let { pressureOf(it, from, to, target) })
}

private fun averageOf(list: List<Reading>): BpAverage? =
    average(list)?.let { (sys, dia, pulse) -> BpAverage(sys, dia, pulse, list.size) }

private fun pressureOf(readings: List<Reading>, from: LocalDate, to: LocalDate, target: Target): ReportPressure {
    val inPeriod = readings.filter { inRange(it.at.toLocalDate(), from, to) }
    val days = inPeriod.groupBy { it.at.toLocalDate() }.toSortedMap().map { (date, day) ->
        BpDay(
            date = date,
            morning = averageOf(day.filter(::isMorning)),
            daytime = averageOf(day.filter { !isMorning(it) && !isEvening(it) }),
            evening = averageOf(day.filter(::isEvening)),
        )
    }
    return ReportPressure(
        target = target,
        morning = averageOf(inPeriod.filter(::isMorning)),
        evening = averageOf(inPeriod.filter(::isEvening)),
        total = inPeriod.size,
        days = days,
    )
}
