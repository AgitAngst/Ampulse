package io.github.agitangst.ampulse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.agitangst.ampulse.Ampulse
import io.github.agitangst.anvil.compose.AnvilTheme

// Общие части экранов запаса и сроков годности (docs/ui-guideline.md, §6): полоса, плитка, строка-пояснение, ссылка.

/** Значки запаса: линии 1,8 на сетке 24, пути — набор Lucide, как в макете (экран 5). */
internal object StockIcons {
    /** Рамка сканера с квадратиками кода: кнопка в шапке «Аптечки». */
    val Scan: ImageVector by lazy {
        icon(
            "stock.scan",
            "M3 7V5a2 2 0 0 1 2-2h2", "M17 3h2a2 2 0 0 1 2 2v2", "M21 17v2a2 2 0 0 1-2 2h-2", "M7 21H5a2 2 0 0 1-2-2v-2",
            "M8 8h3v3H8z", "M13 13h3v3h-3z", "M13 8h3", "M8 13v3",
        )
    }

    /** Календарь с часами: строка-переход «Сроки годности». */
    val CalendarClock: ImageVector by lazy {
        icon(
            "stock.calendar_clock",
            "M21 7.5V6a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h3.5", "M16 2v4", "M8 2v4", "M3 10h5",
            "M17.5 17.5 16 16.3V14", "M22 16a6 6 0 1 1-12 0a6 6 0 0 1 12 0Z",
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

/** Плитка значка 40×40 со скруглением 12 (docs/ui-guideline.md, §5). */
@Composable
internal fun StockTile(icon: ImageVector, modifier: Modifier = Modifier, warn: Boolean = false, soft: Boolean = false) {
    val p = AnvilTheme.palette
    val fill = when {
        warn -> Ampulse.colors.warnSoft
        soft -> Ampulse.colors.accentSoft
        else -> p.raised
    }
    Box(modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(fill), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = if (warn) p.warning else if (soft) p.accentText else p.weak)
    }
}

/** Полоса запаса или срока: дорожка и заливка, [fraction] от 0 до 1; [warn] — цвет внимания. Для TalkBack — декор. */
@Composable
internal fun StockBar(fraction: Float, warn: Boolean, modifier: Modifier = Modifier, height: Int = 4) {
    val shape = RoundedCornerShape((height / 2).dp)
    Box(modifier.fillMaxWidth().height(height.dp).clip(shape).background(Ampulse.colors.track)) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(height.dp)
                .clip(shape)
                .background(if (warn) Ampulse.colors.warnLine else AnvilTheme.palette.accent),
        )
    }
}

/** Подпись раздела слева и пояснение справа (макет, экран 40). */
@Composable
internal fun StockSectionHeader(title: String, hint: String?, modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    Row(
        modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            title.uppercase(),
            Modifier.weight(1f).semantics { heading() },
            color = p.weak,
            style = MaterialTheme.typography.labelLarge,
        )
        if (hint != null) {
            Text(hint, color = p.weak, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Строка с точкой под вторичной: прогноз «успеете / не успеете» и пометки (docs/ui-guideline.md, §6). */
@Composable
internal fun StockNote(text: String, good: Boolean, modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(if (good) p.accent else Ampulse.colors.warnLine),
        )
        Text(
            text,
            color = if (good) p.accentText else p.warning,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
        )
    }
}

/** Крупное число с единицей под ним — справа в строке запаса и срока (Literata 22 по макету). */
@Composable
internal fun StockBigNumber(number: String, unit: String, warn: Boolean, modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            number,
            color = if (warn) p.warning else p.text,
            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
        )
        Text(unit, color = p.weak, style = MaterialTheme.typography.bodySmall)
    }
}

/** Тихая кнопка-ссылка: без рамки и заливки, 48 dp, текст цвета [color]. */
@Composable
internal fun StockLinkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = AnvilTheme.palette.accentText, description: String? = null) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
    }
}

/** Плашка «сделано» на мягком акценте с галочкой и «Отменить» справа (макет, экраны 5 и 40). */
@Composable
internal fun StockDoneBar(text: String, undo: String, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Ampulse.colors.accentSoft)
            .padding(start = 16.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TodayIcons.Check, contentDescription = null, modifier = Modifier.size(20.dp), tint = p.accentText)
        Text(
            text,
            Modifier.weight(1f).padding(vertical = 10.dp),
            color = p.accentText,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
        )
        StockLinkButton(undo, onUndo)
    }
}
