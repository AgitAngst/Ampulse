package io.github.agitangst.ampulse.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.Locale

class ProfileWeightTest {
    @Test
    fun parsesCommaAndDot() {
        assertEquals(4.2, parseWeightKg("4,2")!!, 1e-9)
        assertEquals(4.2, parseWeightKg("4.2")!!, 1e-9)
        assertEquals(18.0, parseWeightKg(" 18 ")!!, 1e-9)
    }

    @Test
    fun rejectsNonsense() {
        assertNull(parseWeightKg(""))
        assertNull(parseWeightKg("0"))
        assertNull(parseWeightKg("4.2.1"))
        assertNull(parseWeightKg(","))
        assertNull(parseWeightKg("250"))
    }

    @Test
    fun formatsByLocaleWithoutTrailingZeros() {
        assertEquals("4,2", weightNumber(4.2, Locale.forLanguageTag("ru")))
        assertEquals("4.2", weightNumber(4.2, Locale.US))
        assertEquals("18", weightNumber(18.0, Locale.US))
    }
}
