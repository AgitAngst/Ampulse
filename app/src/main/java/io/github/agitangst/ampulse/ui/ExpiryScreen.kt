package io.github.agitangst.ampulse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.agitangst.ampulse.Ampulse
import io.github.agitangst.ampulse.AppGraph
import io.github.agitangst.ampulse.Nav
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.Route
import io.github.agitangst.ampulse.domain.ExpiryForecast
import io.github.agitangst.ampulse.domain.ExpiryState
import io.github.agitangst.ampulse.domain.Medicine
import io.github.agitangst.ampulse.domain.Pack
import io.github.agitangst.ampulse.domain.dailyNeed
import io.github.agitangst.ampulse.domain.expiryState
import io.github.agitangst.ampulse.domain.goodUntil
import io.github.agitangst.ampulse.domain.timeLeft
import io.github.agitangst.ampulse.screenTitleStyle
import io.github.agitangst.anvil.compose.AnvilCard
import io.github.agitangst.anvil.compose.AnvilTheme
import io.github.agitangst.anvil.compose.Segmented
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Остаток срока 30 дней и меньше — цвет внимания (docs/ui-guideline.md, §6). */
private const val SOON_DAYS = 30L

/** У вскрытой упаковки цвет внимания — в последние три дня (макет, экран 40). */
private const val OPENED_WARN_DAYS = 3L

/** Упаковка с лекарством, расходом в день по схеме и состоянием срока. */
private class ExpiryItem(val med: Medicine, val pack: Pack, val others: List<Pack>, val need: Double, val state: ExpiryState) {
    val until: LocalDate? = pack.goodUntil()
}

/**
 * «Сроки годности» (макет, экран 40): просроченные — с «Убрал из аптечки» (и «Отменить») и «Как утилизировать»;
 * вскрытые — с отсчётом дней после вскрытия; «скоро истекают» (в выбранном окне 1 / 3 / 6 мес.) и «в порядке» — с
 * остатком срока и прогнозом «успеете ли». Окно запоминается. Внизу «Добавить упаковку» — сначала лекарство.
 */
@Composable
fun ExpiryScreen(nav: Nav) {
    val repo = AppGraph.repository
    val context = LocalContext.current
    val texts = rememberMedTexts()
    val scope = rememberCoroutineScope()
    val p = AnvilTheme.palette
    val today = LocalDate.now()

    val medicines by remember { repo.medicines }.collectAsState(initial = null)
    val schedules by remember { repo.schedules }.collectAsState(initial = null)
    val packs by remember { repo.packs }.collectAsState(initial = null)
    var window by rememberSaveable { mutableStateOf(StockPrefs.expiryWindow(context)) }
    var how by rememberSaveable { mutableStateOf(false) }
    var removedId by rememberSaveable { mutableStateOf(0L) }
    var removedName by rememberSaveable { mutableStateOf("") }
    var picking by rememberSaveable { mutableStateOf(false) }

    val list = medicines
    val plans = schedules
    val allPacks = packs

    val items: List<ExpiryItem>? = if (list == null || plans == null || allPacks == null) {
        null
    } else {
        val byId = list.associateBy { it.id }
        val planOf = plans.associate { it.medicineId to it.plan }
        allPacks.filter { it.left > 0 && it.medicineId in byId }.map { pack ->
            val med = byId.getValue(pack.medicineId)
            val need = planOf[med.id]?.let { dailyNeed(it, today) } ?: 0.0
            ExpiryItem(med, pack, allPacks.filter { it.medicineId == med.id }, need, expiryState(pack, today, window))
        }
    }
    val windowLabel = stringResource(R.string.stock_window, window)

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MedBackRow(stringResource(R.string.tab_medicines), nav::back, Modifier.offset(x = (-8).dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.stock_expiry_title),
                    Modifier.semantics { heading() },
                    color = p.text,
                    style = screenTitleStyle(30),
                )
                if (items != null) {
                    val count = pluralStringResource(R.plurals.stock_packs_count, items.size, items.size)
                    Text(
                        count + " · " + stringResource(R.string.stock_remind_ahead, windowLabel),
                        color = p.weak,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (items == null) return@Column

            // Ближайший срок — выше; без срока — в конце.
            fun List<ExpiryItem>.byDate() = sortedBy { it.until ?: LocalDate.MAX }
            val expired = items.filter { it.state == ExpiryState.Expired }.byDate()
            val opened = items.filter { it.state == ExpiryState.Opened }.byDate()
            val soon = items.filter { it.state == ExpiryState.Soon }.byDate()
            val ok = items.filter { it.state == ExpiryState.Ok || it.state == ExpiryState.Unknown }.byDate()

            if (expired.isNotEmpty()) {
                ExpiredCard(
                    expired,
                    texts,
                    today,
                    how,
                    onToggleHow = { how = !how },
                    onRemove = { item ->
                        removedId = item.pack.id
                        removedName = todayMedName(item.med.name, item.med.strength)
                        scope.launch { repo.disposePack(item.pack.id) }
                    },
                )
            }
            if (removedId != 0L) {
                StockDoneBar(
                    stringResource(R.string.stock_removed_text, removedName),
                    stringResource(R.string.stock_undo),
                    onUndo = {
                        val id = removedId
                        removedId = 0L
                        scope.launch { repo.restorePack(id) }
                    },
                )
            }

            if (opened.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StockSectionHeader(stringResource(R.string.stock_opened_h), stringResource(R.string.stock_opened_r))
                    opened.forEach { item -> OpenedCard(item, texts, today) { nav.open(Route.PackEdit(item.med.id, item.pack.id)) } }
                }
            }

            if (items.isEmpty()) {
                AnvilCard {
                    Text(
                        stringResource(R.string.stock_empty_title),
                        color = p.text,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Text(stringResource(R.string.stock_empty_hint), color = p.weak, style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StockSectionHeader(
                        stringResource(R.string.stock_soon_h),
                        stringResource(R.string.stock_soon_r, soon.size, windowLabel),
                    )
                    if (soon.isEmpty()) {
                        AnvilCard {
                            Text(
                                stringResource(R.string.stock_soon_empty, windowLabel),
                                color = p.weak,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    } else {
                        ExpiryList(soon, texts, today) { nav.open(Route.PackEdit(it.med.id, it.pack.id)) }
                    }
                }
            }

            if (ok.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StockSectionHeader(stringResource(R.string.stock_ok_h), stringResource(R.string.stock_ok_r))
                    ExpiryList(ok, texts, today) { nav.open(Route.PackEdit(it.med.id, it.pack.id)) }
                }
            }

            // Окно «скоро истекают».
            AnvilCard {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        stringResource(R.string.stock_remind_h),
                        color = p.text,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Text(
                        stringResource(R.string.stock_remind_note, windowLabel),
                        color = p.weak,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                val options = EXPIRY_WINDOWS.map { stringResource(R.string.stock_window, it) }
                Segmented(
                    options,
                    selected = EXPIRY_WINDOWS.indexOf(window).coerceAtLeast(0),
                    onSelect = { i ->
                        window = EXPIRY_WINDOWS[i]
                        StockPrefs.setExpiryWindow(context, window)
                    },
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
        Column(
            Modifier.fillMaxWidth().background(p.bg).padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.stock_expiry_add_note),
                Modifier.fillMaxWidth(),
                color = p.weak,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            MedMainButton(
                stringResource(R.string.stock_add_pack),
                enabled = list != null,
                onClick = {
                    // Одно лекарство — выбирать не из чего.
                    val only = list?.singleOrNull()
                    if (only != null) nav.open(Route.PackEdit(only.id, 0)) else picking = true
                },
            )
        }
    }

    if (picking) {
        TodaySheet(onDismiss = { picking = false }) { _ ->
            Text(
                stringResource(R.string.stock_pick_med),
                Modifier.semantics { heading() },
                color = p.text,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            val meds = list.orEmpty()
            if (meds.isEmpty()) {
                Text(stringResource(R.string.stock_pick_none), color = p.weak, style = MaterialTheme.typography.bodyLarge)
                MedMainButton(
                    stringResource(R.string.meds_add),
                    onClick = {
                        picking = false
                        nav.open(Route.MedicineEdit(0))
                    },
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    meds.forEach { med ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable(role = Role.Button) {
                                    picking = false
                                    nav.open(Route.PackEdit(med.id, 0))
                                }
                                .padding(horizontal = 4.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            StockTile(MedIcons.of(med.form), soft = true)
                            Text(
                                todayMedName(med.name, med.strength),
                                Modifier.weight(1f),
                                color = p.text,
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Icon(MedIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp), tint = p.faint)
                        }
                    }
                }
            }
        }
    }
}

/** До какого дня годна упаковка — как на упаковке, если срок не сдвинут вскрытием, иначе полной датой. */
private fun MedTexts.untilText(pack: Pack): String {
    val until = pack.goodUntil() ?: return ""
    return if (until == pack.expiry) packExpiry(pack).orEmpty() else date(until)
}

/** Просроченные: баннер с остатком, «Убрал из аптечки» у каждой упаковки и «Как утилизировать» с подсказкой. */
@Composable
private fun ExpiredCard(
    items: List<ExpiryItem>,
    texts: MedTexts,
    today: LocalDate,
    how: Boolean,
    onToggleHow: () -> Unit,
    onRemove: (ExpiryItem) -> Unit,
) {
    val p = AnvilTheme.palette
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Ampulse.colors.warnSoft)
            .border(1.5.dp, Ampulse.colors.warnLine, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(p.card), contentAlignment = Alignment.Center) {
                Icon(MedIcons.Warning, contentDescription = null, modifier = Modifier.size(22.dp), tint = p.warning)
            }
            Text(
                stringResource(R.string.stock_expired_tag),
                Modifier.semantics { heading() },
                color = p.warning,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
        }
        items.forEach { item ->
            val until = item.until ?: today
            val ago = texts.timeLeftText(timeLeft(until = today, today = until))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        todayMedName(item.med.name, item.med.strength),
                        color = p.text,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Text(
                        stringResource(R.string.stock_expired_text, texts.untilText(item.pack), ago, texts.dose(item.pack.left, item.med.unit)),
                        color = p.warning,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                ExpiryButton(stringResource(R.string.stock_expired_remove), { onRemove(item) }, filled = true)
            }
        }
        ExpiryButton(stringResource(R.string.stock_how), onToggleHow, filled = false)
        if (how) {
            Text(stringResource(R.string.stock_how_text), color = p.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Кнопка баннера просроченных: главная залита акцентом, обычная — с рамкой цвета внимания; 48 dp. */
@Composable
private fun ExpiryButton(text: String, onClick: () -> Unit, filled: Boolean) {
    val p = AnvilTheme.palette
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(if (filled) p.accent else p.card)
            .then(if (filled) Modifier else Modifier.border(1.5.dp, Ampulse.colors.warnLine, CircleShape))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
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

/** Вскрытая упаковка: дни до конца срока после вскрытия крупно, полоса отсчёта и прогноз «успеете ли». */
@Composable
private fun OpenedCard(item: ExpiryItem, texts: MedTexts, today: LocalDate, onOpen: () -> Unit) {
    val p = AnvilTheme.palette
    val until = item.until ?: today
    val total = item.pack.afterOpenDays
    val daysToEnd = ChronoUnit.DAYS.between(today, until).coerceAtLeast(0)
    val warn = daysToEnd <= OPENED_WARN_DAYS
    val (num, unit) = texts.timeLeftParts(timeLeft(until, today))
    val opened = item.pack.openedAt ?: today
    val used = (total - daysToEnd.toInt()).coerceIn(0, total)
    val aria = stringResource(R.string.stock_opened_aria, used, texts.days(total))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(p.card)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            StockTile(MedIcons.of(item.med.form))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    todayMedName(item.med.name, item.med.strength),
                    color = p.text,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    stringResource(R.string.stock_opened_sub, texts.dayMonth(opened), texts.days(total)),
                    color = p.weak,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            StockBigNumber(num, unit, warn, Modifier.widthIn(min = 52.dp))
        }
        StockBar(
            if (total > 0) daysToEnd.toFloat() / total else 0f,
            warn,
            Modifier.semantics { contentDescription = aria },
            height = 6,
        )
        ForecastNote(item, texts, today)
    }
}

/** Строки «скоро истекают» или «в порядке» одной карточкой с разделителями. */
@Composable
private fun ExpiryList(items: List<ExpiryItem>, texts: MedTexts, today: LocalDate, onOpen: (ExpiryItem) -> Unit) {
    val p = AnvilTheme.palette
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.card)) {
        items.forEachIndexed { i, item ->
            if (i > 0) Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(p.border))
            ExpiryRow(item, texts, today, onClick = { onOpen(item) })
        }
    }
}

/** Строка упаковки: плитка формы → имя → «годна до … · остаток» → прогноз; справа остаток срока крупно. */
@Composable
private fun ExpiryRow(item: ExpiryItem, texts: MedTexts, today: LocalDate, onClick: () -> Unit) {
    val p = AnvilTheme.palette
    val until = item.until
    val amount = texts.dose(item.pack.left, item.med.unit)
    val sub = if (until == null) {
        stringResource(R.string.stock_row_unknown, amount)
    } else {
        stringResource(R.string.stock_row_until_qty, texts.untilText(item.pack), amount)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        StockTile(MedIcons.of(item.med.form))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                todayMedName(item.med.name, item.med.strength),
                color = p.text,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(sub, color = p.weak, style = MaterialTheme.typography.bodyMedium)
            ForecastNote(item, texts, today)
        }
        if (until != null) {
            val left = timeLeft(until, today)
            val (num, unit) = texts.timeLeftParts(left)
            val days = ChronoUnit.DAYS.between(today, until)
            StockBigNumber(num, unit, warn = days <= SOON_DAYS, modifier = Modifier.widthIn(min = 52.dp))
        }
    }
}

/** Прогноз «успеете ли допить до конца срока»: успеете — акцент и дата, не успеете — цвет внимания и остаток. */
@Composable
private fun ForecastNote(item: ExpiryItem, texts: MedTexts, today: LocalDate) {
    when (val f = packForecast(item.pack, item.others, item.need, today)) {
        is ExpiryForecast.Fits -> StockNote(stringResource(R.string.stock_fits, texts.date(f.runsOut)), good = true)
        is ExpiryForecast.Leftover -> StockNote(
            stringResource(R.string.stock_leftover, texts.approx(f.left, item.med.unit)),
            good = false,
        )
        ExpiryForecast.Unknown -> Unit
    }
}
