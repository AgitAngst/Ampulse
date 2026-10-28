package io.github.agitangst.ampulse.ui

import androidx.compose.runtime.Composable
import io.github.agitangst.ampulse.Nav
import io.github.agitangst.ampulse.domain.PackCode

/** Код последнего скана — экран упаковки забирает его, когда открыт с `fromScan`. */
object PendingScan {
    var code: PackCode? = null
}

/** Сканер упаковки (макет, экран 14). Заготовка — задача 3.1 этапа 0.4. */
@Composable
fun ScanScreen(nav: Nav) = Unit
