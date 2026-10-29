package io.github.agitangst.ampulse.ui

import io.github.agitangst.ampulse.domain.ReadingError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PressureKeypadTest {
    private fun type(start: EntryDigits, digits: String) = digits.fold(start) { d, c -> d.press(EntryKey.Digit(c)) }

    @Test
    fun fieldsAdvanceWhenNumberIsComplete() {
        // 128 — три цифры, потому что начинается с 1: переход к нижнему; 82 — две: к пульсу.
        val sys = type(EntryDigits(), "128")
        assertEquals("128", sys.sys)
        assertEquals(EntryField.Dia, sys.active)
        val dia = type(sys, "82")
        assertEquals("82", dia.dia)
        assertEquals(EntryField.Pulse, dia.active)
        val pulse = type(dia, "71")
        assertEquals("71", pulse.pulse)
        // Пульс — последнее поле: курсор остаётся на нём.
        assertEquals(EntryField.Pulse, pulse.active)
    }

    @Test
    fun twoDigitSystolicStartingWithNineAdvancesAtOnce() {
        val d = type(EntryDigits(), "95")
        assertEquals("95", d.sys)
        assertEquals(EntryField.Dia, d.active)
    }

    @Test
    fun threeDigitDiastolicAndPulseStartWithOne() {
        val d = type(EntryDigits(active = EntryField.Dia), "105")
        assertEquals("105", d.dia)
        assertEquals(EntryField.Pulse, d.active)
        val p = type(d, "110")
        assertEquals("110", p.pulse)
    }

    @Test
    fun digitIsIgnoredInFullField() {
        val full = type(EntryDigits(), "128").pick(EntryField.Sys)
        assertEquals(full, full.press(EntryKey.Digit('9')))
    }

    @Test
    fun leadingZeroIsIgnored() {
        val d = EntryDigits().press(EntryKey.Digit('0'))
        assertEquals(EntryDigits(), d)
        // Внутри числа ноль работает: 105.
        assertEquals("105", type(EntryDigits(), "105").sys)
    }

    @Test
    fun deleteRemovesLastDigitInActiveField() {
        val d = type(EntryDigits(), "12").press(EntryKey.Delete)
        assertEquals("1", d.sys)
        assertEquals(EntryField.Sys, d.active)
    }

    @Test
    fun deleteOnEmptyFieldGoesBackAndErasesPreviousDigit() {
        val afterSys = type(EntryDigits(), "128")
        assertEquals(EntryField.Dia, afterSys.active)
        val back = afterSys.press(EntryKey.Delete)
        assertEquals("12", back.sys)
        assertEquals(EntryField.Sys, back.active)
    }

    @Test
    fun deleteOnEmptyFirstFieldDoesNothing() {
        assertEquals(EntryDigits(), EntryDigits().press(EntryKey.Delete))
    }

    @Test
    fun nextMovesForwardAndStopsAtPulse() {
        val d = EntryDigits().press(EntryKey.Next)
        assertEquals(EntryField.Dia, d.active)
        val p = d.press(EntryKey.Next)
        assertEquals(EntryField.Pulse, p.active)
        assertEquals(p, p.press(EntryKey.Next))
    }

    @Test
    fun pickSelectsFieldKeepingDigits() {
        val d = type(EntryDigits(), "128").pick(EntryField.Sys)
        assertEquals("128", d.sys)
        assertEquals(EntryField.Sys, d.active)
    }

    @Test
    fun incompleteUntilBothNumbersAreTyped() {
        assertEquals(EntryCheck.Incomplete, EntryDigits().check())
        assertEquals(EntryCheck.Incomplete, type(EntryDigits(), "12").check())
        assertEquals(EntryCheck.Incomplete, type(EntryDigits(), "128").check())
    }

    @Test
    fun readyWithAndWithoutPulse() {
        val noPulse = type(EntryDigits(), "12882").check()
        assertEquals(EntryCheck.Ready(128, 82, null, pulseUnfinished = false), noPulse)
        assertTrue((noPulse as EntryCheck.Ready).canSave)
        assertEquals(EntryCheck.Ready(128, 82, 71, pulseUnfinished = false), type(EntryDigits(), "1288271").check())
    }

    @Test
    fun unfinishedPulseBlocksSaving() {
        // «1» в пульсе — это начало трёхзначного числа: допишите или сотрите.
        val ready = type(EntryDigits(), "128821").check() as EntryCheck.Ready
        assertTrue(ready.pulseUnfinished)
        assertFalse(ready.canSave)
        // Стёрли — пульс не обязателен.
        val erased = type(EntryDigits(), "128821").press(EntryKey.Delete).check() as EntryCheck.Ready
        assertTrue(erased.canSave)
        assertEquals(null, erased.pulse)
    }

    @Test
    fun systolicNotAboveDiastolicIsOrderError() {
        // 80 на 90: верхнее должно быть больше нижнего.
        assertEquals(EntryCheck.Invalid(ReadingError.Order), type(EntryDigits(), "8090").check())
        assertEquals(EntryCheck.Invalid(ReadingError.Order), type(EntryDigits(), "9090").check())
    }

    @Test
    fun implausibleNumbersAreRangeError() {
        // Верхнее 40 — ниже 60.
        assertEquals(EntryCheck.Invalid(ReadingError.Range), type(EntryDigits(), "4030").check())
        // Пульс 25 — ниже 30.
        assertEquals(EntryCheck.Invalid(ReadingError.Range), type(EntryDigits(), "1288225").check())
    }

    @Test
    fun filledFromDeviceReading() {
        val full = EntryDigits.filled(128, 82, 71)
        assertEquals(EntryCheck.Ready(128, 82, 71, pulseUnfinished = false), full.check())
        assertEquals(EntryField.Sys, full.active)
        // Тонометр не прислал пульс — курсор на пульсе, замер уже можно сохранить.
        val noPulse = EntryDigits.filled(128, 82, null)
        assertEquals(EntryField.Pulse, noPulse.active)
        assertTrue((noPulse.check() as EntryCheck.Ready).canSave)
    }

    @Test
    fun threeDigitPulseFromDeviceCounts() {
        // Пульс 205 с экрана тонометра: набрать так нельзя (два знака), но принятое число целое.
        val d = EntryDigits.filled(150, 95, 205)
        assertTrue(d.isFull(EntryField.Pulse))
        assertEquals(EntryCheck.Ready(150, 95, 205, pulseUnfinished = false), d.check())
    }
}
