package io.github.agitangst.ampulse.ble

import io.github.agitangst.ampulse.domain.DeviceReading

/**
 * Тонометры часто присылают сохранённые замеры пачкой, а не один. Из пачки нужен последний: по времени замера, если
 * его прислали, иначе — тот, что пришёл последним. Без Android, чтобы проверять тестами.
 */
class ReadingBatch {
    private val items = ArrayList<DeviceReading>()

    /** Сколько замеров пришло. */
    val size: Int get() = items.size

    fun add(reading: DeviceReading) {
        items += reading
    }

    fun latest(): DeviceReading? = latestReading(items)
}

/**
 * Последний замер из пачки [readings] (в порядке прихода): с самым поздним временем; при равном времени — пришедший
 * позже. Замеры без времени, пока есть хоть один со временем, не учитываются: часы у тонометра либо есть, либо нет.
 * Времени нет ни у одного — берётся последний пришедший. Пусто — `null`.
 */
fun latestReading(readings: List<DeviceReading>): DeviceReading? {
    if (readings.isEmpty()) return null
    val timed = readings.withIndex().filter { it.value.at != null }
    if (timed.isEmpty()) return readings.last()
    return timed.maxWithOrNull(compareBy<IndexedValue<DeviceReading>>({ it.value.at }, { it.index }))?.value
}
