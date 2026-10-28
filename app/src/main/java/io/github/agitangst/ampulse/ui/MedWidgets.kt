package io.github.agitangst.ampulse.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.agitangst.ampulse.AmpulseIcons
import io.github.agitangst.ampulse.Ampulse
import io.github.agitangst.ampulse.R
import io.github.agitangst.anvil.compose.AnvilTheme
import io.github.agitangst.anvil.compose.Motion
import io.github.agitangst.anvil.compose.motionSpec
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

// Общие части экранов «Аптечка», «Лекарство» и «По необходимости» (docs/ui-guideline.md, §6): цели нажатия ≥ 48 dp.

/** Строка «назад» вложенного экрана: стрелка и слово, высота не меньше 48 dp; для TalkBack — кнопка «Назад». */
@Composable
internal fun MedBackRow(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            AmpulseIcons.ChevronLeft,
            contentDescription = stringResource(R.string.settings_back),
            modifier = Modifier.size(22.dp),
            tint = p.accentText,
        )
        Text(label, color = p.accentText, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
    }
}

/** Круглая обычная кнопка 48 dp со значком (как шестерёнка в шапке «Сегодня»); [description] — подпись для TalkBack. */
@Composable
internal fun MedRoundButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(p.raised)
            .border(1.5.dp, p.borderStrong, CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(22.dp), tint = p.text)
    }
}

/** Главная кнопка: залита акцентом, 54 dp, полностью круглая. Недоступная — на подложке, причину рисует вызывающий. */
@Composable
internal fun MedMainButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val p = AnvilTheme.palette
    Box(
        modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(CircleShape)
            .background(if (enabled) p.accent else p.raised)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (enabled) p.onAccent else p.faint,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

/** Обычная кнопка с рамкой 1,5 dp, 54 dp; [danger] — цвета внимания (красного в программе нет). */
@Composable
internal fun MedOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    val p = AnvilTheme.palette
    val line = if (danger) Ampulse.colors.warnLine else p.borderStrong
    val ink = if (!enabled) p.faint else if (danger) p.danger else p.text
    Row(
        modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(CircleShape)
            .border(1.5.dp, line, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = ink)
        Text(text, color = ink, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
    }
}

/**
 * Чип выбора: 48 dp, выбранный залит цветом текста. [multi] — можно выбрать несколько (дни недели), иначе один из
 * группы. [minWidth] — чтобы короткие подписи («Пн») не были уже цели нажатия.
 */
@Composable
internal fun MedChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    multi: Boolean = false,
    minWidth: Dp = 48.dp,
    icon: ImageVector? = null,
) {
    val p = AnvilTheme.palette
    val fill by animateColorAsState(if (selected) p.text else p.card, motionSpec(Motion.STATE), label = "chip-fill")
    val ink = if (selected) p.bg else p.text
    val shape = RoundedCornerShape(24.dp)
    val semantic = if (multi) {
        Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onClick() })
    } else {
        Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
    }
    Row(
        modifier
            .heightIn(min = 48.dp)
            .widthIn(min = minWidth)
            .clip(shape)
            .background(fill)
            .border(1.5.dp, if (selected) p.text else p.borderStrong, shape)
            .then(semantic)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = ink)
        Text(label, color = ink, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), textAlign = TextAlign.Center)
    }
}

/** Кнопка «+» или «−» шагового поля: круг 48 dp; нажатие — шаг, удержание — повтор (для больших чисел). */
@Composable
private fun MedStepButton(icon: ImageVector, description: String, enabled: Boolean, onStep: () -> Unit) {
    val p = AnvilTheme.palette
    val step by rememberUpdatedState(onStep)
    val canStep by rememberUpdatedState(enabled)
    val repeated = remember { booleanArrayOf(false) }
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(p.raised)
            .alpha(if (enabled) 1f else 0.5f)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
                if (!enabled) disabled()
                onClick {
                    if (canStep) step()
                    canStep
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        repeated[0] = false
                        coroutineScope {
                            val job = launch {
                                delay(450)
                                while (canStep) {
                                    repeated[0] = true
                                    step()
                                    delay(90)
                                }
                            }
                            tryAwaitRelease()
                            job.cancel()
                        }
                    },
                    onTap = { if (canStep && !repeated[0]) step() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = if (enabled) p.text else p.faint)
    }
}

/** Шаговое поле: две круглые кнопки 48 dp и значение между ними. [label] — к чему относится, для TalkBack. */
@Composable
internal fun MedStepper(
    value: String,
    label: String,
    onLess: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    lessEnabled: Boolean = true,
    moreEnabled: Boolean = true,
) {
    val p = AnvilTheme.palette
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        MedStepButton(MedIcons.Minus, stringResource(R.string.meds_less, label), lessEnabled, onLess)
        Text(
            value,
            Modifier.widthIn(min = 64.dp),
            color = p.text,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            textAlign = TextAlign.Center,
        )
        MedStepButton(MedIcons.Plus, stringResource(R.string.meds_more, label), moreEnabled, onMore)
    }
}

/** Строка «подпись слева — шаговое поле справа» высотой не меньше 60 dp (макет, экран 10). */
@Composable
internal fun MedStepRow(
    label: String,
    value: String,
    onLess: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    lessEnabled: Boolean = true,
    moreEnabled: Boolean = true,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 60.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            Modifier.weight(1f),
            color = AnvilTheme.palette.text,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
        )
        MedStepper(value, label, onLess, onMore, lessEnabled = lessEnabled, moreEnabled = moreEnabled)
    }
}

/** Строка с выключателем: нажимается вся строка (role «выключатель»), под заголовком — пояснение. */
@Composable
internal fun MedSwitchRow(
    title: String,
    hint: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = AnvilTheme.palette
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = p.text, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
            Text(hint, color = p.weak, style = MaterialTheme.typography.bodyMedium)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = p.onAccent,
                checkedTrackColor = p.accent,
                checkedBorderColor = p.accent,
                uncheckedThumbColor = p.weak,
                uncheckedTrackColor = p.raised,
                uncheckedBorderColor = p.borderStrong,
            ),
        )
    }
}

/** Кнопка со временем или датой: значок, текст; высота 48 dp. [description] — для TalkBack. */
@Composable
internal fun MedValueButton(text: String, icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(p.card)
            .border(1.5.dp, p.borderStrong, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = description }
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = p.weak)
        Text(text, color = p.text, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
    }
}

/** Кнопка «добавить» с пунктирной рамкой на всю ширину, 48 dp. */
@Composable
internal fun MedAddButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    val line = p.faint
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .drawBehind {
                drawRoundRect(
                    color = line,
                    cornerRadius = CornerRadius(14.dp.toPx()),
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
                )
            }
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(MedIcons.Plus, contentDescription = null, modifier = Modifier.size(18.dp), tint = p.weak)
        Text(text, color = p.weak, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
    }
}

/** Поле ввода: рамка 1,5 dp, подпись над полем плавает (читается TalkBack), цвета палитры. */
@Composable
internal fun MedTextField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    imeAction: ImeAction = ImeAction.Next,
) {
    val p = AnvilTheme.palette
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        label = { Text(label) },
        placeholder = { if (hint != null) Text(hint) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(capitalization = capitalization, imeAction = imeAction),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = p.text,
            unfocusedTextColor = p.text,
            focusedContainerColor = p.field,
            unfocusedContainerColor = p.field,
            cursorColor = p.accent,
            focusedBorderColor = p.accent,
            unfocusedBorderColor = p.borderStrong,
            focusedLabelColor = p.accentText,
            unfocusedLabelColor = p.weak,
            focusedPlaceholderColor = p.faint,
            unfocusedPlaceholderColor = p.faint,
        ),
    )
}

/**
 * Диалог выбора времени (Material 3 `TimePicker`, 12 или 24 ч — по настройке телефона). [taken] — время, которое уже
 * есть в схеме: на него «ОК» недоступно, причина написана под часами.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MedTimeDialog(
    initial: LocalTime,
    is24: Boolean,
    taken: Set<LocalTime>,
    onConfirm: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = AnvilTheme.palette
    val state = rememberTimePickerState(initial.hour, initial.minute, is24)
    val picked = LocalTime.of(state.hour, state.minute)
    val duplicate = picked in taken
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(horizontal = 16.dp),
            shape = RoundedCornerShape(28.dp),
            color = p.card,
            contentColor = p.text,
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    stringResource(R.string.meds_time_dialog),
                    color = p.weak,
                    style = MaterialTheme.typography.labelLarge,
                )
                TimePicker(state)
                if (duplicate) {
                    Text(stringResource(R.string.meds_time_taken), color = p.warning, style = MaterialTheme.typography.bodyMedium)
                }
                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.meds_cancel), color = p.accentText) }
                    TextButton(onClick = { onConfirm(picked) }, enabled = !duplicate) {
                        Text(stringResource(R.string.meds_ok), color = if (duplicate) p.faint else p.accentText)
                    }
                }
            }
        }
    }
}

/** Диалог выбора даты (Material 3 `DatePicker`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MedDateDialog(initial: LocalDate, onConfirm: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val p = AnvilTheme.palette
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { onConfirm(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                },
                enabled = state.selectedDateMillis != null,
            ) { Text(stringResource(R.string.meds_ok), color = p.accentText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.meds_cancel), color = p.accentText) } },
    ) {
        DatePicker(state)
    }
}

/** Рамка карточки-блока для строк с заливкой `warnSoft` (причины, предупреждения): значок и текст цвета внимания. */
@Composable
internal fun MedNotice(text: String, modifier: Modifier = Modifier, icon: ImageVector = MedIcons.Warning) {
    val p = AnvilTheme.palette
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Ampulse.colors.warnSoft)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = p.warning)
        Text(text, color = p.warning, style = MaterialTheme.typography.bodyMedium)
    }
}
