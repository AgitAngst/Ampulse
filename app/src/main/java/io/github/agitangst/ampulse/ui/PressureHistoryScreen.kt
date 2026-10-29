package io.github.agitangst.ampulse.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.agitangst.ampulse.Ampulse
import io.github.agitangst.ampulse.AppGraph
import io.github.agitangst.ampulse.EmptyState
import io.github.agitangst.ampulse.Nav
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.Route
import io.github.agitangst.ampulse.data.PressurePrefs
import io.github.agitangst.ampulse.domain.PressureState
import io.github.agitangst.ampulse.domain.Reading
import io.github.agitangst.ampulse.domain.ReadingSource
import io.github.agitangst.ampulse.domain.Target
import io.github.agitangst.ampulse.domain.classify
import io.github.agitangst.ampulse.screenTitleStyle
import io.github.agitangst.anvil.compose.AnvilCard
import io.github.agitangst.anvil.compose.AnvilTheme
import io.github.agitangst.anvil.compose.SectionTitle
import io.github.agitangst.anvil.compose.Segmented
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.time.format.TextStyle as DayNameStyle

// «История давления» (макет, экран 27, этап 0.7): период, средние утром и вечером, график по дням с целью врача,
// подсказка и последние замеры с удалением. Логика сводки — в PressureHistoryModel.kt.

/** Сколько висит плашка «Замер удалён · Отменить», мс. */
private const val UNDO_MILLIS = 6_000L

/** Высота поля графика и поля вокруг него (чтобы верхний и нижний столбики не упирались в край), подписи слева. */
private val ChartHeight = 166.dp
private val ChartPad = 6.dp
private val ChartGutter = 34.dp

/** Удалённый замер и чей он: чтобы «Отменить» вернуло его на место, в том числе после поворота экрана. */
private data class RemovedReading(val profileId: Long, val reading: Reading)

private val RemovedSaver = listSaver<RemovedReading?, Any>(
    save = { r ->
        if (r == null) {
            emptyList()
        } else {
            listOf(r.profileId, r.reading.sys, r.reading.dia, r.reading.pulse ?: -1, r.reading.at.toString(), r.reading.note)
        }
    },
    restore = { l ->
        if (l.size < 6) {
            null
        } else {
            RemovedReading(
                l[0] as Long,
                Reading(0, l[1] as Int, l[2] as Int, (l[3] as Int).takeIf { it >= 0 }, LocalDateTime.parse(l[4] as String), l[5] as String),
            )
        }
    },
)

/**
 * «История давления» (макет, экран 27): период 7 / 14 / 30 дней, средние утром и вечером с оценкой относительно цели
 * врача, график по дням (утро слева, вечер справа), подсказка «вечером выше цели в N из M дней» и последние замеры.
 * Нажатие (или долгое нажатие) на замер открывает лист с «Удалить замер»; после удаления — «Отменить». «Изменить»
 * у цели врача открывает лист с двумя числами. Данные — профиль [CurrentProfile] из [AppGraph.repository].
 */
@Composable
fun PressureHistoryScreen(nav: Nav) {
    val repo = AppGraph.repository
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val p = AnvilTheme.palette
    val scope = rememberCoroutineScope()
    val profileId = CurrentProfile.id
    val today = rememberTodayNow().toLocalDate()
    val time = rememberTodayTime()

    var days by rememberSaveable { mutableStateOf(PRESSURE_PERIODS.first()) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var editingTarget by rememberSaveable { mutableStateOf(false) }
    var removed by rememberSaveable(stateSaver = RemovedSaver) { mutableStateOf<RemovedReading?>(null) }
    var selected by remember { mutableStateOf<Reading?>(null) }
    var target by remember(profileId) { mutableStateOf(PressurePrefs.target(context, profileId)) }

    val readings by remember(profileId, days, today) {
        repo.readings(profileId, periodFrom(today, days), today)
    }.collectAsState<List<Reading>, List<Reading>?>(initial = null)
    val history = remember(readings, target, today, days) { readings?.let { pressureHistory(it, target, today, days) } }

    // Плашка «Замер удалён» гаснет сама.
    LaunchedEffect(removed) {
        if (removed != null) {
            delay(UNDO_MILLIS)
            removed = null
        }
    }

    val dayMonthPattern = stringResource(R.string.pressure_hist_pattern_day_month)
    val dayMonthFormat = remember(dayMonthPattern, locale) { DateTimeFormatter.ofPattern(dayMonthPattern, locale) }
    val targetText = "${target.sys}/${target.dia}"
    val range = rangeText(periodFrom(today, days), today, locale, dayMonthFormat)
    val empty = history?.isEmpty == true
    val scroll = rememberScrollState()

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                // Пустое состояние занимает свободное место и прокручивается само.
                .then(if (empty) Modifier else Modifier.verticalScroll(scroll))
                .padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MedBackRow(stringResource(R.string.pressure_hist_back), nav::back, Modifier.offset(x = (-8).dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.pressure_hist_title),
                    Modifier.semantics { heading() },
                    color = p.text,
                    style = screenTitleStyle(30),
                )
                Text(range, color = p.weak, style = MaterialTheme.typography.bodyMedium)
            }
            Segmented(
                options = PRESSURE_PERIODS.map { pluralStringResource(R.plurals.pressure_hist_period, it, it) },
                selected = PRESSURE_PERIODS.indexOf(days).coerceAtLeast(0),
                onSelect = { i ->
                    days = PRESSURE_PERIODS[i]
                    showAll = false
                },
                modifier = periodDescription(),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.pressure_hist_target, targetText),
                    Modifier.weight(1f),
                    color = p.weak,
                    style = MaterialTheme.typography.bodyMedium,
                )
                // Ссылка стоит у правого поля: её отступ 12 dp уходит за край, цель нажатия остаётся 48 dp.
                StockLinkButton(
                    stringResource(R.string.pressure_hist_target_change),
                    onClick = { editingTarget = true },
                    modifier = Modifier.offset(x = 12.dp),
                    description = stringResource(R.string.pressure_hist_target_change_a),
                )
            }

            when {
                history == null -> Unit
                history.isEmpty -> EmptyState(
                    PressureHistoryIcons.Activity,
                    pluralStringResource(R.plurals.pressure_hist_empty_title, days, days),
                    stringResource(R.string.pressure_hist_empty_hint),
                    Modifier.weight(1f),
                )
                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AverageTile(stringResource(R.string.pressure_hist_morning), history.morning, Modifier.weight(1f).fillMaxHeight())
                            AverageTile(stringResource(R.string.pressure_hist_evening), history.evening, Modifier.weight(1f).fillMaxHeight())
                        }
                        Text(
                            stringResource(R.string.pressure_hist_parts_note),
                            Modifier.padding(horizontal = 4.dp),
                            color = p.faint,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (history.columns.any { it.morning != null || it.evening != null }) {
                        PressureChart(history, target, days, locale, dayMonthFormat, range)
                    }
                    InsightCard(history.insight)

                    val shown = if (showAll) history.latest else history.latest.take(PRESSURE_LATEST_SHOWN)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        StockSectionHeader(stringResource(R.string.pressure_hist_latest), null)
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.card).padding(horizontal = 16.dp),
                        ) {
                            shown.forEachIndexed { i, r ->
                                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
                                ReadingRow(r, target, readingWhen(r, today, time, dayMonthFormat)) { selected = r }
                            }
                        }
                        if (history.latest.size > PRESSURE_LATEST_SHOWN) {
                            StockLinkButton(
                                if (showAll) {
                                    stringResource(R.string.pressure_hist_show_less)
                                } else {
                                    stringResource(R.string.pressure_hist_show_all, history.latest.size)
                                },
                                onClick = { showAll = !showAll },
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                            )
                        }
                        Text(
                            stringResource(R.string.pressure_hist_tap_hint),
                            Modifier.fillMaxWidth(),
                            color = p.faint,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
        Column(
            Modifier.fillMaxWidth().background(p.bg).padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            removed?.let { gone ->
                StockDoneBar(
                    stringResource(R.string.pressure_hist_removed),
                    stringResource(R.string.pressure_hist_undo),
                    onUndo = {
                        removed = null
                        // Источник прежнего замера не хранится в Reading — возвращается как введённый руками.
                        scope.launch { repo.addReading(gone.profileId, gone.reading, ReadingSource.Manual) }
                    },
                )
            }
            MedMainButton(stringResource(R.string.pressure_hist_measure), onClick = { nav.open(Route.PressureEntry) })
            Text(
                stringResource(R.string.pressure_hist_pdf_note),
                Modifier.fillMaxWidth(),
                color = p.faint,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }

    selected?.let { r ->
        ReadingSheet(
            r,
            target,
            readingWhen(r, today, time, dayMonthFormat),
            onDelete = {
                removed = RemovedReading(profileId, r)
                scope.launch { repo.deleteReading(r.id) }
            },
            onDismiss = { selected = null },
        )
    }
    if (editingTarget) {
        TargetSheet(
            target,
            onSave = { t ->
                PressurePrefs.setTarget(context, profileId, t)
                target = t
            },
            onDismiss = { editingTarget = false },
        )
    }
}

/** Подпись группы сегментов для TalkBack: «Период». */
@Composable
private fun periodDescription(): Modifier {
    val label = stringResource(R.string.pressure_hist_period_a)
    return Modifier.semantics { contentDescription = label }
}

/** Диапазон дат периода по локали: «20–26 сентября» / «Sep 20–26», через месяц — «28 сентября – 4 октября». */
@Composable
private fun rangeText(from: LocalDate, to: LocalDate, locale: Locale, dayMonth: DateTimeFormatter): String {
    if (YearMonth.from(from) == YearMonth.from(to)) {
        val monthPattern = stringResource(R.string.pressure_hist_pattern_month)
        val month = remember(monthPattern, locale) { DateTimeFormatter.ofPattern(monthPattern, locale) }.format(to)
        return stringResource(R.string.pressure_hist_range_same, from.dayOfMonth, to.dayOfMonth, month)
    }
    return stringResource(R.string.pressure_hist_range_diff, dayMonth.format(from), dayMonth.format(to))
}

private fun partRes(part: DayPart): Int = when (part) {
    DayPart.Morning -> R.string.pressure_hist_part_morning
    DayPart.Midday -> R.string.pressure_hist_part_midday
    DayPart.Evening -> R.string.pressure_hist_part_evening
}

@Composable
private fun stateText(state: PressureState): String = stringResource(
    when (state) {
        PressureState.Within -> R.string.pressure_hist_state_within
        PressureState.Above -> R.string.pressure_hist_state_above
        PressureState.Crisis -> R.string.pressure_hist_state_crisis
        PressureState.Low -> R.string.pressure_hist_state_low
    },
)

/** «сегодня, 08:12 · утро» / «вчера, 20:30 · вечер» / «24 сентября, 20:35 · вечер». */
@Composable
private fun readingWhen(r: Reading, today: LocalDate, time: DateTimeFormatter, dayMonth: DateTimeFormatter): String {
    val part = stringResource(partRes(dayPart(r)))
    val t = time.format(r.at)
    return when (r.at.toLocalDate()) {
        today -> stringResource(R.string.pressure_hist_when_today, t, part)
        today.minusDays(1) -> stringResource(R.string.pressure_hist_when_yesterday, t, part)
        else -> stringResource(R.string.pressure_hist_when_date, dayMonth.format(r.at), t, part)
    }
}

/** Для диктора точка-разделитель «·» заменяется запятой: «сегодня, 08:12, утро». */
private fun String.forSpeech(): String = replace(" · ", ", ")

/** Давление для диктора: «128 на 82» вместо «128/82». */
@Composable
private fun spokenValue(sys: Int, dia: Int): String = stringResource(R.string.pressure_hist_value_a, sys, dia)

/** Точка и слово оценки: цвет не единственный носитель смысла (docs/ui-guideline.md, §3). */
@Composable
private fun StateLabel(state: PressureState, modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    val ok = state == PressureState.Within
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) p.accent else Ampulse.colors.warnLine))
        Text(stateText(state), color = if (ok) p.accentText else p.warning, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Крупное давление («128/82»): засечки Literata по макету, цифры одной ширины. */
@Composable
private fun bigValueStyle(size: Int) = MaterialTheme.typography.headlineSmall.copy(
    fontSize = size.sp,
    lineHeight = (size * 1.1f).sp,
    fontWeight = FontWeight.SemiBold,
    fontFeatureSettings = "tnum",
)

/** Плитка «Утро / Вечер, в среднем»: давление, оценка относительно цели, пульс. Нет замеров — прочерк. */
@Composable
private fun AverageTile(label: String, stat: PartStat?, modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    val none = stringResource(R.string.pressure_hist_none)
    val spoken = if (stat == null) {
        "$label: $none"
    } else {
        val pulse = stat.pulse?.let { stringResource(R.string.pressure_hist_pulse, it) }
        listOfNotNull("$label: ${spokenValue(stat.sys, stat.dia)}", stateText(stat.state), pulse).joinToString(", ")
    }
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(p.card)
            .clearAndSetSemantics { contentDescription = spoken }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, color = p.faint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (stat == null) {
            Text("—", color = p.faint, style = bigValueStyle(30))
            Text(none, color = p.faint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        } else {
            Text("${stat.sys}/${stat.dia}", color = p.text, style = bigValueStyle(30))
            StateLabel(stat.state)
            stat.pulse?.let { Text(stringResource(R.string.pressure_hist_pulse, it), color = p.faint, fontSize = 12.sp) }
        }
    }
}

/**
 * График по дням: на каждый день пара столбиков «верхнее–нижнее» — утро слева, вечер справа; акцент — в пределах цели,
 * цвет внимания — выше цели или низкое; пунктир — цель врача. Для TalkBack — одна подпись со сводкой ([range]).
 */
@Composable
private fun PressureChart(
    history: PressureHistory,
    target: Target,
    days: Int,
    locale: Locale,
    dayMonth: DateTimeFormatter,
    range: String,
) {
    val p = AnvilTheme.palette
    val c = Ampulse.colors
    val n = history.columns.size
    val scale = history.scale
    val accent = p.accent
    val warnLine = c.warnLine
    val gridColor = p.border
    val step = labelStep(days)
    val withWeekday = labelWithWeekday(days)
    val description = chartDescription(history, target, range, dayMonth)

    AnvilCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            SectionTitle(stringResource(R.string.pressure_hist_chart_title), Modifier.weight(1f))
            Text(stringResource(R.string.pressure_hist_unit), color = p.faint, fontSize = 12.sp)
        }
        Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description }) {
            Row(Modifier.fillMaxWidth().height(ChartHeight)) {
                Box(Modifier.width(ChartGutter).fillMaxHeight()) {
                    history.grid.forEach { g ->
                        val y: Dp = ChartPad + (ChartHeight - ChartPad * 2) * scale.fraction(g.value)
                        Text(
                            g.value.toString(),
                            Modifier.width(30.dp).offset(y = y - 7.dp),
                            color = if (g.target) p.warning else p.faint,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                        )
                    }
                }
                Canvas(Modifier.weight(1f).fillMaxHeight()) {
                    val padPx = ChartPad.toPx()
                    val plotH = size.height - 2 * padPx
                    fun y(v: Int) = padPx + plotH * scale.fraction(v)
                    val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
                    history.grid.forEach { g ->
                        drawLine(
                            color = if (g.target) warnLine else gridColor,
                            start = Offset(0f, y(g.value)),
                            end = Offset(size.width, y(g.value)),
                            strokeWidth = if (g.target) 1.5.dp.toPx() else 1.dp.toPx(),
                            pathEffect = if (g.target) dash else null,
                        )
                    }
                    val colW = size.width / n
                    val barW = minOf(8.dp.toPx(), colW * 0.34f)
                    val gap = minOf(4.dp.toPx(), colW * 0.12f)
                    fun bar(span: DaySpan?, left: Float) {
                        if (span == null) return
                        val top = y(span.sys)
                        val bottom = maxOf(y(span.dia), top + barW)
                        drawRoundRect(
                            color = if (span.inTarget) accent else warnLine,
                            topLeft = Offset(left, top),
                            size = Size(barW, bottom - top),
                            cornerRadius = CornerRadius(barW / 2),
                        )
                    }
                    history.columns.forEachIndexed { i, col ->
                        val cx = colW * (i + 0.5f)
                        bar(col.morning, cx - gap / 2 - barW)
                        bar(col.evening, cx + gap / 2)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = ChartGutter, top = 4.dp)) {
                history.columns.forEachIndexed { i, col ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (showDayLabel(i, n, step)) {
                            val isToday = i == n - 1
                            Text(
                                dayLabel(col.date, withWeekday, locale),
                                Modifier.wrapContentWidth(unbounded = true),
                                color = if (isToday) p.text else p.faint,
                                fontSize = 11.sp,
                                lineHeight = 13.sp,
                                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                softWrap = false,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendItem(accent, stringResource(R.string.pressure_hist_legend_in), Modifier.weight(1f, fill = false))
                LegendItem(warnLine, stringResource(R.string.pressure_hist_legend_out), Modifier.weight(1f, fill = false))
            }
            Text(stringResource(R.string.pressure_hist_legend_pair), color = p.weak, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

/** Подпись дня под столбиками: «Сб 26» до недели, дальше — число. День недели и месяц — через java.time по локали. */
private fun dayLabel(date: LocalDate, withWeekday: Boolean, locale: Locale): String {
    if (!withWeekday) return date.dayOfMonth.toString()
    val dow = date.dayOfWeek.getDisplayName(DayNameStyle.SHORT, locale).replaceFirstChar { it.titlecase(locale) }
    return "$dow ${date.dayOfMonth}"
}

@Composable
private fun LegendItem(color: Color, text: String, modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 8.dp, height = 14.dp).clip(RoundedCornerShape(4.dp)).background(color))
        Text(text, color = p.weak, fontSize = 12.sp, lineHeight = 16.sp)
    }
}

/** Сводка графика для диктора: период, дни с замерами, самое высокое и самое низкое среднее, цель. */
@Composable
private fun chartDescription(history: PressureHistory, target: Target, range: String, dayMonth: DateTimeFormatter): String {
    val ex = chartExtremes(history.columns) ?: return ""
    return stringResource(
        R.string.pressure_hist_chart_a,
        range,
        ex.daysWithData,
        history.columns.size,
        spokenPoint(ex.highest, dayMonth),
        spokenPoint(ex.lowest, dayMonth),
        spokenValue(target.sys, target.dia),
    )
}

/** Точка графика для диктора: «138 на 88, 21 сентября, вечер». */
@Composable
private fun spokenPoint(sp: SpanPoint, dayMonth: DateTimeFormatter): String = stringResource(
    R.string.pressure_hist_chart_point,
    spokenValue(sp.span.sys, sp.span.dia),
    dayMonth.format(sp.date),
    stringResource(partRes(sp.part)),
)

/** Подсказка под графиком: «Вечером выше цели в N из M дней» (и утро — если было) с пояснением. */
@Composable
private fun InsightCard(summary: InsightSummary) {
    if (summary is InsightSummary.None) return
    val p = AnvilTheme.palette
    val warn = summary is InsightSummary.Above
    AnvilCard {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (warn) Ampulse.colors.warnSoft else Ampulse.colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(TodayIcons.Lightbulb, contentDescription = null, modifier = Modifier.size(22.dp), tint = if (warn) p.warning else p.accentText)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                val titleStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                when (summary) {
                    InsightSummary.None -> Unit
                    InsightSummary.AllWithin -> Text(stringResource(R.string.pressure_hist_insight_ok), color = p.text, style = titleStyle)
                    is InsightSummary.Above -> {
                        summary.evening?.let { (n, m) ->
                            Text(pluralStringResource(R.plurals.pressure_hist_insight_evening, m, n, m), color = p.text, style = titleStyle)
                        }
                        summary.morning?.let { (n, m) ->
                            Text(pluralStringResource(R.plurals.pressure_hist_insight_morning, m, n, m), color = p.text, style = titleStyle)
                        }
                    }
                }
                Text(
                    stringResource(R.string.pressure_hist_insight_note),
                    color = p.weak,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                )
            }
        }
    }
}

/**
 * Строка «Последних замеров»: давление · пульс, когда и оценка справа. Нажатие — лист с «Удалить замер». Для TalkBack —
 * одна фраза («128 на 82, пульс 71 уд/мин, сегодня, 08:12 · утро, в пределах цели»).
 */
@Composable
private fun ReadingRow(r: Reading, target: Target, whenText: String, onClick: () -> Unit) {
    val p = AnvilTheme.palette
    val state = classify(r.sys, r.dia, target)
    val pulse = r.pulse?.let { stringResource(R.string.pressure_hist_row_pulse, it) }
    val pulseSpoken = r.pulse?.let { stringResource(R.string.pressure_hist_pulse, it) }
    val spoken = listOfNotNull(spokenValue(r.sys, r.dia), pulseSpoken, whenText.forSpeech(), stateText(state)).joinToString(", ")
    val valueLine = buildAnnotatedString {
        append("${r.sys}/${r.dia}")
        if (pulse != null) {
            withStyle(SpanStyle(fontWeight = FontWeight.Normal, color = p.faint)) { append(" · $pulse") }
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(onClickLabel = stringResource(R.string.pressure_hist_row_action), role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = spoken }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                valueLine,
                color = p.text,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
            )
            Text(whenText, color = p.faint, fontSize = 13.sp)
        }
        StateLabel(state)
    }
}

/** Лист замера: значение, когда, оценка и «Удалить замер» (после удаления — «Отменить» внизу экрана). */
@Composable
private fun ReadingSheet(r: Reading, target: Target, whenText: String, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val p = AnvilTheme.palette
    val state = classify(r.sys, r.dia, target)
    val pulse = r.pulse?.let { stringResource(R.string.pressure_hist_pulse, it) }
    val spoken = listOfNotNull(spokenValue(r.sys, r.dia), pulse, whenText.forSpeech()).joinToString(", ")
    TodaySheet(onDismiss) { close ->
        Text(
            stringResource(R.string.pressure_hist_reading_title),
            Modifier.semantics { heading() },
            color = p.text,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${r.sys}/${r.dia}", color = p.text, style = bigValueStyle(30))
            if (pulse != null) Text(pulse, color = p.faint, fontSize = 13.sp)
            Text(whenText, color = p.weak, style = MaterialTheme.typography.bodyMedium)
        }
        StateLabel(state)
        MedOutlineButton(
            stringResource(R.string.pressure_hist_delete),
            onClick = {
                onDelete()
                close()
            },
            icon = MedIcons.Trash,
            danger = true,
        )
        TodayButton(stringResource(R.string.pressure_hist_close), onClick = close, modifier = Modifier.fillMaxWidth(), content = p.weak)
    }
}

/** Лист «Цель врача»: верхнее 100–180 и нижнее 60–110 шагом 5; сохраняется для текущего профиля. */
@Composable
private fun TargetSheet(current: Target, onSave: (Target) -> Unit, onDismiss: () -> Unit) {
    val p = AnvilTheme.palette
    var sys by remember { mutableStateOf(current.sys.coerceIn(TARGET_SYS_RANGE)) }
    var dia by remember { mutableStateOf(current.dia.coerceIn(TARGET_DIA_RANGE)) }
    val valid = isValidTarget(sys, dia)
    TodaySheet(onDismiss) { close ->
        Text(
            stringResource(R.string.pressure_hist_target_title),
            Modifier.semantics { heading() },
            color = p.text,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        Text(stringResource(R.string.pressure_hist_target_note), color = p.weak, style = MaterialTheme.typography.bodyMedium)
        Column {
            MedStepRow(
                label = stringResource(R.string.pressure_hist_target_sys),
                value = sys.toString(),
                onLess = { sys = stepTarget(sys, false, TARGET_SYS_RANGE) },
                onMore = { sys = stepTarget(sys, true, TARGET_SYS_RANGE) },
                lessEnabled = sys > TARGET_SYS_RANGE.first,
                moreEnabled = sys < TARGET_SYS_RANGE.last,
            )
            MedStepRow(
                label = stringResource(R.string.pressure_hist_target_dia),
                value = dia.toString(),
                onLess = { dia = stepTarget(dia, false, TARGET_DIA_RANGE) },
                onMore = { dia = stepTarget(dia, true, TARGET_DIA_RANGE) },
                lessEnabled = dia > TARGET_DIA_RANGE.first,
                moreEnabled = dia < TARGET_DIA_RANGE.last,
            )
        }
        if (valid) {
            Text(
                stringResource(R.string.pressure_hist_target_preview, "$sys/$dia"),
                color = p.text,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            )
        } else {
            MedNotice(stringResource(R.string.pressure_hist_target_bad))
        }
        MedMainButton(
            stringResource(R.string.pressure_hist_target_save),
            enabled = valid,
            onClick = {
                onSave(Target(sys, dia))
                close()
            },
        )
        TodayButton(stringResource(R.string.pressure_hist_target_cancel), onClick = close, modifier = Modifier.fillMaxWidth(), content = p.weak)
    }
}

/** Значки экрана: линии 1,8 на сетке 24, путь — набор Lucide (docs/ui-guideline.md, §5). */
private object PressureHistoryIcons {
    /** Линия пульса: пустое состояние «замеров нет». */
    val Activity: ImageVector by lazy {
        ImageVector.Builder(
            name = "pressure_hist.activity",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M22 12h-2.48a2 2 0 0 0-1.93 1.46l-2.35 8.36a.25.25 0 0 1-.48 0L9.24 2.18a.25.25 0 0 0-.48 0l-2.35 8.36A2 2 0 0 1 4.49 12H2"),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }
}
