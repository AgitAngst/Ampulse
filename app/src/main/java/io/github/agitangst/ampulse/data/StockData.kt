package io.github.agitangst.ampulse.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import io.github.agitangst.ampulse.domain.Pack
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** Упаковка (этап 0.4): остаток в единицах дозы, срок, вскрытие, данные из кода «Честного знака». */
@Entity(
    tableName = "pack",
    foreignKeys = [ForeignKey(MedicineEntity::class, ["id"], ["medicineId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("medicineId")],
)
data class PackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val medicineId: Long,
    val remaining: Double,
    val expiry: String?,
    val expiryHasDay: Boolean,
    val openedAt: String?,
    val afterOpenDays: Int,
    val batch: String,
    val serial: String,
    val gtin: String,
    /** Убрана из аптечки (закончилась, выброшена, просрочена). */
    val disposed: Boolean = false,
    val createdAt: Long,
)

/** Сколько из какой упаковки списано отметкой — чтобы «Отменить» вернуло запас на место. */
@Entity(
    tableName = "pack_use",
    foreignKeys = [
        ForeignKey(DoseEventEntity::class, ["id"], ["eventId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(PackEntity::class, ["id"], ["packId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("eventId"), Index("packId")],
)
data class PackUseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: Long,
    val packId: Long,
    val amount: Double,
)

@Dao
interface PackDao {
    @Query("SELECT * FROM pack WHERE disposed = 0 ORDER BY medicineId, expiry")
    fun active(): Flow<List<PackEntity>>

    @Query("SELECT * FROM pack WHERE disposed = 0 AND medicineId = :medicineId")
    suspend fun forMedicine(medicineId: Long): List<PackEntity>

    @Query("SELECT * FROM pack WHERE serial != '' AND serial = :serial AND gtin = :gtin LIMIT 1")
    suspend fun bySerial(gtin: String, serial: String): PackEntity?

    @Query("SELECT * FROM pack WHERE id = :id")
    suspend fun get(id: Long): PackEntity?

    @Insert
    suspend fun insert(p: PackEntity): Long

    @Update
    suspend fun update(p: PackEntity)

    @Query("UPDATE pack SET remaining = remaining + :delta WHERE id = :id")
    suspend fun add(id: Long, delta: Double)

    @Insert
    suspend fun insertUse(u: PackUseEntity)

    @Query("SELECT * FROM pack_use WHERE eventId = :eventId")
    suspend fun usesOf(eventId: Long): List<PackUseEntity>
}

object StockCodec {
    fun pack(e: PackEntity) = Pack(
        id = e.id, medicineId = e.medicineId, left = e.remaining, expiry = e.expiry?.let(LocalDate::parse),
        expiryHasDay = e.expiryHasDay, openedAt = e.openedAt?.let(LocalDate::parse), afterOpenDays = e.afterOpenDays,
        batch = e.batch, serial = e.serial, gtin = e.gtin,
    )

    fun entity(p: Pack, createdAt: Long = System.currentTimeMillis()) = PackEntity(
        id = p.id, medicineId = p.medicineId, remaining = p.left, expiry = p.expiry?.toString(), expiryHasDay = p.expiryHasDay,
        openedAt = p.openedAt?.toString(), afterOpenDays = p.afterOpenDays, batch = p.batch, serial = p.serial, gtin = p.gtin,
        createdAt = createdAt,
    )
}
