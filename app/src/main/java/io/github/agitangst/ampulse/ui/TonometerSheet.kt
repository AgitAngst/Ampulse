package io.github.agitangst.ampulse.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import io.github.agitangst.ampulse.Ampulse
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.ble.FoundDevice
import io.github.agitangst.ampulse.ble.LinkError
import io.github.agitangst.ampulse.ble.LinkState
import io.github.agitangst.ampulse.ble.TonometerLink
import io.github.agitangst.ampulse.data.PressurePrefs
import io.github.agitangst.ampulse.domain.DeviceReading
import io.github.agitangst.ampulse.screenTitleStyle
import io.github.agitangst.anvil.compose.AnvilTheme
import io.github.agitangst.anvil.compose.SectionTitle

private const val TAG = "AmpulseTonometer"

/** Ошибки, которые человек снимает вне программы (разрешение, Bluetooth, геолокация): по возвращении поиск идёт заново. */
private val RECHECK = setOf(LinkError.NoPermission, LinkError.Disabled, LinkError.LocationOff)

/**
 * «Принять с тонометра по Bluetooth» (макет, экран 26: кнопка «Тонометр» рядом с «Фото» и «Голос»). Нажатие открывает
 * лист: разрешения и Bluetooth спрашиваются прямо в нём, потом поиск (запомненный тонометр — сразу), «Нажмите кнопку
 * на тонометре», и полученный замер уходит в [onReading]. Ширину задаёт вызывающий: в макете три кнопки в ряд.
 */
@Composable
fun TonometerButton(onReading: (DeviceReading) -> Unit, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    val p = AnvilTheme.palette
    val description = stringResource(R.string.tono_button_desc)
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(p.card)
            .border(1.5.dp, p.borderStrong, shape)
            .clickable(role = Role.Button) { open = true }
            .clearAndSetSemantics { contentDescription = description }
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TonometerIcons.Bluetooth, contentDescription = null, modifier = Modifier.size(18.dp), tint = p.text)
        Text(
            stringResource(R.string.tono_button),
            Modifier.weight(1f, fill = false),
            color = p.text,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        )
    }
    if (open) TonometerSheet(onReading = onReading, onDismiss = { open = false })
}

/**
 * Лист тонометра. Всё состояние — в [TonometerLink]: лист показывает его и запускает системные запросы, когда связь
 * сообщает, чего не хватает (разрешения, включённого Bluetooth). Каждый запрос автоматически идёт один раз, дальше —
 * кнопкой. Ссылка закрывается вместе с листом.
 */
@Composable
private fun TonometerSheet(onReading: (DeviceReading) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val link = remember { TonometerLink(context) }
    DisposableEffect(link) { onDispose { link.close() } }
    val state by link.state.collectAsState()

    var remembered by remember { mutableStateOf(PressurePrefs.device(context)) }
    var askedPermission by rememberSaveable { mutableStateOf(false) }
    var canAsk by rememberSaveable { mutableStateOf(true) }
    var askedEnable by rememberSaveable { mutableStateOf(false) }
    // Пока открыт системный запрос, возвращение в программу не перезапускает поиск второй раз.
    var requesting by remember { mutableStateOf(false) }

    val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        requesting = false
        // Диалог покажется ещё раз, если система его не заблокировала (первый отказ); после второго — только настройки.
        canAsk = !TonometerLink.hasPermissions(context) &&
            TonometerLink.permissions().any { activity?.shouldShowRequestPermissionRationale(it) == true }
        link.start(remembered)
    }
    val askEnable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
        requesting = false
        link.start(remembered)
    }

    LaunchedEffect(link) { link.start(remembered) }

    // Связь сказала, чего не хватает, — спрашиваем сразу, один раз; отказ остаётся на экране с кнопкой.
    LaunchedEffect(state) {
        val s = state
        if (s is LinkState.Error && !requesting) {
            when (s.kind) {
                LinkError.NoPermission -> if (!askedPermission) {
                    askedPermission = true
                    requesting = true
                    try {
                        askPermissions.launch(TonometerLink.permissions())
                    } catch (e: ActivityNotFoundException) {
                        Log.w(TAG, "Permission request did not open", e)
                        requesting = false
                    }
                }
                LinkError.Disabled -> if (!askedEnable) {
                    askedEnable = true
                    requesting = true
                    try {
                        askEnable.launch(enableBluetoothIntent())
                    } catch (e: ActivityNotFoundException) {
                        Log.w(TAG, "Bluetooth enable request did not open", e)
                        requesting = false
                    }
                }
                else -> Unit
            }
        }
    }

    // Вернулись из настроек системы (или из шторки) — проверить заново то, что мешало.
    DisposableEffect(activity) {
        val lifecycle = (activity as? LifecycleOwner)?.lifecycle
        var paused = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) paused = true
            if (event == Lifecycle.Event.ON_RESUME && paused) {
                paused = false
                val s = link.state.value
                if (!requesting && s is LinkState.Error && s.kind in RECHECK) link.start(remembered)
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    val retry: () -> Unit = { link.start(remembered) }
    val forget: () -> Unit = {
        PressurePrefs.setDevice(context, null)
        remembered = null
        link.start(null)
    }
    val allow: () -> Unit = {
        if (canAsk) {
            requesting = true
            try {
                askPermissions.launch(TonometerLink.permissions())
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "Permission request did not open", e)
                requesting = false
            }
        } else {
            openActivity(context, appSettingsIntent(context))
        }
    }
    val turnOn: () -> Unit = {
        requesting = true
        try {
            askEnable.launch(enableBluetoothIntent())
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Bluetooth enable request did not open", e)
            requesting = false
        }
    }

    val p = AnvilTheme.palette
    TodaySheet(onDismiss) { close ->
        // Замер пришёл: запомнить тонометр, отдать числа и закрыть лист.
        LaunchedEffect(state) {
            val s = state
            if (s is LinkState.Got) {
                if (s.device.address.isNotBlank()) PressurePrefs.setDevice(context, s.device.address)
                onReading(s.reading)
                close()
            }
        }

        Text(
            stringResource(R.string.tono_title),
            Modifier.semantics { heading() },
            color = p.text,
            style = screenTitleStyle(24),
        )
        when (val s = state) {
            LinkState.Idle -> StatusRow(stringResource(R.string.tono_searching))
            is LinkState.Scanning -> ScanningBody(s, remembered, onPick = { link.connect(it.address) }, onAgain = retry)
            is LinkState.Connecting -> {
                val name = s.device.name
                StatusRow(
                    if (name == null) {
                        stringResource(R.string.tono_connecting_unnamed)
                    } else {
                        stringResource(R.string.tono_connecting, name)
                    },
                    hint = stringResource(R.string.tono_pairing_hint),
                )
            }
            is LinkState.Waiting -> if (s.received == 0) {
                StatusRow(stringResource(R.string.tono_waiting_title), hint = stringResource(R.string.tono_waiting_hint))
            } else {
                StatusRow(stringResource(R.string.tono_receiving))
            }
            is LinkState.Got -> StatusRow(stringResource(R.string.tono_receiving))
            is LinkState.Error -> ErrorBody(
                kind = s.kind,
                canAsk = canAsk,
                hasRemembered = remembered != null,
                onRetry = retry,
                onAllow = allow,
                onTurnOn = turnOn,
                onLocationSettings = { openActivity(context, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                onForget = forget,
                onClose = close,
            )
        }
        // Пока идёт работа — «Отмена» и (если есть запомненный) «Забыть тонометр»; у ошибки свои кнопки.
        if (state !is LinkState.Error) {
            if (remembered != null && state is LinkState.Scanning) QuietButton(stringResource(R.string.tono_forget), forget)
            QuietButton(stringResource(R.string.tono_cancel), close)
        }
    }
}

// --- Состояния листа ---

/** Строка состояния: вращение (идёт процесс) и текст; под ней — что делать. Для TalkBack читается как изменение. */
@Composable
private fun StatusRow(text: String, hint: String? = null) {
    val p = AnvilTheme.palette
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp).clearAndSetSemantics { },
                color = p.accent,
                trackColor = Ampulse.colors.track,
                strokeWidth = 3.dp,
            )
            Text(
                text,
                Modifier.weight(1f),
                color = p.text,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
        }
        if (hint != null) Text(hint, color = p.weak, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Поиск: идёт (вращение и подсказка) или закончился (найденные ждут выбора); найденные — строками, запомненный первым. */
@Composable
private fun ScanningBody(
    scan: LinkState.Scanning,
    remembered: String?,
    onPick: (FoundDevice) -> Unit,
    onAgain: () -> Unit,
) {
    val p = AnvilTheme.palette
    val devices = scan.devices.sortedByDescending { it.address == remembered }
    if (scan.active) {
        StatusRow(
            stringResource(if (remembered != null) R.string.tono_searching_mine else R.string.tono_searching),
            hint = stringResource(R.string.tono_search_hint),
        )
    } else {
        val missing = remembered != null && devices.none { it.address == remembered }
        Text(
            stringResource(if (missing) R.string.tono_search_done_mine else R.string.tono_search_done),
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = p.weak,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    if (devices.isNotEmpty()) {
        SectionTitle(stringResource(R.string.tono_found))
        devices.forEach { DeviceRow(it, mine = it.address == remembered, onClick = { onPick(it) }) }
    }
    if (!scan.active) MedOutlineButton(stringResource(R.string.tono_search_again), onAgain)
}

/** Строка найденного тонометра: плитка значка, имя, адрес (моноширинным), метка «Ваш тонометр» и уровень сигнала. */
@Composable
private fun DeviceRow(device: FoundDevice, mine: Boolean, onClick: () -> Unit) {
    val p = AnvilTheme.palette
    val name = device.name ?: stringResource(R.string.tono_no_name)
    val signal = stringResource(signalText(device.rssi))
    val mineLabel = stringResource(R.string.tono_remembered)
    val spoken = listOfNotNull(name, mineLabel.takeIf { mine }, signal).joinToString(", ")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = spoken }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Ampulse.colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(TonometerIcons.Bluetooth, contentDescription = null, modifier = Modifier.size(22.dp), tint = p.accentText)
        }
        Column(Modifier.weight(1f)) {
            Text(
                name,
                color = p.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                device.address,
                color = p.weak,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            )
            if (mine) {
                Text(
                    mineLabel,
                    color = p.accentText,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
        Text(
            signal,
            Modifier.widthIn(max = 96.dp),
            color = p.weak,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun signalText(rssi: Int): Int = when {
    rssi >= -65 -> R.string.tono_signal_strong
    rssi >= -80 -> R.string.tono_signal_medium
    else -> R.string.tono_signal_weak
}

/**
 * Ошибка: что случилось и что делать (общий гайдлайн, §1.4). Цвет внимания плюс значок и слова; красного нет. Одна
 * главная кнопка — действие, которое снимает ошибку; «Забыть тонометр» — если он запомнен и дело может быть в нём.
 */
@Composable
private fun ErrorBody(
    kind: LinkError,
    canAsk: Boolean,
    hasRemembered: Boolean,
    onRetry: () -> Unit,
    onAllow: () -> Unit,
    onTurnOn: () -> Unit,
    onLocationSettings: () -> Unit,
    onForget: () -> Unit,
    onClose: () -> Unit,
) {
    val p = AnvilTheme.palette
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Ampulse.colors.warnSoft)
            .padding(16.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(MedIcons.Warning, contentDescription = null, modifier = Modifier.size(22.dp), tint = p.warning)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(errorTitle(kind)),
                color = p.text,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(stringResource(errorHint(kind)), color = p.weak, style = MaterialTheme.typography.bodyMedium)
            if (kind == LinkError.NoPermission && !canAsk) {
                Text(stringResource(R.string.tono_err_perm_blocked), color = p.warning, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    when (kind) {
        LinkError.NoBluetooth -> Unit
        LinkError.Disabled -> MedMainButton(stringResource(R.string.tono_turn_on), onTurnOn)
        LinkError.NoPermission ->
            MedMainButton(stringResource(if (canAsk) R.string.tono_allow else R.string.tono_open_settings), onAllow)
        LinkError.LocationOff -> MedMainButton(stringResource(R.string.tono_open_settings), onLocationSettings)
        LinkError.NotTonometer -> MedMainButton(stringResource(R.string.tono_search_again), onRetry)
        LinkError.ScanFailed, LinkError.NotFound, LinkError.Lost, LinkError.Timeout ->
            MedMainButton(stringResource(R.string.tono_retry), onRetry)
    }
    val aboutDevice = kind in setOf(LinkError.ScanFailed, LinkError.NotFound, LinkError.NotTonometer, LinkError.Lost, LinkError.Timeout)
    if (hasRemembered && aboutDevice) QuietButton(stringResource(R.string.tono_forget), onForget)
    QuietButton(stringResource(R.string.tono_close), onClose)
}

private fun errorTitle(kind: LinkError): Int = when (kind) {
    LinkError.NoBluetooth -> R.string.tono_err_nobt_title
    LinkError.Disabled -> R.string.tono_err_off_title
    LinkError.NoPermission -> R.string.tono_err_perm_title
    LinkError.LocationOff -> R.string.tono_err_loc_title
    LinkError.ScanFailed -> R.string.tono_err_scan_title
    LinkError.NotFound -> R.string.tono_err_notfound_title
    LinkError.NotTonometer -> R.string.tono_err_unsupported_title
    LinkError.Lost -> R.string.tono_err_lost_title
    LinkError.Timeout -> R.string.tono_err_timeout_title
}

private fun errorHint(kind: LinkError): Int = when (kind) {
    LinkError.NoBluetooth -> R.string.tono_err_nobt_hint
    LinkError.Disabled -> R.string.tono_err_off_hint
    LinkError.NoPermission ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) R.string.tono_err_perm_hint else R.string.tono_err_perm_hint_legacy
    LinkError.LocationOff -> R.string.tono_err_loc_hint
    LinkError.ScanFailed -> R.string.tono_err_scan_hint
    LinkError.NotFound -> R.string.tono_err_notfound_hint
    LinkError.NotTonometer -> R.string.tono_err_unsupported_hint
    LinkError.Lost -> R.string.tono_err_lost_hint
    LinkError.Timeout -> R.string.tono_err_timeout_hint
}

/** Тихая кнопка без рамки и заливки на всю ширину, цель не меньше 48 dp. */
@Composable
private fun QuietButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = AnvilTheme.palette.accentText,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

// --- Системные окна ---

/**
 * Запрос включить Bluetooth. Право BLUETOOTH_CONNECT к этому моменту уже выдано: связь сообщает «выключен» только
 * после проверки разрешений, а запрос идёт в ответ на это сообщение.
 */
@SuppressLint("MissingPermission")
private fun enableBluetoothIntent() = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)

private fun appSettingsIntent(context: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

private fun openActivity(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "Settings screen not found", e)
    }
}

/** Значок Bluetooth: линии 1,8 на сетке 24, путь — набор Lucide, как в макете (docs/ui-guideline.md, §5). */
private object TonometerIcons {
    val Bluetooth: ImageVector by lazy {
        ImageVector.Builder(
            name = "tonometer.bluetooth",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("m7 7 10 10-5 5V2l5 5L7 17"),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }
}
