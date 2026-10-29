package io.github.agitangst.ampulse.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Чьи лекарства: человек или питомец (TODO 1 владельца: «учёт для животных»). */
enum class ProfileKind { Person, Cat, Dog, Other }

/** Профиль: «Я», «Мама», кот Барсик. У каждого лекарства — свой профиль; первый — «Я». */
data class Profile(
    val id: Long,
    val name: String,
    val kind: ProfileKind,
    val weightKg: Double? = null,
    /** Копия чужого расписания по QR (этап 0.8, макет экран 12): видно, но напоминаний и отметок нет. */
    val watchOnly: Boolean = false,
)

/**
 * Место укола (макет, экраны 3, 17–18, 44–49). Подкожно — живот, бёдра, плечи; в мышцу — ягодицы и бёдра; у питомцев —
 * холка и бока. Левое и правое — отдельно.
 */
enum class Site(val forPets: Boolean, val intramuscular: Boolean) {
    AbdomenLeft(false, false), AbdomenRight(false, false),
    ThighLeft(false, false), ThighRight(false, false),
    ArmLeft(false, false), ArmRight(false, false),
    ButtockLeft(false, true), ButtockRight(false, true),
    OuterThighLeft(false, true), OuterThighRight(false, true),
    Scruff(true, false), FlankLeft(true, false), FlankRight(true, false),
    PetThighLeft(true, true), PetThighRight(true, true),
}

/** Места для профиля и вида укола. */
fun sitesFor(kind: ProfileKind, intramuscular: Boolean): List<Site> = Site.entries.filter {
    it.forPets == (kind == ProfileKind.Cat || kind == ProfileKind.Dog || kind == ProfileKind.Other) && it.intramuscular == intramuscular
}

/** Место укола с отдыхом: дней с прошлого укола (`null` — сюда ещё не кололи). */
data class SiteRest(val site: Site, val daysSince: Int?) {
    /** «Пусть отдохнёт» — кололи 3 дня назад и меньше. */
    val resting: Boolean get() = daysSince != null && daysSince <= REST_DAYS
}

const val REST_DAYS = 3

/** Отдых мест по прошлым уколам; рекомендуемое — дольше всех отдыхавшее (никогда не коловшееся — первым). */
fun rotation(sites: List<Site>, injections: List<Pair<Site, LocalDateTime>>, today: LocalDate): List<SiteRest> =
    sites.map { site ->
        val last = injections.filter { it.first == site }.maxOfOrNull { it.second }
        SiteRest(site, last?.let { ChronoUnit.DAYS.between(it.toLocalDate(), today).toInt() })
    }

fun recommended(rest: List<SiteRest>): Site? =
    rest.maxWithOrNull(compareBy<SiteRest> { it.daysSince == null }.thenBy { it.daysSince ?: 0 })?.site

/** Объёмы в мышцу (памятка, экран 46): не больше — врач и инструкция главнее. */
fun maxIntramuscularMl(site: Site): Double? = when (site) {
    Site.ButtockLeft, Site.ButtockRight, Site.OuterThighLeft, Site.OuterThighRight -> 5.0
    else -> null
}

/** Отсчёт при введении в мышцу: около 10 секунд на 1 мл; подкожно ручкой — держать 10 секунд. */
fun injectSeconds(intramuscular: Boolean, ml: Double): Int = if (intramuscular) maxOf(10, Math.round(ml * 10).toInt()) else 10
