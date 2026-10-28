package io.github.agitangst.ampulse.reminders

import io.github.agitangst.ampulse.domain.DoseEvent
import io.github.agitangst.ampulse.domain.EventStatus
import io.github.agitangst.ampulse.domain.Medicine
import io.github.agitangst.ampulse.domain.PlannedDose
import io.github.agitangst.ampulse.domain.Schedule
import io.github.agitangst.ampulse.domain.plannedOn
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Номера «ячеек» одного приёма: у каждого будильника и каждого `PendingIntent` свой код, стабильный по лекарству и
 * времени, — тогда будильник, поставленный дважды, заменяет сам себя, а лишний находится и отменяется.
 */
object ReminderCodes {
    /** 0 — в срок, 1..[ReminderPlan.MAX_REPEATS] — повторы важного. */
    const val SLOT_ON_TIME = 0

    /** «Отложить на 10 мин». */
    const val SLOT_SNOOZE = 9

    /** Кнопка «Принять» в уведомлении. */
    const val SLOT_TAKE = 10

    /** Кнопка «Отложить» в уведомлении. */
    const val SLOT_SNOOZE_ACTION = 11

    /** Касание уведомления — открыть программу. */
    const val SLOT_OPEN = 12

    /** Экран «Напоминание» на весь экран. */
    const val SLOT_SCREEN = 13
}

/**
 * Приём по расписанию, о котором напоминаем: лекарство, схема, время по расписанию и доза. Ключ — лекарство и время
 * ([planned]), как у отметок.
 */
data class DoseRef(val medicineId: Long, val scheduleId: Long, val planned: LocalDateTime, val amount: Double) {
    /** Код ячейки [slot] этого приёма — стабильный (зависит только от лекарства, времени и ячейки). */
    fun code(slot: Int): Int {
        val minutes = planned.toEpochSecond(ZoneOffset.UTC) / 60
        var h = mix(medicineId)
        h = mix(h xor minutes)
        h = mix(h xor slot.toLong())
        return (h xor (h ushr 32)).toInt()
    }

    /** Номер уведомления: один на приём, повтор заменяет прежнее уведомление, а не копится рядом. */
    val notificationId: Int get() = code(ReminderCodes.SLOT_ON_TIME)

    fun toPlannedDose() = PlannedDose(medicineId, scheduleId, planned, amount)

    private companion object {
        /** Перемешивание битов (финализатор splitmix64): близкие входы не дают близких кодов. */
        fun mix(value: Long): Long {
            var z = value
            z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
            z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
            return z xor (z ushr 31)
        }
    }
}

/** Одно напоминание: приём, какое по счёту (0 — в срок, 1..3 — повтор важного) и когда сработать. */
data class Reminder(val dose: DoseRef, val important: Boolean, val repeat: Int, val fireAt: LocalDateTime) {
    /** Код будильника. */
    val requestCode: Int get() = dose.code(repeat)
}

/**
 * Планировщик напоминаний — чистая логика без Android, проверяется тестами на JVM. Берёт схемы, лекарства и отметки
 * и отвечает: что и когда должно сработать в ближайшие [HORIZON].
 */
object ReminderPlan {
    /** На сколько вперёд ставим будильники. Дальше — обновляет [REFRESH_AFTER] и каждый запуск программы. */
    val HORIZON: Duration = Duration.ofHours(48)

    /** Через сколько после времени приёма повторяем важное, пока нет отметки. */
    val REPEAT_AFTER: Duration = Duration.ofMinutes(10)

    /** Сколько повторов у важного лекарства после основного напоминания. */
    const val MAX_REPEATS = 3

    /** «Отложить» — на столько минут. */
    const val SNOOZE_MINUTES = 10

    /** Раз в столько часов будильник «пересчитать»: окно [HORIZON] двигается, даже если программу не открывали. */
    const val REFRESH_HOURS = 24L

    /** Потолок числа будильников программы — у системы свой предел (около 500). */
    const val MAX_ALARMS = 200

    /**
     * Ближайшие напоминания за [HORIZON] от [now] по времени возрастания. Приёмы с отметкой («принято» или
     * «пропущено») пропускаются; у важного лекарства кроме основного — до [MAX_REPEATS] повторов с шагом
     * [REPEAT_AFTER], пока отметки нет. Прошедшее (раньше или в [now]) не включается: оно уже сработало.
     */
    fun upcoming(
        schedules: List<Schedule>,
        medicines: List<Medicine>,
        events: List<DoseEvent>,
        now: LocalDateTime,
    ): List<Reminder> {
        val byId = medicines.associateBy { it.id }
        val usable = schedules.filter { it.medicineId in byId }.map { it.id to (it.medicineId to it.plan) }
        val end = now.plus(HORIZON)
        val result = ArrayList<Reminder>()
        // Со вчерашнего дня: повторы приёма в 23:55 приходятся уже на сегодня.
        var date = now.toLocalDate().minusDays(1)
        val last = end.toLocalDate()
        while (!date.isAfter(last)) {
            for (planned in plannedOn(date, usable)) {
                if (isHandled(planned.medicineId, planned.at, events)) continue
                val dose = DoseRef(planned.medicineId, planned.scheduleId, planned.at, planned.amount)
                val important = byId.getValue(planned.medicineId).important
                val repeats = if (important) MAX_REPEATS else 0
                for (k in 0..repeats) {
                    val fireAt = planned.at.plus(REPEAT_AFTER.multipliedBy(k.toLong()))
                    if (fireAt > now && fireAt <= end) result += Reminder(dose, important, k, fireAt)
                }
            }
            date = date.plusDays(1)
        }
        return result
            .sortedWith(compareBy({ it.fireAt }, { it.dose.medicineId }, { it.repeat }))
            .distinctBy { it.requestCode }
            .take(MAX_ALARMS)
    }

    /** Приём уже отмечен («принято» или «пропущено»): напоминать о нём не нужно. Лишний приём — не отметка. */
    fun isHandled(medicineId: Long, planned: LocalDateTime, events: List<DoseEvent>): Boolean =
        events.any {
            it.medicineId == medicineId && it.planned == planned &&
                (it.status == EventStatus.Taken || it.status == EventStatus.Skipped)
        }
}
