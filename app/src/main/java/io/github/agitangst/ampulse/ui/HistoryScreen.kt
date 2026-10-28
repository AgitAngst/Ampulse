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

/** «История» (макет, экран 6). Заготовка — наполняет задача 2.2 этапа 0.3. */
@Composable
fun HistoryScreen(nav: Nav, modifier: Modifier = Modifier) {
    PageLayout(modifier, header = { TabHeader(stringResource(R.string.tab_history)) }) {
        EmptyState(AmpulseIcons.Calendar, stringResource(R.string.history_empty_title), stringResource(R.string.history_empty_hint), Modifier.weight(1f))
    }
}
