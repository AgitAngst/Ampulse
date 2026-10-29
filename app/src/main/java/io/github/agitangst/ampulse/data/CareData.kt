package io.github.agitangst.ampulse.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import io.github.agitangst.ampulse.domain.Profile
import io.github.agitangst.ampulse.domain.ProfileKind
import kotlinx.coroutines.flow.Flow

/**
 * Профиль: чьи лекарства (этап 0.6). «Я» — профиль 0, строки в таблице у него нет: так база версии 2 переходит на 3
 * без вставки данных, а все прежние лекарства остаются «моими».
 */
@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: String,
    val weightKg: Double?,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "0") val watchOnly: Boolean = false,
)

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profile ORDER BY createdAt")
    fun all(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profile WHERE id = :id")
    suspend fun get(id: Long): ProfileEntity?

    @Query("SELECT * FROM profile ORDER BY createdAt")
    suspend fun allNow(): List<ProfileEntity>

    @Upsert
    suspend fun upsert(p: ProfileEntity): Long

    @Query("DELETE FROM profile WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM medicine WHERE profileId = :id")
    suspend fun deleteMedicines(id: Long)

    /** Уколы профиля с местом — для ротации мест (макет, экраны 3, 17–18); пропуски не в счёт. */
    @Query(
        """SELECT e.* FROM dose_event e JOIN medicine m ON m.id = e.medicineId
           WHERE m.profileId = :profileId AND e.site IS NOT NULL AND e.status != 'Skipped' ORDER BY e.at DESC""",
    )
    fun injections(profileId: Long): Flow<List<DoseEventEntity>>
}

object CareCodec {
    fun profile(e: ProfileEntity) = Profile(e.id, e.name, ProfileKind.entries.firstOrNull { it.name == e.kind } ?: ProfileKind.Other, e.weightKg, e.watchOnly)

    fun entity(p: Profile, createdAt: Long) = ProfileEntity(p.id, p.name.trim(), p.kind.name, p.weightKg, createdAt, p.watchOnly)
}
