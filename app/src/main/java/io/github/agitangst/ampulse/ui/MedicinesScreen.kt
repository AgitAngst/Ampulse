package io.github.agitangst.ampulse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.agitangst.ampulse.Ampulse
import io.github.agitangst.ampulse.AmpulseIcons
import io.github.agitangst.ampulse.AppGraph
import io.github.agitangst.ampulse.EmptyState
import io.github.agitangst.ampulse.Nav
import io.github.agitangst.ampulse.PageLayout
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.Route
import io.github.agitangst.ampulse.TabHeader
import io.github.agitangst.ampulse.domain.ExpiryState
import io.github.agitangst.ampulse.domain.PlanKind
import io.github.agitangst.ampulse.domain.expiryState
import io.github.agitangst.ampulse.domain.goodUntil
import io.github.agitangst.anvil.compose.AnvilTheme
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Полоса запаса полная, когда хватит на 30 дней и больше (макет, экран 5). */
private const val BAR_DAYS = 30f

/**
 * «Аптечка» (макет, экран 5): сверху строка-переход «Сроки годности» со сводкой, баннер «мало запаса» и список
 * лекарств. В строке — остаток, «хватит на N дней» крупно справа и полоса (7 дней и меньше — цвет внимания); у «по
 * необходимости» и лекарств без упаковок — сводка схемы словами. Касание строки открывает запас лекарства, у
 * «по необходимости» справа ещё «Принять» (таймер и счётчик). В шапке — сканер упаковки и «+».
 */
@Composable
fun MedicinesScreen(nav: Nav, modifier: Modifier = Modifier) {
    val repo = AppGraph.repository
    val context = LocalContext.current
    val medicines by remember { repo.medicines }.collectAsState(initial = null)
    val schedules by remember { repo.schedules }.collectAsState(initial = null)
    val packs by remember { repo.packs }.collectAsState(initial = null)
    val texts = rememberMedTexts()
    val add = stringResource(R.string.meds_add)
    PageLayout(
        modifier,
        header = {
            TabHeader(
                stringResource(R.string.tab_medicines),
                action = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MedRoundButton(StockIcons.Scan, stringResource(R.string.stock_scan), onClick = { nav.open(Route.Scan) })
                        MedRoundButton(MedIcons.Plus, add, onClick = { nav.open(Route.MedicineEdit(0)) })
                    }
                },
            )
        },
    ) {
        ProfileSwitcher(nav)
        val list = medicines?.filter { it.profileId == CurrentProfile.id }
        val plans = schedules
        val allPacks = packs
        when {
            // Данные ещё читаются: пустую «Аптечку» не мигаем.
            list == null || plans == null || allPacks == null -> Box(Modifier.weight(1f))
            list.isEmpty() -> {
                EmptyState(
                    AmpulseIcons.BriefcaseMedical,
                    stringResource(R.string.medicines_empty_title),
                    stringResource(R.string.medicines_empty_hint),
                    Modifier.weight(1f),
                )
                MedMainButton(add, onClick = { nav.open(Route.MedicineEdit(0)) })
            }
            else -> {
                val today = LocalDate.now()
                val window = remember { StockPrefs.expiryWindow(context) }
                val stock = remember(list, plans, allPacks, today) { buildStock(list, plans, allPacks, today) }
                // Сначала те, чей запас кончится раньше; без расчёта — после них, в прежнем порядке.
                val rows = remember(stock) {
                    stock.sortedWith(compareBy<MedStock>({ it.days == null }, { it.days ?: 0 }))
                }
                val low = remember(stock) { stock.filter { it.low }.minByOrNull { it.days ?: 0 } }
                var inList by rememberSaveable(low?.med?.id) {
                    mutableStateOf(low != null && StockPrefs.inShoppingList(context, low.med.id))
                }

                // Сводка сроков по упаковкам, в которых что-то осталось.
                val live = stock.flatMap { it.packs }.filter { it.left > 0 }
                val expired = live.count { it.isExpired(today) }
                val soon = live.count {
                    when (expiryState(it, today, window)) {
                        ExpiryState.Soon -> true
                        // Вскрытая живёт недолго — «скоро» только в последние три дня (как в «Сроках годности»).
                        ExpiryState.Opened -> it.goodUntil()?.let { until -> until <= today.plusDays(3) } == true
                        else -> false
                    }
                }
                val summary = expirySummary(expired, soon, live.size)

                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item(key = "expiry-link") {
                        ExpiryLink(summary.first, summary.second, onClick = { nav.open(Route.Expiry) }, Modifier.padding(bottom = 8.dp))
                    }
                    if (low != null) {
                        item(key = "low-supply") {
                            LowSupplyBanner(
                                low,
                                texts,
                                inList,
                                onBuy = { nav.open(Route.PackEdit(low.med.id, 0)) },
                                onToggleList = {
                                    inList = !inList
                                    StockPrefs.setShoppingList(context, low.med.id, inList)
                                },
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                    }
                    items(rows, key = { it.med.id }) { item ->
                        MedicineRow(
                            item,
                            texts.summary(item.plan, item.med.unit),
                            texts,
                            onOpen = { nav.open(Route.Stock(item.med.id)) },
                            onTake = { nav.open(Route.AsNeeded(item.med.id)) },
                        )
                    }
                }
            }
        }
    }
}

/** Сводка сроков одной строкой: «1 просрочена, 2 скоро истекают» (цвет внимания) или «в порядке» / «нет упаковок». */
@Composable
private fun expirySummary(expired: Int, soon: Int, total: Int): Pair<String, Boolean> {
    val expiredText = if (expired > 0) pluralStringResource(R.plurals.stock_sum_expired, expired, expired) else null
    val soonText = if (soon > 0) pluralStringResource(R.plurals.stock_sum_soon, soon, soon) else null
    val parts = listOfNotNull(expiredText, soonText)
    return when {
        parts.isNotEmpty() -> parts.joinToString(", ") to true
        total > 0 -> stringResource(R.string.stock_sum_ok) to false
        else -> stringResource(R.string.stock_sum_no_packs) to false
    }
}

/** Строка-переход «Сроки годности ›» со сводкой (docs/ui-guideline.md, §6 «Переход на другой экран»). */
@Composable
private fun ExpiryLink(summary: String, warn: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(p.card)
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StockTile(StockIcons.CalendarClock)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.stock_expiry_link),
                color = p.text,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(summary, color = if (warn) p.warning else p.weak, style = MaterialTheme.typography.bodyMedium)
        }
        Icon(MedIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp), tint = p.faint)
    }
}

/**
 * Баннер «мало запаса» (7 дней и меньше): что кончается и на сколько хватит. Главная — «Купил упаковку» (открывает
 * новую упаковку), обычная — «В список покупок» (только отметка, список покупок в программе не ведётся).
 */
@Composable
private fun LowSupplyBanner(
    item: MedStock,
    texts: MedTexts,
    inList: Boolean,
    onBuy: () -> Unit,
    onToggleList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = AnvilTheme.palette
    val name = todayMedName(item.med.name, item.med.strength)
    val empty = item.left <= 0.0
    val title = stringResource(if (empty) R.string.stock_low_empty_title else R.string.stock_low_title, name)
    val text = if (empty) {
        stringResource(R.string.stock_low_text_empty)
    } else {
        stringResource(R.string.stock_low_text, texts.dose(item.left, item.med.unit), texts.days(item.days ?: 0))
    }
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Ampulse.colors.warnSoft)
            .border(1.5.dp, Ampulse.colors.warnLine, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(p.card), contentAlignment = Alignment.Center) {
                Icon(MedIcons.Warning, contentDescription = null, modifier = Modifier.size(22.dp), tint = p.warning)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = p.text, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                Text(text, color = p.warning, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BannerButton(stringResource(R.string.stock_buy), onBuy, Modifier.weight(1f), filled = true)
            BannerButton(
                stringResource(if (inList) R.string.stock_in_list else R.string.stock_to_list),
                onToggleList,
                Modifier.weight(1f),
                filled = false,
            )
        }
    }
}

/** Кнопка баннера: главная залита акцентом, обычная — с рамкой цвета внимания; 48 dp. */
@Composable
private fun BannerButton(text: String, onClick: () -> Unit, modifier: Modifier, filled: Boolean) {
    val p = AnvilTheme.palette
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(if (filled) p.accent else p.card)
            .then(if (filled) Modifier else Modifier.border(1.5.dp, Ampulse.colors.warnLine, CircleShape))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (filled) p.onAccent else p.warning,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Строка списка (docs/ui-guideline.md, §6): плитка значка формы 40×40 → имя с дозировкой → вторичная строка (остаток и
 * расход в день, а без расчёта — сводка схемы) → справа «хватит на N дней» крупно или действие. Под вторичной строкой
 * — полоса запаса. У «по необходимости» справа «Принять».
 */
@Composable
private fun MedicineRow(item: MedStock, summary: String, texts: MedTexts, onOpen: () -> Unit, onTake: () -> Unit) {
    val p = AnvilTheme.palette
    val med = item.med
    val plan = item.plan
    val paused = plan?.paused == true
    val title = if (med.strength.isBlank()) med.name else "${med.name} ${med.strength}"
    val days = item.days

    // Вторичная строка: с расчётом — «27 табл. · 1 в день» и пометка о сроке, иначе сводка схемы.
    val soonUntil = item.usable.filter { it.left > 0 }.mapNotNull { it.goodUntil() }.minOrNull()
        ?.takeIf { ChronoUnit.DAYS.between(item.today, it) <= 30 }
    val sub: String
    var subWarn = paused
    if (days != null) {
        val perDay = stringResource(R.string.stock_per_day, texts.dose(item.need, med.unit))
        val base = texts.dose(item.left, med.unit) + " · " + perDay
        val note = when {
            item.hasExpired -> stringResource(R.string.stock_row_expired)
            soonUntil != null -> stringResource(R.string.stock_row_until, texts.monthYear(soonUntil))
            else -> null
        }
        sub = if (note == null) base else "$base · $note"
        subWarn = note != null
    } else {
        sub = summary
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(p.card)
            .heightIn(min = 64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 64.dp)
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = if (days != null) 16.dp else 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Ampulse.colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(MedIcons.of(med.form), contentDescription = null, modifier = Modifier.size(22.dp), tint = p.accentText)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    color = p.text,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    sub,
                    color = if (subWarn) p.warning else p.weak,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (days != null) {
                    StockBar(days / BAR_DAYS, warn = item.low, modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (days != null) {
                val (num, unit) = splitNumber(texts.days(days), days)
                StockBigNumber(num, unit, warn = item.low, modifier = Modifier.widthIn(min = 48.dp))
            }
        }
        if (plan?.kind == PlanKind.AsNeeded && !paused) {
            Box(
                Modifier
                    .padding(end = 8.dp)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Ampulse.colors.accentSoft)
                    .clickable(role = Role.Button, onClick = onTake)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.meds_prn_take),
                    color = p.accentText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        } else if (days == null) {
            Icon(
                MedIcons.ChevronRight,
                contentDescription = null,
                modifier = Modifier.padding(end = 12.dp).size(20.dp),
                tint = p.faint,
            )
        }
    }
}
