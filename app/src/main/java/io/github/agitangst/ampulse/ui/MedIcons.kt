package io.github.agitangst.ampulse.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import io.github.agitangst.ampulse.AmpulseIcons
import io.github.agitangst.ampulse.domain.MedForm

/**
 * Значки экранов «Аптечка» и правки лекарства: линии 1,8 на сетке 24, пути — набор Lucide, как в макете
 * (docs/ui-guideline.md, §5). Цвет задаёт вызывающий (`tint`).
 */
internal object MedIcons {
    val Plus: ImageVector by lazy { icon("med.plus", "M5 12h14", "M12 5v14") }

    val Minus: ImageVector by lazy { icon("med.minus", "M5 12h14") }

    val Close: ImageVector by lazy { icon("med.close", "M18 6 6 18", "m6 6 12 12") }

    val ChevronRight: ImageVector by lazy { icon("med.chevron_right", "m9 18 6-6-6-6", autoMirror = true) }

    val Clock: ImageVector by lazy {
        icon("med.clock", "M22 12a10 10 0 1 1-20 0a10 10 0 0 1 20 0Z", "M12 6v6l4 2")
    }

    val Trash: ImageVector by lazy {
        icon(
            "med.trash",
            "M3 6h18", "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6", "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2",
            "M10 11v6", "M14 11v6",
        )
    }

    val Warning: ImageVector by lazy {
        icon("med.warning", "m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3", "M12 9v4", "M12 17h.01")
    }

    private val Tablet: ImageVector by lazy { icon("med.tablet", "M20.5 12a8.5 8.5 0 1 1-17 0a8.5 8.5 0 0 1 17 0Z", "M6 18 18 6") }

    private val Syringe: ImageVector by lazy {
        icon(
            "med.syringe",
            "m18 2 4 4", "m17 7 3-3", "M19 9 8.7 19.3c-1 1-2.5 1-3.4 0l-.6-.6c-1-1-1-2.5 0-3.4L15 5",
            "m9 11 4 4", "m5 19-3 3", "m14 4 6 6",
        )
    }

    private val Drop: ImageVector by lazy {
        icon("med.drop", "M12 22a7 7 0 0 0 7-7c0-2-1-3.9-3-5.5s-3.5-4-4-6.5c-.5 2.5-2 4.9-4 6.5C6 11.1 5 13 5 15a7 7 0 0 0 7 7z")
    }

    private val Flask: ImageVector by lazy {
        icon(
            "med.flask",
            "M10 2v7.527a2 2 0 0 1-.211.896L4.72 20.55a1 1 0 0 0 .9 1.45h12.76a1 1 0 0 0 .9-1.45l-5.069-10.127A2 2 0 0 1 14 9.527V2",
            "M8.5 2h7", "M7 16h10",
        )
    }

    private val Box: ImageVector by lazy {
        icon("med.box", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z", "M12 8v8", "M8 12h8")
    }

    /** Значок формы лекарства: плитка в списке и чипы формы. */
    fun of(form: MedForm): ImageVector = when (form) {
        MedForm.Tablet -> Tablet
        MedForm.Capsule -> AmpulseIcons.Pill
        MedForm.Injection -> Syringe
        MedForm.Drops -> Drop
        MedForm.Liquid -> Flask
        MedForm.Other -> Box
    }

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
