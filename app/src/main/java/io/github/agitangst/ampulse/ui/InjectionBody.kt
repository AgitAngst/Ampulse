package io.github.agitangst.ampulse.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.agitangst.ampulse.Ampulse
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.domain.Site
import io.github.agitangst.anvil.compose.AnvilTheme

/**
 * Схема тела на экране «Место укола» (макет, экраны 3, 17–18): человек спереди или сзади, кошка и собака сбоку; на
 * схеме — точки мест с цифрой «дней с укола». Рисунок — `Canvas` по сетке макета ([bodyGrid]), точки — кнопки 48 dp
 * с кругом 44 по макету.
 */

/** Точка места: цифра, метка и подпись для TalkBack. */
internal data class BodySpot(val site: Site, val number: String, val tag: SiteTag, val description: String)

/** Цвета силуэта питомца (макет, экраны 17–18 и их тёмные копии); в палитру общих цветов не входят. */
private class PetColors(val body: Color, val far: Color, val ear: Color, val nose: Color)

private val PetLight = PetColors(Color(0xFFDCD6CA), Color(0xFFCFC8BA), Color(0xFFC4BDAE), Color(0xFFB9B2A5))
private val PetDark = PetColors(Color(0xFF34403D), Color(0xFF2C3634), Color(0xFF28312F), Color(0xFF56615E))

/** Схема с точками. [side] — показанная сторона питомца (левый бок зеркалит рисунок); у человека не используется. */
@Composable
internal fun BodyScheme(
    view: BodyView,
    spots: List<BodySpot>,
    selected: Site,
    side: PetSide,
    onPick: (Site) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (view) {
        BodyView.Front, BodyView.Back -> PersonScheme(view == BodyView.Back, spots, selected, onPick, modifier)
        BodyView.Cat, BodyView.Dog -> PetScheme(view == BodyView.Dog, spots, selected, side, onPick, modifier)
    }
}

/** Выбранная точка рисуется последней — кольцо выбора лежит поверх соседей. */
private fun List<BodySpot>.selectedLast(selected: Site): List<BodySpot> = sortedBy { it.site == selected }

@Composable
private fun PersonScheme(back: Boolean, spots: List<BodySpot>, selected: Site, onPick: (Site) -> Unit, modifier: Modifier) {
    val p = AnvilTheme.palette
    val view = if (back) BodyView.Back else BodyView.Front
    val (gridW, gridH) = bodyGrid(view)
    // Спереди правая сторона человека — слева от зрителя; сзади — справа.
    val leftWord = stringResource(if (back) R.string.injection_person_left else R.string.injection_person_right)
    val rightWord = stringResource(if (back) R.string.injection_person_right else R.string.injection_person_left)
    Box(modifier.fillMaxWidth().height(gridH.dp)) {
        SideWord(leftWord, Modifier.align(Alignment.TopStart))
        SideWord(rightWord, Modifier.align(Alignment.TopEnd))
        Box(Modifier.align(Alignment.Center).size(gridW.dp, gridH.dp)) {
            val fill = p.bg
            val line = p.borderStrong
            Canvas(Modifier.fillMaxSize()) { drawPerson(back, fill, line) }
            spots.selectedLast(selected).forEach { spot ->
                val (x, y) = spotOf(view, spot.site) ?: return@forEach
                SpotButton(x, y, spot, spot.site == selected) { onPick(spot.site) }
            }
        }
    }
}

@Composable
private fun SideWord(text: String, modifier: Modifier) {
    Text(
        text.uppercase(),
        modifier.clearAndSetSemantics {},
        color = AnvilTheme.palette.faint,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
    )
}

@Composable
private fun PetScheme(dog: Boolean, spots: List<BodySpot>, selected: Site, side: PetSide, onPick: (Site) -> Unit, modifier: Modifier) {
    val p = AnvilTheme.palette
    val view = if (dog) BodyView.Dog else BodyView.Cat
    val (gridW, gridH) = bodyGrid(view)
    val flip = side == PetSide.Left
    val colors = if (p.dark) PetDark else PetLight
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // На узком телефоне схема уменьшается целиком; точки остаются 48 dp.
        val k = minOf(1f, maxWidth.value / gridW)
        Box(Modifier.size((gridW * k).dp, (gridH * k).dp)) {
            Canvas(Modifier.fillMaxSize()) { drawPet(dog, flip, colors) }
            spots.selectedLast(selected).forEach { spot ->
                val (x, y) = spotOf(view, spot.site) ?: return@forEach
                SpotButton((if (flip) gridW - x else x) * k, y * k, spot, spot.site == selected) { onPick(spot.site) }
            }
        }
    }
}

/**
 * Точка места: круг 44 в зоне нажатия 48. Цвет — по метке (рекомендуем — залита акцентом; можно — карточка с рамкой
 * акцента; отдыхает — мягкое внимание с рамкой внимания), выбранная — кольцо 3 + 3: карточка, затем цвет текста.
 */
@Composable
private fun SpotButton(cx: Float, cy: Float, spot: BodySpot, selected: Boolean, onClick: () -> Unit) {
    val p = AnvilTheme.palette
    val c = Ampulse.colors
    val (fill, line, ink) = when (spot.tag) {
        SiteTag.Recommended -> Triple(p.accent, p.accent, p.onAccent)
        SiteTag.Ok -> Triple(p.card, p.accent, p.accentText)
        SiteTag.Resting -> Triple(c.warnSoft, c.warnLine, p.warning)
    }
    Box(
        Modifier
            .offset((cx - 24f).dp, (cy - 24f).dp)
            .size(48.dp)
            .semantics { contentDescription = spot.description }
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .drawBehind {
                    if (selected) {
                        val r = size.minDimension / 2
                        drawCircle(p.text, radius = r + 6.dp.toPx())
                        drawCircle(p.card, radius = r + 3.dp.toPx())
                    }
                }
                .clip(CircleShape)
                .background(fill)
                .border(2.dp, line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(spot.number, Modifier.clearAndSetSemantics {}, color = ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Силуэт человека: голова, шея, туловище, руки, ноги — заливка цвета фона и рамка 1,5; сзади — линия позвоночника. */
private fun DrawScope.drawPerson(back: Boolean, fill: Color, line: Color) {
    val k = size.width / 200f
    val stroke = Stroke(width = 1.5.dp.toPx())
    fun block(x: Float, y: Float, w: Float, h: Float, r: Float) {
        val corner = CornerRadius(r * k)
        drawRoundRect(fill, Offset(x * k, y * k), Size(w * k, h * k), corner)
        drawRoundRect(line, Offset(x * k, y * k), Size(w * k, h * k), corner, style = stroke)
    }
    block(52f, 200f, 46f, 118f, 20f)
    block(102f, 200f, 46f, 118f, 20f)
    block(22f, 80f, 24f, 150f, 12f)
    block(154f, 80f, 24f, 150f, 12f)
    block(90f, 50f, 20f, 24f, 0f)
    block(52f, 68f, 96f, 150f, 30f)
    drawCircle(fill, 24f * k, Offset(100f * k, 34f * k))
    drawCircle(line, 24f * k, Offset(100f * k, 34f * k), style = stroke)
    if (back) drawLine(line, Offset(100f * k, 76f * k), Offset(100f * k, 200f * k), strokeWidth = 1.5.dp.toPx())
}

/** Силуэт кошки или собаки сбоку по макету (сетка 320×220, морда вправо); [flip] — левый бок, рисунок зеркалится. */
private fun DrawScope.drawPet(dog: Boolean, flip: Boolean, c: PetColors) {
    val k = size.width / 320f
    withTransform({
        scale(k, k, pivot = Offset.Zero)
        if (flip) scale(-1f, 1f, pivot = Offset(160f, 0f))
    }) {
        fun oval(cx: Float, cy: Float, rx: Float, ry: Float, color: Color) =
            drawOval(color, Offset(cx - rx, cy - ry), Size(rx * 2, ry * 2))
        fun block(x: Float, y: Float, w: Float, h: Float, r: Float, color: Color) =
            drawRoundRect(color, Offset(x, y), Size(w, h), CornerRadius(r))
        fun polygon(color: Color, vararg xy: Float) {
            val path = Path().apply {
                moveTo(xy[0], xy[1])
                for (i in 2 until xy.size step 2) lineTo(xy[i], xy[i + 1])
                close()
            }
            drawPath(path, color)
        }
        if (dog) {
            val tail = Path().apply {
                moveTo(62f, 94f)
                cubicTo(44f, 86f, 36f, 66f, 42f, 46f)
            }
            drawPath(tail, c.body, style = Stroke(width = 13f, cap = StrokeCap.Round))
            oval(94f, 112f, 34f, 38f, c.body)
            block(74f, 126f, 18f, 80f, 8f, c.body)
            block(98f, 128f, 18f, 78f, 8f, c.far)
            oval(152f, 108f, 92f, 38f, c.body)
            block(228f, 124f, 18f, 82f, 8f, c.far)
            block(206f, 126f, 18f, 80f, 8f, c.body)
            oval(222f, 102f, 32f, 37f, c.body)
            rotate(35f, pivot = Offset(246f, 76f)) { oval(246f, 76f, 20f, 30f, c.body) }
            oval(266f, 52f, 26f, 22f, c.body)
            block(274f, 48f, 38f, 22f, 11f, c.body)
            drawCircle(c.nose, 5f, Offset(309f, 54f))
            val ear = Path().apply {
                moveTo(252f, 36f)
                cubicTo(238f, 40f, 234f, 64f, 242f, 80f)
                cubicTo(252f, 78f, 260f, 60f, 262f, 40f)
                close()
            }
            drawPath(ear, c.ear)
        } else {
            val tail = Path().apply {
                moveTo(70f, 100f)
                cubicTo(42f, 94f, 28f, 66f, 36f, 34f)
            }
            drawPath(tail, c.body, style = Stroke(width = 12f, cap = StrokeCap.Round))
            oval(92f, 118f, 30f, 34f, c.body)
            block(76f, 130f, 14f, 74f, 7f, c.body)
            block(96f, 132f, 14f, 72f, 7f, c.far)
            oval(150f, 112f, 88f, 36f, c.body)
            block(224f, 126f, 14f, 78f, 7f, c.far)
            block(204f, 128f, 14f, 76f, 7f, c.body)
            oval(216f, 106f, 30f, 33f, c.body)
            rotate(35f, pivot = Offset(240f, 86f)) { oval(240f, 86f, 16f, 27f, c.body) }
            polygon(c.body, 244f, 54f, 248f, 20f, 267f, 44f)
            polygon(c.body, 263f, 42f, 283f, 18f, 287f, 54f)
            drawCircle(c.body, 26f, Offset(262f, 64f))
            oval(284f, 74f, 12f, 10f, c.body)
        }
    }
}
