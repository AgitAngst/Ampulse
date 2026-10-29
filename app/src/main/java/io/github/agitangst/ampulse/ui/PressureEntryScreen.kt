package io.github.agitangst.ampulse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
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
import io.github.agitangst.ampulse.Route
import io.github.agitangst.ampulse.data.PressurePrefs
import io.github.agitangst.ampulse.domain.DeviceReading
import io.github.agitangst.ampulse.domain.PressureState
import io.github.agitangst.ampulse.domain.Reading
import io.github.agitangst.ampulse.domain.ReadingError
import io.github.agitangst.ampulse.domain.ReadingSource
import io.github.agitangst.ampulse.domain.Target
import io.github.agitangst.ampulse.domain.average
import io.github.agitangst.ampulse.domain.classify
import io.github.agitangst.ampulse.domain.isEvening
import io.github.agitangst.ampulse.domain.isMorning
import io.github.agitangst.ampulse.domain.readingFromText
import io.github.agitangst.ampulse.screenTitleStyle
import io.github.agitangst.anvil.compose.AnvilTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Замер серии: что было записано (для среднего). Сериализуется — переживает пересоздание экрана. */
internal data class Measured(val sys: Int, val dia: Int, val pulse: Int?) : Serializable

/** Шаги серии: первый замер, второй замер, итог. */
private const val STEP_FIRST = 1
private const val STEP_SECOND = 2
private const val STEP_DONE = 3

private val EntryDigitsSaver = listSaver<EntryDigits, String>(
    save = { listOf(it.sys, it.dia, it.pulse, it.active.name) },
    restore = { EntryDigits(it[0], it[1], it[2], EntryField.valueOf(it[3])) },
)

/**
 * Ввод давления (макет, экран 26): три поля, своя цифровая клавиатура (системная не открывается), оценка относительно
 * цели врача — цветом и словом, серия из двух замеров и среднее. Числа можно взять с фото экрана тонометра, из
 * диктовки или принять по Bluetooth. Каждый замер сразу пишется в базу текущего профиля; приложение ничего не советует
 * по лечению — при очень высоком и низком давлении только «звоните 103 или 112».
 */
@Composable
fun PressureEntryScreen(nav: Nav) {
    val repo = AppGraph.repository
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profileId = CurrentProfile.id
    val now = rememberTodayNow()
    val time = rememberTodayTime()
    val target = remember(profileId) { PressurePrefs.target(context, profileId) }

    // Прошлый замер — до этой серии: читаем один раз, чтобы он не подменялся первым замером серии. `null` — читается.
    val previous by produceState<List<Reading>?>(null, profileId) { value = listOfNotNull(repo.lastReading(profileId).first()) }

    var digits by rememberSaveable(stateSaver = EntryDigitsSaver) { mutableStateOf(EntryDigits()) }
    var step by rememberSaveable { mutableStateOf(STEP_FIRST) }
    var first by rememberSaveable { mutableStateOf<Measured?>(null) }
    var second by rememberSaveable { mutableStateOf<Measured?>(null) }
    var source by rememberSaveable { mutableStateOf(ReadingSource.Manual) }
    var deviceAt by rememberSaveable { mutableStateOf<LocalDateTime?>(null) }
    var unreadable by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    val verdict = digits.check()
    val at = deviceAt ?: now
    val canSave = (verdict as? EntryCheck.Ready)?.canSave == true && !saving

    fun accept(reading: DeviceReading, from: ReadingSource) {
        digits = EntryDigits.filled(reading.sys, reading.dia, reading.pulse)
        source = from
        // Время тонометра берём, если оно есть и не из будущего (часы прибора бывают сбиты).
        deviceAt = reading.at?.takeIf { it <= LocalDateTime.now().plusMinutes(1) }
        unreadable = false
    }

    fun save() {
        val ready = verdict as? EntryCheck.Ready ?: return
        if (!ready.canSave || saving) return
        val readAt = deviceAt ?: LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
        val from = source
        saving = true
        scope.launch {
            try {
                repo.addReading(profileId, Reading(0, ready.sys, ready.dia, ready.pulse, readAt), from)
                val measured = Measured(ready.sys, ready.dia, ready.pulse)
                if (step == STEP_FIRST) {
                    first = measured
                    step = STEP_SECOND
                } else {
                    second = measured
                    step = STEP_DONE
                }
                digits = EntryDigits()
                source = ReadingSource.Manual
                deviceAt = null
                unreadable = false
            } finally {
                saving = false
            }
        }
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                HeaderLink(AmpulseIcons.ChevronLeft, 22, stringResource(R.string.bp_back), nav::back, Modifier.offset(x = (-8).dp))
                HeaderLink(
                    PressureIcons.Chart, 20, stringResource(R.string.bp_history),
                    { nav.open(Route.PressureHistory) }, Modifier.offset(x = 8.dp),
                )
            }
            EntryTitle(
                series = if (step == STEP_SECOND) 2 else 1,
                showSeries = step != STEP_DONE,
                sub = whenLabel(at, now.toLocalDate(), time),
            )
            if (step == STEP_SECOND) {
                SecondBanner(onSkip = { second = null; step = STEP_DONE })
            }
            if (step != STEP_DONE) {
                val invalid = verdict as? EntryCheck.Invalid
                Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EntryField.entries.forEach { field ->
                        val label = stringResource(fieldLabel(field))
                        FieldTile(
                            label = label,
                            value = digits.value(field),
                            unit = stringResource(if (field == EntryField.Pulse) R.string.bp_unit_pulse else R.string.bp_unit_pressure),
                            selected = digits.active == field,
                            bad = invalid != null && (invalid.error == ReadingError.Range || field != EntryField.Pulse),
                            onClick = { digits = digits.pick(field) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                EntryStatus(unreadable, verdict, target, previous, now.toLocalDate(), time)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhotoTextButton(
                        onText = { text -> readingFromText(text)?.let { accept(it, ReadingSource.Photo) } ?: run { unreadable = true } },
                        modifier = Modifier.weight(1f),
                    )
                    VoiceTextButton(
                        onText = { text -> readingFromText(text)?.let { accept(it, ReadingSource.Voice) } ?: run { unreadable = true } },
                        modifier = Modifier.weight(1f),
                    )
                    TonometerButton(onReading = { accept(it, ReadingSource.Bluetooth) }, modifier = Modifier.weight(1f))
                }
                Keypad(onPress = { key -> unreadable = false; digits = digits.press(key) })
            } else {
                DoneContent(first, second, target)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (step != STEP_DONE) {
                MedMainButton(
                    stringResource(if (step == STEP_SECOND) R.string.bp_save_second else R.string.bp_save_first),
                    onClick = ::save,
                    enabled = canSave,
                )
            } else {
                MedOutlineButton(
                    stringResource(R.string.bp_to_history),
                    // Итог закрываем, а не оставляем под историей: «Назад» из истории ведёт на «Сегодня».
                    onClick = { nav.back(); nav.open(Route.PressureHistory) },
                    modifier = Modifier.weight(1f),
                )
                MedMainButton(stringResource(R.string.bp_done), onClick = nav::back, modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun fieldLabel(field: EntryField) = when (field) {
    EntryField.Sys -> R.string.bp_sys
    EntryField.Dia -> R.string.bp_dia
    EntryField.Pulse -> R.string.bp_pulse
}

// --- Шапка и заголовок ---

/** Ссылка шапки: значок и слово акцентного цвета, цель нажатия 48 dp. */
@Composable
private fun HeaderLink(icon: ImageVector, iconSize: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(iconSize.dp), tint = p.accentText)
        Text(label, color = p.accentText, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
    }
}

/** Заголовок экрана, справа — «Замер 1 из 2»; под ним — время суток и время замера. */
@Composable
private fun EntryTitle(series: Int, showSeries: Boolean, sub: String) {
    val p = AnvilTheme.palette
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.bp_title),
                Modifier.weight(1f).semantics { heading() },
                color = p.text,
                style = screenTitleStyle(30),
            )
            if (showSeries) {
                Text(
                    stringResource(R.string.bp_series, series),
                    Modifier.clip(RoundedCornerShape(12.dp)).background(p.raised).padding(horizontal = 10.dp, vertical = 5.dp),
                    color = p.weak,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Text(sub, color = p.faint, style = MaterialTheme.typography.bodyMedium)
    }
}

/** «Время суток, 08:12»: утро — до 12:00, вечер — с 17:00 (`isMorning`, `isEvening`); не сегодняшний замер — с датой. */
@Composable
private fun whenLabel(at: LocalDateTime, today: LocalDate, time: DateTimeFormatter): String {
    val probe = Reading(0, 0, 0, null, at)
    val part = stringResource(
        when {
            isMorning(probe) -> R.string.bp_part_morning
            isEvening(probe) -> R.string.bp_part_evening
            else -> R.string.bp_part_day
        },
    )
    val t = time.format(at)
    val date = at.toLocalDate()
    return if (date == today) {
        stringResource(R.string.bp_when, part, t)
    } else {
        stringResource(R.string.bp_when_date, shortDate(date), part, t)
    }
}

@Composable
private fun shortDate(date: LocalDate): String {
    val locale = LocalConfiguration.current.locales[0]
    return date.format(DateTimeFormatter.ofPattern(stringResource(R.string.bp_date_short), locale))
}

/** «сегодня, 20:30», «вчера, 20:30», «26 сентября, 20:30» — когда был замер. */
@Composable
internal fun readingWhen(at: LocalDateTime, today: LocalDate, time: DateTimeFormatter): String {
    val t = time.format(at)
    val date = at.toLocalDate()
    return when (date) {
        today -> stringResource(R.string.bp_day_today, t)
        today.minusDays(1) -> stringResource(R.string.bp_day_yesterday, t)
        else -> stringResource(R.string.bp_day_date, shortDate(date), t)
    }
}

/** Баннер второго замера: подсказка и «Пропустить». */
@Composable
private fun SecondBanner(onSkip: () -> Unit) {
    val p = AnvilTheme.palette
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Ampulse.colors.accentSoft)
            .padding(start = 14.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(MedIcons.Clock, contentDescription = null, modifier = Modifier.size(20.dp), tint = p.accentText)
        Text(
            stringResource(R.string.bp_banner),
            Modifier.weight(1f),
            color = p.accentText,
            fontSize = 14.sp,
            lineHeight = 19.sp,
        )
        Box(
            Modifier
                .heightIn(min = 48.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onSkip)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.bp_skip), color = p.accentText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// --- Поля ---

/**
 * Поле замера 112 dp: подпись, число крупно, единица. Активное — мягкий акцент и рамка акцента 2 dp, с ошибкой — рамка
 * внимания. Для TalkBack — «Верхнее: 128» (пустое — «пусто»), выбранное.
 */
@Composable
private fun FieldTile(
    label: String,
    value: String,
    unit: String,
    selected: Boolean,
    bad: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = AnvilTheme.palette
    val c = Ampulse.colors
    val shape = RoundedCornerShape(18.dp)
    val description = stringResource(R.string.bp_field_value, label, value.ifEmpty { stringResource(R.string.bp_field_empty) })
    Column(
        modifier
            .heightIn(min = 112.dp)
            .clip(shape)
            .background(if (selected) c.accentSoft else p.card)
            .border(2.dp, if (bad) c.warnLine else if (selected) p.accent else p.card, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // Дети скрыты от TalkBack: вся плитка читается одной фразой выше.
        Text(label, Modifier.clearAndSetSemantics {}, color = p.faint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(
            value.ifEmpty { "—" },
            Modifier.clearAndSetSemantics {},
            color = if (value.isEmpty()) p.faint else p.text,
            style = MaterialTheme.typography.headlineLarge.copy(
                fontSize = 40.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum",
            ),
            textAlign = TextAlign.Center,
        )
        Text(unit, Modifier.clearAndSetSemantics {}, color = p.faint, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

// --- Оценка ---

/** Тон оценки: цвет всегда с словом рядом (общий гайдлайн, §3). */
internal enum class PressureTone { Idle, Good, Warn }

internal fun pressureTone(state: PressureState) = if (state == PressureState.Within) PressureTone.Good else PressureTone.Warn

/** Цвета тона: подложка, точка и текст. */
internal class ToneColors(val bg: Color, val dot: Color, val fg: Color)

@Composable
internal fun toneColors(tone: PressureTone): ToneColors {
    val p = AnvilTheme.palette
    val c = Ampulse.colors
    return when (tone) {
        PressureTone.Idle -> ToneColors(p.card, p.borderStrong, p.text)
        PressureTone.Good -> ToneColors(c.accentSoft, p.accent, p.accentText)
        PressureTone.Warn -> ToneColors(c.warnSoft, c.warnLine, p.warning)
    }
}

/** Слово оценки: «В пределах цели врача», «Выше цели врача», «Очень высокое», «Низкое». */
@Composable
internal fun pressureStateTitle(state: PressureState): String = stringResource(
    when (state) {
        PressureState.Within -> R.string.bp_in_target
        PressureState.Above -> R.string.bp_above
        PressureState.Crisis -> R.string.bp_crisis
        PressureState.Low -> R.string.bp_low
    },
)

/** Очень высокое и низкое — «звоните 103 или 112»; выше цели и в пределах — цель врача. */
@Composable
private fun pressureStateNote(state: PressureState, target: Target): String =
    if (state == PressureState.Crisis || state == PressureState.Low) {
        stringResource(R.string.bp_call)
    } else {
        stringResource(R.string.bp_target, target.sys, target.dia)
    }

/** Строка состояния под полями: подсказка, ошибка ввода или оценка. Для TalkBack — вежливо озвучивается при смене. */
@Composable
private fun EntryStatus(
    unreadable: Boolean,
    check: EntryCheck,
    target: Target,
    previous: List<Reading>?,
    today: LocalDate,
    time: DateTimeFormatter,
) {
    val tone: PressureTone
    val title: String
    val note: String?
    when {
        unreadable -> {
            tone = PressureTone.Warn
            title = stringResource(R.string.bp_read_fail)
            note = stringResource(R.string.bp_read_fail_note)
        }
        check is EntryCheck.Invalid -> {
            tone = PressureTone.Warn
            title = stringResource(if (check.error == ReadingError.Order) R.string.bp_err_order else R.string.bp_err_range)
            note = null
        }
        check is EntryCheck.Ready -> {
            val state = classify(check.sys, check.dia, target)
            tone = pressureTone(state)
            title = pressureStateTitle(state)
            note = if (check.pulseUnfinished) stringResource(R.string.bp_pulse_left) else pressureStateNote(state, target)
        }
        else -> {
            tone = PressureTone.Idle
            title = stringResource(R.string.bp_empty)
            val last = previous?.firstOrNull()
            note = when {
                previous == null -> null
                last == null -> stringResource(R.string.bp_prev_none)
                else -> stringResource(R.string.bp_prev, readingWhen(last.at, today, time), last.sys, last.dia)
            }
        }
    }
    StatusPanel(tone, title, note)
}

/** Панель состояния: точка и слово цвета тона, под ним пояснение. */
@Composable
private fun StatusPanel(tone: PressureTone, title: String, note: String?) {
    val p = AnvilTheme.palette
    val colors = toneColors(tone)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.bg)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 5.dp).size(10.dp).clip(CircleShape).background(colors.dot))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = colors.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (note != null) Text(note, color = p.faint, fontSize = 13.sp, lineHeight = 17.sp)
        }
    }
}

// --- Клавиатура ---

/** Своя цифровая клавиатура 3×4 (docs/ui-guideline.md, §6): цифры, «Стереть», «Далее»; клавиши 56 dp. */
@Composable
private fun Keypad(onPress: (EntryKey) -> Unit) {
    val rows = listOf(
        listOf<EntryKey>(EntryKey.Digit('1'), EntryKey.Digit('2'), EntryKey.Digit('3')),
        listOf<EntryKey>(EntryKey.Digit('4'), EntryKey.Digit('5'), EntryKey.Digit('6')),
        listOf<EntryKey>(EntryKey.Digit('7'), EntryKey.Digit('8'), EntryKey.Digit('9')),
        listOf<EntryKey>(EntryKey.Delete, EntryKey.Digit('0'), EntryKey.Next),
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { key -> KeypadKey(key, onPress) }
            }
        }
    }
}

@Composable
private fun RowScope.KeypadKey(key: EntryKey, onPress: (EntryKey) -> Unit) {
    val p = AnvilTheme.palette
    val digit = key is EntryKey.Digit
    val description = when (key) {
        EntryKey.Delete -> stringResource(R.string.bp_del)
        EntryKey.Next -> stringResource(R.string.bp_next)
        is EntryKey.Digit -> key.digit.toString()
    }
    Row(
        Modifier
            .weight(1f)
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (digit) p.card else p.raised)
            .clickable(role = Role.Button) { onPress(key) }
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (key) {
            is EntryKey.Digit -> Text(
                key.digit.toString(),
                Modifier.clearAndSetSemantics {},
                color = p.text,
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
            )
            EntryKey.Delete -> Icon(PressureIcons.Backspace, contentDescription = null, modifier = Modifier.size(24.dp), tint = p.text)
            EntryKey.Next -> {
                Text(
                    stringResource(R.string.bp_next),
                    Modifier.clearAndSetSemantics {},
                    color = p.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(PressureIcons.ArrowRight, contentDescription = null, modifier = Modifier.size(18.dp), tint = p.text)
            }
        }
    }
}

// --- Итог ---

/** Итог серии: «Сохранено», среднее крупно, пульс и оценка среднего (цвет плюс слово). */
@Composable
private fun DoneContent(first: Measured?, second: Measured?, target: Target) {
    val p = AnvilTheme.palette
    val measured = listOfNotNull(first, second)
    val avg = average(measured.map { Reading(0, it.sys, it.dia, it.pulse, LocalDateTime.MIN) }) ?: return
    val (sys, dia, pulse) = avg
    val state = classify(sys, dia, target)
    val colors = toneColors(pressureTone(state))
    Column(
        Modifier.fillMaxWidth().heightIn(min = 400.dp).padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(Ampulse.colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(PressureIcons.Check, contentDescription = null, modifier = Modifier.size(34.dp), tint = p.accentText)
        }
        Text(
            stringResource(R.string.bp_done_title),
            Modifier.semantics { heading() },
            color = p.text,
            style = MaterialTheme.typography.headlineMedium.copy(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
        )
        Text(
            stringResource(if (measured.size > 1) R.string.bp_done_avg else R.string.bp_done_one),
            color = p.faint,
            fontSize = 14.sp,
        )
        Text(
            "$sys/$dia",
            color = p.text,
            style = MaterialTheme.typography.displayMedium.copy(
                fontSize = 48.sp,
                lineHeight = 48.sp,
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum",
            ),
        )
        if (pulse != null) {
            Text(stringResource(R.string.bp_done_pulse, pulse), color = p.weak, fontSize = 15.sp)
        }
        Row(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(colors.bg)
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(colors.dot))
            Text(pressureStateTitle(state), color = colors.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        if (state == PressureState.Crisis || state == PressureState.Low) {
            Text(stringResource(R.string.bp_call), color = p.warning, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
    }
}

// --- Значки ---

/** Значки ввода давления: линии 1,8 на сетке 24, пути — набор Lucide, как в макете (docs/ui-guideline.md, §5). */
internal object PressureIcons {
    /** Стереть: клавиша ⌫. */
    val Backspace: ImageVector by lazy {
        icon(
            "bp.backspace",
            "M10 5a2 2 0 0 0-1.344.519l-6.328 5.74a1 1 0 0 0 0 1.481l6.328 5.741A2 2 0 0 0 10 19h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2z",
            "m12 9 6 6", "m18 9-6 6",
        )
    }

    /** Стрелка вправо: клавиша «Далее». */
    val ArrowRight: ImageVector by lazy { icon("bp.arrow_right", "M5 12h14", "m12 5 7 7-7 7") }

    /** График: «История». */
    val Chart: ImageVector by lazy { icon("bp.chart", "M3 3v16a2 2 0 0 0 2 2h16", "m19 9-5 5-4-4-3 3") }

    /** Галочка: «Сохранено». */
    val Check: ImageVector by lazy { icon("bp.check", "M20 6 9 17l-5-5") }

    /** Сердце с пульсом: карточка на «Сегодня». */
    val HeartPulse: ImageVector by lazy {
        icon(
            "bp.heart_pulse",
            "M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z",
            "M3.22 12H9.5l.5-1 2 4.5 2-7 1.5 3.5h5.27",
        )
    }

    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .apply {
                paths.forEach {
                    addPath(
                        pathData = addPathNodes(it),
                        stroke = SolidColor(Color.Black),
                        strokeLineWidth = 1.8f,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }.build()
}
