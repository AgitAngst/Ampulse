package io.github.agitangst.ampulse.domain

import io.github.agitangst.ampulse.data.ScheduleShare
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class FamilyTest {
    private val start = LocalDate.of(2026, 9, 26)
    private val metformin = Medicine(7, "Метформин", "850 мг", MedForm.Tablet, "табл.", important = true, food = FoodRule.With, note = "с\tтабом")
    private val insulin = Medicine(8, "Инсулин гларгин", "100 ЕД/мл", MedForm.Injection, "ЕД")

    @Test
    fun shareRoundTrip() {
        val items = listOf(
            metformin to Plan(PlanKind.Daily, listOf(DoseTime(LocalTime.of(8, 0), 1.0), DoseTime(LocalTime.of(20, 0), 1.0)), start),
            insulin to Plan(PlanKind.Taper, listOf(DoseTime(LocalTime.of(22, 0), 14.0)), start, steps = listOf(TaperStep(14.0, 3), TaperStep(12.0, 3))),
        )
        val code = ScheduleShare.encode("Мама", items)
        assertTrue(code.startsWith(ScheduleShare.PREFIX))
        assertTrue(code.length < 1500, "QR must stay small: ${code.length}")
        val back = ScheduleShare.decode(code)!!
        assertEquals("Мама", back.owner)
        assertEquals(2, back.items.size)
        val (m, p) = back.items[0]
        assertEquals("Метформин", m.name)
        assertEquals("850 мг", m.strength)
        assertTrue(m.important)
        assertEquals(FoodRule.With, m.food)
        assertEquals("с табом", m.note)
        assertEquals(items[0].second.times, p.times)
        assertEquals(listOf(TaperStep(14.0, 3), TaperStep(12.0, 3)), back.items[1].second.steps)
        assertEquals(PlanKind.Taper, back.items[1].second.kind)
    }

    @Test
    fun foreignCodesRejected() {
        assertNull(ScheduleShare.decode("https://example.org"))
        assertNull(ScheduleShare.decode(ScheduleShare.PREFIX + "not-base64!"))
    }

    @Test
    fun texts() {
        val at = LocalDateTime.of(2026, 9, 26, 8, 0)
        assertEquals("Ampulse: Мама не отметил(а) приём Метформин 850 мг в 08:00.", FamilyTexts.missed(true, "Мама", metformin, at))
        val card = FamilyTexts.card(
            ru = true, name = "Мама", card = EmergencyCard(0, allergies = "пенициллин", bloodType = "II+"),
            medicines = listOf(metformin), lastDoses = mapOf(7L to at), contacts = listOf(Contact(1, "Анна", "+70000000000")),
        )
        assertEquals(
            """
            ЭКСТРЕННАЯ КАРТА — Мама
            Аллергии: пенициллин
            Группа крови: II+
            Лекарства:
            - Метформин 850 мг (последний приём 26.09 08:00)
            Звонить: Анна +70000000000
            """.trimIndent(),
            card,
        )
    }

    @Test
    fun adherenceCountsOnlyPast() {
        val plan = Plan(PlanKind.Daily, listOf(DoseTime(LocalTime.of(8, 0), 1.0), DoseTime(LocalTime.of(20, 0), 1.0)), start)
        val events = listOf(
            DoseEvent(1, 7, 1, start.atTime(8, 0), start.atTime(8, 5), EventStatus.Taken, 1.0),
            DoseEvent(2, 7, 1, start.atTime(20, 0), start.atTime(20, 1), EventStatus.Skipped, 1.0, note = "забыла дома"),
            DoseEvent(3, 7, null, null, start.atTime(21, 0), EventStatus.Extra, 1.0),
        )
        // Сейчас — 27.09, 12:00: 26-е целиком прошло, 27-го прошёл утренний приём (пропущен), вечерний ещё впереди.
        val s = adherence(start, start.plusDays(1), listOf(metformin), listOf(Schedule(1, 7, plan)), events, start.plusDays(1).atTime(12, 0)).single()
        assertEquals(3, s.planned)
        assertEquals(1, s.taken)
        assertEquals(1, s.skipped)
        assertEquals(1, s.missed)
        assertEquals(1, s.extra)
        assertEquals(33, s.percent)
        assertEquals(listOf(start.atTime(20, 0) to "забыла дома"), s.skips)
    }
}
