package io.github.agitangst.ampulse.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Лекарство. Перечисления хранятся именами (`MedForm.name`), даты — ISO-строками, моменты — миллисекундами. */
@Entity(tableName = "medicine")
data class MedicineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val strength: String,
    val form: String,
    val unit: String,
    val important: Boolean,
    val food: String,
    val note: String,
    /** Убрано из аптечки, но история сохраняется. */
    val archived: Boolean = false,
    val createdAt: Long,
    /** Профиль (этап 0.6): 0 — «Я». */
    @ColumnInfo(defaultValue = "0") val profileId: Long = 0,
)

/** Схема приёма; сложные части — строками (`Codec`): времена «08:00=1;20:00=0.5», ступени «4:3;3:3». */
@Entity(
    tableName = "schedule",
    foreignKeys = [ForeignKey(MedicineEntity::class, ["id"], ["medicineId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("medicineId")],
)
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val medicineId: Long,
    val kind: String,
    val times: String,
    val startDate: String,
    val endDate: String?,
    /** Дни недели битами: понедельник — 1, воскресенье — 64. */
    val weekdays: Int,
    val everyN: Int,
    val cycleOn: Int,
    val cycleOff: Int,
    val steps: String,
    val prnIntervalHours: Int,
    val prnMaxPerDay: Int,
    val paused: Boolean,
)

/** Отметка о приёме: принято, пропущено (с причиной в `note`) или лишний приём. */
@Entity(
    tableName = "dose_event",
    foreignKeys = [ForeignKey(MedicineEntity::class, ["id"], ["medicineId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("medicineId"), Index("at"), Index("plannedAt")],
)
data class DoseEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val medicineId: Long,
    val scheduleId: Long?,
    val plannedAt: Long?,
    val at: Long,
    val status: String,
    val amount: Double,
    val note: String,
    /** Место укола (`Site.name`, этап 0.6). */
    val site: String? = null,
)
