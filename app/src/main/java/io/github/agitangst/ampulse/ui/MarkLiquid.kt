package io.github.agitangst.ampulse.ui

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp

/** Цвета знака: от темы приложения не зависят (знак — одна картинка, как значок запуска). */
private val TileColor = Color(0xFF2A2A2E)
private val GlassColor = Color(0xFFE8E8EA)
private val LiquidColor = Color(0xFF4FBFB0)

/** Контур ампулы в координатах мастера 1024 (openspec/changes/ampulse-mark-motion/reference/ampulse-mark.svg). */
private const val AMPOULE_PATH =
    "M464,240 Q464,190 512,190 Q560,190 560,240 L560,340 C560,410 702,400 702,520 L702,640 " +
        "A190,190 0 0,1 322,640 L322,520 C322,400 464,410 464,340 Z"

private const val MASTER = 1024f
private const val TILE_RADIUS = 229f // 22,37 % стороны, как у значка запуска

private val AmpouleShape: Path by lazy { PathParser().parsePathString(AMPOULE_PATH).toPath() }

/** Ломаная поверхности жидкости: плоско на уровне [level], пик вверх и провал вниз силой [amp]; ниже — до дна мастера. */
private fun liquidPath(level: Float, amp: Float): Path = Path().apply {
    moveTo(322f, level)
    lineTo(400f, level)
    lineTo(474f, level - 150f * amp)
    lineTo(552f, level + 130f * amp)
    lineTo(612f, level)
    lineTo(702f, level)
    lineTo(702f, 900f)
    lineTo(322f, 900f)
    close()
}

/**
 * Знак Ampulse одним кадром: тёмная плитка, светлая ампула, бирюзовая жидкость с пиком пульса на поверхности.
 * Для TalkBack — украшение, имя программы читается рядом.
 */
@Composable
fun MarkLiquid(size: Dp, frame: MarkMotion.Frame = MarkMotion.REST, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size).clearAndSetSemantics {}) {
        scale(this.size.minDimension / MASTER, pivot = Offset.Zero) {
            drawRoundRect(TileColor, cornerRadius = CornerRadius(TILE_RADIUS))
            drawPath(AmpouleShape, GlassColor)
            clipPath(AmpouleShape) { drawPath(liquidPath(frame.level, frame.amp), LiquidColor) }
        }
    }
}

/**
 * Знак с движением. [scene] — что играть; `null` — покой. Неповторяемая сцена по концу оставляет знак в покое и
 * один раз зовёт [onDone]. Если в системе отключены анимации, знак сразу в покое и [onDone] зовётся тут же.
 * В покое кадры не запрашиваются.
 */
@Composable
fun AnimatedMark(
    size: Dp,
    scene: MarkMotion.Scene?,
    modifier: Modifier = Modifier,
    onDone: () -> Unit = {},
) {
    val context = LocalContext.current
    val reduced = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    var frame by remember { mutableStateOf(MarkMotion.REST) }
    LaunchedEffect(scene, reduced) {
        if (scene == null || reduced) {
            frame = MarkMotion.REST
            if (scene != null && !scene.loops) onDone()
            return@LaunchedEffect
        }
        val start = withFrameNanos { it }
        while (true) {
            val t = (withFrameNanos { it } - start) / 1_000_000L
            if (!scene.loops && t >= scene.durationMs) break
            frame = MarkMotion.frame(scene, t)
        }
        frame = MarkMotion.REST
        onDone()
    }
    MarkLiquid(size, frame, modifier)
}
