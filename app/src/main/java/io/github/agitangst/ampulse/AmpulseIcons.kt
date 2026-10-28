package io.github.agitangst.ampulse

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Значки Ampulse: линии 1,8 на сетке 24, пути — набор Lucide, как в макете (docs/ui-guideline.md, §5).
 * Цвет задаёт вызывающий (`tint`), в самих значках он чёрный.
 */
object AmpulseIcons {
    /** Вкладка «Сегодня». */
    val Home: ImageVector by lazy {
        icon("ampulse.home", "M3 10.5 12 3l9 7.5", "M5 9.5V21h14V9.5", "M10 21v-6h4v6")
    }

    /** Вкладка «История». */
    val Calendar: ImageVector by lazy {
        icon(
            "ampulse.calendar",
            "M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2Z",
            "M16 2v4", "M8 2v4", "M3 10h18",
        )
    }

    /** Вкладка «Аптечка». */
    val BriefcaseMedical: ImageVector by lazy {
        icon(
            "ampulse.briefcase_medical",
            "M12 11v4", "M14 13h-4",
            "M16 6V4a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v2",
            "M18 6v14", "M6 6v14",
            "M4 6h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2Z",
        )
    }

    /** Вкладка «Близкие». */
    val Users: ImageVector by lazy {
        icon(
            "ampulse.users",
            "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2",
            "M9 3a4 4 0 1 0 0 8a4 4 0 1 0 0-8Z",
            "M22 21v-2a4 4 0 0 0-3-3.87",
            "M16 3.13a4 4 0 0 1 0 7.75",
        )
    }

    /** Шестерёнка: вход в «Настройки». */
    val Settings: ImageVector by lazy {
        icon(
            "ampulse.settings",
            "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73" +
                "l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73" +
                "l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44" +
                "a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39" +
                "a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73" +
                "l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2Z",
            "M15 12a3 3 0 1 1-6 0a3 3 0 0 1 6 0Z",
        )
    }

    /** Стрелка «назад»; в письме справа налево зеркалится. */
    val ChevronLeft: ImageVector by lazy { icon("ampulse.chevron_left", "m15 18-6-6 6-6", autoMirror = true) }

    /** Таблетка: пустое состояние «Сегодня». */
    val Pill: ImageVector by lazy {
        icon("ampulse.pill", "m10.5 20.5 10-10a4.95 4.95 0 1 0-7-7l-10 10a4.95 4.95 0 1 0 7 7Z", "m8.5 8.5 7 7")
    }

    /** Ампула: знак Ampulse (временный, пока нет значка по ICONS.md) — горлышко, колба и уровень жидкости. */
    val Ampoule: ImageVector by lazy {
        icon(
            "ampulse.ampoule",
            "M10.5 3h3v5c3 1.5 4.5 4 4.5 7a6 6 0 0 1-12 0c0-3 1.5-5.5 4.5-7V3Z",
            "M6.2 15.5h11.6",
        )
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
