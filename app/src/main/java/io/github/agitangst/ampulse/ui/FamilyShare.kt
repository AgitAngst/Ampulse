package io.github.agitangst.ampulse.ui

import android.Manifest
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.common.Barcode
import io.github.agitangst.ampulse.AmpulseColors
import io.github.agitangst.ampulse.AppGraph
import io.github.agitangst.ampulse.EmptyState
import io.github.agitangst.ampulse.LocalAmpulseColors
import io.github.agitangst.ampulse.Nav
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.ampulsePalette
import io.github.agitangst.ampulse.data.ScheduleShare
import io.github.agitangst.ampulse.domain.Medicine
import io.github.agitangst.ampulse.domain.Plan
import io.github.agitangst.ampulse.screenTitleStyle
import io.github.agitangst.anvil.compose.AnvilCard
import io.github.agitangst.anvil.compose.AnvilTheme
import io.github.agitangst.anvil.compose.LocalAnvilPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.floor

private const val TAG = "AmpulseFamily"

/** Поля тишины вокруг QR — четыре модуля, как требует стандарт. */
private const val QUIET_MODULES = 4

/** QR всегда тёмный на белом, и в тёмной теме тоже — иначе камера не считает (docs/ui-guideline.md, §3). */
private val QrInk = Color(0xFF16211F)
private val QrPaper = Color.White

/** Цвета окна камеры не зависят от темы (docs/ui-guideline.md, §3 — как у сканера упаковки). */
private object CameraColors {
    val Bg = Color(0xFF0E1412)
    val Reticle = Color(0xFF9FE0D6)
    val Round = Color.White.copy(alpha = 0.14f)
    val Ink = Color.White
}

/**
 * «Моё расписание» (макет, экран Access): крупный QR с лекарствами и схемами текущего профиля — близкий наводит
 * камеру и получает копию. Тёмные модули на белом в любой теме, поля тишины, яркость экрана на максимум, пока открыт
 * экран. У «Я» можно вписать, как подписать расписание у близкого (повторная передача с тем же именем обновляет его
 * копию, а не заводит вторую); у остальных профилей — имя профиля. Нет лекарств — вместо кода объяснение.
 */
@Composable
fun ShareQrScreen(nav: Nav) {
    val repo = AppGraph.repository
    val context = LocalContext.current
    val activity = LocalActivity.current
    val p = AnvilTheme.palette
    val profileId = CurrentProfile.id
    val profiles by remember { repo.profiles }.collectAsState(initial = emptyList())
    val profile = profiles.firstOrNull { it.id == profileId }
    var label by rememberSaveable { mutableStateOf(FamilyPrefs.shareLabel(context)) }
    val owner = shareOwner(profile, label, stringResource(R.string.family_me))

    val items by produceState<List<Pair<Medicine, Plan>>?>(null, profileId) { value = repo.shareItems(profileId) }
    val qr by produceState<QrResult?>(null, items, owner) {
        val list = items
        value = if (list.isNullOrEmpty()) null else withContext(Dispatchers.Default) { QrModules.build(ScheduleShare.encode(owner, list)) }
    }

    // Яркость на максимум, пока код на экране: с тусклого экрана камера читает хуже.
    DisposableEffect(activity) {
        val window = activity?.window
        val before = window?.attributes?.screenBrightness
        if (window != null) window.attributes = window.attributes.also { it.screenBrightness = 1f }
        onDispose {
            if (window != null && before != null) window.attributes = window.attributes.also { it.screenBrightness = before }
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
                stringResource(R.string.family_share_title),
                Modifier.semantics { heading() },
                color = p.text,
                style = screenTitleStyle(30),
            )
            val list = items
            when {
                list == null -> Box(Modifier.fillMaxWidth().heightIn(min = 240.dp))
                list.isEmpty() -> AnvilCard {
                    Text(
                        stringResource(R.string.family_share_empty_title),
                        color = p.text,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Text(stringResource(R.string.family_share_empty_hint), color = p.weak, style = MaterialTheme.typography.bodyMedium)
                }
                else -> {
                    Text(stringResource(R.string.family_share_desc), color = p.weak, style = MaterialTheme.typography.bodyMedium)
                    when (val code = qr) {
                        is QrResult.Ok -> {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                QrView(code, stringResource(R.string.family_share_a11y), Modifier.widthIn(max = 320.dp).fillMaxWidth())
                            }
                            Column(
                                Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    stringResource(R.string.family_share_for, owner),
                                    color = p.text,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    textAlign = TextAlign.Center,
                                )
                                Text(
                                    pluralStringResource(R.plurals.family_meds_count, list.size, list.size),
                                    color = p.weak,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                        QrResult.TooBig -> MedNotice(stringResource(R.string.family_share_too_big))
                        null -> Box(Modifier.fillMaxWidth().heightIn(min = 240.dp))
                    }
                    // Подпись у «Я»: у остальных профилей имя уже есть.
                    if (profile == null || profile.id == 0L) {
                        FamilyField(
                            value = label,
                            onChange = {
                                label = it.take(40)
                                FamilyPrefs.setShareLabel(context, label)
                            },
                            label = stringResource(R.string.family_share_label),
                            hint = stringResource(R.string.family_share_label_hint),
                            capitalization = KeyboardCapitalization.Words,
                            imeAction = ImeAction.Done,
                        )
                    }
                    MedNotice(stringResource(R.string.family_share_warn))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
        Column(Modifier.fillMaxWidth().background(p.bg).padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 20.dp)) {
            MedMainButton(stringResource(R.string.family_done), onClick = nav::back)
        }
    }
}

/**
 * Рисует QR: модули — целыми пикселями (без швов и размытия), по сторонам белые поля тишины в [QUIET_MODULES]
 * модулей. Подряд идущие тёмные модули строки рисуются одним прямоугольником.
 */
@Composable
internal fun QrView(code: QrResult.Ok, description: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Canvas(
        modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(QrPaper)
            .border(1.5.dp, AnvilTheme.palette.borderStrong, shape)
            .semantics {
                contentDescription = description
                role = Role.Image
            },
    ) {
        val n = code.size
        val cell = floor(size.minDimension / (n + 2 * QUIET_MODULES))
        if (cell < 1f) return@Canvas
        val side = cell * (n + 2 * QUIET_MODULES)
        val left = floor((size.width - side) / 2f) + cell * QUIET_MODULES
        val top = floor((size.height - side) / 2f) + cell * QUIET_MODULES
        for (y in 0 until n) {
            val row = code.modules[y]
            var x = 0
            while (x < n) {
                if (!row[x]) {
                    x++
                    continue
                }
                var end = x
                while (end + 1 < n && row[end + 1]) end++
                drawRect(QrInk, Offset(left + x * cell, top + y * cell), Size((end - x + 1) * cell, cell))
                x = end + 1
            }
        }
    }
}

/**
 * Сканер QR близкого (макет, экран Care): окно камеры читает только QR, код разбирается без сети. Расписание —
 * в карточке: «Мама: 5 лекарств», список, новая это копия или обновление имеющейся; «Сохранить копию» заводит профиль-копию
 * (повторный скан обновляет её), «Сканировать ещё» ищет другой код. Чужой QR — «Это не код Ampulse». Камера спрашивается при входе;
 * отказ — объяснение и «Разрешить» / «Открыть настройки».
 */
@Composable
fun CareScanScreen(nav: Nav) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val repo = AppGraph.repository
    val scope = rememberCoroutineScope()
    val p = AnvilTheme.palette
    val darkPalette = remember { ampulsePalette(true) }
    val darkColors = remember { AmpulseColors.of(true) }
    val profiles by remember { repo.profiles }.collectAsState(initial = emptyList())

    // Разрешение камеры спрашивается при входе (anvil/docs/ANDROID.md, §10); отказ — объяснение и «Разрешить».
    var granted by remember { mutableStateOf(familyCameraGranted(context)) }
    var requested by rememberSaveable { mutableStateOf(false) }
    var denied by rememberSaveable { mutableStateOf(false) }
    var canAsk by rememberSaveable { mutableStateOf(true) }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
        canAsk = !ok && activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == true
    }
    LaunchedEffect(Unit) {
        if (!granted && !requested) {
            requested = true
            askCamera.launch(Manifest.permission.CAMERA)
        }
    }
    // Вернулись из настроек системы с включённой камерой — открыть её.
    DisposableEffect(activity) {
        val lifecycle = (activity as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && familyCameraGranted(context)) {
                granted = true
                denied = false
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    var torch by rememberSaveable { mutableStateOf(false) }
    var hasFlash by remember { mutableStateOf(false) }
    var cameraFailed by remember { mutableStateOf(false) }
    var shared by remember { mutableStateOf<ScheduleShare.Shared?>(null) }
    var notOurs by remember { mutableStateOf(false) }
    var notOursTick by remember { mutableIntStateOf(0) }
    var lastNotOurs by remember { mutableLongStateOf(0L) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    // «Это не код Ampulse» гаснет через 3 с без новых чужих кодов.
    LaunchedEffect(notOursTick) {
        if (notOursTick > 0) {
            delay(3000)
            notOurs = false
        }
    }
    val onRaw: (String) -> Unit = { raw ->
        val parsed = ScheduleShare.decode(raw)
        if (parsed != null) {
            shared = parsed
            notOurs = false
        } else {
            notOurs = true
            // Один и тот же чужой код читается с каждого кадра — таймер перезапускаем не чаще раза в секунду.
            val now = SystemClock.elapsedRealtime()
            if (now - lastNotOurs > 1000) {
                lastNotOurs = now
                notOursTick++
            }
        }
    }

    val current = shared
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
                stringResource(R.string.family_scan_title),
                Modifier.semantics { heading() },
                color = p.text,
                style = screenTitleStyle(30),
            )

            // Окно камеры тёмное в любой теме; внутри — палитра тёмной темы.
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(CameraColors.Bg),
            ) {
                CompositionLocalProvider(
                    LocalAnvilPalette provides darkPalette,
                    LocalAmpulseColors provides darkColors,
                    LocalContentColor provides darkPalette.text,
                ) {
                    when {
                        granted && !cameraFailed -> {
                            ScanCamera(
                                torch = torch,
                                scanning = current == null,
                                onRaw = onRaw,
                                onFlash = { hasFlash = it },
                                onError = { cameraFailed = true },
                                modifier = Modifier.fillMaxSize(),
                                formats = Barcode.FORMAT_QR_CODE,
                            )
                            if (current == null) QrReticle(Modifier.align(Alignment.Center))
                            if (hasFlash) {
                                // Выключатель: TalkBack читает «Фонарик, включено/выключено»; включённый залит светлой бирюзой.
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(12.dp)
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(if (torch) CameraColors.Reticle else CameraColors.Round)
                                        .toggleable(value = torch, role = Role.Switch, onValueChange = { torch = it }),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        FamilyIcons.Flashlight,
                                        contentDescription = stringResource(R.string.scan_torch),
                                        modifier = Modifier.size(22.dp),
                                        tint = if (torch) CameraColors.Bg else CameraColors.Ink,
                                    )
                                }
                            }
                        }
                        granted -> EmptyState(
                            FamilyIcons.Scan,
                            stringResource(R.string.scan_cam_failed_title),
                            stringResource(R.string.scan_cam_failed_hint),
                            Modifier.fillMaxSize(),
                        )
                        denied -> EmptyState(
                            FamilyIcons.Scan,
                            stringResource(R.string.scan_perm_title),
                            stringResource(R.string.family_scan_perm_hint) +
                                if (canAsk) "" else "\n" + stringResource(R.string.scan_perm_blocked),
                            Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            if (current == null) {
                if (denied && !granted) {
                    MedOutlineButton(
                        stringResource(if (canAsk) R.string.scan_perm_allow else R.string.scan_perm_settings),
                        onClick = { if (canAsk) askCamera.launch(Manifest.permission.CAMERA) else openFamilyAppSettings(context) },
                    )
                } else {
                    Text(
                        stringResource(R.string.family_scan_hint),
                        Modifier.fillMaxWidth(),
                        color = p.weak,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
                if (notOurs) {
                    Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                        MedNotice(stringResource(R.string.family_scan_not_ours))
                    }
                }
            } else {
                val exists = profiles.any { it.watchOnly && it.name.equals(current.owner, ignoreCase = true) }
                ScanResult(current, exists)
            }
        }
        if (current != null) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
            Column(
                Modifier.fillMaxWidth().background(p.bg).padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (failed) {
                    Text(
                        stringResource(R.string.family_scan_failed),
                        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                        color = p.warning,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
                val exists = profiles.any { it.watchOnly && it.name.equals(current.owner, ignoreCase = true) }
                MedMainButton(
                    stringResource(if (exists) R.string.family_scan_update_btn else R.string.family_scan_save),
                    enabled = current.items.isNotEmpty() && !saving,
                    onClick = {
                        saving = true
                        failed = false
                        scope.launch {
                            try {
                                val profileId = repo.importShared(current)
                                FamilyPrefs.markCopyUpdated(context, profileId)
                                nav.back()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w(TAG, "Could not save the schedule copy", e)
                                saving = false
                                failed = true
                            }
                        }
                    },
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button) {
                            shared = null
                            failed = false
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.family_scan_again),
                        color = p.accentText,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }
        }
    }
}

/** Карточка прочитанного кода: чьё расписание, сколько и какие лекарства, новая это копия или обновление. */
@Composable
private fun ScanResult(shared: ScheduleShare.Shared, exists: Boolean) {
    val p = AnvilTheme.palette
    val region = stringResource(R.string.family_scan_result)
    val count = shared.items.size
    AnvilCard(Modifier.semantics { paneTitle = region }) {
        Row(
            Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(TodayIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp), tint = p.accentText)
            Text(
                stringResource(R.string.family_scan_read),
                color = p.accentText,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            )
        }
        Text(
            stringResource(R.string.family_scan_who, shared.owner, pluralStringResource(R.plurals.family_meds_count, count, count)),
            color = p.text,
            style = screenTitleStyle(24),
        )
        if (count == 0) {
            MedNotice(stringResource(R.string.family_scan_empty))
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                shared.items.take(MAX_LISTED).forEach { (medicine, _) ->
                    Text(
                        listOf(medicine.name, medicine.strength).filter { it.isNotBlank() }.joinToString(" "),
                        color = p.weak,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (count > MAX_LISTED) {
                    Text(
                        stringResource(R.string.family_scan_more, count - MAX_LISTED),
                        color = p.faint,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Text(
                if (exists) stringResource(R.string.family_scan_update, shared.owner) else stringResource(R.string.family_scan_new),
                color = p.weak,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Сколько лекарств перечислять в карточке; остальное — «и ещё N». */
private const val MAX_LISTED = 5

/** Четыре угла рамки наведения 200 dp: линия 4 dp цвета `#9FE0D6`, скругление 12 dp. Код читается и вне рамки. */
@Composable
private fun QrReticle(modifier: Modifier = Modifier) {
    Canvas(modifier.size(200.dp).clearAndSetSemantics { }) {
        val side = size.width
        val arm = 40.dp.toPx()
        val radius = 12.dp.toPx()
        val width = 4.dp.toPx()
        val half = width / 2
        // Левый верхний угол; остальные — повороты на 90 градусов вокруг центра.
        val corner = Path().apply {
            moveTo(half, arm)
            lineTo(half, half + radius)
            arcTo(Rect(half, half, half + 2 * radius, half + 2 * radius), 180f, 90f, false)
            lineTo(arm, half)
        }
        for (i in 0..3) {
            rotate(i * 90f, Offset(side / 2, side / 2)) {
                drawPath(corner, CameraColors.Reticle, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}
