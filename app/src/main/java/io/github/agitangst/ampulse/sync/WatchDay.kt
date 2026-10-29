package io.github.agitangst.ampulse.sync

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * «Сегодня» чужого профиля (openspec: server-watch): день из приёмов, которые прислал сервер, без правил схем. Чистая
 * часть без Android. День и время — в поясе профиля; состояния — как `stateOf` в `domain/Day.kt`: «Пора» за 30 минут до
 * и час после времени приёма, дальше «Просрочено».
 */
enum class WatchState { Taken, Skipped, Due, Overdue, Later }

/** Строка дня: приём, его препарат и состояние на момент [WatchDay.now]. */
data class WatchRow(val intake: WatchIntake, val med: WatchMed, val state: WatchState) {
    val planned: Instant get() = Instant.ofEpochSecond(intake.plannedAt)
    val takenAt: Instant? get() = intake.takenAt?.let(Instant::ofEpochSecond)
}

/** День профиля: [date] в поясе [zone], строки по времени, [now] — момент, по которому считались состояния. */
data class WatchDay(val date: LocalDate, val zone: ZoneId, val now: Instant, val rows: List<WatchRow>) {
    val taken: Int get() = rows.count { it.state == WatchState.Taken }
    val total: Int get() = rows.size

    /** Первый просроченный приём. */
    val overdue: WatchRow? get() = rows.firstOrNull { it.state == WatchState.Overdue }

    /** Ближайший впереди: пора или позже. */
    val next: WatchRow? get() = rows.firstOrNull { it.state == WatchState.Due || it.state == WatchState.Later }
}

object WatchToday {
    private val DUE_BEFORE: Duration = Duration.ofMinutes(30)
    private val DUE_AFTER: Duration = Duration.ofMinutes(60)

    /** Состояние приёма на момент [now]. */
    fun stateOf(intake: WatchIntake, now: Instant): WatchState {
        when (intake.status) {
            WatchStatus.TAKEN -> return WatchState.Taken
            WatchStatus.SKIPPED -> return WatchState.Skipped
        }
        val planned = Instant.ofEpochSecond(intake.plannedAt)
        return when {
            now < planned.minus(DUE_BEFORE) -> WatchState.Later
            now <= planned.plus(DUE_AFTER) -> WatchState.Due
            else -> WatchState.Overdue
        }
    }

    /** Пояс профиля; незнакомое название — UTC. */
    fun zoneOf(profile: WatchProfile): ZoneId = runCatching { ZoneId.of(profile.timezone) }.getOrDefault(ZoneId.of("UTC"))

    /**
     * «Сейчас» для чужого профиля, миллисекунды. Связь есть — часы телефона с поправкой на расхождение с сервером в
     * момент последнего обновления. Нет связи (последняя попытка не удалась) — время данных, то есть часы сервера на
     * последнем удачном обновлении: «просрочено» не растёт от того, что данные устарели. Обновлений не было — часы телефона.
     */
    fun nowMillis(cache: WatchCache, phoneNowMs: Long): Long = when {
        !cache.everRefreshed -> phoneNowMs
        cache.error != null -> cache.serverTime * 1000
        else -> phoneNowMs + (cache.serverTime * 1000 - cache.phoneAt)
    }

    /** День профиля [profileId] на момент [nowMs] (по [nowMillis]). Приёмы без препарата в кэше пропускаются. */
    fun build(cache: WatchCache, profileId: String, nowMs: Long): WatchDay? {
        val profile = cache.profiles[profileId] ?: return null
        val zone = zoneOf(profile)
        val now = Instant.ofEpochMilli(nowMs)
        val date = now.atZone(zone).toLocalDate()
        val from = date.atStartOfDay(zone).toEpochSecond()
        val to = date.plusDays(1).atStartOfDay(zone).toEpochSecond()
        val rows = cache.intakes.values
            .filter { it.profileId == profileId && it.plannedAt in from until to }
            .mapNotNull { i -> cache.meds[i.medId]?.let { WatchRow(i, it, stateOf(i, now)) } }
            .sortedWith(compareBy({ it.intake.plannedAt }, { it.med.name.lowercase() }, { it.intake.id }))
        return WatchDay(date, zone, now, rows)
    }
}
