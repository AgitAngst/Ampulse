package io.github.agitangst.ampulse

import androidx.compose.runtime.Composable
import io.github.agitangst.ampulse.ui.AsNeededScreen
import io.github.agitangst.ampulse.ui.ExpiryScreen
import io.github.agitangst.ampulse.ui.ImportScreen
import io.github.agitangst.ampulse.ui.CareScanScreen
import io.github.agitangst.ampulse.ui.ContactEditScreen
import io.github.agitangst.ampulse.ui.EmergencyEditScreen
import io.github.agitangst.ampulse.ui.EmergencyScreen
import io.github.agitangst.ampulse.ui.InjectionScreen
import io.github.agitangst.ampulse.ui.ReportScreen
import io.github.agitangst.ampulse.ui.ServerScreen
import io.github.agitangst.ampulse.ui.WatchCodeScreen
import io.github.agitangst.ampulse.ui.WatchListScreen
import io.github.agitangst.ampulse.ui.ShareQrScreen
import io.github.agitangst.ampulse.ui.PressureEntryScreen
import io.github.agitangst.ampulse.ui.PressureHistoryScreen
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

    /**
     * Укол пошагово (макет, экраны 44–45): подкожно ручкой или в мышцу — по лекарству. `site` — выбранное на экране места
     * (`Site.name`); в конце приём отмечается с этим местом.
     */
    data class ShotGuide(val medicineId: Long, val plannedAt: Long?, val site: String?) : Route

    /** Правила уколов (макет, экран 46). */
    data object ShotRules : Route

    /** Профили: «Я», близкие, питомцы. */
    data object Profiles : Route

    /** Новый профиль (`id` = 0) или правка. */
    data class ProfileEdit(val id: Long) : Route

    /** Ввод давления (макет, экран 26): клавиатура, серия из двух, тонометр по Bluetooth. Профиль — текущий. */
    data object PressureEntry : Route

    /** История давления (макет, экран 27). */
    data object PressureHistory : Route

    /** Близкий: имя, телефон, какие SMS (`id` = 0 — новый). */
    data class ContactEdit(val id: Long) : Route

    /** QR с моим расписанием — для телефона близкого (макет, экран 13). */
    data object ShareQr : Route

    /** Сканер QR чужого расписания → профиль-копия (макет, экран 12). */
    data object CareScan : Route

    /** Экстренная карта текущего профиля (макет, экран 16). */
    data object Emergency : Route

    data object EmergencyEdit : Route

    /** Отчёт врачу: период, PDF, «Поделиться» (макет, экраны 6, 13). */
    data object Report : Route

    /** «Сервер»: необязательное зеркало на свой сервер (только сборка github, openspec: server-sync). */
    data object Server : Route

    /** «Профили других людей» (сборка github, openspec: server-watch); `fromToday` — открыто из переключателя на «Сегодня». */
    data class Watch(val fromToday: Boolean = false) : Route

    /** Код приглашения к чужому профилю. */
    data object WatchCode : Route

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
        is ShotGuide -> "shot:$medicineId:${plannedAt ?: ""}:${site.orEmpty()}"
        ShotRules -> "shotrules"
        Profiles -> "profiles"
        is ProfileEdit -> "profile:$id"
        PressureEntry -> "bp"
        PressureHistory -> "bphistory"
        is ContactEdit -> "contact:$id"
        ShareQr -> "shareqr"
        CareScan -> "carescan"
        Emergency -> "emergency"
        EmergencyEdit -> "emergencyedit"
        Report -> "report"
        Server -> "server"
        is Watch -> "watch:${if (fromToday) 1 else 0}"
        WatchCode -> "watchcode"
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
                "inj" -> arg?.split(':')?.takeIf { it.size == 2 }?.let { (m, p) -> Injection(m.toLongOrNull() ?: return null, p.toLongOrNull()) }
                "shot" -> arg?.split(':')?.takeIf { it.size == 3 }?.let { (m, p, s) ->
                    ShotGuide(m.toLongOrNull() ?: return null, p.toLongOrNull(), s.ifEmpty { null })
                }
                "shotrules" -> ShotRules
                "profiles" -> Profiles
                "profile" -> arg?.toLongOrNull()?.let(::ProfileEdit)
                "bp" -> PressureEntry
                "bphistory" -> PressureHistory
                "contact" -> arg?.toLongOrNull()?.let(::ContactEdit)
                "shareqr" -> ShareQr
                "carescan" -> CareScan
                "emergency" -> Emergency
                "emergencyedit" -> EmergencyEdit
                "report" -> Report
                "server" -> Server
                "watch" -> Watch(arg == "1")
                "watchcode" -> WatchCode
                else -> null
            }
        }
    }
}

/** Переходы: открыть экран поверх или вернуться назад. */
class Nav(private val push: (Route) -> Unit, private val pop: () -> Unit, private val home: () -> Unit = {}) {
    fun open(route: Route) = push(route)

    fun back() = pop()

    /** Закрыть все экраны поверх вкладок (после укола пошагово — «На главную»). */
    fun home() = home.invoke()
}

/** Содержимое экрана поверх вкладок. */
@Composable
fun RouteContent(route: Route, nav: Nav, settings: AnvilSettings) {
    when (route) {
        Route.Settings -> SettingsScreen(
            settings, onBack = nav::back, onProfiles = { nav.open(Route.Profiles) }, onShotRules = { nav.open(Route.ShotRules) },
            onEmergency = { nav.open(Route.Emergency) },
            onServer = { nav.open(Route.Server) },
        )
        is Route.MedicineEdit -> MedicineEditScreen(route.id, nav)
        is Route.AsNeeded -> AsNeededScreen(route.medicineId, nav)
        is Route.Stock -> StockDetailScreen(route.medicineId, nav)
        is Route.PackEdit -> PackEditScreen(route.medicineId, route.packId, route.fromScan, nav)
        Route.Expiry -> ExpiryScreen(nav)
        Route.Scan -> ScanScreen(nav)
        Route.Import -> ImportScreen(nav)
        is Route.Injection -> InjectionScreen(route.medicineId, route.plannedAt, nav)
        is Route.ShotGuide -> ShotGuideScreen(route.medicineId, route.plannedAt, route.site, nav)
        Route.ShotRules -> ShotRulesScreen(nav)
        Route.Profiles -> ProfilesScreen(nav)
        is Route.ProfileEdit -> ProfileEditScreen(route.id, nav)
        Route.PressureEntry -> PressureEntryScreen(nav)
        Route.PressureHistory -> PressureHistoryScreen(nav)
        is Route.ContactEdit -> ContactEditScreen(route.id, nav)
        Route.ShareQr -> ShareQrScreen(nav)
        Route.CareScan -> CareScanScreen(nav)
        Route.Emergency -> EmergencyScreen(nav)
        Route.EmergencyEdit -> EmergencyEditScreen(nav)
        Route.Report -> ReportScreen(nav)
        Route.Server -> ServerScreen(nav)
        is Route.Watch -> WatchListScreen(route.fromToday, nav)
        Route.WatchCode -> WatchCodeScreen(nav)
    }
}
