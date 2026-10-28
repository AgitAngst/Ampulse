package io.github.agitangst.ampulse.ui

import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.agitangst.ampulse.AmpulseIcons
import io.github.agitangst.ampulse.domain.MedForm
import io.github.agitangst.anvil.compose.AnvilTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Общие части экранов «Сегодня» и «История»: значки, часы, формат времени и дозы, кнопки и лист поверх.
 * Имена с приставкой Today — чтобы не пересекаться с другими экранами пакета.
 */

/** Значки экранов «Сегодня» и «История»: линии 1,8 на сетке 24, пути — набор Lucide, как в макете. */
internal object TodayIcons {
    /** Галочка: «принято». */
    val Check: ImageVector by lazy { icon("today.check", "M20 6 9 17l-5-5") }

    /** Плюс: добавить лекарство. */
    val Plus: ImageVector by lazy { icon("today.plus", "M5 12h14", "M12 5v14") }

    /** Три точки: меню действий строки. */
    val More: ImageVector by lazy {
        icon(
            "today.more",
            "M12 11a1 1 0 1 0 0 2a1 1 0 1 0 0-2Z",
            "M19 11a1 1 0 1 0 0 2a1 1 0 1 0 0-2Z",
            "M5 11a1 1 0 1 0 0 2a1 1 0 1 0 0-2Z",
        )
    }

    /** Треугольник с восклицательным знаком: внимание. */
    val Alert: ImageVector by lazy {
        icon(
            "today.alert",
            "m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3",
            "M12 9v4", "M12 17h.01",
        )
    }

    /** Листок с записью: «записали лишний приём». */
    val FileText: ImageVector by lazy {
        icon(
            "today.file_text",
            "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z",
            "M14 2v4a2 2 0 0 0 2 2h4", "M9 13h6", "M9 17h4",
        )
    }

    /** Стрелка вправо; в письме справа налево зеркалится. */
    val ChevronRight: ImageVector by lazy { icon("today.chevron_right", "m9 18 6-6-6-6", autoMirror = true) }

    /** Шприц: уколы. */
    val Syringe: ImageVector by lazy {
        icon(
            "today.syringe",
            "m18 2 4 4", "m17 7 3-3", "M19 9 8.7 19.3c-1 1-2.5 1-3.4 0l-.6-.6c-1-1-1-2.5 0-3.4L15 5",
            "m9 11 4 4", "m5 19-3 3", "m14 4 6 6",
        )
    }

    /** Капля: капли и жидкости. */
    val Droplet: ImageVector by lazy {
        icon("today.droplet", "M12 22a7 7 0 0 0 7-7c0-2-1-3.9-3-5.5s-3.5-4-4-6.5c-.5 2.5-2 4.9-4 6.5C6 11.1 5 13 5 15a7 7 0 0 0 7 7z")
    }

    /** Лампочка: подсказка. */
    val Lightbulb: ImageVector by lazy {
        icon(
            "today.lightbulb",
            "M15 14c.2-1 .7-1.7 1.5-2.5 1-.9 1.5-2.2 1.5-3.5A6 6 0 0 0 6 8c0 1 .2 2.2 1.5 3.5.7.7 1.3 1.5 1.5 2.5",
            "M9 18h6", "M10 22h4",
        )
    }

    /** Крестик: «пропущено». */
    val Cross: ImageVector by lazy { icon("today.cross", "M18 6 6 18", "m6 6 12 12") }

    private fun icon(name: String, vararg paths: String, autoMirror: Boolean = false): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = autoMirror,
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

/** Значок в плитке строки — по форме лекарства. */
internal fun todayFormIcon(form: MedForm): ImageVector = when (form) {
    MedForm.Injection -> TodayIcons.Syringe
    MedForm.Drops, MedForm.Liquid -> TodayIcons.Droplet
    MedForm.Tablet, MedForm.Capsule, MedForm.Other -> AmpulseIcons.Pill
}

/** «Сейчас» с точностью до минуты; обновляется на границе минуты. */
@Composable
internal fun rememberTodayNow(): LocalDateTime {
    var now by remember { mutableStateOf(LocalDateTime.now().withSecond(0).withNano(0)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L + 20L)
            now = LocalDateTime.now().withSecond(0).withNano(0)
        }
    }
    return now
}

/** Формат времени по языку программы и настройке телефона (12 или 24 часа). */
@Composable
internal fun rememberTodayTime(): DateTimeFormatter {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val is24 = DateFormat.is24HourFormat(context)
    return remember(is24, locale) { DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "h:mm a", locale) }
}

/** Доза с единицей: «1 табл.», «0,5 табл.», «12 ЕД» — число по локали. */
@Composable
internal fun todayDoseText(amount: Double, unit: String): String {
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { NumberFormat.getInstance(locale).apply { maximumFractionDigits = 2 } }
    return "${format.format(amount)} $unit".trim()
}

/** Имя с дозировкой, как на упаковке: «Метформин 850 мг». */
internal fun todayMedName(name: String, strength: String): String =
    listOf(name, strength).filter { it.isNotBlank() }.joinToString(" ")

/** Круглая или овальная кнопка с текстом: заливка, рамка и цвет текста задаёт вызывающий. Высота — не меньше [height]. */
@Composable
internal fun TodayButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = Color.Transparent,
    content: Color = AnvilTheme.palette.text,
    border: BorderStroke? = null,
    height: Int = 48,
    fontSize: Int = 15,
    paddingH: Int = 20,
) {
    val shape = CircleShape
    Box(
        modifier
            .heightIn(min = height.dp)
            .clip(shape)
            .background(container)
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = paddingH.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = content, fontSize = fontSize.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

/**
 * Лист поверх (docs/ui-guideline.md, §6): скругление 24 сверху, ручка 36×4, затемнение 50 % (в тёмной теме — чёрное
 * 60 %). [content] получает `close` — закрыть лист с анимацией и сообщить [onDismiss].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TodaySheet(onDismiss: () -> Unit, content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    val p = AnvilTheme.palette
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { state.hide() }.invokeOnCompletion { onDismiss() } }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = p.card,
        contentColor = p.text,
        scrimColor = if (p.dark) Color.Black.copy(alpha = 0.6f) else p.text.copy(alpha = 0.5f),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 4.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(p.borderStrong),
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            this.content(close)
        }
    }
}
