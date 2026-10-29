package io.github.agitangst.ampulse.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import io.github.agitangst.ampulse.domain.Contact
import io.github.agitangst.ampulse.domain.EmergencyCard
import io.github.agitangst.ampulse.domain.Medicine
import io.github.agitangst.ampulse.domain.Plan
import kotlinx.coroutines.flow.Flow
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Близкий (этап 0.8). */
@Entity(tableName = "contact")
data class ContactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String,
    val smsMissed: Boolean,
    val smsEmergency: Boolean,
    val createdAt: Long,
)

/** Экстренная карта профиля (этап 0.8); у профиля одна. */
@Entity(tableName = "emergency")
data class EmergencyEntity(
    @PrimaryKey val profileId: Long,
    val allergies: String,
    val diagnoses: String,
    val bloodType: String,
    val notes: String,
    val onLockScreen: Boolean,
)

@Dao
interface FamilyDao {
    @Query("SELECT * FROM contact ORDER BY createdAt")
    fun contacts(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contact ORDER BY createdAt")
    suspend fun contactsNow(): List<ContactEntity>

    @Upsert
    suspend fun upsert(c: ContactEntity): Long

    @Query("DELETE FROM contact WHERE id = :id")
    suspend fun deleteContact(id: Long)

    @Query("SELECT * FROM emergency WHERE profileId = :profileId")
    fun emergency(profileId: Long): Flow<EmergencyEntity?>

    @Query("SELECT * FROM emergency WHERE profileId = :profileId")
    suspend fun emergencyNow(profileId: Long): EmergencyEntity?

    @Upsert
    suspend fun upsert(e: EmergencyEntity)
}

object FamilyCodec {
    fun contact(e: ContactEntity) = Contact(e.id, e.name, e.phone, e.smsMissed, e.smsEmergency)

    fun entity(c: Contact, createdAt: Long) = ContactEntity(c.id, c.name.trim(), c.phone.trim(), c.smsMissed, c.smsEmergency, createdAt)

    fun card(e: EmergencyEntity) = EmergencyCard(e.profileId, e.allergies, e.diagnoses, e.bloodType, e.notes, e.onLockScreen)

    fun entity(c: EmergencyCard) = EmergencyEntity(c.profileId, c.allergies.trim(), c.diagnoses.trim(), c.bloodType.trim(), c.notes.trim(), c.onLockScreen)
}

/**
 * Расписание для QR (макет, экраны 12–13): мама показывает QR — у Анны появляется копия её расписания. Без сервера:
 * всё в самом коде. Формат: `ampulse:1:` + Base64 (URL) сжатого текста; строки — табами, как в базе (`Codec`).
 */
object ScheduleShare {
    const val PREFIX = "ampulse:1:"
    private const val MAGIC = "AMPULSE1"

    data class Shared(val owner: String, val items: List<Pair<Medicine, Plan>>)

    fun encode(owner: String, items: List<Pair<Medicine, Plan>>): String {
        val text = buildString {
            appendLine(MAGIC)
            appendLine(clean(owner))
            items.forEach { (m, p) ->
                val s = Codec.entity(0, p)
                appendLine(
                    listOf("m", m.name, m.strength, m.form.name, m.unit, m.food.name, if (m.important) "1" else "0", m.note)
                        .joinToString("\t", transform = ::clean),
                )
                appendLine(
                    listOf(
                        "p", s.kind, s.times, s.startDate, s.endDate.orEmpty(), s.weekdays.toString(), s.everyN.toString(),
                        s.cycleOn.toString(), s.cycleOff.toString(), s.steps, s.prnIntervalHours.toString(), s.prnMaxPerDay.toString(),
                        if (s.paused) "1" else "0",
                    ).joinToString("\t"),
                )
            }
        }
        return PREFIX + b64(deflate(text.toByteArray(Charsets.UTF_8)))
    }

    /** Разобрать QR; чужой или битый код — `null`. */
    fun decode(code: String): Shared? = runCatching {
        if (!code.startsWith(PREFIX)) return null
        val lines = String(inflate(unb64(code.removePrefix(PREFIX))), Charsets.UTF_8).lines()
        if (lines.firstOrNull() != MAGIC) return null
        val owner = lines.getOrNull(1) ?: return null
        val rows = lines.drop(2).filter { it.isNotBlank() }.map { it.split('\t') }
        val items = rows.chunked(2).mapNotNull { pair ->
            val m = pair.getOrNull(0)?.takeIf { it[0] == "m" && it.size >= 8 } ?: return@mapNotNull null
            val p = pair.getOrNull(1)?.takeIf { it[0] == "p" && it.size >= 13 } ?: return@mapNotNull null
            val med = Codec.medicine(
                MedicineEntity(name = m[1], strength = m[2], form = m[3], unit = m[4], important = m[6] == "1", food = m[5], note = m[7], createdAt = 0),
            )
            val plan = Codec.plan(
                ScheduleEntity(
                    medicineId = 0, kind = p[1], times = p[2], startDate = p[3], endDate = p[4].ifEmpty { null }, weekdays = p[5].toInt(),
                    everyN = p[6].toInt(), cycleOn = p[7].toInt(), cycleOff = p[8].toInt(), steps = p[9], prnIntervalHours = p[10].toInt(),
                    prnMaxPerDay = p[11].toInt(), paused = p[12] == "1",
                ),
            )
            med to plan
        }
        Shared(owner, items)
    }.getOrNull()

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    private fun deflate(b: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION).apply { setInput(b); finish() }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    private fun inflate(b: ByteArray): ByteArray {
        val i = Inflater().apply { setInput(b) }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!i.finished()) {
            val n = i.inflate(buf)
            if (n == 0 && (i.needsInput() || i.needsDictionary())) break
            out.write(buf, 0, n)
            check(out.size() < 1 shl 20) { "Shared schedule is too large" }
        }
        i.end()
        return out.toByteArray()
    }

    // java.util.Base64, а не android.util: тот в юнит-тестах — заглушка.
    private fun b64(b: ByteArray) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    private fun unb64(s: String) = java.util.Base64.getUrlDecoder().decode(s)
}
