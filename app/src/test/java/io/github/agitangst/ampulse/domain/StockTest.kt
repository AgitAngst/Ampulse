package io.github.agitangst.ampulse.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class StockTest {
    private val today = LocalDate.of(2026, 9, 26)
    private val twiceDaily = Plan(PlanKind.Daily, listOf(DoseTime(LocalTime.of(8, 0), 1.0), DoseTime(LocalTime.of(20, 0), 1.0)), today)

    @Test
    fun supplyAndNeed() {
        assertEquals(2.0, dailyNeed(twiceDaily, today), 1e-9)
        val packs = listOf(Pack(1, 1, 4.0, null), Pack(2, 1, 30.0, null))
        assertEquals(17, daysLeft(packs, twiceDaily, today))
        assertNull(daysLeft(packs, Plan(PlanKind.AsNeeded, emptyList(), today), today))
        val weekly = Plan(PlanKind.Weekly, listOf(DoseTime(LocalTime.of(9, 0), 1.0)), today, weekdays = setOf(today.dayOfWeek))
        assertEquals(1.0 / 7, dailyNeed(weekly, today), 1e-9)
    }

    @Test
    fun fefoTakesOpenedThenEarliest() {
        val packs = listOf(
            Pack(1, 1, 30.0, LocalDate.of(2028, 8, 31)),
            Pack(2, 1, 4.0, LocalDate.of(2027, 1, 31), openedAt = today.minusDays(3)),
            Pack(3, 1, 60.0, LocalDate.of(2027, 6, 30)),
        )
        assertEquals(listOf(2L, 3L, 1L), fefo(packs).map { it.id })
        assertEquals(listOf(2L to 4.0, 3L to 2.0), consume(packs, 6.0))
    }

    @Test
    fun expiryStatesAndOpened() {
        val insulin = Pack(1, 1, 48.0, LocalDate.of(2027, 4, 30), openedAt = LocalDate.of(2026, 9, 5), afterOpenDays = 28)
        assertEquals(LocalDate.of(2026, 10, 3), insulin.goodUntil())
        assertEquals(ExpiryState.Opened, expiryState(insulin, today, 3))
        assertEquals(ExpiryState.Expired, expiryState(Pack(2, 1, 14.0, LocalDate.of(2026, 8, 31)), today, 3))
        assertEquals(ExpiryState.Soon, expiryState(Pack(3, 1, 12.0, LocalDate.of(2026, 11, 30)), today, 3))
        assertEquals(ExpiryState.Ok, expiryState(Pack(3, 1, 12.0, LocalDate.of(2026, 11, 30)), today, 1))
        assertEquals(ExpiryState.Unknown, expiryState(Pack(4, 1, 1.0, null), today, 3))
    }

    @Test
    fun forecasts() {
        assertEquals(ExpiryForecast.Fits(LocalDate.of(2026, 10, 8)), forecast(Pack(1, 1, 12.0, LocalDate.of(2026, 11, 30)), 1.0, today))
        val left = forecast(Pack(2, 1, 27.0, LocalDate.of(2026, 10, 15)), 1.0, today)
        assertTrue(left is ExpiryForecast.Leftover && left.left == 7.0)
    }

    @Test
    fun timeLeftWords() {
        assertEquals(TimeLeft.Days(19), timeLeft(LocalDate.of(2026, 10, 15), today))
        assertEquals(TimeLeft.Months(2), timeLeft(LocalDate.of(2026, 11, 30), today))
        assertEquals(TimeLeft.Years(3), timeLeft(LocalDate.of(2030, 6, 30), today))
    }

    @Test
    fun chestnyZnakCode() {
        val gs = '\u001D'
        val raw = "0104607012345674" + "215XKNQ8ZT7K2Q" + gs + "17280831" + "10240715" + gs + "91EE10" + gs + "92abc=="
        val code = parsePackCode(raw)!!
        assertEquals("04607012345674", code.gtin)
        assertEquals("5XKNQ8ZT7K2Q", code.serial)
        assertEquals(LocalDate.of(2028, 8, 31), code.expiry)
        assertTrue(code.expiryHasDay)
        assertEquals("240715", code.batch)
        val monthOnly = parsePackCode("0104607012345674" + "17280800" + "21ABC")!!
        assertEquals(LocalDate.of(2028, 8, 31), monthOnly.expiry)
        assertEquals(false, monthOnly.expiryHasDay)
        assertNull(parsePackCode("hello"))
        assertNull(parsePackCode("01123"))
    }
}
