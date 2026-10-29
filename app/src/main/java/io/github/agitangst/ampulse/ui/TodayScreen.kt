package io.github.agitangst.ampulse.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.agitangst.ampulse.Ampulse
import io.github.agitangst.ampulse.AmpulseIcons
import io.github.agitangst.ampulse.AppGraph
import io.github.agitangst.ampulse.EmptyState
import io.github.agitangst.ampulse.Nav
import io.github.agitangst.ampulse.PageLayout
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.Route
import io.github.agitangst.ampulse.SettingsButton
import io.github.agitangst.ampulse.TabHeader
import io.github.agitangst.ampulse.domain.DayItem
import io.github.agitangst.ampulse.domain.DoseEvent
import io.github.agitangst.ampulse.domain.DoseState
import io.github.agitangst.ampulse.domain.EventStatus
import io.github.agitangst.ampulse.domain.FoodRule
import io.github.agitangst.ampulse.domain.MarkCheck
import io.github.agitangst.ampulse.domain.MedForm
import io.github.agitangst.ampulse.domain.Medicine
import io.github.agitangst.ampulse.domain.PlanKind
import io.github.agitangst.ampulse.domain.PrnCheck
import io.github.agitangst.ampulse.domain.Schedule
import io.github.agitangst.ampulse.domain.checkPrn
import io.github.agitangst.ampulse.domain.dayItems
import io.github.agitangst.ampulse.domain.progress
import io.github.agitangst.ampulse.todayText
import io.github.agitangst.anvil.compose.AnvilCard
import io.github.agitangst.anvil.compose.AnvilTheme
import io.github.agitangst.anvil.compose.Motion
import io.github.agitangst.anvil.compose.SectionTitle
import io.github.agitangst.anvil.compose.motionSpec
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Часть дня для групп ленты: утро — до 12, день — с 12 до 18, вечер — с 18. */
private enum class TodayPart(val titleRes: Int) {
    Morning(R.string.today_group_morning),
    Day(R.string.today_group_day),
    Evening(R.string.today_group_evening),
}

private fun partOf(at: LocalDateTime) = when {
    at.hour < 12 -> TodayPart.Morning
    at.hour < 18 -> TodayPart.Day
    else -> TodayPart.Evening
}

/** Что открыто листом поверх «Сегодня». */
private sealed interface TodaySheetState {
    data class Skip(val item: DayItem) : TodaySheetState
    data class DoubleDose(val item: DayItem, val markedAt: LocalDateTime) : TodaySheetState
}

/** Строка блока «По необходимости»: лекарство и можно ли принять сейчас. */
private data class TodayPrnLine(val medicine: Medicine, val check: PrnCheck)

/** Записанный лишний приём: id отметки (для «Отменить запись») и время. */
private data class TodayLogged(val id: Long?, val at: LocalDateTime)

private const val SEP = " · "

/**
 * «Сегодня» (макет, экран 1): кольцо дня, приёмы по времени (утро, день, вечер) с отметкой одним касанием, пропуск
 * с причиной, лист «уже отмечен» (экран 15) и блок «По необходимости». Данные — из [AppGraph.repository].
 */
@Composable
fun TodayScreen(nav: Nav, modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    val repo = AppGraph.repository
    val scope = rememberCoroutineScope()
    val now = rememberTodayNow()
    val today = now.toLocalDate()
    val time = rememberTodayTime()
    val medicines by repo.medicines.collectAsState<List<Medicine>, List<Medicine>?>(initial = null)
    val schedules by repo.schedules.collectAsState(initial = emptyList())
    // Вчера тоже: поздний приём мог быть отмечен после полуночи, а ранний — накануне.
    val events by remember(today) { repo.events(today.minusDays(1), today.plusDays(1)) }.collectAsState(initial = emptyList())
    var sheet by remember { mutableStateOf<TodaySheetState?>(null) }
    val meds = medicines

    val items = remember(meds, schedules, events, now) {
        if (meds == null) emptyList<DayItem>() else dayItems(today, meds, schedules, events, now)
    }
    val prn = remember(meds, schedules, events, now) { prnLines(meds.orEmpty(), schedules, events, now) }

    val onTake: (DayItem) -> Unit = { item ->
        scope.launch {
            val check = repo.mark(item.planned, EventStatus.Taken, LocalDateTime.now())
            // Повторная отметка не записывается — показываем, когда приём уже отмечен (защита от двойной дозы).
            if (check is MarkCheck.AlreadyTaken) sheet = TodaySheetState.DoubleDose(item, check.at)
        }
    }
    val onUndo: (DayItem) -> Unit = { item -> item.event?.let { e -> scope.launch { repo.undo(e.id) } } }

    PageLayout(
        modifier,
        header = {
            TabHeader(
                stringResource(R.string.tab_today),
                date = todayText(),
                action = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsButton { nav.open(Route.Settings) }
                        AddButton { nav.open(Route.MedicineEdit(0)) }
                    }
                },
            )
        },
    ) {
        when {
            meds == null -> Spacer(Modifier.weight(1f))
            meds.isEmpty() -> {
                PressureTodayCard(nav)
                EmptyState(
                    AmpulseIcons.Pill,
                    stringResource(R.string.today_empty_title),
                    stringResource(R.string.today_empty_hint),
                    Modifier.weight(1f),
                )
                TodayButton(
                    stringResource(R.string.today_add_medicine),
                    onClick = { nav.open(Route.MedicineEdit(0)) },
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.5.dp, p.text),
                    height = 54,
                )
            }
            else -> DayContent(
                items = items,
                prn = prn,
                time = time,
                today = today,
                onTake = onTake,
                onSkip = { sheet = TodaySheetState.Skip(it) },
                onUndo = onUndo,
                onTakeAsNeeded = { nav.open(Route.AsNeeded(it)) },
                pressureCard = { PressureTodayCard(nav) },
            )
        }
    }

    when (val s = sheet) {
        null -> Unit
        is TodaySheetState.Skip -> SkipSheet(
            item = s.item,
            time = time,
            onPick = { reason ->
                scope.launch { repo.mark(s.item.planned, EventStatus.Skipped, LocalDateTime.now(), reason) }
            },
            onDismiss = { sheet = null },
        )
        is TodaySheetState.DoubleDose -> {
            val next = nextDose(s.item.medicine.id, schedules, now)
            DoubleDoseSheet(
                item = s.item,
                markedAt = s.markedAt,
                time = time,
                nextLine = next?.let { whenText(it, today, time, withToday = true) },
                onLog = {
                    val at = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
                    repo.logExtra(s.item.medicine.id, s.item.planned.amount, at)
                    // logExtra id не возвращает — находим запись по лекарству и времени, чтобы её можно было отменить.
                    val id = repo.eventsNow(at.minusSeconds(1), at.plusSeconds(1))
                        .lastOrNull { it.medicineId == s.item.medicine.id && it.status == EventStatus.Extra && it.at == at }?.id
                    TodayLogged(id, at)
                },
                onUndoLog = { repo.undo(it) },
                onDismiss = { sheet = null },
            )
        }
    }
}

/** Содержимое при наличии лекарств: итог дня, группы по времени суток, «По необходимости». */
@Composable
private fun ColumnScope.DayContent(
    items: List<DayItem>,
    prn: List<TodayPrnLine>,
    time: DateTimeFormatter,
    today: LocalDate,
    onTake: (DayItem) -> Unit,
    onSkip: (DayItem) -> Unit,
    onUndo: (DayItem) -> Unit,
    onTakeAsNeeded: (Long) -> Unit,
    pressureCard: @Composable () -> Unit,
) {
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (items.isEmpty()) {
            FreeDayCard()
        } else {
            SummaryCard(items, time)
            TodayPart.entries.forEach { part ->
                val group = items.filter { partOf(it.planned.at) == part }
                if (group.isNotEmpty()) {
                    DoseGroup(stringResource(part.titleRes), group, time, onTake, onSkip, onUndo)
                }
            }
        }
        if (prn.isNotEmpty()) PrnSection(prn, today, time, onTakeAsNeeded)
        pressureCard()
    }
}

/** Круглая тёмная «+»: новое лекарство. Цель нажатия — 48 dp. */
@Composable
private fun AddButton(onClick: () -> Unit) {
    val p = AnvilTheme.palette
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(p.text).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            TodayIcons.Plus,
            contentDescription = stringResource(R.string.today_add_medicine),
            modifier = Modifier.size(22.dp),
            tint = p.card,
        )
    }
}

/** Сегодня по расписанию ничего нет (лекарства есть, но у них другие дни). */
@Composable
private fun FreeDayCard() {
    val p = AnvilTheme.palette
    AnvilCard(Modifier.semantics(mergeDescendants = true) {}) {
        Text(stringResource(R.string.today_empty_title), color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.today_free_hint), color = p.weak, fontSize = 14.sp)
    }
}

/** Карточка итога: кольцо «принято из всего» и строка про следующий приём. */
@Composable
private fun SummaryCard(items: List<DayItem>, time: DateTimeFormatter) {
    val p = AnvilTheme.palette
    val (taken, total) = progress(items)
    val overdue = items.firstOrNull { it.state == DoseState.Overdue }
    val next = items.firstOrNull { it.state == DoseState.Due || it.state == DoseState.Later }
    val title = if (overdue == null && next == null) {
        stringResource(R.string.today_summary_all_marked)
    } else {
        stringResource(R.string.today_summary_taken, taken, total)
    }
    val line = when {
        overdue != null -> stringResource(
            R.string.today_unmarked,
            todayMedName(overdue.medicine.name, overdue.medicine.strength),
            time.format(overdue.planned.at),
        )
        next != null -> stringResource(
            R.string.today_next,
            todayMedName(next.medicine.name, next.medicine.strength),
            time.format(next.planned.at),
        )
        else -> stringResource(R.string.today_all_done)
    }
    AnvilCard(Modifier.semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            DayRing(taken, total)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(line, color = p.weak, fontSize = 14.sp, lineHeight = 19.sp)
            }
        }
    }
}

/** Кольцо дня 60 dp: дорожка и дуга «принято из всего», в центре — «2/6». Для TalkBack скрыто: то же сказано в заголовке. */
@Composable
private fun DayRing(taken: Int, total: Int) {
    val p = AnvilTheme.palette
    val track = Ampulse.colors.track
    val fraction by animateFloatAsState(
        targetValue = if (total == 0) 0f else taken.toFloat() / total,
        animationSpec = motionSpec(Motion.GRAPH),
        label = "ring",
    )
    Box(Modifier.size(60.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 6.dp.toPx()
            val topLeft = Offset(stroke / 2, stroke / 2)
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, topLeft, arc, style = Stroke(stroke))
            if (fraction > 0f) {
                drawArc(p.accent, -90f, 360f * fraction, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Text(
            "$taken/$total",
            color = p.text,
            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
        )
    }
}

/** Группа ленты: подпись раздела со счётчиком «принято из всех» и строки приёмов. */
@Composable
private fun DoseGroup(
    title: String,
    items: List<DayItem>,
    time: DateTimeFormatter,
    onTake: (DayItem) -> Unit,
    onSkip: (DayItem) -> Unit,
    onUndo: (DayItem) -> Unit,
) {
    val p = AnvilTheme.palette
    val done = items.count { it.state == DoseState.Taken }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(title, Modifier.weight(1f))
            Text(stringResource(R.string.today_group_meta, done, items.size), color = p.faint, fontSize = 13.sp)
        }
        items.forEach { item ->
            key(item.planned.scheduleId, item.planned.at) {
                DoseRow(item, time, onTake = { onTake(item) }, onSkip = { onSkip(item) }, onUndo = { onUndo(item) })
            }
        }
    }
}

/**
 * Строка приёма (docs/ui-guideline.md, §6): плитка значка → имя с дозировкой → вторичная строка → справа состояние.
 * Принято — круг с галочкой, пора — главная «Принять», просрочено — рамка и кнопка внимания, позже — пустое кольцо,
 * пропущено — слово. Справа ещё «⋯» с действиями: пропустить или отменить отметку.
 */
@Composable
private fun DoseRow(item: DayItem, time: DateTimeFormatter, onTake: () -> Unit, onSkip: () -> Unit, onUndo: () -> Unit) {
    val p = AnvilTheme.palette
    val c = Ampulse.colors
    val state = item.state
    val name = todayMedName(item.medicine.name, item.medicine.strength)
    val (sub, subColor) = subLine(item, time)
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .clip(shape)
            .background(p.card)
            .then(if (state == DoseState.Overdue) Modifier.border(1.5.dp, c.warnLine, shape) else Modifier)
            .padding(start = 14.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FormTile(item.medicine.form, accent = state == DoseState.Taken)
        Column(
            Modifier.weight(1f).semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(name, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(sub, color = subColor, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        when (state) {
            DoseState.Taken -> TakenMark(name, onUndo)
            DoseState.Due -> TakeButton(name, overdue = false, onClick = onTake)
            DoseState.Overdue -> TakeButton(name, overdue = true, onClick = onTake)
            DoseState.Later -> EarlyRing(name, onTake)
            DoseState.Skipped -> Text(
                stringResource(R.string.today_state_skipped),
                color = p.weak,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (state != DoseState.Taken) RowMenu(name, skipped = state == DoseState.Skipped, onSkip = onSkip, onUndo = onUndo)
    }
}

/** Плитка значка 40×40: у принятого — мягкий акцент. */
@Composable
private fun FormTile(form: MedForm, accent: Boolean) {
    val p = AnvilTheme.palette
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (accent) Ampulse.colors.accentSoft else p.raised),
        contentAlignment = Alignment.Center,
    ) {
        Icon(todayFormIcon(form), contentDescription = null, modifier = Modifier.size(22.dp), tint = if (accent) p.accent else p.weak)
    }
}

/** Вторичная строка и её цвет по состоянию. */
@Composable
private fun subLine(item: DayItem, time: DateTimeFormatter): Pair<String, Color> {
    val p = AnvilTheme.palette
    val m = item.medicine
    val plannedTime = time.format(item.planned.at)
    val dose = todayDoseText(item.planned.amount, m.unit)
    val plain = listOfNotNull(plannedTime, dose, foodText(m.food)).joinToString(SEP)
    val overdueWord = stringResource(R.string.today_state_overdue)
    val takenAt = item.event?.at?.let { time.format(it) } ?: plannedTime
    val reason = item.event?.note.orEmpty()
    return when (item.state) {
        DoseState.Taken -> stringResource(
            if (m.form == MedForm.Injection) R.string.today_sub_done else R.string.today_sub_taken,
            takenAt,
        ) to p.accentText
        DoseState.Skipped ->
            (if (reason.isNotBlank()) stringResource(R.string.today_sub_skipped, reason) else plain) to p.weak
        DoseState.Overdue -> listOf(overdueWord, plannedTime, dose).joinToString(SEP) to p.warning
        DoseState.Due -> plain to p.text
        DoseState.Later -> plain to p.faint
    }
}

@Composable
private fun foodText(food: FoodRule): String? = when (food) {
    FoodRule.None -> null
    FoodRule.EmptyStomach -> stringResource(R.string.today_food_empty)
    FoodRule.Before -> stringResource(R.string.today_food_before)
    FoodRule.With -> stringResource(R.string.today_food_with)
    FoodRule.After -> stringResource(R.string.today_food_after)
}

/** Принято: круг с галочкой, залитый акцентом; касание открывает «Отменить отметку». */
@Composable
private fun TakenMark(name: String, onUndo: () -> Unit) {
    val p = AnvilTheme.palette
    var menu by remember { mutableStateOf(false) }
    val undo = stringResource(R.string.today_menu_undo)
    Box {
        Box(
            Modifier.size(48.dp).clip(CircleShape).clickable(onClickLabel = undo, role = Role.Button) { menu = true },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(p.accent), contentAlignment = Alignment.Center) {
                Icon(
                    TodayIcons.Check,
                    contentDescription = stringResource(R.string.today_taken_named, name),
                    modifier = Modifier.size(22.dp),
                    tint = p.onAccent,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(undo) }, onClick = { menu = false; onUndo() })
        }
    }
}

/** Главная «Принять» (пора) или та же в цвете внимания (просрочено). */
@Composable
private fun TakeButton(name: String, overdue: Boolean, onClick: () -> Unit) {
    val p = AnvilTheme.palette
    val c = Ampulse.colors
    val desc = stringResource(R.string.today_take_named, name)
    TodayButton(
        stringResource(R.string.today_take),
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = desc },
        container = if (overdue) c.warnSoft else p.accent,
        content = if (overdue) p.warning else p.onAccent,
        border = if (overdue) BorderStroke(1.5.dp, c.warnLine) else null,
        paddingH = 14,
    )
}

/** Позже: пустое кольцо; касание отмечает приём досрочно. */
@Composable
private fun EarlyRing(name: String, onClick: () -> Unit) {
    val p = AnvilTheme.palette
    val desc = stringResource(R.string.today_take_named, name)
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(40.dp).border(2.dp, p.borderStrong, CircleShape))
    }
}

/** «⋯» у строки: пропустить (с причиной) или, если уже пропущено, отменить отметку. */
@Composable
private fun RowMenu(name: String, skipped: Boolean, onSkip: () -> Unit, onUndo: () -> Unit) {
    val p = AnvilTheme.palette
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button) { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                TodayIcons.More,
                contentDescription = stringResource(R.string.today_more_actions, name),
                modifier = Modifier.size(22.dp),
                tint = p.weak,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (skipped) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.today_menu_undo)) },
                    onClick = { open = false; onUndo() },
                )
            } else {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.today_menu_skip)) },
                    onClick = { open = false; onSkip() },
                )
            }
        }
    }
}

/** «По необходимости»: лекарства без расписания; «Принять…» открывает экран приёма (интервал, лимит, подтверждение). */
@Composable
private fun PrnSection(lines: List<TodayPrnLine>, today: LocalDate, time: DateTimeFormatter, onTake: (Long) -> Unit) {
    val p = AnvilTheme.palette
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.today_prn_title), Modifier.padding(horizontal = 4.dp))
        lines.forEach { line ->
            key(line.medicine.id) {
                val m = line.medicine
                val name = todayMedName(m.name, m.strength)
                val (sub, subColor) = when (val check = line.check) {
                    PrnCheck.Allowed -> stringResource(R.string.today_prn_ready) to p.weak
                    is PrnCheck.TooSoon -> stringResource(
                        R.string.today_prn_wait,
                        whenText(check.nextAllowed, today, time, withToday = false),
                        check.takenToday,
                    ) to p.weak
                    is PrnCheck.LimitReached -> stringResource(
                        R.string.today_prn_limit,
                        whenText(check.nextAllowed, today, time, withToday = false),
                    ) to p.warning
                }
                val desc = stringResource(R.string.today_prn_take_named, name)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 68.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(p.card)
                        .padding(start = 14.dp, top = 8.dp, end = 10.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FormTile(m.form, accent = false)
                    Column(
                        Modifier.weight(1f).semantics(mergeDescendants = true) {},
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(name, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(sub, color = subColor, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    TodayButton(
                        stringResource(R.string.today_prn_take),
                        onClick = { onTake(m.id) },
                        modifier = Modifier.semantics { contentDescription = desc },
                        container = p.raised,
                        content = p.text,
                        paddingH = 14,
                    )
                }
            }
        }
    }
}

/** Лекарства «по необходимости» со схемой и ответом защиты (интервал, лимит за сутки). Пауза скрывает строку. */
private fun prnLines(meds: List<Medicine>, schedules: List<Schedule>, events: List<DoseEvent>, now: LocalDateTime): List<TodayPrnLine> =
    meds.mapNotNull { m ->
        val plan = schedules.firstOrNull { it.medicineId == m.id && it.plan.kind == PlanKind.AsNeeded && !it.plan.paused }?.plan
        plan?.let { TodayPrnLine(m, checkPrn(it, m.id, events, now)) }
    }

/** Ближайший будущий приём лекарства по его схемам (до 60 дней вперёд); `null` — приёмов больше нет. */
private fun nextDose(medicineId: Long, schedules: List<Schedule>, now: LocalDateTime): LocalDateTime? {
    val plans = schedules.filter { it.medicineId == medicineId }.map { it.plan }
    for (d in 0L..60L) {
        val date = now.toLocalDate().plusDays(d)
        val next = plans.flatMap { plan -> plan.dosesOn(date) }.map { date.atTime(it.time) }.filter { it > now }.minOrNull()
        if (next != null) return next
    }
    return null
}

/** Время словами: «сегодня в 18:00», «завтра в 08:30», «28 сентября в 08:30»; без «сегодня» — просто «18:00». */
@Composable
private fun whenText(at: LocalDateTime, today: LocalDate, time: DateTimeFormatter, withToday: Boolean): String {
    val t = time.format(at)
    val date = at.toLocalDate()
    return when {
        date == today -> if (withToday) stringResource(R.string.today_when_today, t) else t
        date == today.plusDays(1) -> stringResource(R.string.today_when_tomorrow, t)
        else -> {
            val locale = LocalConfiguration.current.locales[0]
            val day = date.format(DateTimeFormatter.ofPattern(stringResource(R.string.today_date_short), locale))
            stringResource(R.string.today_when_date, day, t)
        }
    }
}

/** Заголовок листа: Literata-подобный, 24 sp. */
@Composable
private fun sheetTitleStyle(): TextStyle =
    MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold)

/** Лист «Пропустить приём»: причины списком; выбор — одно касание (docs/ui-guideline.md, §6). */
@Composable
private fun SkipSheet(item: DayItem, time: DateTimeFormatter, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val p = AnvilTheme.palette
    val reasons = listOf(
        R.string.today_reason_forgot,
        R.string.today_reason_no_medicine,
        R.string.today_reason_unwell,
        R.string.today_reason_doctor,
        R.string.today_reason_other,
    ).map { stringResource(it) }
    TodaySheet(onDismiss) { close ->
        Text(stringResource(R.string.today_skip_title), Modifier.semantics { heading() }, color = p.text, style = sheetTitleStyle())
        Text(
            todayMedName(item.medicine.name, item.medicine.strength) + SEP + time.format(item.planned.at),
            color = p.weak,
            fontSize = 15.sp,
        )
        SectionTitle(stringResource(R.string.today_skip_reason))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            reasons.forEach { reason ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(p.raised)
                        .clickable(role = Role.Button) { onPick(reason); close() }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(reason, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        TodayButton(stringResource(R.string.today_cancel), onClick = close, modifier = Modifier.fillMaxWidth(), content = p.weak)
    }
}

/**
 * Лист «Этот приём уже отмечен» (макет, экран 15). Двойная доза не записывается; «Понятно, не принимаю» закрывает лист,
 * «Вторая доза уже принята — записать» пишет лишний приём для врача и показывает вторую часть листа.
 */
@Composable
private fun DoubleDoseSheet(
    item: DayItem,
    markedAt: LocalDateTime,
    time: DateTimeFormatter,
    nextLine: String?,
    onLog: suspend () -> TodayLogged,
    onUndoLog: suspend (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = AnvilTheme.palette
    val c = Ampulse.colors
    val scope = rememberCoroutineScope()
    var logged by remember { mutableStateOf<TodayLogged?>(null) }
    val name = todayMedName(item.medicine.name, item.medicine.strength)
    val emergency = stringResource(R.string.today_dd_emergency)
    TodaySheet(onDismiss) { close ->
        val done = logged
        if (done == null) {
            SheetHeader(
                icon = TodayIcons.Alert,
                iconBg = c.warnSoft,
                iconTint = p.warning,
                title = stringResource(R.string.today_dd_title),
                text = stringResource(R.string.today_dd_text, name, time.format(item.planned.at), time.format(markedAt)),
            )
            InfoBox(withBold(stringResource(R.string.today_dd_warning, emergency), emergency))
            TodayButton(
                stringResource(R.string.today_dd_ok),
                onClick = close,
                modifier = Modifier.fillMaxWidth(),
                container = p.accent,
                content = p.onAccent,
                height = 54,
                fontSize = 17,
            )
            TodayButton(
                stringResource(R.string.today_dd_log),
                onClick = { scope.launch { logged = onLog() } },
                modifier = Modifier.fillMaxWidth(),
                content = p.warning,
            )
        } else {
            SheetHeader(
                icon = TodayIcons.FileText,
                iconBg = p.raised,
                iconTint = p.text,
                title = stringResource(R.string.today_dd_logged_title),
                text = stringResource(R.string.today_dd_logged_text, name, time.format(done.at)),
            )
            val unwell = stringResource(R.string.today_dd_unwell, emergency)
            val after = listOfNotNull(nextLine?.let { stringResource(R.string.today_dd_next, item.medicine.name, it) }, unwell)
            InfoBox(withBold(after.joinToString(" "), emergency))
            TodayButton(
                stringResource(R.string.today_dd_done),
                onClick = close,
                modifier = Modifier.fillMaxWidth(),
                container = p.text,
                content = p.card,
                height = 54,
                fontSize = 17,
            )
            TodayButton(
                stringResource(R.string.today_dd_undo),
                onClick = {
                    scope.launch {
                        done.id?.let { onUndoLog(it) }
                        close()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                content = p.weak,
            )
        }
    }
}

/** Шапка листа: плитка значка 48 dp, заголовок и пояснение. */
@Composable
private fun SheetHeader(icon: ImageVector, iconBg: Color, iconTint: Color, title: String, text: String) {
    val p = AnvilTheme.palette
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(iconBg), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp), tint = iconTint)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, Modifier.semantics { heading() }, color = p.text, style = sheetTitleStyle())
            Text(text, color = p.weak, fontSize = 15.sp, lineHeight = 22.sp)
        }
    }
}

/** Пояснение на фоне экрана внутри листа (радиус 14). */
@Composable
private fun InfoBox(text: AnnotatedString) {
    val p = AnvilTheme.palette
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.bg).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(text, color = p.text, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

/** Текст, где подстрока [bold] выделена жирным (номера экстренных служб). */
private fun withBold(text: String, bold: String): AnnotatedString = buildAnnotatedString {
    append(text)
    val i = text.indexOf(bold)
    if (i >= 0) addStyle(SpanStyle(fontWeight = FontWeight.Bold), i, i + bold.length)
}
