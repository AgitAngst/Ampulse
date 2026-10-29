package io.github.agitangst.ampulse.data

import io.github.agitangst.ampulse.domain.DoseEvent
import io.github.agitangst.ampulse.domain.DoseTime
import io.github.agitangst.ampulse.domain.EventStatus
import io.github.agitangst.ampulse.domain.FoodRule
import io.github.agitangst.ampulse.domain.MedForm
import io.github.agitangst.ampulse.domain.Medicine
import io.github.agitangst.ampulse.domain.Plan
import io.github.agitangst.ampulse.domain.PlanKind
import io.github.agitangst.ampulse.domain.Site
import io.github.agitangst.ampulse.domain.Schedule
import io.github.agitangst.ampulse.domain.TaperStep
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Перевод между строками базы и моделью. Чистые функции — проверяются тестами на JVM. */
object Codec {
    fun times(list: List<DoseTime>): String = list.joinToString(";") { "${it.time}=${num(it.amount)}" }

    fun times(text: String): List<DoseTime> = text.split(';').filter { it.isNotBlank() }.map {
        val (t, a) = it.split('=')
        DoseTime(LocalTime.parse(t), a.toDouble())
    }

    fun steps(list: List<TaperStep>): String = list.joinToString(";") { "${num(it.amount)}:${it.days}" }

    fun steps(text: String): List<TaperStep> = text.split(';').filter { it.isNotBlank() }.map {
        val (a, d) = it.split(':')
        TaperStep(a.toDouble(), d.toInt())
    }

    fun weekdays(days: Set<DayOfWeek>): Int = days.fold(0) { acc, d -> acc or (1 shl (d.value - 1)) }

    fun weekdays(bits: Int): Set<DayOfWeek> = DayOfWeek.entries.filter { bits and (1 shl (it.value - 1)) != 0 }.toSet()

    /** Число без лишнего «.0»: 1 → «1», 0.5 → «0.5». */
    fun num(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

    fun millis(t: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()): Long = t.atZone(zone).toInstant().toEpochMilli()

    fun time(ms: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)

    fun medicine(e: MedicineEntity) = Medicine(
        e.id, e.name, e.strength, enumOr(e.form, MedForm.Other), e.unit, e.important, enumOr(e.food, FoodRule.None), e.note, e.profileId,
    )

    fun plan(e: ScheduleEntity) = Plan(
        kind = enumOr(e.kind, PlanKind.Daily),
        times = times(e.times),
        start = LocalDate.parse(e.startDate),
        end = e.endDate?.let(LocalDate::parse),
        weekdays = weekdays(e.weekdays),
        everyN = e.everyN,
        cycleOn = e.cycleOn,
        cycleOff = e.cycleOff,
        steps = steps(e.steps),
        prnIntervalHours = e.prnIntervalHours,
        prnMaxPerDay = e.prnMaxPerDay,
        paused = e.paused,
    )

    fun schedule(e: ScheduleEntity) = Schedule(e.id, e.medicineId, plan(e))

    fun entity(medicineId: Long, plan: Plan, id: Long = 0) = ScheduleEntity(
        id = id,
        medicineId = medicineId,
        kind = plan.kind.name,
        times = times(plan.times),
        startDate = plan.start.toString(),
        endDate = plan.end?.toString(),
        weekdays = weekdays(plan.weekdays),
        everyN = plan.everyN,
        cycleOn = plan.cycleOn,
        cycleOff = plan.cycleOff,
        steps = steps(plan.steps),
        prnIntervalHours = plan.prnIntervalHours,
        prnMaxPerDay = plan.prnMaxPerDay,
        paused = plan.paused,
    )

    fun event(e: DoseEventEntity) = DoseEvent(
        e.id, e.medicineId, e.scheduleId, e.plannedAt?.let { time(it) }, time(e.at), enumOr(e.status, EventStatus.Taken), e.amount, e.note,
        e.site?.let { s -> Site.entries.firstOrNull { it.name == s } },
    )

    private inline fun <reified T : Enum<T>> enumOr(name: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback
}
