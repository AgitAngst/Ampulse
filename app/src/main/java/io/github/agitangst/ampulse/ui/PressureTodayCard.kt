package io.github.agitangst.ampulse.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import io.github.agitangst.ampulse.Ampulse
import io.github.agitangst.ampulse.AppGraph
import io.github.agitangst.ampulse.Nav
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.Route
import io.github.agitangst.ampulse.data.PressurePrefs
import io.github.agitangst.ampulse.domain.classify
import io.github.agitangst.anvil.compose.AnvilCard
import io.github.agitangst.anvil.compose.AnvilTheme
import io.github.agitangst.anvil.compose.SectionTitle

/**
 * «Давление на главной» — показывать карточку на «Сегодня», даже если у профиля ещё нет замеров. Ключ `bp_on_today`
 * в `SharedPreferences("ampulse")`, по умолчанию выключено; с первым замером карточка появляется сама.
 */
internal object PressureOnToday {
    const val KEY = "bp_on_today"

    fun enabled(context: Context): Boolean = context.getSharedPreferences("ampulse", Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun set(context: Context, on: Boolean) {
        context.getSharedPreferences("ampulse", Context.MODE_PRIVATE).edit { putBoolean(KEY, on) }
    }
}

/**
 * Карточка «Давление» на «Сегодня» (макет, экран 1): последний замер профиля с оценкой и когда он был — или «Замеров
 * ещё нет»; справа «Измерить». Касание карточки открывает ввод. Есть хоть один замер или включено «Давление на
 * главной» — показывается, иначе места не занимает.
 */
@Composable
fun PressureTodayCard(nav: Nav) {
    val context = LocalContext.current
    val profileId = CurrentProfile.id
    val last by remember(profileId) { AppGraph.repository.lastReading(profileId) }.collectAsState(initial = null)
    // Экран «Сегодня» собирается заново после «Настроек», поэтому переключатель читаем один раз.
    val always = remember { PressureOnToday.enabled(context) }
    val reading = last
    if (reading == null && !always) return
    val p = AnvilTheme.palette
    val c = Ampulse.colors
    val now = rememberTodayNow()
    val time = rememberTodayTime()
    val target = remember(profileId) { PressurePrefs.target(context, profileId) }

    val state = reading?.let { classify(it.sys, it.dia, target) }
    val stateLine = if (reading != null && state != null) "${reading.sys}/${reading.dia} · ${pressureStateTitle(state)}" else null
    val whenLine = reading?.let { readingWhen(it.at, now.toLocalDate(), time) }
    val stateColor = state?.let { toneColors(pressureTone(it)).fg } ?: p.weak
    val title = stringResource(R.string.bp_title)
    val measure = stringResource(R.string.bp_today_measure)
    val summary = listOfNotNull(stateLine, whenLine).joinToString(", ").ifEmpty { stringResource(R.string.bp_today_none) }
    val description = stringResource(R.string.bp_today_card, "$summary. $measure")

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.bp_today_section), Modifier.padding(horizontal = 4.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(p.card)
                .clickable(role = Role.Button) { nav.open(Route.PressureEntry) }
                .semantics { contentDescription = description }
                .padding(start = 14.dp, top = 10.dp, end = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Содержимое строки читается одной фразой выше — дети скрыты от TalkBack.
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.accentSoft).clearAndSetSemantics {},
                contentAlignment = Alignment.Center,
            ) {
                Icon(PressureIcons.HeartPulse, contentDescription = null, modifier = Modifier.size(22.dp), tint = p.accent)
            }
            Column(Modifier.weight(1f).clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    stateLine ?: stringResource(R.string.bp_today_none),
                    color = stateColor,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (whenLine != null) Text(whenLine, color = p.faint, fontSize = 13.sp, lineHeight = 17.sp)
            }
            Box(
                Modifier
                    .heightIn(min = 48.dp)
                    .clip(CircleShape)
                    .background(p.raised)
                    .padding(horizontal = 16.dp)
                    .clearAndSetSemantics {},
                contentAlignment = Alignment.Center,
            ) {
                Text(measure, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Строка «Настроек»: «Давление на главной» — выключатель на всю строку, под заголовком пояснение. */
@Composable
fun PressureTodaySetting() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(PressureOnToday.enabled(context)) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.bp_title), Modifier.padding(horizontal = 4.dp))
        AnvilCard {
            MedSwitchRow(
                title = stringResource(R.string.bp_setting_title),
                hint = stringResource(R.string.bp_setting_hint),
                checked = on,
                onChange = {
                    on = it
                    PressureOnToday.set(context, it)
                },
            )
        }
    }
}
