package io.github.agitangst.ampulse.reminders

import android.Manifest
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import io.github.agitangst.ampulse.AppGraph
import io.github.agitangst.ampulse.R
import io.github.agitangst.anvil.compose.AnvilCard
import io.github.agitangst.anvil.compose.AnvilTheme
import io.github.agitangst.anvil.compose.SectionTitle
import io.github.agitangst.anvil.compose.Tone

/**
 * Раздел «Напоминания» в настройках: что разрешено — уведомления, точные напоминания, напоминание на весь экран для
 * важных — и кнопка к нужной настройке системы. Состояние перечитывается, когда человек возвращается в программу
 * (вернулся из настроек системы). Разрешение на уведомления спрашивается здесь, а не при запуске
 * (anvil/docs/ANDROID.md, §10): отказ ничего не ломает — строка объясняет последствие и предлагает «Разрешить».
 */
@Composable
fun RemindersSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = LocalActivity.current

    // Счётчик возвращений на экран: меняется — состояние читается заново.
    var resumes by remember { mutableIntStateOf(0) }
    DisposableEffect(activity) {
        val lifecycle = (activity as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) resumes++ }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    val access = remember(resumes) { ReminderAccess.read(context) }

    // Точные будильники включили или выключили в системе — пересчитать, чтобы будильники стали точными (или нет).
    var lastExact by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(access.exact) {
        val before = lastExact
        if (before != null && before != access.exact) AppGraph.reminders.reschedule()
        lastExact = access.exact
    }

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumes++ }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.rem_section), Modifier.padding(horizontal = 4.dp))
        AnvilCard {
            AccessRow(
                title = stringResource(R.string.rem_notifications),
                status = stringResource(if (access.notifications) R.string.rem_notifications_on else R.string.rem_off_plural),
                ok = access.notifications,
                note = if (access.notifications) null else stringResource(R.string.rem_notifications_note),
                action = if (access.notifications) null else stringResource(R.string.rem_allow),
                onAction = {
                    if (activity != null && ReminderAccess.canAskNotifications(activity)) {
                        ReminderAccess.markNotificationsAsked(context)
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        // Запрещено насовсем или выключено в системе — диалога не будет, только настройки.
                        ReminderAccess.openNotificationSettings(context)
                    }
                },
            )
            HorizontalDivider(color = AnvilTheme.palette.border)
            AccessRow(
                title = stringResource(R.string.rem_exact),
                status = stringResource(if (access.exact) R.string.rem_exact_on else R.string.rem_off_plural),
                ok = access.exact,
                note = if (access.exact) null else stringResource(R.string.rem_exact_note),
                action = if (access.exact) null else stringResource(R.string.rem_allow),
                onAction = { ReminderAccess.openExactAlarmSettings(context) },
            )
            HorizontalDivider(color = AnvilTheme.palette.border)
            AccessRow(
                title = stringResource(R.string.rem_fullscreen),
                status = stringResource(if (access.fullScreen) R.string.rem_fullscreen_on else R.string.rem_off_neuter),
                ok = access.fullScreen,
                note = if (access.fullScreen) null else stringResource(R.string.rem_fullscreen_note),
                action = if (access.fullScreen) null else stringResource(R.string.rem_allow),
                onAction = { ReminderAccess.openFullScreenSettings(context) },
            )
        }
    }
}

/**
 * Строка состояния: слева название, под ним «Разрешены» (цвет успеха) или «Выключены» (цвет внимания) и пояснение
 * последствия, справа — кнопка к настройке, если нужна. Цвет не единственный носитель смысла: статус — словом.
 */
@Composable
private fun AccessRow(
    title: String,
    status: String,
    ok: Boolean,
    note: String?,
    action: String?,
    onAction: () -> Unit,
) {
    val p = AnvilTheme.palette
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = p.text)
            Text(status, style = MaterialTheme.typography.bodyMedium, color = p.tone(if (ok) Tone.Success else Tone.Warning))
            if (note != null) {
                Text(note, style = MaterialTheme.typography.bodySmall, color = p.weak)
            }
        }
        if (action != null) {
            Spacer(Modifier.width(12.dp))
            Button(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) }
        }
    }
}
