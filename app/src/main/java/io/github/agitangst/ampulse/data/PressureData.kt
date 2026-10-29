package io.github.agitangst.ampulse.data

import android.content.Context
import androidx.core.content.edit
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import io.github.agitangst.ampulse.domain.Reading
import io.github.agitangst.ampulse.domain.ReadingSource
import io.github.agitangst.ampulse.domain.Target
import kotlinx.coroutines.flow.Flow

/** Замер давления (этап 0.7). Профиль — как у лекарств: 0 — «Я». */
@Entity(tableName = "pressure", indices = [Index("profileId", "at")])
data class PressureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val at: Long,
    val sys: Int,
    val dia: Int,
    val pulse: Int?,
    /** `ReadingSource.name`. */
    val source: String,
    val note: String,
)

@Dao
interface PressureDao {
    @Query("SELECT * FROM pressure WHERE profileId = :profileId AND at >= :from AND at < :to ORDER BY at DESC")
    fun between(profileId: Long, from: Long, to: Long): Flow<List<PressureEntity>>

    @Query("SELECT * FROM pressure WHERE profileId = :profileId ORDER BY at DESC LIMIT 1")
    fun last(profileId: Long): Flow<PressureEntity?>

    @Insert
    suspend fun insert(e: PressureEntity): Long

    @Query("DELETE FROM pressure WHERE id = :id")
    suspend fun delete(id: Long)
}

object PressureCodec {
    fun reading(e: PressureEntity) = Reading(e.id, e.sys, e.dia, e.pulse, Codec.time(e.at), e.note)

    fun source(e: PressureEntity) = ReadingSource.entries.firstOrNull { it.name == e.source } ?: ReadingSource.Manual
}

/** Цель врача по давлению — у каждого профиля своя; по умолчанию ниже 135/85. `SharedPreferences("ampulse")`. */
object PressurePrefs {
    fun target(context: Context, profileId: Long): Target {
        val p = context.getSharedPreferences("ampulse", Context.MODE_PRIVATE)
        val d = Target()
        return Target(p.getInt("bp_target_sys_$profileId", d.sys), p.getInt("bp_target_dia_$profileId", d.dia))
    }

    fun setTarget(context: Context, profileId: Long, t: Target) {
        context.getSharedPreferences("ampulse", Context.MODE_PRIVATE).edit {
            putInt("bp_target_sys_$profileId", t.sys)
            putInt("bp_target_dia_$profileId", t.dia)
        }
    }

    /** Адрес тонометра, с которым уже соединялись, — чтобы в следующий раз не искать. */
    fun device(context: Context): String? = context.getSharedPreferences("ampulse", Context.MODE_PRIVATE).getString("bp_device", null)

    fun setDevice(context: Context, address: String?) {
        context.getSharedPreferences("ampulse", Context.MODE_PRIVATE).edit { putString("bp_device", address) }
    }
}
