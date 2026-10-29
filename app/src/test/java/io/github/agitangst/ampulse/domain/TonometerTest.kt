package io.github.agitangst.ampulse.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class TonometerTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun parsesMmHgWithTimeAndPulse() {
        // Флаги: мм рт. ст., время, пульс. 128 / 82 / MAP 97, 2026-09-26 08:12:05, пульс 71.
        val r = BloodPressureGatt.parse(bytes(0x06, 128, 0, 82, 0, 97, 0, 0xEA, 0x07, 9, 26, 8, 12, 5, 71, 0))!!
        assertEquals(128, r.sys)
        assertEquals(82, r.dia)
        assertEquals(71, r.pulse)
        assertEquals(LocalDateTime.of(2026, 9, 26, 8, 12, 5), r.at)
    }

    @Test
    fun parsesKpaWithExponent() {
        // 17.1 кПа = 171 · 10^-1 (степень -1 → 0xF в старших битах), 10.9 кПа; без времени и пульса.
        val r = BloodPressureGatt.parse(bytes(0x01, 171, 0xF0, 109, 0xF0, 0, 0))!!
        assertEquals(128, r.sys)
        assertEquals(82, r.dia)
        assertNull(r.pulse)
        assertNull(r.at)
    }

    @Test
    fun rejectsShortAndNaN() {
        assertNull(BloodPressureGatt.parse(bytes(0x00, 128, 0)))
        assertNull(BloodPressureGatt.parse(bytes(0x00, 0xFF, 0x07, 82, 0, 0, 0)))
    }

    @Test
    fun readsNumbersFromText() {
        assertEquals(DeviceReading(128, 82, 71, null), readingFromText("128 на 82, пульс 71"))
        assertEquals(DeviceReading(131, 84, null, null), readingFromText("SYS 131 DIA 84"))
        // Время на экране тонометра (08 12) не мешает: 12/128 неправдоподобно.
        assertEquals(DeviceReading(128, 82, 71, null), readingFromText("08:12 128 82 71"))
        assertNull(readingFromText("пульс 71"))
    }
}
