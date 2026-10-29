package io.github.agitangst.ampulse.ble

import io.github.agitangst.ampulse.domain.DeviceReading
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class ReadingBatchTest {
    private fun reading(sys: Int, at: LocalDateTime?) = DeviceReading(sys, 80, null, at)

    private fun at(day: Int, hour: Int) = LocalDateTime.of(2026, 9, day, hour, 0)

    @Test
    fun emptyBatchHasNoReading() {
        assertNull(ReadingBatch().latest())
        assertNull(latestReading(emptyList()))
    }

    @Test
    fun singleReadingIsReturned() {
        val batch = ReadingBatch()
        batch.add(reading(120, at(26, 8)))
        assertEquals(120, batch.latest()?.sys)
        assertEquals(1, batch.size)
    }

    @Test
    fun latestByTimeEvenWhenItArrivedFirst() {
        // Сохранённые замеры пришли не по порядку: берётся самый поздний по времени.
        val list = listOf(reading(130, at(26, 20)), reading(125, at(25, 8)), reading(118, at(26, 8)))
        assertEquals(130, latestReading(list)?.sys)
    }

    @Test
    fun withoutTimeTheLastArrivedWins() {
        val list = listOf(reading(130, null), reading(125, null), reading(118, null))
        assertEquals(118, latestReading(list)?.sys)
    }

    @Test
    fun sameTimeTheLaterArrivalWins() {
        val list = listOf(reading(130, at(26, 8)), reading(125, at(26, 8)))
        assertEquals(125, latestReading(list)?.sys)
    }

    @Test
    fun timedReadingsBeatUntimedOnes() {
        val list = listOf(reading(130, at(26, 8)), reading(125, null))
        assertEquals(130, latestReading(list)?.sys)
    }

    @Test
    fun batchKeepsArrivalOrder() {
        val batch = ReadingBatch()
        batch.add(reading(118, null))
        batch.add(reading(121, null))
        assertEquals(121, batch.latest()?.sys)
        assertEquals(2, batch.size)
    }
}
