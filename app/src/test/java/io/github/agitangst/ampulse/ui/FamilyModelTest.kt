package io.github.agitangst.ampulse.ui

import io.github.agitangst.ampulse.data.ScheduleShare
import io.github.agitangst.ampulse.domain.DoseTime
import io.github.agitangst.ampulse.domain.MedForm
import io.github.agitangst.ampulse.domain.Medicine
import io.github.agitangst.ampulse.domain.Plan
import io.github.agitangst.ampulse.domain.PlanKind
import io.github.agitangst.ampulse.domain.Profile
import io.github.agitangst.ampulse.domain.ProfileKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class FamilyModelTest {
    @Test
    fun phoneInputKeepsOnlyPhoneCharacters() {
        assertEquals("+7 (900) 123-45-67", FamilyPhone.typed("+7 (900) 123-45-67"))
        assertEquals("89001234567", FamilyPhone.typed("8900abc1234567"))
        // «+» только первым знаком.
        assertEquals("+7900", FamilyPhone.typed("+7+9+00"))
        assertEquals("7900", FamilyPhone.typed("79+00"))
    }

    @Test
    fun cleanPhoneKeepsDigitsAndLeadingPlus() {
        assertEquals("+79001234567", FamilyPhone.clean(" +7 (900) 123-45-67 "))
        assertEquals("89001234567", FamilyPhone.clean("8 900 123 45 67"))
        assertEquals("", FamilyPhone.clean("  "))
    }

    @Test
    fun phoneValidityIsFiveToFifteenDigits() {
        assertFalse(FamilyPhone.valid(""))
        assertFalse(FamilyPhone.valid("+7"))
        assertFalse(FamilyPhone.valid("1234"))
        assertTrue(FamilyPhone.valid("12345"))
        assertTrue(FamilyPhone.valid("+7 900 123-45-67"))
        assertTrue(FamilyPhone.valid("+123456789012345"))
        assertFalse(FamilyPhone.valid("+1234567890123456"))
    }

    @Test
    fun shareOwnerUsesProfileNameOrTypedLabel() {
        val cat = Profile(4, "Барсик", ProfileKind.Cat)
        assertEquals("Барсик", shareOwner(cat, "Мама", "Близкий"))
        // «Я»: введённая подпись, а пусто — запасная.
        assertEquals("Мама", shareOwner(null, "  Мама ", "Близкий"))
        assertEquals("Близкий", shareOwner(null, "  ", "Близкий"))
        assertEquals(40, shareOwner(null, "а".repeat(60), "Близкий").length)
    }

    @Test
    fun qrHasFinderPatternsAndIsSquare() {
        val result = QrModules.build("ampulse:1:abc")
        val ok = assertInstanceOf(QrResult.Ok::class.java, result)
        val m = ok.modules
        assertTrue(ok.size >= 21)
        assertTrue(m.all { it.size == m.size })
        // Левый верхний поисковый квадрат 7×7: тёмная рамка, светлое кольцо, тёмная сердцевина.
        for (i in 0..6) {
            assertTrue(m[0][i] && m[6][i] && m[i][0] && m[i][6])
        }
        assertFalse(m[1][1])
        assertTrue(m[3][3])
    }

    @Test
    fun scheduleQrFitsForARealisticList() {
        val items = (1..12).map { i ->
            Medicine(0, "Лекарство номер $i", "$i мг", MedForm.Tablet, "табл.", important = i % 2 == 0, note = "Заметка $i") to
                Plan(PlanKind.Daily, listOf(DoseTime(LocalTime.of(8, 0), 1.0), DoseTime(LocalTime.of(20, 0), 1.0)), LocalDate.of(2026, 9, 1))
        }
        val code = ScheduleShare.encode("Мама", items)
        assertInstanceOf(QrResult.Ok::class.java, QrModules.build(code))
        // И обратно: то, что в коде, разбирается.
        val back = ScheduleShare.decode(code)
        assertEquals("Мама", back?.owner)
        assertEquals(12, back?.items?.size)
    }

    @Test
    fun tooLongTextIsRejected() {
        assertEquals(QrResult.TooBig, QrModules.build("a".repeat(QrModules.MAX_BYTES + 1)))
    }
}
