package io.github.agitangst.ampulse

import io.github.agitangst.ampulse.ui.FamilyTab
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.agitangst.anvil.compose.AnvilTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** «Близкие» (этап 0.8) — `ui/FamilyScreens.kt`. */
@Composable
fun FamilyScreen(nav: Nav, modifier: Modifier = Modifier) = FamilyTab(nav, modifier)

/** Корень вкладки: поля 20, сверху 24, промежуток между блоками 16 (docs/ui-guideline.md, §2). */
@Composable
internal fun PageLayout(
    modifier: Modifier,
    header: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxSize().padding(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        header()
        content()
    }
}

/** Шапка вкладки: над заголовком — [date] (если есть), справа — [action] (если есть), выровнены по низу. */
@Composable
internal fun TabHeader(
    title: String,
    modifier: Modifier = Modifier,
    date: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val p = AnvilTheme.palette
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (date != null) {
                Text(date, color = p.faint, style = MaterialTheme.typography.bodyMedium)
            }
            Text(title, Modifier.semantics { heading() }, color = p.text, style = screenTitleStyle(34))
        }
        action?.invoke()
    }
}

/** Круглая обычная кнопка 48 dp со значком шестерёнки; для TalkBack — «Настройки». */
@Composable
internal fun SettingsButton(onClick: () -> Unit) {
    val p = AnvilTheme.palette
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(p.raised)
            .border(1.5.dp, p.borderStrong, CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            AmpulseIcons.Settings,
            contentDescription = stringResource(R.string.settings_title),
            modifier = Modifier.size(22.dp),
            tint = p.text,
        )
    }
}

/**
 * Пустое состояние (общий гайдлайн, §6): значок в круге мягкого акцента, заголовок про этот список и что сделать.
 * Стоит посередине свободного места; при крупном шрифте прокручивается.
 */
@Composable
internal fun EmptyState(icon: ImageVector, title: String, hint: String, modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    BoxWithConstraints(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = 12.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(Ampulse.colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp), tint = p.accentText)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                title,
                Modifier.widthIn(max = 320.dp),
                color = p.text,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                textAlign = TextAlign.Center,
            )
            Text(
                hint,
                Modifier.widthIn(max = 320.dp),
                color = p.weak,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Заголовок экрана: Literata-подобный `headlineLarge` с размером [size] sp — 34 у корней вкладок, 30 у вложенных. */
@Composable
internal fun screenTitleStyle(size: Int): TextStyle =
    MaterialTheme.typography.headlineLarge.copy(
        fontSize = size.sp,
        lineHeight = (size * 1.15f).sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = if (size >= 34) (-0.4).sp else 0.sp,
    )

/** Сегодняшняя дата словами по языку программы: «Суббота, 26 сентября» / «Saturday, September 26». */
@Composable
internal fun todayText(): String {
    val locale = LocalConfiguration.current.locales[0]
    val pattern = stringResource(R.string.date_pattern)
    return LocalDate.now().format(DateTimeFormatter.ofPattern(pattern, locale))
        .replaceFirstChar { it.titlecase(locale) }
}
