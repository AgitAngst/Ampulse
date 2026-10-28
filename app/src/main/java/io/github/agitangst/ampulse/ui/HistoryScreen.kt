package io.github.agitangst.ampulse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import io.github.agitangst.ampulse.TabHeader
import io.github.agitangst.ampulse.domain.DayItem
import io.github.agitangst.ampulse.domain.DoseEvent
import io.github.agitangst.ampulse.domain.DoseState
import io.github.agitangst.ampulse.domain.EventStatus
import io.github.agitangst.ampulse.domain.Medicine
import io.github.agitangst.ampulse.domain.dayItems
import io.github.agitangst.anvil.compose.AnvilCard
import io.github.agitangst.anvil.compose.AnvilTheme
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale
import java.time.format.TextStyle as DayNameStyle

/** Приёмы одного дня календаря: по расписанию, с отметками и состоянием. */
private data class HistoryDay(val date: LocalDate, val items: List<DayItem>) {
    val taken: Int get() = items.count { it.state == DoseState.Taken }
}

/** Как закрашена клетка дня. */
private enum class HistoryFill { Full, Part, Missed, None }

/** Что чаще всего пропускается: лекарство, время приёма и сколько раз за месяц. */
private data class HistoryTip(val medicine: Medicine, val time: LocalTime, val count: Int)

/** Вид отметки в списке выбранного дня. */
private enum class HistoryLineKind { Taken, Skipped, Extra, Unmarked, Planned }

private class HistoryLine(
    /** Время строки: по расписанию, а у лишнего приёма и «по необходимости» — когда записан. */
    val at: LocalDateTime,
    val medicine: Medicine?,
    val kind: HistoryLineKind,
    val markedAt: LocalDateTime?,
    val note: String,
)

/**
 * «История» (макет, экран 6): календарь месяца с долей принятого, итог месяца, подсказка про частые пропуски и отметки
 * выбранного дня. Данные — из [AppGraph.repository]. Отчёт врачу (PDF) — этап 0.8.
 */
@Composable
fun HistoryScreen(nav: Nav, modifier: Modifier = Modifier) {
    val repo = AppGraph.repository
    val locale = LocalConfiguration.current.locales[0]
    val now = rememberTodayNow()
    val today = now.toLocalDate()
    val time = rememberTodayTime()
    val currentIndex = today.year * 12 + today.monthValue - 1
    // Месяц — число месяцев от нулевого года, выбранный день — номер дня: оба переживают пересоздание экрана.
    var monthIndex by rememberSaveable { mutableStateOf(currentIndex) }
    var selectedEpoch by rememberSaveable { mutableStateOf(today.toEpochDay()) }
    val month = YearMonth.of(monthIndex / 12, monthIndex % 12 + 1)

    val medicines by repo.medicines.collectAsState<List<Medicine>, List<Medicine>?>(initial = null)
    val schedules by repo.schedules.collectAsState(initial = emptyList())
    // Сутки с краёв: отметка могла быть сделана после полуночи или накануне.
    val events by remember(monthIndex) {
        repo.events(month.atDay(1).minusDays(1), month.atEndOfMonth().plusDays(2))
    }.collectAsState(initial = emptyList())
    val meds = medicines

    val days = remember(meds, schedules, events, now, monthIndex) {
        if (meds == null) {
            emptyList<HistoryDay>()
        } else {
            (1..month.lengthOfMonth()).map { d ->
                val date = month.atDay(d)
                HistoryDay(date, dayItems(date, meds, schedules, events, now))
            }
        }
    }
    // Считаются приёмы, чьё время уже прошло или которые отмечены: «ещё впереди» в долю не входит.
    val decided = remember(days) {
        days.flatMap { it.items }.filter {
            it.state == DoseState.Taken || it.state == DoseState.Skipped || it.state == DoseState.Overdue
        }
    }
    val tip = remember(days) { frequentMiss(days) }
    var tipDismissed by rememberSaveable(monthIndex) { mutableStateOf(false) }
    val selected = LocalDate.ofEpochDay(selectedEpoch).takeIf { YearMonth.from(it) == month } ?: month.atDay(1)

    val changeMonth: (Int) -> Unit = { delta ->
        monthIndex += delta
        val m = YearMonth.of(monthIndex / 12, monthIndex % 12 + 1)
        selectedEpoch = if (m == YearMonth.from(today)) today.toEpochDay() else m.atDay(1).toEpochDay()
    }

    PageLayout(modifier, header = { TabHeader(stringResource(R.string.tab_history)) }) {
        when {
            meds == null -> Spacer(Modifier.weight(1f))
            meds.isEmpty() -> EmptyState(
                AmpulseIcons.Calendar,
                stringResource(R.string.history_empty_title),
                stringResource(R.string.history_empty_hint),
                Modifier.weight(1f),
            )
            else -> {
                MonthBar(
                    title = month.atDay(1).format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))
                        .replaceFirstChar { it.titlecase(locale) },
                    canGoNext = monthIndex < currentIndex,
                    onPrev = { changeMonth(-1) },
                    onNext = { changeMonth(1) },
                )
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    AnvilCard {
                        MonthSummary(decided.count { it.state == DoseState.Taken }, decided.size, locale)
                        MonthGrid(days, today, selected, locale) { selectedEpoch = it.toEpochDay() }
                        Legend()
                    }
                    if (tip != null && !tipDismissed) {
                        TipCard(
                            tip,
                            time,
                            onChange = { nav.open(Route.MedicineEdit(tip.medicine.id)) },
                            onDismiss = { tipDismissed = true },
                        )
                    }
                    val stat = days.firstOrNull { it.date == selected }
                    val lines = remember(selected, stat, events, meds) {
                        dayLines(selected, stat, events, meds.associateBy { it.id })
                    }
                    DayCard(selected, lines, time, locale)
                }
            }
        }
    }
}

/** Строка месяца: стрелки 48 dp по краям и название по центру. Дальше текущего месяца вперёд не листается. */
@Composable
private fun MonthBar(title: String, canGoNext: Boolean, onPrev: () -> Unit, onNext: () -> Unit) {
    val p = AnvilTheme.palette
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        MonthArrow(AmpulseIcons.ChevronLeft, stringResource(R.string.history_prev_month), enabled = true, onClick = onPrev)
        Text(
            title,
            Modifier.weight(1f).semantics { heading() },
            color = p.text,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        MonthArrow(TodayIcons.ChevronRight, stringResource(R.string.history_next_month), enabled = canGoNext, onClick = onNext)
    }
}

@Composable
private fun MonthArrow(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val p = AnvilTheme.palette
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .alpha(if (enabled) 1f else 0.38f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(22.dp), tint = p.text)
    }
}

/** Итог месяца: крупная доля принятого и «принято N из M». Считаются приёмы, чьё время уже прошло. */
@Composable
private fun MonthSummary(taken: Int, total: Int, locale: Locale) {
    val p = AnvilTheme.palette
    if (total == 0) {
        Text(stringResource(R.string.history_summary_none), color = p.weak, fontSize = 14.sp)
        return
    }
    val percent = remember(locale, taken, total) { NumberFormat.getPercentInstance(locale).format(taken.toDouble() / total) }
    val count = pluralStringResource(R.plurals.history_summary_count, total, taken, total)
    val spoken = stringResource(R.string.history_summary_a11y, percent) + ". " + count
    Row(
        Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            percent,
            color = p.text,
            style = MaterialTheme.typography.displaySmall.copy(fontSize = 48.sp, lineHeight = 48.sp, fontWeight = FontWeight.SemiBold),
        )
        Text(count, Modifier.padding(bottom = 4.dp), color = p.weak, fontSize = 14.sp, lineHeight = 19.sp)
    }
}

/**
 * Сетка месяца: неделя начинается по локали (в русской — с понедельника). Клетка — 48 dp высотой на всю ширину столбца
 * (около 45 dp на телефоне 390 dp: семь столбцов шире не дают), круг внутри — 38 dp.
 */
@Composable
private fun MonthGrid(days: List<HistoryDay>, today: LocalDate, selected: LocalDate, locale: Locale, onSelect: (LocalDate) -> Unit) {
    val p = AnvilTheme.palette
    val firstDow = remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    val lead = (days.first().date.dayOfWeek.value - firstDow.value + 7) % 7
    val rows = (lead + days.size + 6) / 7
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Названия дней недели — для глаз; TalkBack читает полную дату в каждой клетке.
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
            repeat(7) { i ->
                Text(
                    firstDow.plus(i.toLong()).getDisplayName(DayNameStyle.SHORT, locale),
                    Modifier.weight(1f),
                    color = p.faint,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }
        repeat(rows) { r ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { c ->
                    val index = r * 7 + c - lead
                    val stat = if (index >= 0) days.getOrNull(index) else null
                    Box(Modifier.weight(1f).height(48.dp), contentAlignment = Alignment.Center) {
                        if (stat != null) {
                            DayCell(stat, today, isSelected = stat.date == selected, locale = locale, onClick = { onSelect(stat.date) })
                        }
                    }
                }
            }
        }
    }
}

private fun fillOf(stat: HistoryDay, today: LocalDate): HistoryFill {
    val total = stat.items.size
    if (total == 0 || stat.date > today) return HistoryFill.None
    val taken = stat.taken
    // Сегодня, пока что-то ещё впереди, «ничего не принято» — не пропуск.
    val pending = stat.items.any { it.state == DoseState.Due || it.state == DoseState.Later }
    return when {
        taken == total -> HistoryFill.Full
        taken > 0 -> HistoryFill.Part
        pending -> HistoryFill.None
        else -> HistoryFill.Missed
    }
}

/**
 * Клетка дня: круг, окрашенный по доле принятого (всё — акцент, часть — приглушённый акцент, ничего — мягкое внимание
 * с рамкой). Цвет не единственный носитель: у клетки есть подпись для TalkBack («26 сентября: принято 3 из 4»).
 * Выбранный день — рамка цвета текста, сегодняшний — рамка слабее.
 */
@Composable
private fun DayCell(stat: HistoryDay, today: LocalDate, isSelected: Boolean, locale: Locale, onClick: () -> Unit) {
    val p = AnvilTheme.palette
    val c = Ampulse.colors
    val fill = fillOf(stat, today)
    val isToday = stat.date == today
    val (bg, fg) = when (fill) {
        HistoryFill.Full -> p.accent to p.onAccent
        HistoryFill.Part -> c.accentMuted to p.text
        HistoryFill.Missed -> c.warnSoft to p.warning
        HistoryFill.None -> Color.Transparent to (if (stat.date > today) p.faint else p.weak)
    }
    val outline = when {
        isSelected -> p.text to 2.dp
        isToday -> p.weak to 2.dp
        fill == HistoryFill.Missed -> c.warnLine to 1.5.dp
        else -> Color.Transparent to 0.dp
    }
    val dateText = stat.date.format(DateTimeFormatter.ofPattern(stringResource(R.string.today_date_short), locale))
    val label = if (isToday) stringResource(R.string.history_today_suffix, dateText) else dateText
    val total = stat.items.size
    val spoken = when {
        total == 0 -> stringResource(R.string.history_cell_none, label)
        stat.date > today -> stringResource(R.string.history_cell_planned, label, total)
        else -> stringResource(R.string.history_cell_taken, label, stat.taken, total)
    }
    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = spoken
                this.selected = isSelected
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(bg)
                .then(if (outline.second > 0.dp) Modifier.border(outline.second, outline.first, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stat.date.dayOfMonth.toString(),
                color = fg,
                fontSize = 14.sp,
                fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.SemiBold,
            )
        }
    }
}

/** Подписи к цветам клеток: цвет сопровождается словом. */
@Composable
private fun Legend() {
    val c = Ampulse.colors
    val p = AnvilTheme.palette
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendItem(p.accent, null, stringResource(R.string.history_legend_full))
            LegendItem(c.accentMuted, null, stringResource(R.string.history_legend_part))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendItem(c.warnSoft, c.warnLine, stringResource(R.string.history_legend_missed))
            LegendItem(Color.Transparent, p.weak, stringResource(R.string.history_legend_today))
        }
    }
}

@Composable
private fun LegendItem(fill: Color, border: Color?, text: String) {
    val p = AnvilTheme.palette
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(fill)
                .then(if (border != null) Modifier.border(2.dp, border, CircleShape) else Modifier),
        )
        Text(text, color = p.weak, fontSize = 13.sp)
    }
}

/** Подсказка: что чаще всего пропускается. Время можно поменять в «Аптечке» (дозы приложение не меняет). */
@Composable
private fun TipCard(tip: HistoryTip, time: DateTimeFormatter, onChange: () -> Unit, onDismiss: () -> Unit) {
    val p = AnvilTheme.palette
    AnvilCard {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Ampulse.colors.warnSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(TodayIcons.Lightbulb, contentDescription = null, modifier = Modifier.size(22.dp), tint = p.warning)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    pluralStringResource(
                        R.plurals.history_tip_title,
                        tip.count,
                        todayMedName(tip.medicine.name, tip.medicine.strength),
                        tip.count,
                    ),
                    Modifier.semantics { heading() },
                    color = p.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.history_tip_text, time.format(tip.time)),
                    color = p.weak,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TodayButton(
                stringResource(R.string.history_tip_change),
                onClick = onChange,
                container = p.accent,
                content = p.onAccent,
                paddingH = 18,
            )
            TodayButton(stringResource(R.string.history_tip_dismiss), onClick = onDismiss, content = p.weak, paddingH = 16)
        }
    }
}

/** Отметки выбранного дня: время, лекарство, принято / пропущено с причиной / лишний приём. */
@Composable
private fun DayCard(day: LocalDate, lines: List<HistoryLine>, time: DateTimeFormatter, locale: Locale) {
    val p = AnvilTheme.palette
    AnvilCard {
        Text(
            day.format(DateTimeFormatter.ofPattern(stringResource(R.string.today_date_short), locale)),
            Modifier.semantics { heading() },
            color = p.text,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (lines.isEmpty()) {
            Text(stringResource(R.string.history_day_empty), color = p.weak, fontSize = 14.sp)
        } else {
            lines.forEach { DayLineRow(it, time) }
        }
    }
}

@Composable
private fun DayLineRow(line: HistoryLine, time: DateTimeFormatter) {
    val p = AnvilTheme.palette
    val c = Ampulse.colors
    val m = line.medicine
    val name = if (m != null) todayMedName(m.name, m.strength) else stringResource(R.string.history_medicine_removed)
    val status = when (line.kind) {
        HistoryLineKind.Taken -> if (line.markedAt != null) {
            stringResource(R.string.history_kind_taken, time.format(line.markedAt))
        } else {
            stringResource(R.string.history_kind_taken_plain)
        }
        HistoryLineKind.Skipped -> if (line.note.isNotBlank()) {
            stringResource(R.string.history_kind_skipped, line.note)
        } else {
            stringResource(R.string.history_kind_skipped_plain)
        }
        HistoryLineKind.Extra -> stringResource(R.string.history_kind_extra)
        HistoryLineKind.Unmarked -> stringResource(R.string.history_kind_unmarked)
        HistoryLineKind.Planned -> stringResource(R.string.history_kind_planned)
    }
    val icon = when (line.kind) {
        HistoryLineKind.Taken -> TodayIcons.Check
        HistoryLineKind.Skipped -> TodayIcons.Cross
        HistoryLineKind.Extra, HistoryLineKind.Unmarked -> TodayIcons.Alert
        HistoryLineKind.Planned -> if (m != null) todayFormIcon(m.form) else AmpulseIcons.Pill
    }
    val (tileBg, tileFg) = when (line.kind) {
        HistoryLineKind.Taken -> c.accentSoft to p.accent
        HistoryLineKind.Extra, HistoryLineKind.Unmarked -> c.warnSoft to p.warning
        HistoryLineKind.Skipped, HistoryLineKind.Planned -> p.raised to p.weak
    }
    val statusColor = when (line.kind) {
        HistoryLineKind.Taken -> p.accentText
        HistoryLineKind.Extra, HistoryLineKind.Unmarked -> p.warning
        HistoryLineKind.Skipped, HistoryLineKind.Planned -> p.weak
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tileBg), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = tileFg)
        }
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(status, color = statusColor, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(time.format(line.at), color = p.weak, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Что чаще всего пропускается за месяц: пропущенные и неотмеченные приёмы по паре «лекарство + время». Меньше двух
 * раз — не закономерность, подсказки нет.
 */
private fun frequentMiss(days: List<HistoryDay>): HistoryTip? {
    val misses = days.flatMap { it.items }.filter { it.state == DoseState.Skipped || it.state == DoseState.Overdue }
    val top = misses.groupBy { it.medicine.id to it.planned.at.toLocalTime() }.maxByOrNull { it.value.size } ?: return null
    return if (top.value.size >= 2) HistoryTip(top.value.first().medicine, top.key.second, top.value.size) else null
}

/** Строки выбранного дня: приёмы по расписанию и отметки вне расписания (лишний приём, «по необходимости»), по времени. */
private fun dayLines(date: LocalDate, stat: HistoryDay?, events: List<DoseEvent>, byId: Map<Long, Medicine>): List<HistoryLine> {
    val items = stat?.items.orEmpty()
    val matched = items.mapNotNull { it.event?.id }.toSet()
    val scheduled = items.map { item ->
        val kind = when (item.state) {
            DoseState.Taken -> HistoryLineKind.Taken
            DoseState.Skipped -> HistoryLineKind.Skipped
            DoseState.Overdue -> HistoryLineKind.Unmarked
            DoseState.Due, DoseState.Later -> HistoryLineKind.Planned
        }
        HistoryLine(item.planned.at, item.medicine, kind, item.event?.at, item.event?.note.orEmpty())
    }
    // Отметки, у которых нет приёма в расписании (лишний, «по необходимости», приём из прежней схемы).
    val loose = events
        .filter { e -> e.id !in matched && (e.planned?.toLocalDate() ?: e.at.toLocalDate()) == date }
        .map { e ->
            val kind = when (e.status) {
                EventStatus.Taken -> HistoryLineKind.Taken
                EventStatus.Skipped -> HistoryLineKind.Skipped
                EventStatus.Extra -> HistoryLineKind.Extra
            }
            HistoryLine(e.planned ?: e.at, byId[e.medicineId], kind, e.at, e.note)
        }
    return (scheduled + loose).sortedBy { it.at }
}
