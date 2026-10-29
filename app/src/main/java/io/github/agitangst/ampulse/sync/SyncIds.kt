package io.github.agitangst.ampulse.sync

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Идентификаторы записей «зеркала» на сервере (openspec: server-sync). Сервер принимает любые 8–64 знака `A-Za-z0-9_-`;
 * приложение выбирает их так, чтобы они были:
 *
 * - **детерминированными** — одна и та же запись при каждой выгрузке получает один и тот же id, поэтому повтор ничего
 *   не задваивает и не нужна таблица соответствий в базе;
 * - **необратимыми** — id = Base64URL(SHA-256(соль ‖ вид ‖ локальные числа)), первые 22 знака. Соль — 16 случайных
 *   байт, создаётся один раз на телефоне и никуда не уходит; без неё по id нельзя ни узнать номер записи в базе
 *   телефона, ни проверить догадку.
 */
object SyncIds {
    /** Длина id: 22 знака Base64URL — 132 бита, больше чем нужно против совпадений, и в пределах 8–64 сервера. */
    const val LENGTH = 22

    const val SALT_BYTES = 16

    fun newSalt(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(SALT_BYTES).also(random::nextBytes)

    /** Профиль; «Я» — локальный номер 0. */
    fun profile(salt: ByteArray, profileId: Long): String = id(salt, "profile", profileId)

    fun medicine(salt: ByteArray, medicineId: Long): String = id(salt, "med", medicineId)

    /** Приём не по расписанию (лишний, «по необходимости»): по номеру отметки. */
    fun event(salt: ByteArray, eventId: Long): String = id(salt, "event", eventId)

    /** Приём по расписанию: номер схемы и плановое время в минутах Unix (секунды делятся нацело на 60, вниз). */
    fun planned(salt: ByteArray, scheduleId: Long, plannedEpochSecond: Long): String =
        id(salt, "plan", scheduleId, Math.floorDiv(plannedEpochSecond, 60L))

    fun id(salt: ByteArray, kind: String, vararg numbers: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(kind.toByteArray(Charsets.UTF_8))
        for (n in numbers) {
            digest.update(':'.code.toByte())
            digest.update(n.toString().toByteArray(Charsets.UTF_8))
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest()).take(LENGTH)
    }
}
