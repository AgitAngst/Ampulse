package io.github.agitangst.ampulse.ui

import io.github.agitangst.ampulse.domain.DoseTime
import io.github.agitangst.ampulse.domain.MedForm
import io.github.agitangst.ampulse.domain.Medicine
import io.github.agitangst.ampulse.domain.Plan
import io.github.agitangst.ampulse.domain.PlanKind
import io.github.agitangst.ampulse.domain.Prescription
import io.github.agitangst.ampulse.domain.Routine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.Charset
import java.time.LocalDate
import java.time.LocalTime

/** Логика экрана «Назначение → план»: совпадение с лекарством из списка, итог «сколько купить», чтение файла. */
class ImportModelTest {
    private val start = LocalDate.of(2026, 10, 29)
    private val routine = Routine()
    private val metformin = Medicine(1, "Метформин", "850 мг", MedForm.Tablet, "табл.")
    private val glargine = Medicine(2, "Инсулин гларгин", "12 ЕД", MedForm.Injection, "ЕД", important = true, note = "утро")

    private val unitFor: (MedForm) -> String = { form ->
        when (form) {
            MedForm.Tablet -> "табл."
            MedForm.Capsule -> "капс."
            MedForm.Injection -> "ЕД"
            MedForm.Drops -> "кап."
            MedForm.Liquid -> "мл"
            MedForm.Other -> "шт."
        }
    }

    private fun parse(line: String) = Prescription.parse(line)!!

    private fun planOf(line: String, perDay: Int?, existing: Medicine? = null): ImportPlan {
        val row = ImportRow(parse(line), perDay, existing)
        return planFor(row, start, routine, unitFor)!!
    }

    @Test
    fun matchIgnoresCaseSpacesAndYo() {
        assertEquals(metformin, matchExisting("  МЕТФОРМИН ", listOf(glargine, metformin)))
        val yolka = Medicine(3, "Ёлка", "", MedForm.Other, "шт.")
        assertEquals(yolka, matchExisting("елка", listOf(yolka)))
    }

    @Test
    fun matchNeedsTheWholeName() {
        assertNull(matchExisting("Метформин Канон", listOf(metformin)))
        assertNull(matchExisting("Метфор", listOf(metformin)))
        assertNull(matchExisting("", listOf(metformin)))
    }

    @Test
    fun matchSkipsTakenMedicines() {
        assertNull(matchExisting("Метформин", listOf(metformin), setOf(1L)))
    }

    @Test
    fun reviewRowsTakeFrequencyFromTextOrAnswer() {
        val parsed = Prescription.parseAll("Метформин 500 мг 2 р/д, 30 дней\nМексидол 2 мл в/м, 10 дней")
        val (met, mex) = parsed

        val rows = reviewRows(parsed, emptySet(), emptyMap(), listOf(metformin))
        assertEquals(2, rows.size)
        assertEquals(2, rows[0].perDay)
        assertFalse(rows[0].asked)
        assertEquals(metformin, rows[0].existing)
        assertNull(rows[1].perDay)
        assertTrue(rows[1].isOpen)
        assertTrue(rows[1].asked)
        assertNull(rows[1].existing)

        // Ответ по исходной строке; убранная строка из проверки исчезает.
        val answered = reviewRows(parsed, setOf(met.source), mapOf(mex.source to 3), listOf(metformin))
        assertEquals(1, answered.size)
        assertEquals(3, answered[0].perDay)
        assertFalse(answered[0].isOpen)
        assertTrue(answered[0].asked)
    }

    @Test
    fun absurdFrequencyIsAskedAgain() {
        val parsed = Prescription.parseAll("Мексидол 2 мл 0 р/д, 5 дней")
        assertEquals(0, parsed[0].timesPerDay)
        val row = reviewRows(parsed, emptySet(), emptyMap(), emptyList())[0]
        assertTrue(row.asked)
        assertTrue(row.isOpen)
        assertEquals(2, reviewRows(parsed, emptySet(), mapOf(parsed[0].source to 2), emptyList())[0].perDay)
    }

    @Test
    fun oneExistingMedicineIsNotClaimedTwice() {
        val parsed = Prescription.parseAll("Метформин 500 мг 2 р/д, 10 дней\nМетформин 850 мг 1 р/д, 10 дней")
        val rows = reviewRows(parsed, emptySet(), emptyMap(), listOf(metformin))
        assertEquals(metformin, rows[0].existing)
        assertNull(rows[1].existing)
    }

    @Test
    fun doseDiffersOnlyWhenBothKnownAndUnequal() {
        assertTrue(doseDiffers(glargine, parse("Инсулин гларгин 14 ЕД п/к на ночь")))
        assertFalse(doseDiffers(glargine, parse("Инсулин гларгин 12 ЕД п/к на ночь")))
        assertFalse(doseDiffers(glargine, parse("Инсулин гларгин п/к на ночь")))
        assertFalse(doseDiffers(glargine.copy(strength = ""), parse("Инсулин гларгин 14 ЕД п/к на ночь")))
    }

    @Test
    fun injectionUnitFollowsTheDoseInTheText() {
        assertEquals("мг", medicineOf(parse("Мексидол 125 мг в/м, 5 дней"), unitFor).unit)
        assertEquals("мл", medicineOf(parse("Мексидол 2 мл в/м, 10 дней"), unitFor).unit)
        assertEquals("ЕД", medicineOf(parse("Инсулин гларгин 14 ЕД п/к на ночь"), unitFor).unit)
        // Дозы нет — «1 шт.», а не выдуманные миллилитры.
        assertEquals("шт.", medicineOf(parse("Цитрамон в/м"), unitFor).unit)
        assertEquals("капс.", medicineOf(parse("Амоксициллин 500 мг по 1 капс. 3 р/д, 7 дней"), unitFor).unit)
    }

    @Test
    fun planForExistingKeepsIdentityAndTakesNewDose() {
        val ip = planOf("Инсулин гларгин 14 ЕД п/к на ночь", 1, glargine)
        assertEquals(2L, ip.medicine.id)
        assertEquals("14 ЕД", ip.medicine.strength)
        assertTrue(ip.medicine.important)
        assertEquals("утро", ip.medicine.note)
        assertEquals(listOf(LocalTime.of(22, 0)), ip.plan.times.map { it.time })
        assertEquals(14.0, ip.plan.times.single().amount)
    }

    @Test
    fun planForExistingWithoutFormKeepsTheOldShape() {
        // В тексте нет «таб.» и «капс.» — форма и единица остаются прежними, дозировка берётся из назначения.
        val ip = planOf("Метформин 500 мг 2 р/д, 30 дней", 2, metformin)
        assertEquals(1L, ip.medicine.id)
        assertEquals(MedForm.Tablet, ip.medicine.form)
        assertEquals("табл.", ip.medicine.unit)
        assertEquals("500 мг", ip.medicine.strength)
    }

    @Test
    fun planForNewMedicineGetsIdZeroAndNeedsFrequency() {
        assertEquals(0L, planOf("Амоксициллин 500 мг по 1 капс. 3 р/д, 7 дней", 3).medicine.id)
        assertNull(planFor(ImportRow(parse("Мексидол 2 мл в/м, 10 дней"), null, null), start, routine, unitFor))
    }

    @Test
    fun courseTotalOfDailyCourse() {
        val total = courseTotal(planOf("Амоксициллин 500 мг по 1 капс. 3 р/д, 7 дней", 3).plan)
        assertEquals(21, total.doses)
        assertEquals(21.0, total.amount)
        assertEquals(7, total.days)
        assertFalse(total.monthly)
    }

    @Test
    fun courseTotalOfTaperIsSumOfSteps() {
        val line = "Преднизолон 5 мг по 4 таб. утром после еды 3 дня, затем по 3 таб. 3 дня, затем по 2 таб. 3 дня, затем по 1 таб. 3 дня"
        val total = courseTotal(planOf(line, 1).plan)
        assertEquals(12, total.doses)
        assertEquals(30.0, total.amount)
        assertEquals(12, total.days)
    }

    @Test
    fun courseTotalOfInjectionIsDoseTimesCount() {
        val total = courseTotal(planOf("Мексидол 2 мл в/м, 10 дней", 1).plan)
        assertEquals(10, total.doses)
        assertEquals(20.0, total.amount)
    }

    @Test
    fun courseTotalOfEveryOtherDay() {
        val total = courseTotal(planOf("Метформин 500 мг через день, 10 дней", 1).plan)
        assertEquals(5, total.doses)
        assertEquals(5.0, total.amount)
    }

    @Test
    fun courseWithoutEndIsCountedForAMonth() {
        val total = courseTotal(planOf("Инсулин гларгин 14 ЕД п/к на ночь", 1).plan)
        assertTrue(total.monthly)
        assertEquals(30, total.days)
        assertEquals(30, total.doses)
        assertEquals(420.0, total.amount)
    }

    @Test
    fun daySlotsGroupByTimeAndCountDoses() {
        val row = ImportRow(parse("Аспирин 100 мг 1 р/д"), 1, null)
        val med = Medicine(0, "Аспирин", "100 мг", MedForm.Tablet, "табл.")
        val a = ImportPlan(row, med, Plan(PlanKind.Daily, listOf(DoseTime(LocalTime.of(20, 0), 1.0), DoseTime(LocalTime.of(8, 0), 1.0)), start))
        val b = ImportPlan(row, med, Plan(PlanKind.Daily, listOf(DoseTime(LocalTime.of(20, 0), 1.0), DoseTime(LocalTime.of(7, 0), 1.0)), start))
        val slots = daySlots(listOf(a, b))
        assertEquals(listOf(LocalTime.of(7, 0), LocalTime.of(8, 0), LocalTime.of(20, 0)), slots.map { it.time })
        assertEquals(2, slots.last().entries.size)
        assertEquals(4, dosesPerDay(listOf(a, b)))
    }

    @Test
    fun repeatedTimeInOnePlanIsOneDose() {
        // Завтрак и обед в одно время: три «приёма с едой» дали бы две записи на одно время.
        val same = Routine(breakfast = LocalTime.of(12, 0), lunch = LocalTime.of(12, 0))
        val p = parse("Тест 500 мг по 1 таб. 3 р/д после еды, 5 дней")
        val ip = planFor(ImportRow(p, 3, null), start, same, unitFor)!!
        assertEquals(listOf(LocalTime.of(12, 30), LocalTime.of(19, 30)), ip.plan.times.map { it.time })
    }

    @Test
    fun spreadHoursOnlyForEvenlySpacedDoses() {
        val three = parse("Тест 500 мг по 1 таб. 3 р/д, 5 дней")
        assertEquals(8, spreadHours(three, Prescription.timesFor(three, 3, routine)))
        val two = parse("Тест 500 мг по 1 таб. 2 р/д, 5 дней")
        assertEquals(12, spreadHours(two, Prescription.timesFor(two, 2, routine)))
        val meals = parse("Тест 500 мг по 1 таб. 3 р/д после еды, 5 дней")
        assertEquals(0, spreadHours(meals, Prescription.timesFor(meals, 3, routine)))
        val once = parse("Тест 500 мг по 1 таб. 1 р/д, 5 дней")
        assertEquals(0, spreadHours(once, Prescription.timesFor(once, 1, routine)))
    }

    @Test
    fun routineNeedsWakeBeforeSleep() {
        assertTrue(Routine().isValid())
        assertFalse(Routine(wake = LocalTime.of(8, 0), sleep = LocalTime.of(0, 30)).isValid())
        assertFalse(Routine(wake = LocalTime.of(8, 0), sleep = LocalTime.of(8, 0)).isValid())
    }

    @Test
    fun appendTextStartsANewLine() {
        assertEquals("a", appendText("", "  a \n"))
        assertEquals("a\nb", appendText("a\n", "b"))
        assertEquals("a", appendText("a", "   "))
        assertEquals("a\nb", appendText("a", "b"))
    }

    @Test
    fun decodeTextHandlesUtf8Utf16AndWindows1251() {
        val text = "Амоксициллин 500 мг по 1 капс. 3 р/д, 7 дней"
        assertEquals(text, decodeText(text.toByteArray(Charsets.UTF_8)))
        assertEquals(text, decodeText(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)))
        assertEquals(text, decodeText(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)))
        assertEquals(text, decodeText(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)))
        assertEquals(text, decodeText(text.toByteArray(Charset.forName("windows-1251"))))
        assertEquals("Amoxicillin 500 mg", decodeText("Amoxicillin 500 mg".toByteArray(Charsets.US_ASCII)))
        assertNotNull(decodeText(ByteArray(0)))
    }
}
