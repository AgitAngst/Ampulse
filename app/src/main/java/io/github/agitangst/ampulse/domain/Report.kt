package io.github.agitangst.ampulse.domain

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Соблюдение по лекарству за период — для отчёта врачу (PDF) и «Истории». Считаются только приёмы, время которых прошло:
 * будущее не «пропущено». Пропуск с причиной — в [skips].
 */
data class MedStats(
    val medicine: Medicine,
    val planned: Int,
    val taken: Int,
    val skipped: Int,
    val missed: Int,
    val extra: Int,
    val skips: List<Pair<LocalDateTime, String>>,
) {
    /** Процент принятого; `null` — приёмов по схеме не было («по необходимости»). */
    val percent: Int? get() = if (planned == 0) null else Math.round(taken * 100.0 / planned).toInt()
}

fun adherence(
    from: LocalDate,
    to: LocalDate,
    medicines: List<Medicine>,
    schedules: List<Schedule>,
    events: List<DoseEvent>,
    now: LocalDateTime,
): List<MedStats> {
    val items = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }
        .flatMap { dayItems(it, medicines, schedules, events, now) }
        .filter { it.state != DoseState.Later && it.state != DoseState.Due }
        .toList()
    val extras = events.filter { it.status == EventStatus.Extra && !it.at.toLocalDate().isBefore(from) && !it.at.toLocalDate().isAfter(to) }
    return medicines.map { m ->
        val mine = items.filter { it.medicine.id == m.id }
        MedStats(
            medicine = m,
            planned = mine.size,
            taken = mine.count { it.state == DoseState.Taken },
            skipped = mine.count { it.state == DoseState.Skipped },
            missed = mine.count { it.state == DoseState.Overdue },
            extra = extras.count { it.medicineId == m.id },
            skips = mine.filter { it.state == DoseState.Skipped }.map { it.planned.at to (it.event?.note ?: "") },
        )
    }.filter { it.planned > 0 || it.extra > 0 }
}
