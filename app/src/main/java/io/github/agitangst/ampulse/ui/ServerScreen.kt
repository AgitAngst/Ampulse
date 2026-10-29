package io.github.agitangst.ampulse.ui

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.agitangst.ampulse.Nav
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.Route
import io.github.agitangst.ampulse.screenTitleStyle
import io.github.agitangst.ampulse.sync.ServerSync
import io.github.agitangst.ampulse.sync.SyncFailure
import io.github.agitangst.anvil.compose.AnvilCard
import io.github.agitangst.anvil.compose.AnvilTheme
import kotlinx.coroutines.launch

/**
 * «Сервер» (Настройки → «Сервер», только сборка github; openspec: server-sync): вход на свой сервер Ampulse и
 * состояние односторонней выгрузки. Холста-макета нет — экран собран по образцам «Близкий» (форма с кнопкой внизу) и
 * «Настроек» (docs/ui-guideline.md, §6, «Сервер»).
 */
@Composable
fun ServerScreen(nav: Nav) {
    val status by ServerSync.status.collectAsState()
    // В сборках магазинов экрана нет (строки в настройках тоже): сюда можно попасть только по устаревшему маршруту.
    if (!ServerSync.available) {
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    val p = AnvilTheme.palette
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var address by rememberSaveable { mutableStateOf(status.server.substringAfter("://")) }
    var login by rememberSaveable { mutableStateOf(status.login) }
    // Пароль — не в rememberSaveable: в сохранённое состояние экрана (Bundle) он попадать не должен.
    var password by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<SyncFailure?>(null) }
    var working by remember { mutableStateOf(false) }
    var confirmErase by rememberSaveable { mutableStateOf(false) }
    var outMessage by remember { mutableStateOf<Int?>(null) }

    fun enter(create: Boolean) {
        working = true
        failure = null
        scope.launch {
            val result = ServerSync.signIn(address, login, password, create)
            working = false
            password = ""
            failure = result
        }
    }

    fun leave(erase: Boolean) {
        working = true
        outMessage = null
        scope.launch {
            val result = ServerSync.signOut(erase)
            working = false
            outMessage = when {
                result == null -> null
                result.kind == SyncFailure.Kind.Unauthorized -> R.string.server_e_expired
                else -> R.string.server_e_erase
            }
        }
    }

    val ready = address.isNotBlank() && login.isNotBlank() && password.isNotEmpty()

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Сдвиг влево на поля строки: стрелка и слово встают на общее поле 20 dp, а цель нажатия остаётся широкой.
            MedBackRow(stringResource(R.string.settings_title), nav::back, Modifier.offset(x = (-8).dp))
            Text(
                stringResource(R.string.server_title),
                Modifier.semantics { heading() },
                color = p.text,
                style = screenTitleStyle(30),
            )
            if (!status.signedIn) {
                if (status.sessionEnded) MedNotice(stringResource(R.string.server_ended))
                AnvilCard {
                    Text(stringResource(R.string.server_intro), color = p.text, style = MaterialTheme.typography.bodyMedium)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(R.string.server_sent_title),
                            color = p.text,
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        )
                        Text(stringResource(R.string.server_sent_text), color = p.weak, style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.server_not_sent_text), color = p.weak, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FamilyField(
                        value = address,
                        onChange = { address = it.trim() },
                        label = stringResource(R.string.server_address),
                        hint = stringResource(R.string.server_address_hint),
                        keyboardType = KeyboardType.Uri,
                    )
                    FamilyField(
                        value = login,
                        onChange = { login = it.trim() },
                        label = stringResource(R.string.server_login),
                    )
                    FamilyField(
                        value = password,
                        onChange = { password = it },
                        label = stringResource(R.string.server_password),
                        imeAction = ImeAction.Done,
                        password = true,
                    )
                }
                failure?.let { MedNotice(failureText(context, it)) }
            } else {
                AnvilCard {
                    Text(
                        stringResource(R.string.server_connected),
                        color = p.accentText,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.server_row_server), color = p.weak, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            status.server.substringAfter("://"),
                            Modifier.weight(1f),
                            color = p.text,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.End,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.server_row_login), color = p.weak, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            status.login,
                            Modifier.weight(1f),
                            color = p.text,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.End,
                        )
                    }
                    Text(
                        if (status.lastAt > 0) stringResource(R.string.server_last, formatMoment(context, status.lastAt))
                        else stringResource(R.string.server_last_never),
                        color = p.text,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (status.stored > 0) {
                        Text(
                            pluralStringResource(R.plurals.server_stored, status.stored, status.stored),
                            color = p.weak,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                WatchPeopleRow { nav.open(Route.Watch()) }
                status.problem?.let { MedNotice(problemText(context, status)) }
                if (status.rejected > 0) {
                    MedNotice(stringResource(R.string.server_rejected, status.rejected, status.rejectedCodes.joinToString(", ").ifEmpty { "?" }))
                }
                outMessage?.let { MedNotice(stringResource(it)) }
                MedOutlineButton(stringResource(R.string.server_sign_out), onClick = { leave(false) }, enabled = !working)
                MedOutlineButton(
                    stringResource(R.string.server_erase),
                    onClick = { confirmErase = true },
                    icon = MedIcons.Trash,
                    danger = true,
                    enabled = !working,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
        Column(
            Modifier.fillMaxWidth().background(p.bg).padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!status.signedIn) {
                if (!ready && !working) {
                    Text(
                        stringResource(R.string.server_need_fields),
                        Modifier.fillMaxWidth(),
                        color = p.weak,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
                MedMainButton(
                    stringResource(if (working) R.string.server_working else R.string.server_sign_in),
                    enabled = ready && !working,
                    onClick = { enter(create = false) },
                )
                MedOutlineButton(stringResource(R.string.server_create), onClick = { enter(create = true) }, enabled = ready && !working)
            } else {
                MedMainButton(
                    stringResource(if (status.busy || working) R.string.server_sending else R.string.server_send_now),
                    enabled = !status.busy && !working,
                    onClick = ServerSync::syncNow,
                )
            }
        }
    }

    if (confirmErase) {
        AlertDialog(
            onDismissRequest = { confirmErase = false },
            containerColor = p.card,
            titleContentColor = p.text,
            textContentColor = p.weak,
            title = { Text(stringResource(R.string.server_erase_title)) },
            text = { Text(stringResource(R.string.server_erase_text)) },
            confirmButton = {
                TextButton(onClick = { confirmErase = false; leave(true) }) {
                    Text(stringResource(R.string.server_erase_confirm), color = p.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmErase = false }) { Text(stringResource(R.string.server_cancel), color = p.accentText) }
            },
        )
    }
}

/** «5 ноября, 14:07» — по языку и 12/24 ч телефона. */
private fun formatMoment(context: Context, millis: Long): String =
    DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)

private fun problemText(context: Context, s: ServerSync.Status): String = when (s.problem) {
    ServerSync.Problem.NoConnection -> context.getString(R.string.server_p_noconn)
    ServerSync.Problem.UnknownHost -> context.getString(R.string.server_p_host)
    ServerSync.Problem.Tls -> context.getString(R.string.server_p_tls)
    ServerSync.Problem.RateLimited -> context.getString(R.string.server_p_rate, maxOf(1, (s.retryAfterSecs + 59) / 60))
    ServerSync.Problem.Server -> context.getString(R.string.server_p_server, s.problemCode ?: "?")
    else -> context.getString(R.string.server_p_other)
}

/** Отказ при входе или создании аккаунта — словами, без кодов там, где код понятен человеку. */
private fun failureText(context: Context, f: SyncFailure): String = when (f.kind) {
    SyncFailure.Kind.BadAddress -> context.getString(R.string.server_e_address)
    SyncFailure.Kind.Insecure -> context.getString(R.string.server_e_insecure)
    SyncFailure.Kind.NoConnection -> context.getString(
        when (f.code) {
            "host" -> R.string.server_p_host
            "tls" -> R.string.server_p_tls
            else -> R.string.server_p_noconn
        },
    )
    SyncFailure.Kind.Unauthorized -> context.getString(R.string.server_e_credentials)
    SyncFailure.Kind.RateLimited -> context.getString(R.string.server_p_rate, maxOf(1, ((f.retryAfterSecs ?: 0) + 59) / 60))
    SyncFailure.Kind.Rejected -> when (f.code) {
        "bad_login" -> context.getString(R.string.server_e_bad_login)
        "bad_password" -> context.getString(R.string.server_e_bad_password)
        "conflict" -> context.getString(R.string.server_e_taken)
        "registration_closed" -> context.getString(R.string.server_e_closed)
        else -> context.getString(R.string.server_e_rejected, f.code ?: "?")
    }
    SyncFailure.Kind.Server -> context.getString(R.string.server_p_server, f.code ?: "?")
}
