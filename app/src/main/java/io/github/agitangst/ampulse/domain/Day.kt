package io.github.agitangst.ampulse.domain

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/** Лекарство так, как его видит логика дня. */
data class Medicine(
    val id: Long,
    val name: String,
    /** Дозировка словами, как на упаковке: «850 мг», «100 ЕД/мл». */
    val strength: String,
    val form: MedForm,
    /** Единица дозы: «табл.», «капс.», «ЕД», «мл». */
    val unit: String,
    /** Важное: напоминание на весь экран и повтор (макет, экран 2). */
    val important: Boolean = false,
    val food: FoodRule = FoodRule.None,
    val note: String = "",
    /** Чьё лекарство: 0 — «Я», иначе профиль (близкий, питомец). */
    val profileId: Long = 0,
)

/** Что сделано с приёмом. `Extra` — лишний приём, записанный для врача (макет, экран 15). */
enum class EventStatus { Taken, Skipped, Extra }

/** Отметка о приёме. `planned` — время по расписанию; у «по необходимости» и лишнего — `null`. */
data class DoseEvent(
    val id: Long,
    val medicineId: Long,
    val scheduleId: Long?,
    val planned: LocalDateTime?,
    val at: LocalDateTime,
    val status: EventStatus,
    val amount: Double,
    val note: String = "",
    /** Место укола (этап 0.6); у таблеток — `null`. */
    val site: Site? = null,
)

/** Состояние приёма на ленте дня (docs/ui-guideline.md, «Состояния строки приёма»). */
enum class DoseState { Taken, Skipped, Due, Overdue, Later }

/** Приём по расписанию на конкретный момент. */
data class PlannedDose(val medicineId: Long, val scheduleId: Long, val at: LocalDateTime, val amount: Double)

/** Строка ленты дня. */
data class DayItem(val medicine: Medicine, val planned: PlannedDose, val state: DoseState, val event: DoseEvent?)

/** Окно «пора»: за 30 минут до времени и час после — потом «просрочено». */
val DUE_BEFORE: Duration = Duration.ofMinutes(30)
val DUE_AFTER: Duration = Duration.ofMinutes(60)

fun stateOf(planned: PlannedDose, event: DoseEvent?, now: LocalDateTime): DoseState = when {
    event?.status == EventStatus.Taken -> DoseState.Taken
    event?.status == EventStatus.Skipped -> DoseState.Skipped
    now < planned.at.minus(DUE_BEFORE) -> DoseState.Later
    now <= planned.at.plus(DUE_AFTER) -> DoseState.Due
    else -> DoseState.Overdue
}

/** Запланированные приёмы всех схем на день — по времени. */
fun plannedOn(date: LocalDate, schedules: List<Pair<Long, Pair<Long, Plan>>>): List<PlannedDose> =
    schedules.flatMap { (scheduleId, medAndPlan) ->
        val (medicineId, plan) = medAndPlan
        plan.dosesOn(date).map { PlannedDose(medicineId, scheduleId, date.atTime(it.time), it.amount) }
    }.sortedWith(compareBy({ it.at }, { it.medicineId }))

/** Лента дня: приёмы по расписанию с отметками и состоянием. */
fun dayItems(
    date: LocalDate,
    medicines: List<Medicine>,
    schedules: List<Schedule>,
    events: List<DoseEvent>,
    now: LocalDateTime,
): List<DayItem> {
    val byId = medicines.associateBy { it.id }
    val planned = plannedOn(date, schedules.filter { it.medicineId in byId }.map { it.id to (it.medicineId to it.plan) })
    return planned.map { p ->
        val event = events.firstOrNull { it.medicineId == p.medicineId && it.planned == p.at && it.status != EventStatus.Extra }
        DayItem(byId.getValue(p.medicineId), p, stateOf(p, event, now), event)
    }
}

/** Схема лекарства. */
data class Schedule(val id: Long, val medicineId: Long, val plan: Plan)

/** Итог дня для кольца: сколько принято из скольких. */
fun progress(items: List<DayItem>): Pair<Int, Int> = items.count { it.state == DoseState.Taken } to items.size

/** Защита от двойной дозы: что будет, если отметить приём ещё раз. */
sealed interface MarkCheck {
    data object Ok : MarkCheck
    data class AlreadyTaken(val at: LocalDateTime) : MarkCheck
}

fun checkMark(planned: PlannedDose, events: List<DoseEvent>): MarkCheck {
    val taken = events.firstOrNull { it.medicineId == planned.medicineId && it.planned == planned.at && it.status == EventStatus.Taken }
    return if (taken != null) MarkCheck.AlreadyTaken(taken.at) else MarkCheck.Ok
}

/** «По необходимости» (макет, экран 11): можно ли принять сейчас. */
sealed interface PrnCheck {
    data object Allowed : PrnCheck
    /** Раньше интервала — только через подтверждение. */
    data class TooSoon(val nextAllowed: LocalDateTime, val takenToday: Int) : PrnCheck
    /** Лимит за сутки достигнут — нельзя. */
    data class LimitReached(val takenToday: Int, val nextAllowed: LocalDateTime) : PrnCheck
}

/** Проверка «по необходимости» за последние 24 часа. */
fun checkPrn(plan: Plan, medicineId: Long, events: List<DoseEvent>, now: LocalDateTime): PrnCheck {
    val dayAgo = now.minusHours(24)
    val recent = events.filter {
        it.medicineId == medicineId && it.at > dayAgo && it.at <= now &&
            (it.status == EventStatus.Taken || it.status == EventStatus.Extra)
    }.sortedBy { it.at }
    if (plan.prnMaxPerDay > 0 && recent.size >= plan.prnMaxPerDay) {
        val byLimit = recent[recent.size - plan.prnMaxPerDay].at.plusHours(24)
        val byInterval = recent.last().at.plusHours(plan.prnIntervalHours.toLong())
        return PrnCheck.LimitReached(recent.size, maxOf(byLimit, byInterval))
    }
    val last = recent.lastOrNull() ?: return PrnCheck.Allowed
    val next = last.at.plusHours(plan.prnIntervalHours.toLong())
    return if (plan.prnIntervalHours > 0 && now < next) PrnCheck.TooSoon(next, recent.size) else PrnCheck.Allowed
}
