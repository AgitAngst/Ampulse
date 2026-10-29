package io.github.agitangst.ampulse.ui

import io.github.agitangst.ampulse.domain.Contact
import io.github.agitangst.ampulse.domain.DoseEvent
import io.github.agitangst.ampulse.domain.DoseTime
import io.github.agitangst.ampulse.domain.EmergencyCard
import io.github.agitangst.ampulse.domain.EventStatus
import io.github.agitangst.ampulse.domain.MedForm
import io.github.agitangst.ampulse.domain.Medicine
import io.github.agitangst.ampulse.domain.Plan
import io.github.agitangst.ampulse.domain.PlanKind
import io.github.agitangst.ampulse.domain.Profile
import io.github.agitangst.ampulse.domain.ProfileKind
import io.github.agitangst.ampulse.domain.Schedule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class EmergencyCardTest {
    @Test
    fun linesSplitOnNewlineAndSemicolon() {
        assertEquals(listOf("Лидокаин — отёк", "Йод — сыпь", "Пыльца"), cardLines("Лидокаин — отёк\n Йод — сыпь ;; Пыльца\n"))
        assertEquals(emptyList<String>(), cardLines("  \n ; "))
    }

    @Test
    fun allergySplitsNameAndReaction() {
        assertEquals("Лидокаин" to "отёк", parseAllergy("Лидокаин — отёк"))
        assertEquals("Пенициллин" to "сыпь", parseAllergy("Пенициллин - сыпь"))
        assertEquals("Пенициллин" to "сыпь", parseAllergy("Пенициллин: сыпь"))
        assertEquals("Орехи" to "", parseAllergy("Орехи"))
        // Дефис внутри слова — не разделитель.
        assertEquals("Анти-D" to "", parseAllergy("Анти-D"))
    }

    @Test
    fun bloodTypeRoundTrip() {
        for (group in 1..4) for (rh in 1..2) {
            assertEquals(group to rh, BloodType.parse(BloodType.format(group, rh)))
        }
        assertEquals("A(II) Rh+", BloodType.format(2, 1))
        assertEquals("AB(IV) Rh−", BloodType.format(4, 2))
        assertEquals("", BloodType.format(0, 0))
        assertEquals("Rh−", BloodType.format(0, 2))
        assertEquals("B(III)", BloodType.format(3, 0))
    }

    @Test
    fun bloodTypeReadsOtherWritings() {
        assertEquals(2 to 1, BloodType.parse("II+"))
        assertEquals(4 to 2, BloodType.parse("AB-"))
        assertEquals(1 to 1, BloodType.parse("O+"))
        assertEquals(0 to 0, BloodType.parse("неизвестно"))
        assertEquals(0 to 0, BloodType.parse(""))
    }

    @Test
    fun cardTextIsOneLinePerField() {
        val card = EmergencyCard(0, allergies = "Лидокаин — отёк\nЙод — сыпь", diagnoses = "Гипотиреоз\n\nДиабет", notes = " a \n b ")
        val text = card.forText()
        assertEquals("Лидокаин — отёк; Йод — сыпь", text.allergies)
        assertEquals("Гипотиреоз; Диабет", text.diagnoses)
        assertEquals("a b", text.notes)
    }

    @Test
    fun smsNotMoreOftenThanEveryThirtyMinutes() {
        val start = 1_000_000L
        assertTrue(emergencySmsDue(0L, start))
        assertFalse(emergencySmsDue(start, start + 29 * 60_000L))
        assertTrue(emergencySmsDue(start, start + 30 * 60_000L))
        // Часы ушли назад — метка из будущего не должна глушить SMS навсегда.
        assertTrue(emergencySmsDue(start + 10_000_000L, start))
    }

    @Test
    fun dataKeepsOwnActiveMedicinesWithLastDose() {
        val daily = Plan(PlanKind.Daily, listOf(DoseTime(LocalTime.of(8, 0), 1.0)), LocalDate.of(2026, 9, 1))
        val metformin = Medicine(1, "Метформин", "850 мг", MedForm.Tablet, "табл.")
        val insulin = Medicine(2, "Инсулин", "100 ЕД/мл", MedForm.Injection, "ЕД")
        val paused = Medicine(3, "Курс окончен", "", MedForm.Tablet, "табл.")
        val others = Medicine(4, "Чужое", "", MedForm.Tablet, "табл.", profileId = 5)
        val schedules = listOf(
            Schedule(10, 1, daily), Schedule(11, 2, daily), Schedule(12, 3, daily.copy(paused = true)), Schedule(13, 4, daily),
        )
        val morning = LocalDateTime.of(2026, 9, 26, 8, 41)
        val events = listOf(
            DoseEvent(1, 1, 10, morning, morning, EventStatus.Taken, 1.0),
            DoseEvent(2, 1, 10, morning.minusDays(1), morning.minusDays(1), EventStatus.Taken, 1.0),
            // Пропуск — не приём: последним для инсулина остаётся предыдущий.
            DoseEvent(3, 2, 11, morning, morning.plusHours(1), EventStatus.Skipped, 1.0),
            DoseEvent(4, 2, null, null, morning.minusDays(2), EventStatus.Extra, 1.0),
        )
        val contacts = listOf(Contact(1, "Анна", "+70000000000"))
        val data = buildEmergencyData(
            profileId = 0, ownerName = "Владелец", card = EmergencyCard(0, allergies = "Йод"),
            medicines = listOf(metformin, insulin, paused, others), schedules = schedules, events = events,
            contacts = contacts, profiles = emptyList(),
        )
        assertEquals("Владелец", data.name)
        assertEquals(listOf("Инсулин", "Метформин"), data.medicines.map { it.medicine.name })
        assertEquals(morning.minusDays(2), data.medicines[0].last)
        assertEquals(morning, data.medicines[1].last)
        assertEquals(contacts, data.contacts)
    }

    @Test
    fun dataUsesProfileName() {
        val profiles = listOf(Profile(5, "Мама", ProfileKind.Person))
        val data = buildEmergencyData(5, "Владелец", EmergencyCard(5), emptyList(), emptyList(), emptyList(), emptyList(), profiles)
        assertEquals("Мама", data.name)
        assertNull(data.medicines.firstOrNull())
        // Профиль удалён — имя владельца, а не пустая строка.
        assertEquals("Владелец", buildEmergencyData(9, "Владелец", EmergencyCard(9), emptyList(), emptyList(), emptyList(), emptyList(), profiles).name)
    }
}
