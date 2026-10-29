package io.github.agitangst.ampulse.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64

class SyncIdsTest {
    private val salt = ByteArray(16) { (it * 7 + 3).toByte() }
    private val other = ByteArray(16) { (it * 5 + 1).toByte() }
    private val valid = Regex("[A-Za-z0-9_-]{22}")

    @Test
    fun deterministicAndWellFormed() {
        val a = SyncIds.medicine(salt, 42)
        assertEquals(a, SyncIds.medicine(salt, 42))
        assertTrue(valid.matches(a), a)
        // Под разрешённый сервером вид (8–64 знака A-Za-z0-9_-) подходят все виды id.
        listOf(SyncIds.profile(salt, 0), SyncIds.event(salt, 9), SyncIds.planned(salt, 3, 1_800_000_000)).forEach { assertTrue(valid.matches(it), it) }
    }

    @Test
    fun formulaIsSha256OfSaltKindAndNumbers() {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update("med:42".toByteArray())
        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest()).take(22)
        assertEquals(expected, SyncIds.medicine(salt, 42))
    }

    @Test
    fun kindSaltAndNumberChangeTheId() {
        val base = SyncIds.profile(salt, 1)
        assertNotEquals(base, SyncIds.medicine(salt, 1))
        assertNotEquals(base, SyncIds.event(salt, 1))
        assertNotEquals(base, SyncIds.profile(other, 1))
        assertNotEquals(base, SyncIds.profile(salt, 2))
        // Профиль «Я» (0) — тоже обычный id, и он не совпадает с лекарством 0.
        assertNotEquals(SyncIds.profile(salt, 0), SyncIds.medicine(salt, 0))
    }

    @Test
    fun plannedIdUsesScheduleAndMinute() {
        val t = 1_800_000_000L - 1_800_000_000L % 60
        assertEquals(SyncIds.planned(salt, 5, t), SyncIds.planned(salt, 5, t + 59))
        assertNotEquals(SyncIds.planned(salt, 5, t), SyncIds.planned(salt, 5, t + 60))
        assertNotEquals(SyncIds.planned(salt, 5, t), SyncIds.planned(salt, 6, t))
    }

    @Test
    fun idsDoNotRevealLocalNumbers() {
        val ids = (0L..999L).map { SyncIds.medicine(salt, it) }
        assertEquals(1000, ids.toSet().size)
        // Ни номер записи, ни его прямое кодирование в id не видны; без соли id не восстановить.
        ids.forEachIndexed { n, id ->
            assertFalse(id.contains("med"), id)
            assertNotEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(n.toString().toByteArray()), id.take(4))
        }
        // С другой солью те же номера дают другой набор — по id нельзя связать два телефона или две выгрузки.
        assertTrue((0L..999L).map { SyncIds.medicine(other, it) }.intersect(ids.toSet()).isEmpty())
    }

    @Test
    fun saltIsRandomSixteenBytes() {
        val a = SyncIds.newSalt()
        val b = SyncIds.newSalt()
        assertEquals(16, a.size)
        assertFalse(a.contentEquals(b))
    }
}
