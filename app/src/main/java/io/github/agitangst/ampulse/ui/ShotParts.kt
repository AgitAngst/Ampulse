package io.github.agitangst.ampulse.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.agitangst.ampulse.Ampulse
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.domain.Site
import io.github.agitangst.anvil.compose.AnvilTheme

// Части экранов «Укол пошагово» и «Правила уколов» (docs/ui-guideline.md, §6 «Пошаговый процесс», «Разрез кожи»).

/** Значки уколов: линии 1,8 на сетке 24, пути — набор Lucide, как в макете (экраны 44–46). */
internal object ShotIcons {
    /** Раскрытая книга: «Правила», памятка. */
    val Book: ImageVector by lazy {
        icon("shot.book", "M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z", "M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z")
    }

    /** Круг с вопросом: «Что-то пошло не так?». */
    val Help: ImageVector by lazy {
        icon("shot.help", "M22 12a10 10 0 1 1-20 0a10 10 0 0 1 20 0Z", "M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3", "M12 17h.01")
    }

    /** Стрелка вниз: раскрыть. */
    val ChevronDown: ImageVector by lazy { icon("shot.chevron_down", "m6 9 6 6 6-6") }

    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
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

/** Слой кожи на разрезе и рамка блока «Когда нужен врач»: `#E7B894`, в тёмной теме `#6E4727` (docs/ui-guideline.md, §6). */
@Composable
internal fun skinColor(): Color = if (AnvilTheme.palette.dark) Color(0xFF6E4727) else Color(0xFFE7B894)

/** Строка-переход «Правила уколов ›» (docs/ui-guideline.md, §6 «Переход на другой экран»): вход в памятку из «Настроек». */
@Composable
fun ShotRulesLink(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(p.card)
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StockTile(ShotIcons.Book)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.shot_rules_title),
                color = p.text,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(stringResource(R.string.shot_rules_link_sub), color = p.weak, style = MaterialTheme.typography.bodyMedium)
        }
        Icon(MedIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp), tint = p.faint)
    }
}

/** «Шаг N из M» и полоса из M отрезков: пройденные — акцент, текущий — слабый цвет, дальше — дорожка. */
@Composable
internal fun ShotProgress(text: String, total: Int, done: Int, current: Int, modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text,
            Modifier.clearAndSetSemantics {},
            color = p.faint,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(done.toFloat(), 0f..total.toFloat())
                    contentDescription = text
                },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            repeat(total) { i ->
                val color = when {
                    i < done -> p.accent
                    i == current -> p.faint
                    else -> Ampulse.colors.track
                }
                Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
            }
        }
    }
}

/** Сделанные шаги свёрнутыми строками с итогом справа; нажатие возвращает к шагу. */
@Composable
internal fun ShotDoneRows(rows: List<Pair<String, String>>, onOpen: (Int) -> Unit, modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.card).padding(horizontal = 16.dp)) {
        rows.forEachIndexed { i, (title, short) ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clickable(role = Role.Button, onClick = { onOpen(i) })
                    .clearAndSetSemantics { contentDescription = "$title: $short" }
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(Ampulse.colors.accentSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(TodayIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp), tint = p.accentText)
                }
                Text(
                    title,
                    Modifier.weight(1f),
                    color = p.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                )
                Text(
                    short,
                    Modifier.widthIn(max = 150.dp),
                    color = p.faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                )
            }
        }
    }
}

/** Карточка текущего шага: рамка акцента 2 dp, номер в круге и название; содержимое и главная кнопка — внутри. */
@Composable
internal fun ShotStepCard(num: Int, title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val p = AnvilTheme.palette
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(p.card).border(2.dp, p.accent, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(p.accent).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
                Text(num.toString(), color = p.onAccent, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold))
            }
            Text(
                title,
                Modifier.weight(1f).semantics { heading() },
                color = p.text,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
            )
        }
        content()
    }
}

/** Текст шага. */
@Composable
internal fun ShotBody(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = AnvilTheme.palette.weak, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp))
}

/** «В окошке ручки»: доза крупно на подложке фона. */
@Composable
internal fun ShotDoseTile(label: String, value: String, modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(p.bg)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(label, Modifier.weight(1f), color = p.faint, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp))
        Text(value, color = p.text, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold))
    }
}

/** Проверка из данных приложения: галочка на мягком акценте; [warn] — значок и цвет внимания на мягком внимании. */
@Composable
internal fun ShotCheckRow(line: ShotCheckLine, modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    val fill = if (line.warn) Ampulse.colors.warnSoft else Ampulse.colors.accentSoft
    val ink = if (line.warn) p.warning else p.accentText
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(fill)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            if (line.warn) TodayIcons.Alert else TodayIcons.Check,
            contentDescription = null,
            modifier = Modifier.padding(top = 1.dp).size(18.dp),
            tint = ink,
        )
        Text(line.text, color = ink, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp))
    }
}

/**
 * Кольцо отсчёта 132 dp: дорожка и дуга акцента от верха по часовой на [fraction]; внутри крупное [big] и единица
 * [small]. [description] читает TalkBack; подпись под кольцом — живая область, она объявит конец отсчёта.
 */
@Composable
internal fun ShotTimerRing(big: String, small: String, fraction: Float, description: String, bigSize: Int, modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    val track = Ampulse.colors.track
    Box(modifier.size(132.dp).clearAndSetSemantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(132.dp)) {
            val stroke = 10.dp.toPx()
            val inset = stroke / 2
            val box = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, useCenter = false, topLeft = Offset(inset, inset), size = box, style = Stroke(stroke))
            if (fraction > 0f) {
                drawArc(p.accent, -90f, 360f * fraction.coerceIn(0f, 1f), useCenter = false, topLeft = Offset(inset, inset), size = box, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                big,
                color = p.text,
                maxLines = 1,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = bigSize.sp,
                    lineHeight = (bigSize + 4).sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFeatureSettings = "tnum",
                ),
            )
            if (small.isNotEmpty()) Text(small, color = p.faint, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp))
        }
    }
}

/** Подпись под кольцом: до конца отсчёта — как держать, потом — что можно; живая область. */
@Composable
internal fun ShotTimerCaption(text: String, done: Boolean, modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    Text(
        text,
        modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = if (done) p.accentText else p.weak,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
    )
}

/** Небольшая обычная кнопка 48 dp с рамкой 1,5 dp цвета текста: «Начать отсчёт». */
@Composable
internal fun ShotOutlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .border(1.5.dp, p.text, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = p.text, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
    }
}

/** Ещё не пройденные шаги: номер в кольце и название. */
@Composable
internal fun ShotNextRows(rows: List<Pair<Int, String>>, modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    Column(modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        rows.forEach { (num, title) ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(28.dp).clip(CircleShape).border(1.5.dp, p.borderStrong, CircleShape), contentAlignment = Alignment.Center) {
                    Text(num.toString(), color = p.faint, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
                }
                Text(title, color = p.weak, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp))
            }
        }
    }
}

/** Итог: мягкий акцент с галочкой и названием; строки текста и кнопки — внутри. */
@Composable
internal fun ShotResultCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val p = AnvilTheme.palette
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Ampulse.colors.accentSoft).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(TodayIcons.Check, contentDescription = null, modifier = Modifier.size(24.dp), tint = p.accentText)
            Text(
                title,
                Modifier.weight(1f).semantics { heading(); liveRegion = LiveRegionMode.Polite },
                color = p.accentText,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
            )
        }
        content()
    }
}

/** Тихая кнопка 48 dp на карточке итога. */
@Composable
internal fun ShotQuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = AnvilTheme.palette.accentText) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
    }
}

/** Раскрывающийся блок «Что-то пошло не так?»: проблемы кнопками, совет — на мягком внимании. */
@Composable
internal fun ShotProblems(
    open: Boolean,
    onToggle: () -> Unit,
    titles: List<String>,
    selected: Int,
    onPick: (Int) -> Unit,
    advice: String?,
    modifier: Modifier = Modifier,
) {
    val p = AnvilTheme.palette
    val state = stringResource(if (open) R.string.shot_expanded else R.string.shot_collapsed)
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.card).padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics(mergeDescendants = true) { stateDescription = state },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(ShotIcons.Help, contentDescription = null, modifier = Modifier.size(22.dp), tint = p.warning)
            Text(
                stringResource(R.string.shot_prob_h),
                Modifier.weight(1f),
                color = p.text,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            )
            Icon(ShotIcons.ChevronDown, contentDescription = null, modifier = Modifier.size(20.dp).rotate(if (open) 180f else 0f), tint = p.faint)
        }
        if (open) {
            Column(Modifier.padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                titles.forEachIndexed { i, title ->
                    val on = i == selected
                    val shape = RoundedCornerShape(14.dp)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(shape)
                            .background(if (on) Ampulse.colors.warnSoft else p.card)
                            .border(1.5.dp, if (on) Ampulse.colors.warnLine else p.borderStrong, shape)
                            .toggleable(value = on, role = Role.Button, onValueChange = { onPick(i) })
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(title, color = p.text, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium))
                    }
                }
                if (advice != null) {
                    Text(
                        advice,
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Ampulse.colors.warnSoft)
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        color = p.text,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                    )
                }
            }
        }
    }
}

/**
 * Ягодицы сзади (макет, экран 45): две половины, у выбранной стороны — четверти пунктиром и отмечена верхняя наружная.
 * Левая ягодица — слева на рисунке, правая — справа и зеркально.
 */
@Composable
internal fun ShotButtockDiagram(site: Site, modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    val soft = Ampulse.colors.accentSoft
    val left = isLeftSide(site)
    val description = stringResource(R.string.shot_diagram_a, stringResource(if (left) R.string.shot_side_left else R.string.shot_side_right))
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(p.bg)
            .padding(12.dp)
            .clearAndSetSemantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Canvas(Modifier.widthIn(max = 300.dp).fillMaxWidth().aspectRatio(300f / 132f)) {
            val s = size.width / 300f
            // x выбранной стороны в координатах рисунка «левой»; у правой — зеркально относительно середины.
            fun mx(x: Float) = (if (left) x else 300f - x) * s
            fun y(v: Float) = v * s
            val round = CornerRadius(54f * s)
            val line = Stroke(1.5f * s)
            for (x in listOf(14f, 158f)) {
                drawRoundRect(p.card, Offset(x * s, y(8f)), Size(128f * s, y(116f)), round)
                drawRoundRect(p.borderStrong, Offset(x * s, y(8f)), Size(128f * s, y(116f)), round, style = line)
            }
            // Верхняя наружная четверть выбранной стороны.
            val quarter = Path().apply {
                moveTo(mx(14f), y(66f))
                lineTo(mx(78f), y(66f))
                lineTo(mx(78f), y(8f))
                lineTo(mx(68f), y(8f))
                arcTo(Rect(mx(68f) - 54f * s, y(8f), mx(68f) + 54f * s, y(116f)), -90f, if (left) -90f else 90f, false)
                close()
            }
            drawPath(quarter, soft)
            val dash = PathEffect.dashPathEffect(floatArrayOf(4f * s, 4f * s))
            val w = 1.2f * s
            drawLine(p.faint, Offset(mx(78f), y(8f)), Offset(mx(78f), y(124f)), strokeWidth = w, pathEffect = dash)
            drawLine(p.faint, Offset(mx(14f), y(66f)), Offset(mx(142f), y(66f)), strokeWidth = w, pathEffect = dash)
            // Другая сторона — те же пунктиры цветом рамки.
            fun other(x: Float) = (if (left) 300f - x else x) * s
            drawLine(p.borderStrong, Offset(other(78f), y(8f)), Offset(other(78f), y(124f)), strokeWidth = w, pathEffect = dash)
            drawLine(p.borderStrong, Offset(other(14f), y(66f)), Offset(other(142f), y(66f)), strokeWidth = w, pathEffect = dash)
            drawCircle(p.accent, 9f * s, Offset(mx(50f), y(40f)))
            drawCircle(p.accent.copy(alpha = 0.5f), 15f * s, Offset(mx(50f), y(40f)), style = Stroke(1.5f * s))
        }
        Row(Modifier.widthIn(max = 300.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val tiny = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp)
            Text(stringResource(R.string.shot_side_left), color = p.faint, style = tiny)
            Text(stringResource(R.string.shot_back_view), color = p.faint, style = tiny)
            Text(stringResource(R.string.shot_side_right), color = p.faint, style = tiny)
        }
    }
}

/**
 * Разрез кожи (макет, экран 46): кожа, подкожный жир, мышца и шприц под прямым углом; острие в жире (подкожно) или в
 * мышце. Рисунок 318×168 масштабируется по ширине; подписи слоёв — справа от засечек.
 */
@Composable
internal fun ShotSkinDiagram(kind: ShotKind, description: String, modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    val skin = skinColor()
    val fatColor = Ampulse.colors.warnSoft
    val muscleColor = Ampulse.colors.accentSoft
    val fatH = if (kind == ShotKind.Pen) 60f else 44f
    val muscleY = 52f + fatH
    val tipY = if (kind == ShotKind.Pen) 92f else 138f
    val fatMid = 52f + fatH / 2
    val muscleMid = muscleY + (164f - muscleY) / 2
    BoxWithConstraints(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description }) {
        val s = (maxWidth / 318.dp).coerceAtMost(1f)
        Box(Modifier.fillMaxWidth().height((168f * s).dp)) {
            Canvas(Modifier.size((318f * s).dp, (168f * s).dp)) {
                val k = size.width / 318f
                drawRect(skin, Offset(0f, 40f * k), Size(190f * k, 12f * k))
                drawRect(fatColor, Offset(0f, 52f * k), Size(190f * k, fatH * k))
                drawRect(muscleColor, Offset(0f, muscleY * k), Size(190f * k, (164f - muscleY) * k))
                drawLine(p.borderStrong, Offset(0f, muscleY * k), Offset(190f * k, muscleY * k), strokeWidth = k)
                // Шприц: корпус, игла, острие.
                drawRoundRect(p.card, Offset(86f * k, 6f * k), Size(18f * k, 26f * k), CornerRadius(4f * k))
                drawRoundRect(p.weak, Offset(86f * k, 6f * k), Size(18f * k, 26f * k), CornerRadius(4f * k), style = Stroke(1.5f * k))
                drawLine(p.text, Offset(95f * k, 32f * k), Offset(95f * k, tipY * k), strokeWidth = 2.4f * k, cap = StrokeCap.Round)
                drawCircle(p.accent.copy(alpha = 0.35f), 11f * k, Offset(95f * k, tipY * k))
                drawCircle(p.accent, 5f * k, Offset(95f * k, tipY * k))
                for (yy in listOf(46f, fatMid, muscleMid)) {
                    drawLine(p.faint, Offset(206f * k, yy * k), Offset(198f * k, yy * k), strokeWidth = 1.2f * k)
                }
            }
            val tiny = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp)
            Text(stringResource(R.string.shot_layer_skin), Modifier.offset((212f * s).dp, ((46f - 8f) * s).dp), color = p.weak, style = tiny, maxLines = 1)
            Text(stringResource(R.string.shot_layer_fat), Modifier.offset((212f * s).dp, ((fatMid - 8f) * s).dp), color = p.weak, style = tiny, maxLines = 1)
            Text(stringResource(R.string.shot_layer_muscle), Modifier.offset((212f * s).dp, ((muscleMid - 8f) * s).dp), color = p.weak, style = tiny, maxLines = 1)
            Text("90°", Modifier.offset((112f * s).dp, (10f * s).dp), color = p.text, style = tiny.copy(fontWeight = FontWeight.SemiBold))
        }
    }
}
