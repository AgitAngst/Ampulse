package io.github.agitangst.ampulse

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import io.github.agitangst.ampulse.ui.HistoryScreen
import io.github.agitangst.ampulse.ui.MedicinesScreen
import io.github.agitangst.ampulse.ui.TodayScreen
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.agitangst.anvil.compose.AnvilEasing
import io.github.agitangst.anvil.compose.AnvilSettings
import io.github.agitangst.anvil.compose.AnvilTheme
import io.github.agitangst.anvil.compose.Motion
import io.github.agitangst.anvil.compose.motionSpec

/** Вкладки нижней навигации слева направо (docs/ui-guideline.md, §2). */
enum class Tab(@StringRes val label: Int) {
    Today(R.string.tab_today),
    History(R.string.tab_history),
    Medicines(R.string.tab_medicines),
    Family(R.string.tab_family);

    val icon: ImageVector
        get() = when (this) {
            Today -> AmpulseIcons.Home
            History -> AmpulseIcons.Calendar
            Medicines -> AmpulseIcons.BriefcaseMedical
            Family -> AmpulseIcons.Users
        }
}

/**
 * Оболочка: баннер обновления, содержимое вкладки или «Настройки» и нижняя навигация. Выбранная вкладка и открытые
 * настройки переживают пересоздание экрана (смену языка, поворот); жест «назад» из настроек ведёт к вкладкам.
 * Отступы системных панелей — `safeDrawing`; фон навигации доходит до края экрана под жестовой панелью.
 */
@Composable
fun AppShell(settings: AnvilSettings) {
    val context = LocalContext.current
    val p = AnvilTheme.palette
    var tab by rememberSaveable { mutableStateOf(Tab.Today) }
    // Стек экранов поверх вкладок — строками (Route.encode), чтобы пережить пересоздание экрана.
    var stack by rememberSaveable { mutableStateOf(ArrayList<String>()) }
    val nav = remember {
        Nav(
            push = { route -> stack = ArrayList(stack).apply { add(route.encode()) } },
            pop = { if (stack.isNotEmpty()) stack = ArrayList(stack.dropLast(1)) },
        )
    }
    val top = stack.lastOrNull()?.let(Route::decode)

    // Проверка обновления при первом показе: раз за процесс и только если включено «Проверять обновления».
    if (!LocalInspectionMode.current) {
        LaunchedEffect(Unit) { Store.onStart(context, settings) }
    }
    BackHandler(enabled = stack.isNotEmpty()) { nav.back() }

    Surface(Modifier.fillMaxSize(), color = p.bg, contentColor = p.text) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // Сканер тёмный во весь экран и сам держит отступы системных панелей.
                    .then(
                        if (top == Route.Scan) Modifier
                        else Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)),
                    ),
            ) {
                // Баннер над содержимым; скрытый места не занимает (отступы у самой карточки).
                Store.Banner(Modifier.padding(start = 20.dp, top = 16.dp, end = 20.dp))
                Crossfade(
                    targetState = top,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    animationSpec = motionSpec(Motion.STATE),
                    label = "page",
                ) { route ->
                    if (route != null) RouteContent(route, nav, settings) else TabContent(tab, nav)
                }
            }
            AnimatedVisibility(
                visible = top == null,
                enter = fadeIn(motionSpec(Motion.ENTER)) + expandVertically(motionSpec(Motion.ENTER)),
                exit = fadeOut(motionSpec(Motion.LEAVE, AnvilEasing.In)) +
                    shrinkVertically(motionSpec(Motion.LEAVE, AnvilEasing.In)),
            ) {
                TabBar(tab, onSelect = { tab = it })
            }
        }
    }
}

@Composable
private fun TabContent(tab: Tab, nav: Nav) {
    when (tab) {
        Tab.Today -> TodayScreen(nav)
        Tab.History -> HistoryScreen(nav)
        Tab.Medicines -> MedicinesScreen(nav)
        Tab.Family -> FamilyScreen()
    }
}

/** Нижняя навигация: фон `surface` до края экрана, линия сверху `border`, высота 76 dp плюс жестовая панель. */
@Composable
private fun TabBar(selected: Tab, onSelect: (Tab) -> Unit) {
    val p = AnvilTheme.palette
    Row(
        Modifier
            .fillMaxWidth()
            .background(p.surface)
            .drawBehind {
                val line = 1.dp.toPx()
                drawLine(p.border, Offset(0f, line / 2), Offset(size.width, line / 2), strokeWidth = line)
            }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .selectableGroup(),
    ) {
        Tab.entries.forEach { tab ->
            TabItem(tab, tab == selected, onClick = { onSelect(tab) }, Modifier.weight(1f))
        }
    }
}

/**
 * Вкладка: значок 24 dp и подпись 12 sp. Выбранная — цвет акцента и полужирная подпись, остальные — `weak`.
 * Нажимается вся ячейка высотой не меньше 76 dp.
 */
@Composable
private fun TabItem(tab: Tab, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    val color by animateColorAsState(if (selected) p.accent else p.weak, motionSpec(Motion.STATE), label = "tab-color")
    Column(
        modifier
            .heightIn(min = 76.dp)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(top = 8.dp, bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Icon(tab.icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = color)
        Text(
            stringResource(tab.label),
            color = color,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
