package io.github.agitangst.ampulse.reminders

import io.github.agitangst.ampulse.domain.DoseEvent
import io.github.agitangst.ampulse.domain.DoseTime
import io.github.agitangst.ampulse.domain.EventStatus
import io.github.agitangst.ampulse.domain.MedForm
import io.github.agitangst.ampulse.domain.Medicine
import io.github.agitangst.ampulse.domain.Plan
import io.github.agitangst.ampulse.domain.PlanKind
import io.github.agitangst.ampulse.domain.Schedule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** «Проверка» после повторов важного приёма (SMS о пропуске) и молчание профилей-копий — этап 0.8. */
class ReminderCheckTest {
    private val day = LocalDate.of(2026, 9, 26)

    private val metformin = Medicine(1, "Метформин", "850 мг", MedForm.Tablet, "табл.")
    private val glargine = Medicine(2, "Инсулин гларгин", "100 ЕД/мл", MedForm.Injection, "ЕД", important = true)

    private fun at(h: Int, m: Int = 0, plusDays: Long = 0) = day.plusDays(plusDays).atTime(h, m)

    private val glargineSchedule = Schedule(11, 2, Plan(PlanKind.Daily, listOf(DoseTime(LocalTime.of(22, 0), 12.0)), day))
    private val metforminSchedule = Schedule(10, 1, Plan(PlanKind.Daily, listOf(DoseTime(LocalTime.of(14, 0), 1.0)), day))

    private fun event(medicineId: Long, planned: LocalDateTime, status: EventStatus, scheduleId: Long = 11) =
        DoseEvent(100 + medicineId, medicineId, scheduleId, planned, planned.plusMinutes(3), status, 1.0)

    private fun upcoming(
        schedules: List<Schedule>,
        medicines: List<Medicine>,
        events: List<DoseEvent>,
        now: LocalDateTime,
        silent: Set<Long> = emptySet(),
        check: Boolean = true,
    ) = ReminderPlan.upcoming(schedules, medicines, events, now, silentProfiles = silent, missedCheck = check)

    @Test
    fun checkComesTenMinutesAfterTheLastRepeat() {
        val tonight = upcoming(listOf(glargineSchedule), listOf(glargine), emptyList(), now = at(21, 0))
            .filter { it.dose.planned == at(22) }
        // Основное в 22:00, повторы 22:10, 22:20, 22:30, проверка в 22:40.
        assertEquals(listOf(at(22, 0), at(22, 10), at(22, 20), at(22, 30), at(22, 40)), tonight.map { it.fireAt })
        assertEquals(listOf(false, false, false, false, true), tonight.map { it.check })
        assertEquals(Duration.ofMinutes(40), ReminderPlan.CHECK_AFTER)
        val check = tonight.last()
        assertTrue(check.important)
        assertEquals(ReminderPlan.MAX_REPEATS + 1, check.repeat)
        assertEquals(ReminderCodes.SLOT_CHECK, check.slot)
    }

    @Test
    fun noCheckWithoutRecipients() {
        val list = upcoming(listOf(glargineSchedule), listOf(glargine), emptyList(), now = at(21, 0), check = false)
        assertTrue(list.none { it.check })
        // Прежнее поведение: основное и три повтора.
        assertEquals(4, list.count { it.dose.planned == at(22) })
    }

    @Test
    fun ordinaryMedicineHasNoCheck() {
        val list = upcoming(listOf(metforminSchedule), listOf(metformin), emptyList(), now = at(13, 0))
        assertTrue(list.none { it.check })
    }

    @Test
    fun markedDoseHasNoCheck() {
        for (status in listOf(EventStatus.Taken, EventStatus.Skipped)) {
            val list = upcoming(
                listOf(glargineSchedule), listOf(glargine), listOf(event(2, at(22), status)), now = at(21, 0),
            )
            assertTrue(list.none { it.dose.planned == at(22) }, "status $status")
        }
    }

    @Test
    fun onlyTheCheckRemainsAfterTheLastRepeat() {
        // 22:35: повторы позади, впереди только проверка в 22:40, потом завтрашний приём.
        val list = upcoming(listOf(glargineSchedule), listOf(glargine), emptyList(), now = at(22, 35))
        assertEquals(at(22, 40), list.first().fireAt)
        assertTrue(list.first().check)
        assertEquals(at(22, 0, 1), list[1].fireAt)
        // После проверки (22:41) — сегодняшний приём больше не встречается.
        val after = upcoming(listOf(glargineSchedule), listOf(glargine), emptyList(), now = at(22, 41))
        assertEquals(at(22, 0, 1), after.first().fireAt)
    }

    @Test
    fun checkOfLateDoseCrossesMidnight() {
        val late = Schedule(11, 2, Plan(PlanKind.Daily, listOf(DoseTime(LocalTime.of(23, 55), 12.0)), day))
        val list = upcoming(listOf(late), listOf(glargine), emptyList(), now = at(0, 2, plusDays = 1))
        val check = list.first { it.check }
        assertEquals(at(0, 35, plusDays = 1), check.fireAt)
        assertEquals(at(23, 55), check.dose.planned)
    }

    @Test
    fun checkCodeIsStableAndDifferentFromRepeats() {
        val a = upcoming(listOf(glargineSchedule), listOf(glargine), emptyList(), now = at(21, 0))
        val b = upcoming(listOf(glargineSchedule), listOf(glargine), emptyList(), now = at(21, 30))
        val key = { r: Reminder -> r.dose.planned to r.slot }
        val byKey = b.associateBy(key)
        for (r in a) assertEquals(r.requestCode, byKey.getValue(key(r)).requestCode)
        assertEquals(a.size, a.map { it.requestCode }.toSet().size)
        val dose = DoseRef(2, 11, at(22), 12.0)
        assertNotEquals(dose.code(ReminderCodes.SLOT_CHECK), dose.code(ReminderPlan.MAX_REPEATS + 1))
        assertNotEquals(dose.code(ReminderCodes.SLOT_CHECK), dose.code(ReminderCodes.SLOT_SNOOZE))
        assertNotEquals(dose.code(ReminderCodes.SLOT_CHECK), dose.code(ReminderCodes.SLOT_SCREEN))
    }

    @Test
    fun copiesOfOtherPeoplesSchedulesAreSilent() {
        val mom = Medicine(3, "Левотироксин", "50 мкг", MedForm.Tablet, "табл.", important = true, profileId = 5)
        val momSchedule = Schedule(12, 3, Plan(PlanKind.Daily, listOf(DoseTime(LocalTime.of(22, 0), 1.0)), day))
        val all = listOf(glargine, mom)
        val schedules = listOf(glargineSchedule, momSchedule)
        // Профиль 5 — копия: ни напоминаний, ни проверки; свои лекарства работают как раньше.
        val list = upcoming(schedules, all, emptyList(), now = at(21, 0), silent = setOf(5L))
        assertTrue(list.isNotEmpty())
        assertTrue(list.all { it.dose.medicineId == 2L })
        assertTrue(list.any { it.check })
        // Без «тихих» профилей мамино лекарство напоминается.
        val loud = upcoming(schedules, all, emptyList(), now = at(21, 0))
        assertTrue(loud.any { it.dose.medicineId == 3L })
    }
}
