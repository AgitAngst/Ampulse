package io.github.agitangst.ampulse.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.agitangst.ampulse.Ampulse
import io.github.agitangst.ampulse.AppGraph
import io.github.agitangst.ampulse.Nav
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.Route
import io.github.agitangst.ampulse.domain.Pack
import io.github.agitangst.ampulse.domain.fefo
import io.github.agitangst.ampulse.domain.goodUntil
import io.github.agitangst.ampulse.domain.timeLeft
import io.github.agitangst.ampulse.screenTitleStyle
import io.github.agitangst.anvil.compose.AnvilCard
import io.github.agitangst.anvil.compose.AnvilTheme
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Вскрытая упаковка с коротким сроком после вскрытия — цвет внимания в последние дни (как в макете, экран 40). */
private const val OPENED_WARN_DAYS = 3

/** Остаток срока 30 дней и меньше — цвет внимания (docs/ui-guideline.md, §6). */
private const val SOON_WARN_DAYS = 30

/**
 * Запас лекарства (макет, экран 5): итог «осталось N · хватит на M дней», упаковки по порядку расхода (вскрытые, затем
 * ранний срок) с остатком, сроком и серией. Касание упаковки — её правка; у каждой «Убрать из аптечки» с «Отменить»;
 * внизу главная «Добавить упаковку», вверху ссылка «Изменить лекарство».
 */
@Composable
fun StockDetailScreen(medicineId: Long, nav: Nav) {
    val repo = AppGraph.repository
    val texts = rememberMedTexts()
    val scope = rememberCoroutineScope()
    val p = AnvilTheme.palette
    val medicines by remember { repo.medicines }.collectAsState(initial = null)
    val schedules by remember { repo.schedules }.collectAsState(initial = null)
    val packs by remember { repo.packs }.collectAsState(initial = null)
    var removedId by rememberSaveable { mutableStateOf(0L) }

    val list = medicines
    val plans = schedules
    val allPacks = packs
    val med = list?.firstOrNull { it.id == medicineId }
    // Лекарство удалили (или открыли по старой ссылке) — вернуться.
    LaunchedEffect(list != null, med == null) { if (list != null && med == null) nav.back() }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                MedBackRow(stringResource(R.string.tab_medicines), nav::back, Modifier.offset(x = (-8).dp))
                // Ссылка на другой экран — справа в строке «Назад» (docs/ui-guideline.md, §6); сдвиг на её поле, чтобы текст встал на край.
                StockLinkButton(
                    stringResource(R.string.stock_edit_med),
                    onClick = { nav.open(Route.MedicineEdit(medicineId)) },
                    modifier = Modifier.offset(x = 12.dp),
                )
            }
            if (med == null || plans == null || allPacks == null) return@Column

            val today = LocalDate.now()
            val item = MedStock(med, plans.firstOrNull { it.medicineId == medicineId }?.plan, allPacks.filter { it.medicineId == medicineId }, today)
            val title = todayMedName(med.name, med.strength)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, Modifier.semantics { heading() }, color = p.text, style = screenTitleStyle(30))
                Text(texts.summary(item.plan, med.unit), color = p.weak, style = MaterialTheme.typography.bodyLarge)
            }

            if (item.packs.isNotEmpty()) SupplyCard(item, texts)

            if (removedId != 0L) {
                StockDoneBar(
                    stringResource(R.string.stock_removed),
                    stringResource(R.string.stock_undo),
                    onUndo = {
                        val id = removedId
                        removedId = 0L
                        scope.launch { repo.restorePack(id) }
                    },
                )
            }

            StockSectionHeader(stringResource(R.string.stock_packs_title), null)
            if (item.packs.isEmpty()) {
                AnvilCard {
                    Text(stringResource(R.string.stock_packs_empty), color = p.weak, style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                // Порядок расхода: годные по FEFO, затем просроченные, затем пустые.
                val usable = fefo(item.usable)
                val expired = item.packs.filter { it.left > 0 && it.isExpired(today) }.sortedBy { it.goodUntil() }
                val empty = item.packs.filter { it.left <= 0 }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (usable + expired + empty).forEach { pack ->
                        PackCard(
                            pack,
                            item,
                            texts,
                            first = usable.size > 1 && pack.id == usable.first().id,
                            onOpen = { nav.open(Route.PackEdit(medicineId, pack.id)) },
                            onRemove = {
                                removedId = pack.id
                                scope.launch { repo.disposePack(pack.id) }
                            },
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
        Column(Modifier.fillMaxWidth().background(p.bg).padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 20.dp)) {
            MedMainButton(stringResource(R.string.stock_add_pack), onClick = { nav.open(Route.PackEdit(medicineId, 0)) })
        }
    }
}

/** Итог по запасу: «Осталось 34 табл. · хватит на 17 дней», полоса и пояснение, откуда цифры. */
@Composable
private fun SupplyCard(item: MedStock, texts: MedTexts) {
    val p = AnvilTheme.palette
    val days = item.days
    val left = stringResource(R.string.stock_left_total, texts.dose(item.left, item.med.unit))
    val headline = if (days == null) left else left + " · " + stringResource(R.string.stock_enough, texts.days(days))
    AnvilCard(Modifier.semantics(mergeDescendants = true) {}) {
        Text(
            headline,
            color = if (item.low) p.warning else p.text,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        if (days != null) StockBar(days / 30f, warn = item.low)
        Text(
            if (days != null) {
                stringResource(R.string.stock_by_plan, texts.dose(item.need, item.med.unit))
            } else {
                stringResource(R.string.stock_no_forecast)
            },
            color = p.weak,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (item.hasExpired) {
            Text(stringResource(R.string.stock_not_counted), color = p.warning, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Упаковка в списке: остаток, «годна до …» с остатком срока, «вскрыта … · ещё N дней», серия. Касание — правка, под
 * строкой — «Убрать из аптечки». [first] — упаковка, из которой принимается в первую очередь.
 */
@Composable
private fun PackCard(pack: Pack, item: MedStock, texts: MedTexts, first: Boolean, onOpen: () -> Unit, onRemove: () -> Unit) {
    val p = AnvilTheme.palette
    val today = item.today
    val unit = item.med.unit
    val amount = texts.dose(pack.left, unit)
    val until = pack.goodUntil()
    val expired = pack.left > 0 && pack.isExpired(today)
    val empty = pack.left <= 0
    val opened = pack.openedAt
    val daysToEnd = until?.let { ChronoUnit.DAYS.between(today, it) }
    val openedLimited = opened != null && pack.afterOpenDays > 0

    // Первая строка: срок. Остаток срока — у невскрытых; у вскрытых он в строке о вскрытии.
    val expiryDate = texts.packExpiry(pack)
    var expiryWarn = expired
    val expiryLine = when {
        empty -> stringResource(R.string.stock_pack_empty)
        expired -> stringResource(R.string.stock_pack_expired) + (expiryDate?.let { " · $it" } ?: "")
        expiryDate == null -> stringResource(R.string.stock_pack_no_expiry)
        !openedLimited && until != null -> {
            expiryWarn = daysToEnd != null && daysToEnd <= SOON_WARN_DAYS
            stringResource(R.string.stock_pack_until, expiryDate) + " · " + stringResource(R.string.stock_state_left, texts.timeLeftText(timeLeft(until, today)))
        }
        else -> stringResource(R.string.stock_pack_until, expiryDate)
    }
    val openedLine = when {
        opened == null -> null
        openedLimited && until != null && !expired -> stringResource(
            R.string.stock_pack_opened_left,
            texts.dayMonth(opened),
            texts.timeLeftText(timeLeft(until, today)),
        )
        else -> stringResource(R.string.stock_pack_opened, texts.dayMonth(opened))
    }
    val openedWarn = openedLimited && !expired && daysToEnd != null && daysToEnd <= OPENED_WARN_DAYS

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.card)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        amount,
                        color = if (empty) p.weak else p.text,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                    if (first) {
                        Text(
                            stringResource(R.string.stock_pack_first),
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Ampulse.colors.accentSoft)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            color = p.accentText,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        )
                    }
                }
                Text(expiryLine, color = if (expiryWarn) p.warning else p.weak, style = MaterialTheme.typography.bodyMedium)
                if (openedLine != null) {
                    Text(openedLine, color = if (openedWarn) p.warning else p.weak, style = MaterialTheme.typography.bodyMedium)
                }
                if (pack.batch.isNotBlank()) {
                    Text(stringResource(R.string.stock_pack_batch, pack.batch), color = p.faint, style = MaterialTheme.typography.bodySmall)
                }
            }
            Icon(MedIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp), tint = p.faint)
        }
        StockLinkButton(
            stringResource(R.string.stock_remove),
            onClick = onRemove,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            color = p.weak,
            description = stringResource(R.string.stock_remove_pack, amount),
        )
    }
}
