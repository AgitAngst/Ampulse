package io.github.agitangst.ampulse.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.agitangst.ampulse.AmpulseIcons
import io.github.agitangst.ampulse.EmptyState
import io.github.agitangst.ampulse.Nav
import io.github.agitangst.ampulse.PageLayout
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.TabHeader

/** «Аптечка»: список лекарств. Заготовка — наполняет задача 2.3 этапа 0.3 (запас и сроки — этап 0.4). */
@Composable
fun MedicinesScreen(nav: Nav, modifier: Modifier = Modifier) {
    PageLayout(modifier, header = { TabHeader(stringResource(R.string.tab_medicines)) }) {
        EmptyState(AmpulseIcons.BriefcaseMedical, stringResource(R.string.medicines_empty_title), stringResource(R.string.medicines_empty_hint), Modifier.weight(1f))
    }
}

/** Новое лекарство или правка (макет, экраны 4 и 10). Заготовка — задача 2.3. */
@Composable
fun MedicineEditScreen(id: Long, nav: Nav) = Unit

/** «По необходимости» (макет, экран 11). Заготовка — задача 2.3. */
@Composable
fun AsNeededScreen(medicineId: Long, nav: Nav) = Unit
