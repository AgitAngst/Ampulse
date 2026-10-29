package io.github.agitangst.ampulse.reminders

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReminderTitleTest {
    @Test
    fun ownMedicineKeepsItsName() {
        assertEquals("Метформин", ReminderText.title("Метформин", null))
        assertEquals("Метформин", ReminderText.title("Метформин", " "))
    }

    @Test
    fun profileMedicineGetsNameInBrackets() {
        assertEquals("Инсулин гларгин (Барсик)", ReminderText.title("Инсулин гларгин", "Барсик"))
    }
}
