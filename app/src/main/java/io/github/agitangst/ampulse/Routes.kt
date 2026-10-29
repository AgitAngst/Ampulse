package io.github.agitangst.ampulse

import androidx.compose.runtime.Composable
import io.github.agitangst.ampulse.ui.AsNeededScreen
import io.github.agitangst.ampulse.ui.ExpiryScreen
import io.github.agitangst.ampulse.ui.ImportScreen
import io.github.agitangst.ampulse.ui.InjectionScreen
import io.github.agitangst.ampulse.ui.ProfileEditScreen
import io.github.agitangst.ampulse.ui.ProfilesScreen
import io.github.agitangst.ampulse.ui.ShotGuideScreen
import io.github.agitangst.ampulse.ui.ShotRulesScreen
import io.github.agitangst.ampulse.ui.MedicineEditScreen
import io.github.agitangst.ampulse.ui.PackEditScreen
import io.github.agitangst.ampulse.ui.ScanScreen
import io.github.agitangst.ampulse.ui.StockDetailScreen
import io.github.agitangst.anvil.compose.AnvilSettings

/**
 * Экраны поверх вкладок. Кодируются строкой, поэтому стек переживает пересоздание экрана (смену языка, поворот).
 * Новый экран — новый вариант здесь, строка в [encode]/[decode] и ветка в [RouteContent].
 */
sealed interface Route {
    data object Settings : Route

    /** Новое лекарство (`id` = 0) или правка. */
    data class MedicineEdit(val id: Long) : Route

    /** «По необходимости» (макет, экран 11). */
    data class AsNeeded(val medicineId: Long) : Route

    /** Запас лекарства: упаковки, сроки (макет, экран 5). */
    data class Stock(val medicineId: Long) : Route

    /** Новая упаковка (`packId` = 0) или правка; `fromScan` — поля из кода «Честного знака» (`PendingScan`). */
    data class PackEdit(val medicineId: Long, val packId: Long, val fromScan: Boolean = false) : Route

    /** «Сроки годности» (макет, экран 40). */
    data object Expiry : Route

    /** Сканер упаковки (макет, экран 14). */
    data object Scan : Route

    /** Назначение → план: текст, фото, голос, файл (макет, экраны 7–9). Текст из «Поделиться» — `PendingImport`. */
    data object Import : Route

    /**
     * Место укола перед отметкой (макет, экраны 3, 17–18): ротация мест профиля. `plannedAt` — время приёма по схеме
     * (эпоха, мс); `null` — укол вне расписания.
     */
    data class Injection(val medicineId: Long, val plannedAt: Long?) : Route

    /** Укол пошагово (макет, экраны 44–45): подкожно ручкой или в мышцу — по лекарству. */
    data class ShotGuide(val medicineId: Long, val plannedAt: Long?) : Route

    /** Правила уколов (макет, экран 46). */
    data object ShotRules : Route

    /** Профили: «Я», близкие, питомцы. */
    data object Profiles : Route

    /** Новый профиль (`id` = 0) или правка. */
    data class ProfileEdit(val id: Long) : Route

    fun encode(): String = when (this) {
        Settings -> "settings"
        is MedicineEdit -> "med:$id"
        is AsNeeded -> "prn:$medicineId"
        is Stock -> "stock:$medicineId"
        is PackEdit -> "pack:$medicineId:$packId:${if (fromScan) 1 else 0}"
        Expiry -> "expiry"
        Scan -> "scan"
        Import -> "import"
        is Injection -> "inj:$medicineId:${plannedAt ?: ""}"
        is ShotGuide -> "shot:$medicineId:${plannedAt ?: ""}"
        ShotRules -> "shotrules"
        Profiles -> "profiles"
        is ProfileEdit -> "profile:$id"
    }

    companion object {
        fun decode(text: String): Route? {
            val (name, arg) = text.split(':', limit = 2).let { it[0] to it.getOrNull(1) }
            return when (name) {
                "settings" -> Settings
                "med" -> arg?.toLongOrNull()?.let(::MedicineEdit)
                "prn" -> arg?.toLongOrNull()?.let(::AsNeeded)
                "stock" -> arg?.toLongOrNull()?.let(::Stock)
                "pack" -> arg?.split(':')?.takeIf { it.size == 3 }?.let { (m, p, s) ->
                    val med = m.toLongOrNull() ?: return null
                    PackEdit(med, p.toLongOrNull() ?: 0, s == "1")
                }
                "expiry" -> Expiry
                "scan" -> Scan
                "import" -> Import
                "inj", "shot" -> arg?.split(':')?.takeIf { it.size == 2 }?.let { (m, p) ->
                    val med = m.toLongOrNull() ?: return null
                    if (name == "inj") Injection(med, p.toLongOrNull()) else ShotGuide(med, p.toLongOrNull())
                }
                "shotrules" -> ShotRules
                "profiles" -> Profiles
                "profile" -> arg?.toLongOrNull()?.let(::ProfileEdit)
                else -> null
            }
        }
    }
}

/** Переходы: открыть экран поверх или вернуться назад. */
class Nav(private val push: (Route) -> Unit, private val pop: () -> Unit) {
    fun open(route: Route) = push(route)

    fun back() = pop()
}

/** Содержимое экрана поверх вкладок. */
@Composable
fun RouteContent(route: Route, nav: Nav, settings: AnvilSettings) {
    when (route) {
        Route.Settings -> SettingsScreen(settings, onBack = nav::back)
        is Route.MedicineEdit -> MedicineEditScreen(route.id, nav)
        is Route.AsNeeded -> AsNeededScreen(route.medicineId, nav)
        is Route.Stock -> StockDetailScreen(route.medicineId, nav)
        is Route.PackEdit -> PackEditScreen(route.medicineId, route.packId, route.fromScan, nav)
        Route.Expiry -> ExpiryScreen(nav)
        Route.Scan -> ScanScreen(nav)
        Route.Import -> ImportScreen(nav)
        is Route.Injection -> InjectionScreen(route.medicineId, route.plannedAt, nav)
        is Route.ShotGuide -> ShotGuideScreen(route.medicineId, route.plannedAt, nav)
        Route.ShotRules -> ShotRulesScreen(nav)
        Route.Profiles -> ProfilesScreen(nav)
        is Route.ProfileEdit -> ProfileEditScreen(route.id, nav)
    }
}
