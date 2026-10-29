package io.github.agitangst.ampulse.data

import androidx.room.withTransaction
import io.github.agitangst.ampulse.domain.Reading
import io.github.agitangst.ampulse.domain.ReadingSource
import io.github.agitangst.ampulse.domain.Profile
import io.github.agitangst.ampulse.domain.Site
import io.github.agitangst.ampulse.domain.DoseEvent
import io.github.agitangst.ampulse.domain.EventStatus
import io.github.agitangst.ampulse.domain.MarkCheck
import io.github.agitangst.ampulse.domain.Pack
import io.github.agitangst.ampulse.domain.consume
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
            createdAt = System.currentTimeMillis(), profileId = medicine.profileId,
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
    suspend fun mark(planned: PlannedDose, status: EventStatus, at: LocalDateTime, note: String = "", site: Site? = null): MarkCheck {
        val existing = db.events().forPlanned(planned.medicineId, Codec.millis(planned.at)).map(Codec::event)
        val check = checkMark(planned, existing)
        if (check is MarkCheck.AlreadyTaken && status == EventStatus.Taken) return check
        db.withTransaction {
            // Повторная отметка заменяет прежнюю «пропущено» и наоборот; списанное прежней — возвращается.
            existing.filter { it.status != EventStatus.Extra }.forEach { restore(it.id); db.events().delete(it.id) }
            val eventId = db.events().insert(
                DoseEventEntity(
                    medicineId = planned.medicineId, scheduleId = planned.scheduleId, plannedAt = Codec.millis(planned.at),
                    at = Codec.millis(at), status = status.name, amount = planned.amount, note = note, site = site?.name,
                ),
            )
            if (status == EventStatus.Taken) writeOff(eventId, planned.medicineId, planned.amount)
        }
        onChange.onPlanChanged()
        return MarkCheck.Ok
    }

    /** Лишний приём или приём «по необходимости» — для истории и отчёта врачу. */
    suspend fun logExtra(
        medicineId: Long, amount: Double, at: LocalDateTime, status: EventStatus = EventStatus.Extra, note: String = "", site: Site? = null,
    ): Long {
        val id = db.withTransaction {
            val eventId = db.events().insert(
                DoseEventEntity(medicineId = medicineId, scheduleId = null, plannedAt = null, at = Codec.millis(at), status = status.name, amount = amount, note = note, site = site?.name),
            )
            if (status != EventStatus.Skipped) writeOff(eventId, medicineId, amount)
            eventId
        }
        onChange.onPlanChanged()
        return id
    }

    /** Отменить отметку. */
    suspend fun undo(eventId: Long) {
        db.withTransaction {
            restore(eventId)
            db.events().delete(eventId)
        }
        onChange.onPlanChanged()
    }

    // --- Профили и уколы (этап 0.6) ---

    /** Профили кроме «Я» (профиль 0 — без строки, см. `ProfileEntity`). */
    val profiles: Flow<List<Profile>> = db.profiles().all().map { l -> l.map(CareCodec::profile) }

    suspend fun saveProfile(p: Profile): Long = db.profiles().upsert(CareCodec.entity(p, System.currentTimeMillis())).let { if (p.id == 0L) it else p.id }

    /** Удалить профиль вместе с его лекарствами и историей. «Я» (0) не удаляется. */
    suspend fun deleteProfile(id: Long) {
        if (id == 0L) return
        db.withTransaction {
            db.profiles().deleteMedicines(id)
            db.profiles().delete(id)
        }
        onChange.onPlanChanged()
    }

    /** Уколы профиля с местом и временем — для ротации мест (`rotation` в `domain/Care.kt`). */
    fun injections(profileId: Long): Flow<List<Pair<Site, LocalDateTime>>> =
        db.profiles().injections(profileId).map { l -> l.map(Codec::event).mapNotNull { e -> e.site?.let { it to e.at } } }

    // --- Давление (этап 0.7) ---

    /** Замеры профиля за дни [from, to] — новые сверху. */
    fun readings(profileId: Long, from: LocalDate, to: LocalDate): Flow<List<Reading>> =
        db.pressure().between(profileId, Codec.millis(from.atStartOfDay()), Codec.millis(to.plusDays(1).atStartOfDay()))
            .map { l -> l.map(PressureCodec::reading) }

    /** Последний замер профиля — «Прошлый замер: вчера, 20:30 — 131/84». */
    fun lastReading(profileId: Long): Flow<Reading?> = db.pressure().last(profileId).map { it?.let(PressureCodec::reading) }

    suspend fun addReading(profileId: Long, r: Reading, source: ReadingSource): Long =
        db.pressure().insert(PressureEntity(0, profileId, Codec.millis(r.at), r.sys, r.dia, r.pulse, source.name, r.note))

    suspend fun deleteReading(id: Long) = db.pressure().delete(id)

    // --- Аптечка (этап 0.4) ---

    /** Упаковки в аптечке (без убранных). */
    val packs: Flow<List<Pack>> = db.packs().active().map { l -> l.map(StockCodec::pack) }

    /**
     * Новая упаковка. Упаковка с тем же кодом (GTIN и серийный номер) уже есть — не задваивается: возвращается её id
     * (макет, экран 14: «Повторный скан той же упаковки не задвоит запас»).
     */
    suspend fun addPack(pack: Pack): Long {
        if (pack.serial.isNotBlank()) db.packs().bySerial(pack.gtin, pack.serial)?.let { return it.id }
        return db.packs().insert(StockCodec.entity(pack)).also { onChange.onPlanChanged() }
    }

    suspend fun updatePack(pack: Pack) {
        val old = db.packs().get(pack.id) ?: return
        db.packs().update(StockCodec.entity(pack, old.createdAt).copy(disposed = old.disposed))
        onChange.onPlanChanged()
    }

    /** Убрать упаковку из аптечки (закончилась, просрочена, выброшена); история списаний остаётся. */
    suspend fun disposePack(packId: Long) {
        val old = db.packs().get(packId) ?: return
        db.packs().update(old.copy(disposed = true))
        onChange.onPlanChanged()
    }

    /** Вернуть убранную упаковку («Отменить»). */
    suspend fun restorePack(packId: Long) {
        val old = db.packs().get(packId) ?: return
        db.packs().update(old.copy(disposed = false))
        onChange.onPlanChanged()
    }

    /** Списать дозу из упаковок по порядку FEFO и запомнить, откуда. */
    private suspend fun writeOff(eventId: Long, medicineId: Long, amount: Double) {
        val packs = db.packs().forMedicine(medicineId).map(StockCodec::pack)
        for ((packId, take) in consume(packs, amount)) {
            db.packs().add(packId, -take)
            db.packs().insertUse(PackUseEntity(eventId = eventId, packId = packId, amount = take))
        }
    }

    /** Вернуть списанное отметкой. */
    private suspend fun restore(eventId: Long) {
        db.packs().usesOf(eventId).forEach { db.packs().add(it.packId, it.amount) }
    }
}
