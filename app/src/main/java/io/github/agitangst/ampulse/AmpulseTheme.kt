package io.github.agitangst.ampulse

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import io.github.agitangst.anvil.compose.Accents
import io.github.agitangst.anvil.compose.AnvilPalette
import io.github.agitangst.anvil.compose.AnvilTheme
import io.github.agitangst.anvil.compose.ThemeMode

/**
 * Палитра Ampulse по ролям набора — цвета макета (docs/ui-guideline.md, §3): тёплые нейтральные и бирюзовый
 * акцент. Опасного красного нет: успех — акцент, внимание и опасность — цвет внимания (плюс значок и слово).
 */
fun ampulsePalette(dark: Boolean): AnvilPalette =
    if (dark) {
        AnvilPalette.dark(Accents.AMPULSE).copy(
            bg = Color(0xFF111715), surface = Color(0xFF1A2220), card = Color(0xFF1A2220),
            raised = Color(0xFF252F2C), hover = Color(0xFF2A3431), border = Color(0xFF262F2C),
            borderStrong = Color(0xFF3A4542), field = Color(0xFF1A2220), popup = Color(0xFF252F2C),
            text = Color(0xFFECEAE4), weak = Color(0xFFB7C0BD), faint = Color(0xFF97A29F),
            success = Color(0xFF4FBFB0), warning = Color(0xFFF3A76E), danger = Color(0xFFF3A76E),
        )
    } else {
        AnvilPalette.light(Accents.AMPULSE).copy(
            bg = Color(0xFFF4F2EC), surface = Color(0xFFFFFFFF), card = Color(0xFFFFFFFF),
            raised = Color(0xFFEFEBE3), hover = Color(0xFFE9E5DC), border = Color(0xFFECE8DF),
            borderStrong = Color(0xFFD6D1C6), field = Color(0xFFFFFFFF), popup = Color(0xFFEFEBE3),
            text = Color(0xFF16211F), weak = Color(0xFF4A5654), faint = Color(0xFF5E6967),
            success = Color(0xFF0B6B63), warning = Color(0xFF8F4207), danger = Color(0xFF8F4207),
        )
    }

/** Цвета Ampulse сверх ролей набора — мягкие подложки и линии из макета. */
@Immutable
data class AmpulseColors(
    /** Мягкий акцент: «принято», выбранное на светлом. */
    val accentSoft: Color,
    /** Мягкое внимание: просрочено, мало запаса. */
    val warnSoft: Color,
    /** Рамка внимания. */
    val warnLine: Color,
    /** Приглушённый акцент: впереди по плану. */
    val accentMuted: Color,
    /** Дорожка прогресса, дни перерыва. */
    val track: Color,
) {
    companion object {
        fun of(dark: Boolean) = if (dark) {
            AmpulseColors(Color(0xFF173530), Color(0xFF3A2717), Color(0xFFD98641), Color(0xFF2E6A62), Color(0xFF252F2C))
        } else {
            AmpulseColors(Color(0xFFDDEEEA), Color(0xFFFBEBDD), Color(0xFFB4540A), Color(0xFFBFDCD6), Color(0xFFF1EEE7))
        }
    }
}

val LocalAmpulseColors = staticCompositionLocalOf { AmpulseColors.of(false) }

object Ampulse {
    val colors: AmpulseColors
        @Composable @ReadOnlyComposable
        get() = LocalAmpulseColors.current
}

/**
 * Шрифты: в макете Literata (заголовки, крупные числа) и Onest (текст). Пока файлов шрифтов нет — системные
 * засечки и системный без засечек; файлы появятся с разрешения владельца.
 */
private fun ampulseTypography(): Typography {
    val base = Typography()
    val serif = FontFamily.Serif
    return base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = serif),
        displayMedium = base.displayMedium.copy(fontFamily = serif),
        displaySmall = base.displaySmall.copy(fontFamily = serif),
        headlineLarge = base.headlineLarge.copy(fontFamily = serif),
        headlineMedium = base.headlineMedium.copy(fontFamily = serif),
        headlineSmall = base.headlineSmall.copy(fontFamily = serif),
    )
}

/** Тема Ampulse: набор Anvil с палитрой и шрифтами Ampulse и цветами сверх ролей. */
@Composable
fun AmpulseTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    AnvilTheme(accent = Accents.AMPULSE, mode = mode, palette = ::ampulsePalette, typography = ampulseTypography()) {
        CompositionLocalProvider(LocalAmpulseColors provides AmpulseColors.of(AnvilTheme.palette.dark), content = content)
    }
}
