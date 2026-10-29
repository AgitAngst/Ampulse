package io.github.agitangst.ampulse.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.agitangst.ampulse.Ampulse
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.screenTitleStyle
import io.github.agitangst.anvil.compose.AnvilEasing
import io.github.agitangst.anvil.compose.AnvilTheme
import io.github.agitangst.anvil.compose.Motion
import io.github.agitangst.anvil.compose.rememberReducedMotion

// Вид кнопок «Фото рецепта» и «Голосом» и листов под ними (макет, экран 7; docs/ui-guideline.md, §6).

/**
 * Кнопка входа: обычная (рамка 1,5 dp), полностью круглая, не ниже 48 dp; подпись переносится на вторую строку.
 * Недоступная — серая, [stateText] читает TalkBack вместо «недоступно» (причина рядом пишется текстом).
 */
@Composable
internal fun CaptureButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    stateText: String? = null,
) {
    val p = AnvilTheme.palette
    val ink = if (enabled) p.text else p.faint
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .border(1.5.dp, if (enabled) p.borderStrong else p.border, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (stateText != null) Modifier.semantics { stateDescription = stateText } else Modifier)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = ink)
        Text(
            text,
            color = ink,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

/** Заголовок листа (Literata 24, как у листа выбора лекарства). [live] — TalkBack зачитывает смену текста. */
@Composable
internal fun CaptureSheetTitle(text: String, modifier: Modifier = Modifier, center: Boolean = false, live: Boolean = false) {
    Text(
        text,
        modifier
            .then(if (center) Modifier.fillMaxWidth() else Modifier)
            .semantics {
                heading()
                if (live) liveRegion = LiveRegionMode.Polite
            },
        color = AnvilTheme.palette.text,
        style = screenTitleStyle(24),
        textAlign = if (center) TextAlign.Center else TextAlign.Start,
    )
}

/** Строка выбора источника: плитка значка, название и пояснение; вся строка нажимается, цель не меньше 56 dp. */
@Composable
internal fun CaptureSourceRow(icon: ImageVector, title: String, hint: String, onClick: () -> Unit) {
    val p = AnvilTheme.palette
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StockTile(icon, soft = true)
        Column(Modifier.weight(1f)) {
            Text(title, color = p.text, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
            Text(hint, color = p.weak, style = MaterialTheme.typography.bodyMedium)
        }
        Icon(MedIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp), tint = p.faint)
    }
}

/**
 * Сообщение в листе: заголовок, пояснение, при желании мелкая [note] и две кнопки (главная, ниже — обычная). [warn] —
 * пояснение на мягком внимании со значком (ошибка), иначе простым текстом.
 */
@Composable
internal fun CaptureMessage(
    title: String,
    hint: String,
    primary: String?,
    onPrimary: () -> Unit,
    secondary: String?,
    onSecondary: () -> Unit,
    warn: Boolean = false,
    note: String? = null,
) {
    val p = AnvilTheme.palette
    CaptureSheetTitle(title)
    if (warn) MedNotice(hint) else Text(hint, color = p.weak, style = MaterialTheme.typography.bodyLarge)
    if (note != null) Text(note, color = p.faint, style = MaterialTheme.typography.bodyMedium)
    if (primary != null) MedMainButton(primary, onPrimary)
    if (secondary != null) MedOutlineButton(secondary, onSecondary)
}

/** Идёт распознавание фото: полоса (пока доля неизвестна — бегущая), пояснение и «Отмена». */
@Composable
internal fun CaptureWorking(percent: Int, onCancel: () -> Unit) {
    val p = AnvilTheme.palette
    CaptureSheetTitle(stringResource(R.string.capture_working), live = true)
    val bar = Modifier.fillMaxWidth().clip(CircleShape)
    if (percent <= 0) {
        LinearProgressIndicator(bar, color = p.accent, trackColor = Ampulse.colors.track)
    } else {
        LinearProgressIndicator(progress = { percent.coerceAtMost(100) / 100f }, modifier = bar, color = p.accent, trackColor = Ampulse.colors.track)
    }
    Text(stringResource(R.string.capture_working_hint), color = p.weak, style = MaterialTheme.typography.bodyLarge)
    MedOutlineButton(stringResource(R.string.capture_cancel), onCancel)
}

/**
 * Лист диктовки (макет, экран 7, «Голосом»): круг микрофона 76 dp с кольцом, заголовок, состояние словами, то, что уже
 * распознано (иначе пример), и «Стоп» с «Отмена». [finishing] — «Стоп» нажат, ждём итог.
 */
@Composable
internal fun CaptureVoicePanel(finishing: Boolean, partial: String, onStop: () -> Unit, onCancel: () -> Unit) {
    val p = AnvilTheme.palette
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MicCircle(pulse = !finishing)
        CaptureSheetTitle(stringResource(R.string.capture_voice_title), center = true)
        Text(
            stringResource(if (finishing) R.string.capture_voice_finishing else R.string.capture_voice_listening),
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = p.accentText,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            textAlign = TextAlign.Center,
        )
        if (partial.isBlank()) {
            Text(
                stringResource(R.string.capture_voice_example),
                color = p.weak,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        } else {
            val shape = RoundedCornerShape(16.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 140.dp)
                    .clip(shape)
                    .border(1.5.dp, p.borderStrong, shape)
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
            ) {
                Text(partial, color = p.text, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Text(
            stringResource(R.string.capture_voice_note),
            color = p.faint,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            textAlign = TextAlign.Center,
        )
    }
    MedMainButton(stringResource(R.string.capture_voice_stop), onStop, enabled = !finishing)
    MedOutlineButton(stringResource(R.string.capture_cancel), onCancel)
}

/**
 * Круг микрофона 76 dp цвета акцента с кольцом мягкого акцента 10 dp (как в макете). Пока идёт запись, кольцо дышит
 * (от 6 до 14 dp, 1,2 с); анимация в системе выключена — кольцо стоит. Для TalkBack — декор: состояние говорит текст.
 */
@Composable
private fun MicCircle(pulse: Boolean) {
    val p = AnvilTheme.palette
    val ringColor = Ampulse.colors.accentSoft
    val reduced = rememberReducedMotion()
    val ring: State<Float> = if (pulse && !reduced) {
        rememberInfiniteTransition(label = "mic-ring").animateFloat(
            initialValue = 6f,
            targetValue = 14f,
            animationSpec = infiniteRepeatable(tween(Motion.BLINK, easing = AnvilEasing.Standard), RepeatMode.Reverse),
            label = "mic-ring-width",
        )
    } else {
        remember { mutableFloatStateOf(10f) }
    }
    Box(
        Modifier
            .size(104.dp)
            .clearAndSetSemantics { }
            .drawBehind { drawCircle(ringColor, radius = 38.dp.toPx() + ring.value.dp.toPx()) },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(76.dp).clip(CircleShape).background(p.accent), contentAlignment = Alignment.Center) {
            Icon(CaptureIcons.Mic, contentDescription = null, modifier = Modifier.size(32.dp), tint = p.onAccent)
        }
    }
}

/** Значки ввода назначения: линии 1,8 на сетке 24, пути — набор Lucide, как в макете (docs/ui-guideline.md, §5). */
internal object CaptureIcons {
    /** Камера: «Снять» и кнопка «Фото рецепта». */
    val Camera: ImageVector by lazy {
        icon(
            "capture.camera",
            "M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3l-2.5-3z",
            "M15 13a3 3 0 1 1-6 0a3 3 0 0 1 6 0Z",
        )
    }

    /** Картинка: «Из галереи». */
    val Image: ImageVector by lazy {
        icon(
            "capture.image",
            "M19 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V5a2 2 0 0 0-2-2Z",
            "M11 9a2 2 0 1 1-4 0a2 2 0 0 1 4 0Z",
            "m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21",
        )
    }

    /** Микрофон: кнопка «Голосом» и круг диктовки. */
    val Mic: ImageVector by lazy {
        icon(
            "capture.mic",
            "M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3Z",
            "M19 10v2a7 7 0 0 1-14 0v-2",
            "M12 19v3",
        )
    }

    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
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
