package io.github.agitangst.ampulse.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** Образцы — те же, что в макете (экраны 7–9). */
class PrescriptionTest {
    private val start = LocalDate.of(2026, 9, 26)

    @Test
    fun russianSample() {
        val list = Prescription.parseAll(
            """
            1. Амоксициллин 500 мг по 1 капс. 3 р/д, 7 дней
            2. Омепразол 20 мг утром натощак за 30 мин до еды, 14 дней
            3. Преднизолон 5 мг по 4 таб. утром после еды 3 дня, затем по 3 таб. 3 дня, затем по 2 таб. 3 дня, затем по 1 таб. 3 дня
            4. Мексидол 2 мл в/м, 10 дней
            5. Инсулин гларгин 14 ЕД п/к на ночь
            """.trimIndent(),
        )
        assertEquals(5, list.size)
        val (amox, omep, pred, mex, ins) = list
        assertEquals("Амоксициллин", amox.name)
        assertEquals("500 мг", amox.strength)
        assertEquals(3, amox.timesPerDay)
        assertEquals(MedForm.Capsule, amox.form)
        assertEquals(7, amox.days)

        assertEquals(FoodRule.EmptyStomach, omep.food)
        assertEquals(1, omep.timesPerDay)
        assertEquals(listOf(LocalTime.of(7, 0)), Prescription.timesFor(omep, 1))

        assertEquals(listOf(4.0, 3.0, 2.0, 1.0), pred.steps!!.map { it.amount })
        assertEquals(12, pred.days)
        val predPlan = Prescription.plan(pred, 1, start)
        assertEquals(PlanKind.Taper, predPlan.kind)
        assertEquals(LocalTime.of(8, 0), predPlan.times.single().time)

        assertEquals(Route.Intramuscular, mex.route)
        assertNull(mex.timesPerDay)
        assertFalse(mex.found.frequency)

        assertEquals(Route.Subcutaneous, ins.route)
        assertEquals(TimeHint.Night, ins.hint)
        assertTrue(ins.found.duration.not())
        val insPlan = Prescription.plan(ins, 1, start)
        assertEquals(14.0, insPlan.times.single().amount)
        assertEquals(LocalTime.of(22, 0), insPlan.times.single().time)
        assertEquals("ЕД", Prescription.medicine(ins, ru = true).unit)
        assertNull(insPlan.end)
    }

    @Test
    fun englishSample() {
        val list = Prescription.parseAll(
            """
            1. Amoxicillin 500 mg 1 cap 3 times a day, 7 days
            2. Omeprazole 20 mg in the morning on an empty stomach 30 min before meals, 14 days
            3. Prednisolone 5 mg 4 tabs in the morning after meals 3 days, then 3 tabs 3 days, then 2 tabs 3 days, then 1 tab 3 days
            4. Ibuprofen 400 mg b.i.d. with food, 5 days
            5. Insulin glargine 14 U subcut at bedtime
            """.trimIndent(),
        )
        assertEquals(listOf("Amoxicillin", "Omeprazole", "Prednisolone", "Ibuprofen", "Insulin glargine"), list.map { it.name })
        assertEquals(3, list[0].timesPerDay)
        assertEquals(FoodRule.EmptyStomach, list[1].food)
        assertEquals(4, list[2].steps!!.size)
        assertEquals(2, list[3].timesPerDay)
        assertEquals(FoodRule.With, list[3].food)
        assertEquals(listOf(LocalTime.of(7, 30), LocalTime.of(19, 0)), Prescription.timesFor(list[3], 2))
        assertEquals("14 U", list[4].strength)
        assertEquals(Route.Subcutaneous, list[4].route)
    }

    @Test
    fun spreadAndEveryOtherDay() {
        val p = Prescription.parse("Аспирин 100 мг 3 раза в день через день")!!
        assertTrue(p.everyOtherDay)
        assertEquals(listOf(LocalTime.of(7, 0), LocalTime.of(15, 0), LocalTime.of(23, 0)), Prescription.timesFor(p, 3))
        assertEquals(PlanKind.EveryNDays, Prescription.plan(p, 3, start).kind)
        assertNull(Prescription.parse("12"))
    }

    @Test
    fun insulinUnitsWithoutRoute() {
        val ru = Prescription.parse("Инсулин аспарт 6 ЕД перед едой 3 р/д")!!
        assertEquals(MedForm.Injection, ru.form)
        assertEquals(6.0, Prescription.plan(ru, 3, start).times.first().amount)
        assertEquals("ЕД", Prescription.medicine(ru, ru = true).unit)
        assertEquals(MedForm.Injection, Prescription.parse("Insulin lispro 6 units before meals three times a day")!!.form)
        // Витамин D в МЕ — не укол.
        assertEquals(MedForm.Other, Prescription.parse("Витамин D 2000 МЕ утром")!!.form)
    }
}
