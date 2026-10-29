package io.github.agitangst.ampulse.domain

import java.time.LocalDateTime

/** Откуда замер: введён руками, с фото экрана тонометра, голосом или принят по Bluetooth. */
enum class ReadingSource { Manual, Photo, Voice, Bluetooth }

/** Замер, принятый с тонометра: давление в мм рт. ст., пульс и время — если тонометр их прислал. */
data class DeviceReading(val sys: Int, val dia: Int, val pulse: Int?, val at: LocalDateTime?)

/**
 * Тонометры по Bluetooth LE — стандартный профиль Blood Pressure (служба 0x1810, характеристика 0x2A35, индикации).
 * Разбор пакета — без Android, чтобы проверять тестами.
 */
object BloodPressureGatt {
    const val SERVICE = "00001810-0000-1000-8000-00805f9b34fb"
    const val MEASUREMENT = "00002a35-0000-1000-8000-00805f9b34fb"
    const val CCC_DESCRIPTOR = "00002902-0000-1000-8000-00805f9b34fb"

    private const val KPA_TO_MMHG = 7.50062

    /** Разобрать значение характеристики 0x2A35; `null` — пакет короткий или значения нет (NaN, NRes). */
    fun parse(b: ByteArray): DeviceReading? {
        if (b.size < 7) return null
        val flags = b[0].toInt() and 0xFF
        val kpa = flags and 0x01 != 0
        val hasTime = flags and 0x02 != 0
        val hasPulse = flags and 0x04 != 0
        fun mmHg(v: Double) = Math.round(if (kpa) v * KPA_TO_MMHG else v).toInt()
        val sys = sfloat(b, 1) ?: return null
        val dia = sfloat(b, 3) ?: return null
        var i = 7
        var at: LocalDateTime? = null
        if (hasTime) {
            if (b.size < i + 7) return null
            val year = u16(b, i)
            at = runCatching { LocalDateTime.of(year, b[i + 2].toInt(), b[i + 3].toInt(), b[i + 4].toInt(), b[i + 5].toInt(), b[i + 6].toInt()) }
                .getOrNull()?.takeIf { year > 0 }
            i += 7
        }
        val pulse = if (hasPulse && b.size >= i + 2) sfloat(b, i)?.let { Math.round(it).toInt() } else null
        return DeviceReading(mmHg(sys), mmHg(dia), pulse, at)
    }

    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    /** IEEE-11073 SFLOAT: 4 бита степени десяти и 12 бит мантиссы, оба со знаком; особые значения — `null`. */
    fun sfloat(b: ByteArray, i: Int): Double? {
        val raw = u16(b, i)
        val mantissaRaw = raw and 0x0FFF
        if (mantissaRaw in 0x07FE..0x0802 && (raw shr 12) == 0) return null
        val mantissa = if (mantissaRaw >= 0x0800) mantissaRaw - 0x1000 else mantissaRaw
        val expRaw = raw shr 12
        val exp = if (expRaw >= 0x8) expRaw - 0x10 else expRaw
        return mantissa * Math.pow(10.0, exp.toDouble())
    }
}

/**
 * Числа замера из текста — фото экрана тонометра или голос: «128 на 82, пульс 71», «128/82 71», «SYS 128 DIA 82 PUL 71».
 * Берутся первые правдоподобные: верхнее 60–260, нижнее 30–160 и меньше верхнего, пульс 30–220.
 */
fun readingFromText(text: String): DeviceReading? {
    val n = Regex("""\d{2,3}""").findAll(text).map { it.value.toInt() }.toList()
    for (i in 0 until n.size - 1) {
        val sys = n[i]
        val dia = n[i + 1]
        if (validate(sys, dia, null) == null) {
            val pulse = n.getOrNull(i + 2)?.takeIf { it in 30..220 }
            return DeviceReading(sys, dia, pulse, null)
        }
    }
    return null
}
