package io.github.agitangst.ampulse.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.windowInsetsPadding
import com.google.mlkit.vision.barcode.common.Barcode
import io.github.agitangst.ampulse.Nav
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.Route
import io.github.agitangst.ampulse.relay.PairingQr
import io.github.agitangst.ampulse.relay.RelayState
import io.github.agitangst.ampulse.relay.RelaySync
import io.github.agitangst.ampulse.screenTitleStyle
import io.github.agitangst.anvil.compose.AnvilCard
import io.github.agitangst.anvil.compose.AnvilTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Уведомления близким через свой ретранслятор (openspec: relay-client). Строка на вкладке «Близкие» и экран:
 * до знакомства — адрес и отпечаток сервера (владелец) или сканирование QR (близкий); после — состояние,
 * QR знакомства у владельца, последние уведомления, «Проверить сейчас» и «Забыть». Только сборка github:
 * в остальных строка не показывается, экран закрывается сам.
 */
@Composable
internal fun RelayRow(onClick: () -> Unit) {
    if (!RelaySync.available) return
    val p = AnvilTheme.palette
    val view by RelaySync.view.collectAsState()
    val paired = view.state.paired
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(p.card)
            .heightIn(min = 68.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.relay_row_title),
                color = p.text,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                if (paired) stringResource(
                    if (view.state.role == RelayState.ROLE_OWNER) R.string.relay_state_owner else R.string.relay_state_listener
                ) else stringResource(R.string.relay_row_off),
                color = if (paired) p.weak else p.faint,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Icon(MedIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp), tint = p.faint)
    }
}

@Composable
fun RelayScreen(nav: Nav) {
    if (!RelaySync.available) {
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    val p = AnvilTheme.palette
    val view by RelaySync.view.collectAsState()
    val state = view.state

    var address by rememberSaveable { mutableStateOf("") }
    var fingerprint by rememberSaveable { mutableStateOf("") }
    var pairing by remember { mutableStateOf(false) }
    var pairFailed by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MedBackRow(stringResource(R.string.tab_family), nav::back, Modifier.offset(x = (-8).dp))
            Text(
                stringResource(R.string.relay_title),
                Modifier.semantics { heading() },
                color = p.text,
                style = screenTitleStyle(30),
            )
            if (!state.paired) {
                AnvilCard { Text(stringResource(R.string.relay_intro), color = p.text, style = MaterialTheme.typography.bodyMedium) }
                Text(
                    stringResource(R.string.relay_owner_heading),
                    Modifier.semantics { heading() },
                    color = p.text,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(stringResource(R.string.relay_owner_intro), color = p.faint, style = MaterialTheme.typography.bodyMedium)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FamilyField(
                        value = address,
                        onChange = { address = it; pairFailed = false },
                        label = stringResource(R.string.relay_address),
                        hint = "https://relay.example.org:8740",
                    )
                    FamilyField(
                        value = fingerprint,
                        onChange = { fingerprint = it; pairFailed = false },
                        label = stringResource(R.string.relay_fingerprint),
                        hint = "SHA-256",
                    )
                }
                if (pairFailed) MedNotice(stringResource(R.string.relay_pair_failed))
                MedMainButton(
                    stringResource(if (pairing) R.string.relay_pairing else R.string.relay_pair),
                    enabled = !pairing && address.isNotBlank() && fingerprint.isNotBlank(),
                    onClick = {
                        pairing = true
                        pairFailed = false
                        RelaySync.pairOwner(address, fingerprint) { ok ->
                            pairing = false
                            pairFailed = !ok
                        }
                    },
                )
                Text(
                    stringResource(R.string.relay_listener_heading),
                    Modifier.semantics { heading() },
                    color = p.text,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(stringResource(R.string.relay_listener_intro), color = p.faint, style = MaterialTheme.typography.bodyMedium)
                MedMainButton(stringResource(R.string.relay_scan), onClick = { nav.open(Route.RelayScan) })
            } else {
                AnvilCard {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(
                                if (state.role == RelayState.ROLE_OWNER) R.string.relay_state_owner else R.string.relay_state_listener
                            ),
                            color = p.text,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        )
                        if (state.lastCheckAt > 0) {
                            Text(
                                stringResource(
                                    R.string.relay_last_check,
                                    Instant.ofEpochSecond(state.lastCheckAt).atZone(ZoneId.systemDefault())
                                        .format(DateTimeFormatter.ofPattern("d MMM, HH:mm")),
                                ),
                                color = p.weak,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        state.lastError?.let {
                            Text(stringResource(R.string.relay_error, it), color = p.weak, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (state.role == RelayState.ROLE_OWNER) RelayQrCard(state)
                Text(
                    stringResource(R.string.relay_notices),
                    Modifier.semantics { heading() },
                    color = p.text,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (state.inbox.isEmpty()) {
                    Text(stringResource(R.string.relay_no_notices), color = p.faint, style = MaterialTheme.typography.bodyMedium)
                } else {
                    AnvilCard {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.inbox.forEach { notice ->
                                Text(
                                    stringResource(
                                        R.string.relay_notify_text,
                                        notice.who,
                                        notice.med,
                                        Instant.ofEpochSecond(notice.ts).atZone(ZoneId.systemDefault())
                                            .format(DateTimeFormatter.ofPattern("d MMM, HH:mm")),
                                    ),
                                    color = p.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 1.dp).background(p.border))
        if (state.paired) {
            Column(Modifier.fillMaxWidth().background(p.bg).padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 20.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    MedMainButton(
                        stringResource(R.string.relay_check_now),
                        enabled = !view.busy,
                        onClick = { RelaySync.tick() },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { confirmForget = true }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.relay_forget), color = p.weak)
                    }
                }
            }
        }
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text(stringResource(R.string.relay_forget_title)) },
            text = { Text(stringResource(R.string.relay_forget_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    RelaySync.forget()
                }) { Text(stringResource(R.string.relay_forget)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmForget = false }) { Text(stringResource(R.string.watch_cancel)) }
            },
        )
    }
}

/** QR знакомства у владельца: тот же вид QR, что расписание близких (макет, экран 13). */
@Composable
private fun RelayQrCard(state: RelayState) {
    val p = AnvilTheme.palette
    val qr by produceState<QrResult?>(null, state) {
        val text = PairingQr.encode(state) ?: return@produceState
        value = withContext(Dispatchers.Default) { QrModules.build(text) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.relay_qr_title), color = p.text, style = MaterialTheme.typography.titleMedium)
        when (val code = qr) {
            is QrResult.Ok -> QrView(code, stringResource(R.string.relay_qr_hint), Modifier.fillMaxWidth().heightIn(max = 320.dp))
            is QrResult.TooBig -> MedNotice(stringResource(R.string.family_share_too_big))
            null -> {}
        }
        Text(stringResource(R.string.relay_qr_hint), color = p.faint, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Сканер QR знакомства: камера читает только QR, код применяется сразу; чужой код — короткое пояснение. */
@Composable
fun RelayScanScreen(nav: Nav) {
    if (!RelaySync.available) {
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    val context = LocalContext.current
    val p = AnvilTheme.palette

    var granted by remember { mutableStateOf(familyCameraGranted(context)) }
    var requested by rememberSaveable { mutableStateOf(false) }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) {
        if (!granted && !requested) {
            requested = true
            askCamera.launch(Manifest.permission.CAMERA)
        }
    }

    var applied by remember { mutableStateOf(false) }
    var notOurs by remember { mutableStateOf(false) }
    val onRaw: (String) -> Unit = { raw ->
        if (PairingQr.decode(raw) == null) {
            notOurs = true
        } else if (!applied) {
            notOurs = false
            RelaySync.pairListener(raw) { ok -> applied = ok }
        }
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MedBackRow(stringResource(R.string.tab_family), nav::back, Modifier.offset(x = (-8).dp))
            Text(
                stringResource(R.string.relay_scan),
                Modifier.semantics { heading() },
                color = p.text,
                style = screenTitleStyle(30),
            )
            if (applied) {
                AnvilCard { Text(stringResource(R.string.relay_state_listener), color = p.text, style = MaterialTheme.typography.bodyMedium) }
                MedMainButton(stringResource(R.string.watch_to_list), onClick = nav::back)
            } else {
                Text(stringResource(R.string.family_scan_perm_hint), color = p.faint, style = MaterialTheme.typography.bodyMedium)
                if (granted) {
                    ScanCamera(
                        torch = false,
                        scanning = true,
                        onRaw = onRaw,
                        onFlash = {},
                        onError = {},
                        modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp),
                        formats = Barcode.FORMAT_QR_CODE,
                    )
                }
                if (notOurs) MedNotice(stringResource(R.string.relay_scan_failed))
            }
        }
    }
}
