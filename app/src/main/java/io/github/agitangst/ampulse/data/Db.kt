package io.github.agitangst.ampulse.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MedicineDao {
    @Query("SELECT * FROM medicine WHERE archived = 0 ORDER BY name COLLATE NOCASE")
    fun active(): Flow<List<MedicineEntity>>

    @Query("SELECT * FROM medicine ORDER BY name COLLATE NOCASE")
    fun all(): Flow<List<MedicineEntity>>

    @Query("SELECT * FROM medicine WHERE archived = 0")
    suspend fun allNow(): List<MedicineEntity>

    @Query("SELECT * FROM medicine WHERE id = :id")
    suspend fun get(id: Long): MedicineEntity?

    @Insert
    suspend fun insert(m: MedicineEntity): Long

    @Update
    suspend fun update(m: MedicineEntity)

    @Query("DELETE FROM medicine WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedule")
    fun all(): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedule")
    suspend fun allNow(): List<ScheduleEntity>

    @Query("SELECT * FROM schedule WHERE medicineId = :medicineId")
    suspend fun forMedicine(medicineId: Long): List<ScheduleEntity>

    @Upsert
    suspend fun upsert(s: ScheduleEntity): Long

    @Query("DELETE FROM schedule WHERE medicineId = :medicineId")
    suspend fun deleteFor(medicineId: Long)
}

@Dao
interface DoseEventDao {
    @Query("SELECT * FROM dose_event WHERE at >= :from AND at < :to ORDER BY at")
    fun between(from: Long, to: Long): Flow<List<DoseEventEntity>>

    @Query("SELECT * FROM dose_event WHERE at >= :from AND at < :to ORDER BY at")
    suspend fun betweenNow(from: Long, to: Long): List<DoseEventEntity>

    @Query("SELECT * FROM dose_event WHERE plannedAt = :plannedAt AND medicineId = :medicineId")
    suspend fun forPlanned(medicineId: Long, plannedAt: Long): List<DoseEventEntity>

    @Insert
    suspend fun insert(e: DoseEventEntity): Long

    @Query("DELETE FROM dose_event WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM dose_event WHERE id = :id")
    suspend fun get(id: Long): DoseEventEntity?
}

/** База Ampulse. Новая версия схемы — миграция и файл схемы в `app/schemas` (проверяются тестом миграций). */
@Database(
    entities = [
        MedicineEntity::class, ScheduleEntity::class, DoseEventEntity::class, PackEntity::class, PackUseEntity::class, ProfileEntity::class, PressureEntity::class,
        ContactEntity::class, EmergencyEntity::class,
    ],
    version = 5,
    exportSchema = true,
    // 2 — упаковки и списания (этап 0.4); 3 — профили и место укола (этап 0.6); 4 — давление (этап 0.7);
    // 5 — близкие, экстренная карта, профиль-копия (этап 0.8).
    autoMigrations = [
        AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5),
    ],
)
abstract class AmpulseDb : RoomDatabase() {
    abstract fun medicines(): MedicineDao
    abstract fun schedules(): ScheduleDao
    abstract fun events(): DoseEventDao
    abstract fun packs(): PackDao
    abstract fun profiles(): ProfileDao
    abstract fun pressure(): PressureDao
    abstract fun family(): FamilyDao

    companion object {
        fun open(context: Context): AmpulseDb =
            Room.databaseBuilder(context.applicationContext, AmpulseDb::class.java, "ampulse.db").build()
    }
}
