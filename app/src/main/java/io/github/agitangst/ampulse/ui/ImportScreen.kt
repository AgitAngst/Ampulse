package io.github.agitangst.ampulse.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.agitangst.ampulse.Nav

/** Текст из «Поделиться»: экран разбора забирает его и обнуляет. Наблюдаемое — оболочка открывает разбор сама. */
object PendingImport {
    var text: String? by mutableStateOf(null)
}

/** Назначение → план (макет, экраны 7–9). Заготовка — задача 2.1 этапа 0.5. */
@Composable
fun ImportScreen(nav: Nav) = Unit
