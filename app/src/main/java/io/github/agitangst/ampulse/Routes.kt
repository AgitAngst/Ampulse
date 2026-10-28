package io.github.agitangst.ampulse

import androidx.compose.runtime.Composable
import io.github.agitangst.ampulse.ui.AsNeededScreen
import io.github.agitangst.ampulse.ui.MedicineEditScreen
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

    fun encode(): String = when (this) {
        Settings -> "settings"
        is MedicineEdit -> "med:$id"
        is AsNeeded -> "prn:$medicineId"
    }

    companion object {
        fun decode(text: String): Route? {
            val (name, arg) = text.split(':', limit = 2).let { it[0] to it.getOrNull(1) }
            return when (name) {
                "settings" -> Settings
                "med" -> arg?.toLongOrNull()?.let(::MedicineEdit)
                "prn" -> arg?.toLongOrNull()?.let(::AsNeeded)
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
    }
}
