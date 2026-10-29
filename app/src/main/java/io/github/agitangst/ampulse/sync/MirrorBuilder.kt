package io.github.agitangst.ampulse.sync

import io.github.agitangst.ampulse.domain.DoseEvent
import io.github.agitangst.ampulse.domain.EventStatus
import io.github.agitangst.ampulse.domain.MedForm
import io.github.agitangst.ampulse.domain.Medicine
import io.github.agitangst.ampulse.domain.Profile
import io.github.agitangst.ampulse.domain.ProfileKind
import io.github.agitangst.ampulse.domain.Schedule
import io.github.agitangst.ampulse.domain.plannedOn
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Лекарство с признаком архива (в домене приложения он не нужен, серверу — нужен). */
data class SyncMedicine(val medicine: Medicine, val archived: Boolean)

/**
 * Всё, из чего строится выгрузка. Профиль «Я» (номер 0) в [profiles] не входит — его строки в базе нет; в [events] —
 * отметки окна (по времени приёма или по плановому времени).
 */
data class SyncSnapshot(
    val profiles: List<Profile>,
    val medicines: List<SyncMedicine>,
    val schedules: List<Schedule>,
    val events: List<DoseEvent>,
)

/**
 * Строит из базы телефона то, что лежит на сервере (openspec: server-sync). На сервер уходит результат, а не правило:
 * схемы раскладываются в конкретные приёмы окна, правил (интервалов, циклов, ступеней) там нет.
 *
 * Что берётся: профили (кроме копий чужого расписания), лекарства, приёмы окна — плановые (по схемам и с отметками),
 * лишние и «по необходимости». Чего нет: аптечки и упаковок, давления, близких, экстренной карты, места укола, количества.
 */
class MirrorBuilder(
    private val salt: ByteArray,
    private val zone: ZoneId,
    /** Имя профиля «Я» на языке приложения. */
    private val meName: String,
) {
    /** Окно приёмов для дня [today]: с начала дня за [PAST_DAYS] дней до него до конца дня через [FUTURE_DAYS] дней. */
    fun window(today: LocalDate): MirrorWindow = MirrorWindow(
        today.minusDays(PAST_DAYS).atStartOfDay(zone).toEpochSecond(),
        today.plusDays(FUTURE_DAYS + 1).atStartOfDay(zone).toEpochSecond(),
    )

    private fun second(t: LocalDateTime): Long = t.atZone(zone).toEpochSecond()

    fun build(snapshot: SyncSnapshot, today: LocalDate): MirrorSet {
        val window = window(today)
        val timezone = zone.id.takeIf { '/' in it } ?: "UTC"

        // Профили: «Я» и свои; копии чужого расписания не выкладываются (это чужие данные).
        val profiles = LinkedHashMap<String, MirrorProfile>()
        val serverProfile = HashMap<Long, String>()
        fun addProfile(localId: Long, name: String, kind: ProfileKind) {
            val id = SyncIds.profile(salt, localId)
            serverProfile[localId] = id
            profiles[id] = MirrorProfile(id, cut(name, MAX_NAME), if (kind == ProfileKind.Person) "person" else "animal", "", timezone)
        }
        addProfile(0, meName, ProfileKind.Person)
        snapshot.profiles.filter { !it.watchOnly && it.id != 0L }.forEach { addProfile(it.id, it.name, it.kind) }

        // Лекарства.
        val meds = LinkedHashMap<String, MirrorMed>()
        val serverMed = HashMap<Long, String>()
        val archivedMed = HashSet<Long>()
        for ((m, archived) in snapshot.medicines.map { it.medicine to it.archived }) {
            val profile = serverProfile[m.profileId] ?: continue
            val id = SyncIds.medicine(salt, m.id)
            serverMed[m.id] = id
            if (archived) archivedMed += m.id
            meds[id] = MirrorMed(id, profile, cut(m.name, MAX_NAME), if (m.form == MedForm.Injection) "injection" else "pill", cut(m.strength, MAX_TEXT), cut(m.note, MAX_TEXT), archived)
        }

        // Плановые приёмы окна по схемам (архивные лекарства новых приёмов не получают, но их отметки остаются).
        val usable = snapshot.schedules.filter { it.medicineId in serverMed && it.medicineId !in archivedMed }
        val planned = ArrayList<MirrorIntake>()
        val marks = snapshot.events.filter { it.planned != null && it.status != EventStatus.Extra }
            .groupBy { it.medicineId to it.planned }
        val used = HashSet<Long>()
        var date = today.minusDays(PAST_DAYS)
        val last = today.plusDays(FUTURE_DAYS)
        while (date <= last) {
            for (dose in plannedOn(date, usable.map { it.id to (it.medicineId to it.plan) })) {
                val mark = marks[dose.medicineId to dose.at]?.maxByOrNull { it.at }
                mark?.let { used += it.id }
                val plannedAt = second(dose.at)
                planned += intake(SyncIds.planned(salt, dose.scheduleId, plannedAt), serverMed.getValue(dose.medicineId), plannedAt, mark, true)
            }
            date = date.plusDays(1)
        }

        // Остальные отметки окна: плановые, чья схема изменилась или на паузе (отметка остаётся), и внеплановые.
        val rest = ArrayList<MirrorIntake>()
        for (e in snapshot.events) {
            if (e.id in used) continue
            val med = serverMed[e.medicineId] ?: continue
            val plannedTime = e.planned
            if (plannedTime != null && e.status != EventStatus.Extra) {
                val plannedAt = second(plannedTime)
                if (plannedAt !in window) continue
                rest += intake(SyncIds.planned(salt, e.scheduleId ?: 0L, plannedAt), med, plannedAt, e, true)
            } else {
                val at = second(e.at)
                if (at !in window) continue
                rest += intake(SyncIds.event(salt, e.id), med, at, e, false)
            }
        }

        val intakes = LinkedHashMap<String, MirrorIntake>()
        (planned + rest).sortedWith(compareBy({ it.plannedAt }, { it.id })).forEach { intakes[it.id] = it }
        return MirrorSet(profiles, meds, intakes)
    }

    /** Приём из отметки (или без неё — тогда ожидается). Внеплановый «пропущено» остаётся пропущенным, прочее — принято. */
    private fun intake(id: String, medId: String, plannedAt: Long, mark: DoseEvent?, planned: Boolean): MirrorIntake {
        val status = when {
            mark == null -> "pending"
            mark.status == EventStatus.Skipped -> "skipped"
            else -> "taken"
        }
        val takenAt = if (status == "taken") second(mark!!.at) else null
        return MirrorIntake(id, medId, plannedAt, status, takenAt, cut(mark?.note.orEmpty(), MAX_TEXT), planned)
    }

    companion object {
        /** Сколько дней назад от сегодня приёмы выкладываются. */
        const val PAST_DAYS = 30L

        /** Сколько дней вперёд от сегодня (включительно). */
        const val FUTURE_DAYS = 14L
    }
}
