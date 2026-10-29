package io.github.agitangst.ampulse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.agitangst.ampulse.reminders.RemindersSection
import io.github.agitangst.ampulse.ui.RoutineSection
import io.github.agitangst.anvil.compose.AboutCard
import io.github.agitangst.anvil.compose.AnvilSettings
import io.github.agitangst.anvil.compose.AnvilTheme
import io.github.agitangst.anvil.compose.AppearanceSection
import io.github.agitangst.anvil.compose.SectionTitle

/** Адрес исходного кода для карточки «О программе». */
private const val CODE_URL = "https://github.com/AgitAngst/Ampulse"

/**
 * «Настройки» — вложенный экран: строка «назад» со стрелкой и словом «Сегодня», заголовок 30 sp, затем «Напоминания»,
 * «Оформление», «Обновления» (только сборка `github`) и «О программе». Нижней навигации нет, поэтому отступ жестовой панели — здесь.
 */
@Composable
fun SettingsScreen(settings: AnvilSettings, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val about = Store.about()
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Сдвиг влево на поля строки: стрелка и слово встают на общее поле 20 dp, а цель нажатия остаётся широкой.
        BackRow(stringResource(R.string.tab_today), onBack, Modifier.offset(x = (-8).dp))
        Text(
            stringResource(R.string.settings_title),
            Modifier.semantics { heading() },
            color = AnvilTheme.palette.text,
            style = screenTitleStyle(30),
        )
        RemindersSection()
        RoutineSection()
        AppearanceSection(settings)
        Store.UpdatesSettings(settings)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(stringResource(R.string.settings_about), Modifier.padding(horizontal = 4.dp))
            AboutCard(
                name = stringResource(R.string.app_name),
                version = BuildConfig.VERSION_NAME,
                mark = { AppMark() },
                build = BuildConfig.BUILD_NUMBER.takeIf { it > 0 },
                status = about.status,
                statusTone = about.tone,
                action = about.action,
                error = about.error,
                codeUrl = CODE_URL,
            )
        }
    }
}

/** Строка «назад» вложенного экрана: стрелка и слово, высота не меньше 48 dp; для TalkBack — кнопка «Назад, …». */
@Composable
private fun BackRow(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            AmpulseIcons.ChevronLeft,
            contentDescription = stringResource(R.string.settings_back),
            modifier = Modifier.size(22.dp),
            tint = p.accentText,
        )
        Text(
            label,
            color = p.accentText,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
        )
    }
}

/**
 * Знак Ampulse (временный, до значка по ICONS.md): скруглённый квадрат акцента с ампулой цвета «на акценте».
 * Для TalkBack — украшение, имя читается рядом.
 */
@Composable
private fun AppMark() {
    val p = AnvilTheme.palette
    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(p.accent)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Icon(AmpulseIcons.Ampoule, contentDescription = null, modifier = Modifier.size(28.dp), tint = p.onAccent)
    }
}
