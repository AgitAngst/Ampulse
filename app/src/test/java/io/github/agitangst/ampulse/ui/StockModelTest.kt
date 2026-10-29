package io.github.agitangst.ampulse.ui

import io.github.agitangst.ampulse.domain.DoseTime
import io.github.agitangst.ampulse.domain.ExpiryForecast
import io.github.agitangst.ampulse.domain.MedForm
import io.github.agitangst.ampulse.domain.Medicine
import io.github.agitangst.ampulse.domain.Pack
import io.github.agitangst.ampulse.domain.Plan
import io.github.agitangst.ampulse.domain.PlanKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class StockModelTest {
    private val today = LocalDate.of(2026, 9, 26)
    private val med = Medicine(1, "Метформин", "850 мг", MedForm.Tablet, "табл.")
    private val twiceDaily = Plan(PlanKind.Daily, listOf(DoseTime(LocalTime.of(8, 0), 1.0), DoseTime(LocalTime.of(20, 0), 1.0)), today)

    @Test
    fun noPacksMeansNoForecast() {
        // Упаковок нет — запас не ведётся: «хватит на 0 дней» не показывается.
        val stock = MedStock(med, twiceDaily, emptyList(), today)
        assertNull(stock.days)
        assertFalse(stock.low)
    }

    @Test
    fun expiredPackIsNotCounted() {
        val packs = listOf(Pack(1, 1, 4.0, LocalDate.of(2027, 1, 31)), Pack(2, 1, 30.0, LocalDate.of(2026, 8, 31)))
        val stock = MedStock(med, twiceDaily, packs, today)
        assertEquals(4.0, stock.left, 1e-9)
        assertEquals(2, stock.days)
        assertTrue(stock.low)
        assertTrue(stock.hasExpired)
    }

    @Test
    fun forecastCountsWhatIsUsedFirst() {
        // Начатая пачка (4 табл.) расходуется раньше новой (30 табл. до 08.10): всего 34 табл. по 2 в день — 17 дней.
        val opened = Pack(1, 1, 4.0, LocalDate.of(2027, 1, 31), openedAt = today.minusDays(3))
        val fresh = Pack(0, 1, 30.0, LocalDate.of(2026, 10, 15))
        val f = packForecast(fresh, listOf(opened), 2.0, today)
        // До 15.10 осталось 20 дней = 40 табл. по расходу, а в запасе 34 — успеет, закончится через 17 дней.
        assertEquals(ExpiryForecast.Fits(today.plusDays(17)), f)
        // Без начатой пачки впереди — 15 дней, успевает раньше.
        assertEquals(ExpiryForecast.Fits(today.plusDays(15)), packForecast(fresh, emptyList(), 2.0, today))
    }

    @Test
    fun forecastUnknownWithoutNeed() {
        val pack = Pack(1, 1, 10.0, LocalDate.of(2026, 12, 31))
        assertEquals(ExpiryForecast.Unknown, packForecast(pack, emptyList(), 0.0, today))
    }

    @Test
    fun stepsByForm() {
        assertEquals(1.0, leftStep(MedForm.Tablet), 0.0)
        assertEquals(10.0, leftStep(MedForm.Injection), 0.0)
        assertEquals(1.0, leftStep(MedForm.Liquid), 0.0)
        assertEquals(28, defaultAfterOpen(MedForm.Injection))
        assertEquals(0, defaultAfterOpen(MedForm.Tablet))
    }

    @Test
    fun splitsNumberFromUnit() {
        assertEquals("17" to "дней", splitNumber("17 дней", 17))
        assertEquals("3" to "мес.", splitNumber("3 мес.", 3))
        assertEquals("1" to "year", splitNumber("1 year", 1))
    }
}
