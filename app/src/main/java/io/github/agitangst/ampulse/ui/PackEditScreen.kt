package io.github.agitangst.ampulse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.agitangst.ampulse.Ampulse
import io.github.agitangst.ampulse.AmpulseIcons
import io.github.agitangst.ampulse.AppGraph
import io.github.agitangst.ampulse.Nav
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.domain.ExpiryForecast
import io.github.agitangst.ampulse.domain.Pack
import io.github.agitangst.ampulse.domain.dailyNeed
import io.github.agitangst.ampulse.domain.endOfMonth
import io.github.agitangst.ampulse.domain.goodUntil
import io.github.agitangst.ampulse.domain.timeLeft
import io.github.agitangst.ampulse.screenTitleStyle
import io.github.agitangst.anvil.compose.AnvilCard
import io.github.agitangst.anvil.compose.AnvilTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Остаток срока 30 дней и меньше — цвет внимания (docs/ui-guideline.md, §6). */
private const val SOON_DAYS = 30

/** Число месяца, с которого начинается ввод, когда на упаковке указан день. */
private const val DEFAULT_DAY = 15

/** Предел шагового поля остатка и срока после вскрытия. */
private const val MAX_LEFT = 100_000.0
private const val MAX_AFTER_OPEN = 365

/** Сколько лет вперёд предлагается в выборе года: текущий и пять следующих. */
private const val YEARS_AHEAD = 5

/**
 * Новая упаковка или правка (макет, экран 41, вариант «вручную»): остаток шаговым полем (шаг по форме: таблетки 1, ЕД
 * 10), срок — год чипами и месяц сеткой 4×3 с числом на упаковке по желанию, «уже вскрыта» со сроком после вскрытия.
 * Внизу итог: «Годна до … · ещё …» и прогноз «успеете ли». С `fromScan` срок, серия, номер и GTIN берутся из
 * [PendingScan]. Та же упаковка (тот же код) уже в аптечке — запас не задваивается, экран говорит об этом.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PackEditScreen(medicineId: Long, packId: Long, fromScan: Boolean, nav: Nav) {
    val repo = AppGraph.repository
    val texts = rememberMedTexts()
    val scope = rememberCoroutineScope()
    val p = AnvilTheme.palette
    val today = LocalDate.now()

    val medicines by remember { repo.medicines }.collectAsState(initial = null)
    val schedules by remember { repo.schedules }.collectAsState(initial = null)
    val packs by remember { repo.packs }.collectAsState(initial = null)

    // Черновик: простые поля, чтобы переживать пересоздание экрана (поворот, смену языка).
    var loaded by rememberSaveable(packId) { mutableStateOf(false) }
    var year by rememberSaveable { mutableStateOf(0) }
    var month by rememberSaveable { mutableStateOf(0) }
    var hasDay by rememberSaveable { mutableStateOf(false) }
    var day by rememberSaveable { mutableStateOf(1) }
    var left by rememberSaveable { mutableStateOf(0.0) }
    var opened by rememberSaveable { mutableStateOf(false) }
    var openedDay by rememberSaveable { mutableStateOf(today.toEpochDay()) }
    var afterOpen by rememberSaveable { mutableStateOf(0) }
    var batch by rememberSaveable { mutableStateOf("") }
    var serial by rememberSaveable { mutableStateOf("") }
    var gtin by rememberSaveable { mutableStateOf("") }
    // У упаковки без срока (из кода без поля срока) сохранить без срока можно, у новой — нет.
    var expiryOptional by rememberSaveable { mutableStateOf(false) }
    var dateDialog by rememberSaveable { mutableStateOf(false) }
    var duplicate by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val list = medicines
    val plans = schedules
    val allPacks = packs
    val med = list?.firstOrNull { it.id == medicineId }
    val ready = list != null && plans != null && allPacks != null

    LaunchedEffect(ready) {
        if (!ready || loaded) return@LaunchedEffect
        val existing = allPacks?.firstOrNull { it.id == packId }
        if (med == null || (packId != 0L && existing == null)) {
            nav.back()
            return@LaunchedEffect
        }
        left = defaultLeft(med.form)
        afterOpen = defaultAfterOpen(med.form)
        if (existing != null) {
            left = existing.left
            existing.expiry?.let {
                year = it.year
                month = it.monthValue
                day = it.dayOfMonth
            } ?: run { expiryOptional = true }
            hasDay = existing.expiryHasDay
            existing.openedAt?.let {
                opened = true
                openedDay = it.toEpochDay()
            }
            if (existing.afterOpenDays > 0 || existing.openedAt != null) afterOpen = existing.afterOpenDays
            batch = existing.batch
            serial = existing.serial
            gtin = existing.gtin
        } else if (fromScan) {
            PendingScan.code?.let { code ->
                code.expiry?.let {
                    year = it.year
                    month = it.monthValue
                    day = it.dayOfMonth
                    hasDay = code.expiryHasDay
                }
                batch = code.batch
                serial = code.serial
                gtin = code.gtin
            }
            PendingScan.code = null
        }
        loaded = true
    }

    // Срок и черновик упаковки.
    val lastDay = if (year > 0 && month > 0) endOfMonth(year, month).dayOfMonth else 31
    val dayShown = day.coerceIn(1, lastDay)
    val expiry: LocalDate? = if (year > 0 && month > 0) {
        if (hasDay) LocalDate.of(year, month, dayShown) else endOfMonth(year, month)
    } else {
        null
    }
    val draft = Pack(
        id = packId,
        medicineId = medicineId,
        left = left,
        expiry = expiry,
        expiryHasDay = hasDay && expiry != null,
        openedAt = if (opened) LocalDate.ofEpochDay(openedDay) else null,
        afterOpenDays = afterOpen,
        batch = batch,
        serial = serial,
        gtin = gtin,
    )
    val problem = when {
        expiry == null && !expiryOptional -> R.string.stock_need_expiry
        left <= 0.0 && packId == 0L -> R.string.stock_need_amount
        else -> null
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MedBackRow(
                stringResource(if (packId == 0L) R.string.meds_cancel else R.string.stock_back_stock),
                nav::back,
                Modifier.offset(x = (-8).dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(if (packId == 0L) R.string.stock_pack_new_title else R.string.stock_pack_edit_title),
                    Modifier.semantics { heading() },
                    color = p.text,
                    style = screenTitleStyle(30),
                )
                if (med != null) {
                    Text(todayMedName(med.name, med.strength), color = p.weak, style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (!loaded || med == null || allPacks == null) return@Column

            val unit = med.unit
            val step = leftStep(med.form)
            val plan = plans?.firstOrNull { it.medicineId == medicineId }?.plan
            val need = plan?.let { dailyNeed(it, today) } ?: 0.0
            val others = allPacks.filter { it.medicineId == medicineId && it.id != packId }

            if (batch.isNotBlank() || serial.isNotBlank() || gtin.isNotBlank()) CodeInfo(batch, serial, gtin)

            // Остаток.
            AnvilCard {
                MedStepRow(
                    stringResource(R.string.stock_amount_label),
                    texts.dose(left, unit),
                    onLess = { left = (left - step).coerceAtLeast(0.0) },
                    onMore = { left = (left + step).coerceAtMost(MAX_LEFT) },
                    lessEnabled = left > 0.0,
                    moreEnabled = left < MAX_LEFT,
                )
            }

            // Срок: год, месяц, число.
            StockSectionHeader(stringResource(R.string.stock_expiry_h), null)
            AnvilCard {
                Text(
                    stringResource(R.string.stock_year),
                    color = p.weak,
                    style = MaterialTheme.typography.labelLarge,
                )
                val years = (today.year..today.year + YEARS_AHEAD).toMutableList()
                    .apply { if (year > 0 && year !in this) add(year) }
                    .sorted()
                FlowRow(
                    Modifier.selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    years.forEach { y -> MedChip(y.toString(), selected = y == year, onClick = { year = y }, minWidth = 72.dp) }
                }
                Text(
                    stringResource(R.string.stock_month),
                    Modifier.padding(top = 4.dp),
                    color = p.weak,
                    style = MaterialTheme.typography.labelLarge,
                )
                MonthGrid(month, texts, onPick = { month = it })
                Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
                MedSwitchRow(
                    stringResource(R.string.stock_day_toggle),
                    stringResource(R.string.stock_day_toggle_hint),
                    hasDay,
                    { on ->
                        hasDay = on
                        if (on) day = DEFAULT_DAY.coerceAtMost(lastDay)
                    },
                )
                if (hasDay) {
                    MedStepRow(
                        stringResource(R.string.stock_day_label),
                        dayShown.toString(),
                        onLess = { day = (dayShown - 1).coerceAtLeast(1) },
                        onMore = { day = (dayShown + 1).coerceAtMost(lastDay) },
                        lessEnabled = dayShown > 1,
                        moreEnabled = dayShown < lastDay,
                    )
                }
            }

            // Вскрытие.
            AnvilCard {
                MedSwitchRow(
                    stringResource(R.string.stock_opened_title),
                    stringResource(R.string.stock_opened_hint),
                    opened,
                    { opened = it },
                )
                if (opened) {
                    val openedDate = LocalDate.ofEpochDay(openedDay)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.stock_opened_on),
                            Modifier.weight(1f),
                            color = p.text,
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        )
                        MedValueButton(
                            texts.date(openedDate),
                            AmpulseIcons.Calendar,
                            stringResource(R.string.stock_opened_change, texts.date(openedDate)),
                            onClick = { dateDialog = true },
                        )
                    }
                    MedStepRow(
                        stringResource(R.string.stock_after_open_label),
                        if (afterOpen == 0) "0" else texts.days(afterOpen),
                        onLess = { afterOpen = (afterOpen - 1).coerceAtLeast(0) },
                        onMore = { afterOpen = (afterOpen + 1).coerceAtMost(MAX_AFTER_OPEN) },
                        lessEnabled = afterOpen > 0,
                        moreEnabled = afterOpen < MAX_AFTER_OPEN,
                    )
                    Text(stringResource(R.string.stock_after_open_hint), color = p.weak, style = MaterialTheme.typography.bodySmall)
                }
            }

            PackSummary(draft, hasDay, others, need, unit, texts, today)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
        Column(
            Modifier.fillMaxWidth().background(p.bg).padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (duplicate) {
                MedNotice(stringResource(R.string.stock_duplicate), icon = TodayIcons.Check)
            } else if (loaded && problem != null) {
                Text(
                    stringResource(problem),
                    Modifier.fillMaxWidth(),
                    color = p.weak,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
            MedMainButton(
                stringResource(if (duplicate) R.string.stock_done else R.string.stock_save),
                enabled = loaded && !busy && (duplicate || problem == null),
                onClick = {
                    if (duplicate) {
                        nav.back()
                    } else {
                        busy = true
                        val pack = draft
                        scope.launch {
                            if (packId == 0L) {
                                val before = repo.packs.first().map { it.id }.toSet()
                                val id = repo.addPack(pack)
                                val after = repo.packs.first()
                                when {
                                    // Тот же код уже в аптечке: запас не растёт.
                                    id in before -> {
                                        duplicate = true
                                        busy = false
                                    }
                                    // Тот же код, но упаковку раньше убрали: вернуть её и записать введённое.
                                    after.none { it.id == id } -> {
                                        repo.restorePack(id)
                                        repo.updatePack(pack.copy(id = id))
                                        nav.back()
                                    }
                                    else -> nav.back()
                                }
                            } else {
                                repo.updatePack(pack)
                                nav.back()
                            }
                        }
                    }
                },
            )
        }
    }

    if (dateDialog) {
        MedDateDialog(
            initial = LocalDate.ofEpochDay(openedDay),
            // Вскрытие не бывает в будущем.
            onConfirm = { d ->
                openedDay = minOf(d, today).toEpochDay()
                dateDialog = false
            },
            onDismiss = { dateDialog = false },
        )
    }
}

/** Строка «Из кода: серия …» с номером и GTIN: что прочитано с упаковки (подставлено в поля). */
@Composable
private fun CodeInfo(batch: String, serial: String, gtin: String) {
    val p = AnvilTheme.palette
    val head = listOfNotNull(
        batch.takeIf { it.isNotBlank() }?.let { stringResource(R.string.stock_code_batch, it) },
        serial.takeIf { it.isNotBlank() }?.let { stringResource(R.string.stock_code_serial, it) },
    )
    val gtinText = gtin.takeIf { it.isNotBlank() }?.let { stringResource(R.string.stock_code_gtin, it) }
    val first = head.ifEmpty { listOfNotNull(gtinText) }.joinToString(" · ")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(p.card)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StockTile(StockIcons.Scan, soft = true)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.stock_from_code, first), color = p.text, style = MaterialTheme.typography.bodyMedium)
            if (head.isNotEmpty() && gtinText != null) {
                Text(gtinText, color = p.faint, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Месяцы сеткой 4×3: на кнопке номер как на упаковке («08») и название мелко; цель не меньше 52 dp. */
@Composable
private fun MonthGrid(selected: Int, texts: MedTexts, onPick: (Int) -> Unit) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in 0 until 3) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (col in 0 until 4) {
                    val m = row * 4 + col + 1
                    MonthCell(m, texts, m == selected, onClick = { onPick(m) }, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MonthCell(month: Int, texts: MedTexts, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val p = AnvilTheme.palette
    val number = month.toString().padStart(2, '0')
    val shape = RoundedCornerShape(14.dp)
    val ink = if (selected) p.bg else p.text
    Column(
        modifier
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(if (selected) p.text else p.card)
            .border(1.5.dp, if (selected) p.text else p.borderStrong, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = texts.string(R.string.stock_month_desc, number, texts.monthFull(month)) }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Подпись для TalkBack уже у самой ячейки: «08 — август».
        Column(Modifier.clearAndSetSemantics {}, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(number, color = ink, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
            Text(
                texts.monthShort(month),
                color = if (selected) p.bg else p.faint,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 14.sp),
            )
        }
    }
}

/**
 * Итог по упаковке (макет, экран 41): срок и остаток срока, при вскрытии — до какого дня, прогноз «успеете ли».
 * Срок вышел — упаковка в запас не войдёт. Срок не выбран — подсказка.
 */
@Composable
private fun PackSummary(pack: Pack, hasDay: Boolean, others: List<Pack>, need: Double, unit: String, texts: MedTexts, today: LocalDate) {
    val p = AnvilTheme.palette
    val expiry = pack.expiry
    if (expiry == null) {
        Text(stringResource(R.string.stock_pick_hint), color = p.weak, style = MaterialTheme.typography.bodyMedium)
        return
    }
    val until = pack.goodUntil() ?: expiry
    val expired = until < today
    val limitedByOpening = pack.openedAt != null && pack.afterOpenDays > 0 && until < expiry
    val daysToEnd = ChronoUnit.DAYS.between(today, until)
    val soon = !expired && daysToEnd <= SOON_DAYS
    val forecast = if (expired) ExpiryForecast.Unknown else packForecast(pack, others, need, today)
    val warn = expired || soon || forecast is ExpiryForecast.Leftover
    val titleDate = if (limitedByOpening) texts.date(until) else texts.packExpiry(pack).orEmpty()

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(if (warn) Ampulse.colors.warnSoft else Ampulse.colors.accentSoft)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (warn) Ampulse.colors.warnLine else p.accent),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (expired) {
                Text(
                    stringResource(R.string.stock_state_expired),
                    color = p.warning,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    titleDate + ". " + stringResource(R.string.stock_state_expired_note),
                    color = p.weak,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    stringResource(R.string.stock_state_until, titleDate),
                    color = if (soon) p.warning else p.text,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                )
                val left = texts.timeLeftText(timeLeft(until, today))
                val note = stringResource(if (soon) R.string.stock_state_soon else R.string.stock_state_left, left) +
                    if (!hasDay && !limitedByOpening) " · " + stringResource(R.string.stock_state_month_end) else ""
                Text(note, color = p.weak, style = MaterialTheme.typography.bodyMedium)
            }
            if (limitedByOpening && pack.openedAt != null) {
                Text(
                    stringResource(R.string.stock_state_open_line, texts.dayMonth(pack.openedAt), texts.days(pack.afterOpenDays)),
                    color = p.weak,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            when (forecast) {
                is ExpiryForecast.Fits -> Text(
                    stringResource(R.string.stock_fits, texts.date(forecast.runsOut)),
                    color = p.accentText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                )
                is ExpiryForecast.Leftover -> Text(
                    stringResource(R.string.stock_leftover, texts.approx(forecast.left, unit)),
                    color = p.warning,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                )
                ExpiryForecast.Unknown -> Unit
            }
        }
    }
}
