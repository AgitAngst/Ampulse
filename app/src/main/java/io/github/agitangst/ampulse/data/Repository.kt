package io.github.agitangst.ampulse.data

import androidx.room.withTransaction
import io.github.agitangst.ampulse.domain.DoseEvent
import io.github.agitangst.ampulse.domain.EventStatus
import io.github.agitangst.ampulse.domain.MarkCheck
import io.github.agitangst.ampulse.domain.Medicine
import io.github.agitangst.ampulse.domain.Plan
import io.github.agitangst.ampulse.domain.PlannedDose
import io.github.agitangst.ampulse.domain.Schedule
import io.github.agitangst.ampulse.domain.checkMark
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalDateTime

/** Что-то в расписании или отметках поменялось — напоминания пересчитываются (`reminders/`). */
fun interface ChangeListener {
    fun onPlanChanged()
}

/**
 * Данные Ampulse: лекарства, схемы, отметки. Экраны читают потоки (`Flow`), пишут через suspend-функции; после
 * каждой записи, влияющей на напоминания, зовётся [ChangeListener].
 */
class Repository(private val db: AmpulseDb, private val onChange: ChangeListener) {
    val medicines: Flow<List<Medicine>> = db.medicines().active().map { list -> list.map(Codec::medicine) }

    val schedules: Flow<List<Schedule>> = db.schedules().all().map { list -> list.map(Codec::schedule) }

    /** Отметки за дни `[from, to)` по местному времени. */
    fun events(from: LocalDate, to: LocalDate): Flow<List<DoseEvent>> =
        db.events().between(Codec.millis(from.atStartOfDay()), Codec.millis(to.atStartOfDay())).map { l -> l.map(Codec::event) }

    suspend fun medicine(id: Long): Medicine? = db.medicines().get(id)?.let(Codec::medicine)

    suspend fun schedulesOf(medicineId: Long): List<Schedule> = db.schedules().forMedicine(medicineId).map(Codec::schedule)

    suspend fun schedulesNow(): List<Schedule> = db.schedules().allNow().map(Codec::schedule)

    suspend fun eventsNow(from: LocalDateTime, to: LocalDateTime): List<DoseEvent> =
        db.events().betweenNow(Codec.millis(from), Codec.millis(to)).map(Codec::event)

    /** Новое или изменённое лекарство с его схемой (одна схема на лекарство). Возвращает id лекарства. */
    suspend fun save(medicine: Medicine, plan: Plan): Long = db.withTransaction {
        val entity = MedicineEntity(
            id = medicine.id, name = medicine.name.trim(), strength = medicine.strength.trim(), form = medicine.form.name,
            unit = medicine.unit, important = medicine.important, food = medicine.food.name, note = medicine.note,
            createdAt = System.currentTimeMillis(),
        )
        val id = if (medicine.id == 0L) db.medicines().insert(entity) else entity.id.also { db.medicines().update(entity) }
        val existing = db.schedules().forMedicine(id).firstOrNull()
        db.schedules().upsert(Codec.entity(id, plan, existing?.id ?: 0))
        id
    }.also { onChange.onPlanChanged() }

    /** Удалить лекарство вместе со схемой и отметками. */
    suspend fun delete(medicineId: Long) {
        db.medicines().delete(medicineId)
        onChange.onPlanChanged()
    }

    /**
     * Отметить приём по расписанию. Уже принятый повторно не записывается — [MarkCheck.AlreadyTaken] (защита от
     * двойной дозы, макет экран 15); лишний приём пишется отдельно — [logExtra].
     */
    suspend fun mark(planned: PlannedDose, status: EventStatus, at: LocalDateTime, note: String = ""): MarkCheck {
        val existing = db.events().forPlanned(planned.medicineId, Codec.millis(planned.at)).map(Codec::event)
        val check = checkMark(planned, existing)
        if (check is MarkCheck.AlreadyTaken && status == EventStatus.Taken) return check
        // Повторная отметка заменяет прежнюю «пропущено» и наоборот.
        existing.filter { it.status != EventStatus.Extra }.forEach { db.events().delete(it.id) }
        db.events().insert(
            DoseEventEntity(
                medicineId = planned.medicineId, scheduleId = planned.scheduleId, plannedAt = Codec.millis(planned.at),
                at = Codec.millis(at), status = status.name, amount = planned.amount, note = note,
            ),
        )
        onChange.onPlanChanged()
        return MarkCheck.Ok
    }

    /** Лишний приём или приём «по необходимости» — для истории и отчёта врачу. */
    suspend fun logExtra(medicineId: Long, amount: Double, at: LocalDateTime, status: EventStatus = EventStatus.Extra, note: String = "") {
        db.events().insert(
            DoseEventEntity(medicineId = medicineId, scheduleId = null, plannedAt = null, at = Codec.millis(at), status = status.name, amount = amount, note = note),
        )
        onChange.onPlanChanged()
    }

    /** Отменить отметку. */
    suspend fun undo(eventId: Long) {
        db.events().delete(eventId)
        onChange.onPlanChanged()
    }
}
