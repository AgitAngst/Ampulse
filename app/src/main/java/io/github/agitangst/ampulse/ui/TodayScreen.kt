package io.github.agitangst.ampulse.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.agitangst.ampulse.AmpulseIcons
import io.github.agitangst.ampulse.EmptyState
import io.github.agitangst.ampulse.Nav
import io.github.agitangst.ampulse.PageLayout
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.Route
import io.github.agitangst.ampulse.SettingsButton
import io.github.agitangst.ampulse.TabHeader
import io.github.agitangst.ampulse.todayText

/** «Сегодня» (макет, экран 1). Заготовка — наполняет задача 2.1 этапа 0.3. */
@Composable
fun TodayScreen(nav: Nav, modifier: Modifier = Modifier) {
    PageLayout(
        modifier,
        header = {
            TabHeader(stringResource(R.string.tab_today), date = todayText(), action = { SettingsButton { nav.open(Route.Settings) } })
        },
    ) {
        EmptyState(AmpulseIcons.Pill, stringResource(R.string.today_empty_title), stringResource(R.string.today_empty_hint), Modifier.weight(1f))
    }
}
