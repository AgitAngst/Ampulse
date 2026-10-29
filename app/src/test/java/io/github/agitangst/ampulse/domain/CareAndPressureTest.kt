package io.github.agitangst.ampulse.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class CareAndPressureTest {
    private val today = LocalDate.of(2026, 9, 26)

    @Test
    fun rotationPrefersLongestRested() {
        val sites = sitesFor(ProfileKind.Person, intramuscular = false)
        assertEquals(6, sites.size)
        val shots = listOf(
            Site.AbdomenLeft to today.minusDays(1).atTime(22, 0),
            Site.ThighRight to today.minusDays(2).atTime(22, 0),
            Site.ArmRight to today.minusDays(4).atTime(22, 0),
            Site.AbdomenRight to today.minusDays(6).atTime(22, 0),
            Site.ThighLeft to today.minusDays(3).atTime(22, 0),
            Site.ArmLeft to today.minusDays(5).atTime(22, 0),
        )
        val rest = rotation(sites, shots, today)
        assertEquals(Site.AbdomenRight, recommended(rest))
        assertTrue(rest.first { it.site == Site.AbdomenLeft }.resting)
        assertFalse(rest.first { it.site == Site.ArmRight }.resting)
        // Никогда не коловшееся место — первым.
        assertEquals(Site.ButtockLeft, recommended(rotation(sitesFor(ProfileKind.Person, true), listOf(Site.ButtockRight to today.atTime(9, 0)), today)))
        assertEquals(listOf(Site.Scruff, Site.FlankLeft, Site.FlankRight), sitesFor(ProfileKind.Cat, false))
        assertEquals(20, injectSeconds(true, 2.0))
        assertEquals(10, injectSeconds(false, 0.14))
    }

    @Test
    fun pressureStatesAndKeypad() {
        assertEquals(PressureState.Within, classify(128, 82))
        assertEquals(PressureState.Above, classify(136, 80))
        assertEquals(PressureState.Crisis, classify(182, 95))
        assertEquals(PressureState.Low, classify(88, 58))
        assertEquals(ReadingError.Order, validate(80, 90, null))
        assertEquals(ReadingError.Range, validate(300, 90, null))
        assertEquals(null, validate(128, 82, 71))
        assertEquals(3, maxDigits(0, "1"))
        assertEquals(2, maxDigits(0, "9"))
        assertEquals(2, maxDigits(1, "8"))
        assertEquals(3, maxDigits(2, "1"))
    }

    @Test
    fun averagesAndInsight() {
        val r = listOf(
            Reading(1, 128, 82, 71, today.atTime(8, 0)), Reading(2, 124, 80, 69, today.atTime(8, 2)),
            Reading(3, 140, 88, null, today.atTime(20, 0)),
            Reading(4, 130, 84, null, today.minusDays(1).atTime(20, 30)),
        )
        assertEquals(Triple(126, 81, 70), average(r.take(2)))
        val i = insight(r)
        assertEquals(1, i.eveningAbove)
        assertEquals(2, i.eveningDays)
        assertEquals(0, i.morningAbove)
    }
}
