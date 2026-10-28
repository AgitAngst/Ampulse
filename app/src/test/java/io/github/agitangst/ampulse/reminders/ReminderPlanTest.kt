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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class ReminderPlanTest {
    private val day = LocalDate.of(2026, 9, 26)

    private val metformin = Medicine(1, "Метформин", "850 мг", MedForm.Tablet, "табл.")
    private val glargine = Medicine(2, "Инсулин гларгин", "100 ЕД/мл", MedForm.Injection, "ЕД", important = true)

    private fun at(h: Int, m: Int = 0, plusDays: Long = 0) = day.plusDays(plusDays).atTime(h, m)

    private fun daily(vararg times: LocalTime, start: LocalDate = day) =
        Plan(PlanKind.Daily, times.map { DoseTime(it, 1.0) }, start)

    private val metforminSchedule = Schedule(10, 1, daily(LocalTime.of(8, 30), LocalTime.of(14, 0)))
    private val glargineSchedule = Schedule(11, 2, Plan(PlanKind.Daily, listOf(DoseTime(LocalTime.of(22, 0), 12.0)), day))

    private fun event(medicineId: Long, planned: LocalDateTime, status: EventStatus, scheduleId: Long = 10) =
        DoseEvent(100 + medicineId, medicineId, scheduleId, planned, planned.plusMinutes(3), status, 1.0)

    private fun upcoming(
        schedules: List<Schedule>,
        medicines: List<Medicine>,
        events: List<DoseEvent>,
        now: LocalDateTime,
    ) = ReminderPlan.upcoming(schedules, medicines, events, now)

    @Test
    fun nextTwoDaysInOrder() {
        val list = upcoming(listOf(metforminSchedule), listOf(metformin), emptyList(), now = at(13, 52))
        // 48 часов от 13:52: сегодня 14:00, завтра 8:30 и 14:00, послезавтра 8:30 (14:00 уже за окном).
        assertEquals(listOf(at(14), at(8, 30, 1), at(14, 0, 1), at(8, 30, 2)), list.map { it.fireAt })
        assertTrue(list.all { it.repeat == 0 && !it.important })
        assertEquals(listOf(1L), list.map { it.dose.medicineId }.distinct())
    }

    @Test
    fun horizonEdgeIsInclusive() {
        val schedule = Schedule(10, 1, daily(LocalTime.of(13, 52)))
        // «Сейчас» 13:51: в окно (до 13:51 послезавтра) попадают сегодняшний и завтрашний приёмы, послезавтрашний — нет.
        val before = upcoming(listOf(schedule), listOf(metformin), emptyList(), at(13, 51))
        assertEquals(listOf(at(13, 52), at(13, 52, 1)), before.map { it.fireAt })
        // «Сейчас» 13:52: сегодняшний уже сработал, а послезавтрашний лежит ровно на краю окна (+48 ч) и входит.
        val exact = upcoming(listOf(schedule), listOf(metformin), emptyList(), at(13, 52))
        assertEquals(listOf(at(13, 52, 1), at(13, 52, 2)), exact.map { it.fireAt })
    }

    @Test
    fun pastIsNotScheduled() {
        // Время приёма уже наступило (13:00 при «сейчас» 13:00 и позже) — оно сработало, второй раз не ставится.
        val schedule = Schedule(10, 1, daily(LocalTime.of(13, 0)))
        val list = upcoming(listOf(schedule), listOf(metformin), emptyList(), at(13, 0))
        assertEquals(listOf(at(13, 0, 1), at(13, 0, 2)), list.map { it.fireAt })
    }

    @Test
    fun markedDosesAreSkipped() {
        val events = listOf(
            event(1, at(8, 30), EventStatus.Taken),
            event(1, at(14), EventStatus.Skipped),
        )
        val list = upcoming(listOf(metforminSchedule), listOf(metformin), events, now = at(8, 0))
        assertTrue(list.none { it.dose.planned == at(8, 30) || it.dose.planned == at(14) })
        assertEquals(at(8, 30, 1), list.first().fireAt)
    }

    @Test
    fun extraDoseIsNotAMark() {
        // «Лишний приём» не закрывает плановый.
        val extra = event(1, at(14), EventStatus.Extra)
        val list = upcoming(listOf(metforminSchedule), listOf(metformin), listOf(extra), now = at(13, 0))
        assertEquals(at(14), list.first().fireAt)
    }

    @Test
    fun markOfAnotherMedicineOrTimeDoesNotCount() {
        val events = listOf(
            event(2, at(14), EventStatus.Taken, scheduleId = 11), // другое лекарство в то же время
            event(1, at(14, 5), EventStatus.Taken), // то же лекарство, другое время
        )
        val list = upcoming(listOf(metforminSchedule), listOf(metformin, glargine), events, now = at(13, 0))
        assertEquals(at(14), list.first().fireAt)
    }

    @Test
    fun importantRepeatsEveryTenMinutesThreeTimes() {
        val list = upcoming(listOf(glargineSchedule), listOf(glargine), emptyList(), now = at(21, 0))
        val tonight = list.filter { it.dose.planned == at(22) }
        assertEquals(listOf(at(22, 0), at(22, 10), at(22, 20), at(22, 30)), tonight.map { it.fireAt })
        assertEquals(listOf(0, 1, 2, 3), tonight.map { it.repeat })
        assertTrue(tonight.all { it.important })
    }

    @Test
    fun repeatsContinueUntilMarked() {
        val schedules = listOf(glargineSchedule)
        // 22:05 без отметки: основное напоминание прошло, три повтора ещё впереди.
        val unmarked = upcoming(schedules, listOf(glargine), emptyList(), now = at(22, 5))
        assertEquals(listOf(at(22, 10), at(22, 20), at(22, 30)), unmarked.take(3).map { it.fireAt })
        assertEquals(listOf(1, 2, 3), unmarked.take(3).map { it.repeat })
        // Отметили «принято» — повторов нет. «Пропущено» — тоже.
        for (status in listOf(EventStatus.Taken, EventStatus.Skipped)) {
            val marked = upcoming(schedules, listOf(glargine), listOf(event(2, at(22), status, 11)), now = at(22, 5))
            assertTrue(marked.none { it.dose.planned == at(22) }, "status $status")
        }
        // После последнего повтора (22:30) и 22:31 — только завтрашний приём.
        val after = upcoming(schedules, listOf(glargine), emptyList(), now = at(22, 31))
        assertEquals(at(22, 0, 1), after.first().fireAt)
    }

    @Test
    fun repeatsOfYesterdaysLateDoseCrossMidnight() {
        val late = Schedule(11, 2, Plan(PlanKind.Daily, listOf(DoseTime(LocalTime.of(23, 55), 12.0)), day))
        // Приём 26-го в 23:55 (вчера), «сейчас» 27-го 00:02 — повторы в 00:05, 00:15, 00:25 ещё впереди.
        val now = at(0, 2, plusDays = 1)
        val list = upcoming(listOf(late), listOf(glargine), emptyList(), now)
        assertEquals(listOf(at(0, 5, 1), at(0, 15, 1), at(0, 25, 1)), list.take(3).map { it.fireAt })
        assertEquals(listOf(at(23, 55)), list.take(3).map { it.dose.planned }.distinct())
    }

    @Test
    fun regularMedicineHasNoRepeats() {
        val list = upcoming(listOf(metforminSchedule), listOf(metformin), emptyList(), now = at(0, 1))
        assertTrue(list.all { it.repeat == 0 })
    }

    @Test
    fun pausedAsNeededAndUnknownMedicineGiveNothing() {
        val paused = Schedule(10, 1, daily(LocalTime.of(14, 0)).copy(paused = true))
        val asNeeded = Schedule(12, 3, Plan(PlanKind.AsNeeded, emptyList(), day, prnIntervalHours = 6))
        val ghost = Schedule(13, 99, daily(LocalTime.of(14, 0)))
        val list = upcoming(listOf(paused, asNeeded, ghost), listOf(metformin, Medicine(3, "Ибупрофен", "200 мг", MedForm.Tablet, "табл.")), emptyList(), at(9))
        assertTrue(list.isEmpty())
    }

    @Test
    fun codesAreStableAndDistinct() {
        val schedules = listOf(metforminSchedule, glargineSchedule)
        val medicines = listOf(metformin, glargine)
        val a = upcoming(schedules, medicines, emptyList(), now = at(13, 52))
        // Код не зависит от «сейчас»: тот же приём в другой момент пересчёта даёт тот же код.
        val b = upcoming(schedules, medicines, emptyList(), now = at(13, 30))
        val byKey = b.associateBy { it.dose.medicineId to (it.dose.planned to it.repeat) }
        for (r in a) assertEquals(r.requestCode, byKey.getValue(r.dose.medicineId to (r.dose.planned to r.repeat)).requestCode)
        // Все коды в одном плане разные.
        assertEquals(a.size, a.map { it.requestCode }.toSet().size)
        // Разные лекарства в одно время и разные ячейки одного приёма — разные коды.
        val d1 = DoseRef(1, 10, at(14), 1.0)
        val d2 = DoseRef(2, 11, at(14), 1.0)
        assertNotEquals(d1.code(0), d2.code(0))
        assertNotEquals(d1.code(0), d1.code(1))
        assertNotEquals(d1.code(ReminderCodes.SLOT_SNOOZE), d1.code(ReminderCodes.SLOT_TAKE))
        assertEquals(d1.code(0), DoseRef(1, 99, at(14), 5.0).code(0)) // схема и доза в код не входят
        assertEquals(d1.notificationId, d1.code(0))
    }

    @Test
    fun codesDoNotCollideAcrossManyDoses() {
        // Лекарства 1..5, 40 дней, четыре приёма в день, все ячейки — коллизий нет, в том числе при сдвиге времени
        // на 31 минуту между лекарствами (так ломается простая полиномиальная свёртка).
        val codes = HashSet<Int>()
        var total = 0
        for (medicine in 1L..5L) {
            for (dayShift in 0L..39L) {
                for (hour in listOf(8, 12, 18, 22)) {
                    for (slot in 0..3) {
                        codes += DoseRef(medicine, 1, at(hour, 0, dayShift).plusMinutes(medicine * 31), 1.0).code(slot)
                        total++
                    }
                }
            }
        }
        assertEquals(total, codes.size)
    }

    @Test
    fun capsTheNumberOfAlarms() {
        // Приём каждые полчаса у важного лекарства: за двое суток это сотни напоминаний — берутся ближайшие.
        val times = (0 until 48).map { LocalTime.of(it / 2, (it % 2) * 30) }
        val busy = Schedule(11, 2, Plan(PlanKind.Daily, times.map { DoseTime(it, 1.0) }, day))
        val list = upcoming(listOf(busy), listOf(glargine), emptyList(), now = at(0, 1))
        assertEquals(ReminderPlan.MAX_ALARMS, list.size)
        assertEquals(list.sortedBy { it.fireAt }, list)
        assertEquals(at(0, 10), list.first().fireAt)
    }
}
