package io.github.agitangst.ampulse.domain

import java.time.LocalDate
import java.time.LocalDateTime

/** Замер давления (макет, экраны 26–29). Пульс — по желанию. */
data class Reading(val id: Long, val sys: Int, val dia: Int, val pulse: Int?, val at: LocalDateTime, val note: String = "")

/** Цель врача; по умолчанию — ниже 135/85 (домашние замеры). */
data class Target(val sys: Int = 135, val dia: Int = 85)

/** Оценка — относительно цели врача; критично и низко — цвет внимания, слово и «звоните 103 или 112». */
enum class PressureState { Within, Above, Crisis, Low }

fun classify(sys: Int, dia: Int, target: Target = Target()): PressureState = when {
    sys >= 180 || dia >= 110 -> PressureState.Crisis
    sys < 90 || dia < 60 -> PressureState.Low
    sys >= target.sys || dia >= target.dia -> PressureState.Above
    else -> PressureState.Within
}

/** Проверка ввода: верхнее больше нижнего, числа правдоподобные. */
sealed interface ReadingError {
    data object Order : ReadingError
    data object Range : ReadingError
}

fun validate(sys: Int, dia: Int, pulse: Int?): ReadingError? = when {
    sys <= dia -> ReadingError.Order
    sys !in 60..260 || dia !in 30..160 || (pulse != null && pulse !in 30..220) -> ReadingError.Range
    else -> null
}

/**
 * Клавиатура замера (docs/ui-guideline.md, «Цифровая клавиатура»): сколько цифр в поле — верхнее 3, если начинается
 * с 1 или 2; нижнее и пульс — 3, если начинаются с 1; иначе 2. Набрано — поле переключается само.
 */
fun maxDigits(field: Int, value: String): Int {
    val first = value.firstOrNull()
    return if (field == 0) (if (first == '1' || first == '2') 3 else 2) else (if (first == '1') 3 else 2)
}

/** Среднее серии замеров (двух подряд), с округлением. */
fun average(readings: List<Reading>): Triple<Int, Int, Int?>? {
    if (readings.isEmpty()) return null
    val pulses = readings.mapNotNull { it.pulse }
    return Triple(
        Math.round(readings.map { it.sys }.average()).toInt(),
        Math.round(readings.map { it.dia }.average()).toInt(),
        if (pulses.isEmpty()) null else Math.round(pulses.average()).toInt(),
    )
}

/** Утро — до 12:00, вечер — с 17:00 (макет, экран 27). */
fun isMorning(r: Reading) = r.at.hour < 12
fun isEvening(r: Reading) = r.at.hour >= 17

/** Подсказка «вечером выше цели в N из M дней». */
data class Insight(val eveningAbove: Int, val eveningDays: Int, val morningAbove: Int, val morningDays: Int)

fun insight(readings: List<Reading>, target: Target = Target()): Insight {
    fun count(filter: (Reading) -> Boolean): Pair<Int, Int> {
        val byDay: Map<LocalDate, List<Reading>> = readings.filter(filter).groupBy { it.at.toLocalDate() }
        val above = byDay.count { (_, day) -> average(day)!!.let { classify(it.first, it.second, target) != PressureState.Within } }
        return above to byDay.size
    }
    val (ea, ed) = count(::isEvening)
    val (ma, md) = count(::isMorning)
    return Insight(ea, ed, ma, md)
}
