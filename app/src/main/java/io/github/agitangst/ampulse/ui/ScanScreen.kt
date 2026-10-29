package io.github.agitangst.ampulse.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import io.github.agitangst.ampulse.Ampulse
import io.github.agitangst.ampulse.AmpulseColors
import io.github.agitangst.ampulse.AppGraph
import io.github.agitangst.ampulse.EmptyState
import io.github.agitangst.ampulse.LocalAmpulseColors
import io.github.agitangst.ampulse.Nav
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.Route
import io.github.agitangst.ampulse.ampulsePalette
import io.github.agitangst.ampulse.domain.Medicine
import io.github.agitangst.ampulse.domain.Pack
import io.github.agitangst.ampulse.domain.PackCode
import io.github.agitangst.ampulse.domain.parsePackCode
import io.github.agitangst.ampulse.screenTitleStyle
import io.github.agitangst.anvil.compose.AnvilTheme
import io.github.agitangst.anvil.compose.LocalAnvilPalette
import io.github.agitangst.anvil.compose.Motion
import io.github.agitangst.anvil.compose.motionSpec
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Код последнего скана — экран упаковки забирает его, когда открыт с `fromScan`. */
object PendingScan {
    var code: PackCode? = null
}

private const val TAG = "AmpulseScan"

/** Нет значения (срока или серии в коде не было). */
private const val DASH = "—"

/** Цвета сканера из макета (экран 14): не зависят от темы (docs/ui-guideline.md, §3). */
private object ScanColors {
    val Bg = Color(0xFF0E1412)
    val Title = Color.White
    val Hint = Color(0xFFD5E3E0)
    val Reticle = Color(0xFF9FE0D6)
    val RoundButton = Color.White.copy(alpha = 0.14f)
}

/** Что известно про прочитанный код: лекарство с этим GTIN в аптечке и есть ли уже именно эта упаковка. */
private data class ScanMatch(val medicine: Medicine?, val duplicate: Boolean)

/**
 * Лекарство находится по GTIN у любой его упаковки. Упаковка «уже есть», если у неё тот же GTIN и серийный номер;
 * без номера в коде узнать нельзя — тогда считается новой.
 */
private fun matchCode(code: PackCode, medicines: List<Medicine>, packs: List<Pack>): ScanMatch {
    val same = packs.filter { it.gtin.isNotEmpty() && it.gtin == code.gtin }
    val medicine = same.firstNotNullOfOrNull { p -> medicines.firstOrNull { it.id == p.medicineId } }
    val duplicate = code.serial.isNotBlank() && same.any { it.serial == code.serial }
    return ScanMatch(medicine, duplicate)
}

/**
 * Сканер упаковки (макет, экран 14): тёмный экран с камерой, код DataMatrix «Честного знака» читается на телефоне,
 * без сети. Прочитанное — срок, серия, номер — в карточке результата: лекарство найдено по GTIN, значит «Добавить
 * в аптечку»; не найдено — выбрать лекарство из аптечки или завести новое. Упаковка, которая уже в аптечке, второй
 * раз не добавляется. Код остаётся в [PendingScan], пока человек не вышел из сканера или не нажал «Сканировать ещё»:
 * из него экран упаковки берёт поля, а после правки лекарства сканер показывает ту же карточку.
 */
@Composable
fun ScanScreen(nav: Nav) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val repo = AppGraph.repository
    val texts = rememberMedTexts()
    val darkPalette = remember { ampulsePalette(true) }
    val darkColors = remember { AmpulseColors.of(true) }

    val medicines by remember { repo.medicines }.collectAsState(initial = null)
    val packs by remember { repo.packs }.collectAsState(initial = null)

    // Разрешение камеры спрашивается при входе (anvil/docs/ANDROID.md, §10); отказ — объяснение и «Разрешить».
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    var requested by rememberSaveable { mutableStateOf(false) }
    var denied by rememberSaveable { mutableStateOf(false) }
    var canAsk by rememberSaveable { mutableStateOf(true) }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
        // Диалог ещё покажется, если система его не заблокировала (первый отказ); после второго — только настройки.
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
            if (event == Lifecycle.Event.ON_RESUME && hasCameraPermission(context)) {
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

    // Прочитанный и разобранный код; после правки лекарства или отмены на экране упаковки — тот же, из PendingScan.
    var code by remember { mutableStateOf(PendingScan.code) }
    var notCode by remember { mutableStateOf(false) }
    var notCodeTick by remember { mutableIntStateOf(0) }
    var lastNotCode by remember { mutableLongStateOf(0L) }
    var picking by rememberSaveable { mutableStateOf(false) }

    // «Это не код лекарства» гаснет через 3 с без новых чужих кодов.
    LaunchedEffect(notCodeTick) {
        if (notCodeTick > 0) {
            delay(3000)
            notCode = false
        }
    }

    val onRaw: (String) -> Unit = { raw ->
        val parsed = try {
            parsePackCode(raw)
        } catch (e: Exception) {
            // Разбор кода бросает на битых полях (дата, число); для человека это просто «не код лекарства».
            Log.w(TAG, "Pack code did not parse", e)
            null
        }
        if (parsed != null) {
            PendingScan.code = parsed
            code = parsed
            notCode = false
        } else {
            notCode = true
            // Тот же чужой код читается с каждого кадра — таймер перезапускаем не чаще раза в секунду.
            val now = SystemClock.elapsedRealtime()
            if (now - lastNotCode > 1000) {
                lastNotCode = now
                notCodeTick++
            }
        }
    }

    fun leave() {
        PendingScan.code = null
        nav.back()
    }
    BackHandler { leave() }

    val current = code
    val allMedicines = medicines
    val allPacks = packs
    val match = if (current != null && allMedicines != null && allPacks != null) matchCode(current, allMedicines, allPacks) else null

    BoxWithConstraints(Modifier.fillMaxSize().background(ScanColors.Bg)) {
        // Всё на тёмном — палитра тёмной темы, как бы ни была настроена тема программы.
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
                    )
                    if (current == null) ScanGuide(notCode, Modifier.align(Alignment.TopCenter))
                }
                granted -> ScanMessage(
                    icon = ScanIcons.Camera,
                    title = stringResource(R.string.scan_cam_failed_title),
                    hint = stringResource(R.string.scan_cam_failed_hint),
                    actionLabel = null,
                    onAction = {},
                )
                denied -> ScanMessage(
                    icon = ScanIcons.Camera,
                    title = stringResource(R.string.scan_perm_title),
                    hint = stringResource(R.string.scan_perm_hint) +
                        if (canAsk) "" else "\n" + stringResource(R.string.scan_perm_blocked),
                    actionLabel = stringResource(if (canAsk) R.string.scan_perm_allow else R.string.scan_perm_settings),
                    onAction = {
                        if (canAsk) askCamera.launch(Manifest.permission.CAMERA) else openAppSettings(context)
                    },
                )
            }
            ScanTopBar(
                torchShown = granted && !cameraFailed && hasFlash,
                torch = torch,
                onTorch = { torch = it },
                onClose = { leave() },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }

        if (current != null && match != null) {
            // Карточка выезжает снизу; уходит сразу — сканирование начинается заново.
            val appear = remember(current) { MutableTransitionState(false).apply { targetState = true } }
            AnimatedVisibility(
                visibleState = appear,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(motionSpec(Motion.ENTER)) + slideInVertically(motionSpec(Motion.ENTER)) { it / 3 },
                exit = ExitTransition.None,
            ) {
                ScanResultCard(
                    code = current,
                    match = match,
                    texts = texts,
                    onAdd = {
                        PendingScan.code = current
                        match.medicine?.let { nav.open(Route.PackEdit(it.id, 0, fromScan = true)) }
                    },
                    onChoose = { picking = true },
                    onNew = {
                        PendingScan.code = current
                        nav.open(Route.MedicineEdit(0))
                    },
                    onAgain = {
                        PendingScan.code = null
                        code = null
                        notCode = false
                        picking = false
                    },
                    modifier = Modifier.heightIn(max = maxHeight * 0.85f),
                )
            }
        }
    }

    if (picking && current != null && allMedicines != null) {
        ChooseMedicineSheet(
            medicines = allMedicines,
            onPick = { medicine ->
                picking = false
                PendingScan.code = current
                nav.open(Route.PackEdit(medicine.id, 0, fromScan = true))
            },
            onNew = {
                picking = false
                PendingScan.code = current
                nav.open(Route.MedicineEdit(0))
            },
            onDismiss = { picking = false },
        )
    }
}

private fun hasCameraPermission(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/** Настройки программы в системе: оттуда камеру включают, когда диалог разрешения уже не показывается. */
private fun openAppSettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "App settings not found", e)
    }
}

// --- Окно камеры: рамка, подсказка, кнопки ---

/** Рамка, подсказка и (если была) строка «Это не код лекарства»; код читается и вне рамки — она только целится. */
@Composable
private fun ScanGuide(notCode: Boolean, modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    Column(
        modifier.padding(start = 20.dp, top = 88.dp, end = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScanReticle()
        Text(
            stringResource(R.string.scan_hint),
            color = ScanColors.Hint,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        if (notCode) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Ampulse.colors.warnSoft)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(TodayIcons.Alert, contentDescription = null, modifier = Modifier.size(18.dp), tint = p.warning)
                Text(stringResource(R.string.scan_not_code), color = p.warning, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Четыре угла рамки наведения, 172 dp: линия 4 dp цвета `#9FE0D6`, скругление 10 dp (макет, экран 14). */
@Composable
private fun ScanReticle() {
    Canvas(Modifier.size(172.dp).clearAndSetSemantics { }) {
        val side = size.width
        val arm = 34.dp.toPx()
        val radius = 10.dp.toPx()
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
                drawPath(corner, ScanColors.Reticle, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** Верхняя полоса: «закрыть» слева, название по центру, фонарик справа (если он у камеры есть). */
@Composable
private fun ScanTopBar(
    torchShown: Boolean,
    torch: Boolean,
    onTorch: (Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        // Полоса темнеет сверху: белое название читается и на светлом кадре.
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(ScanColors.Bg.copy(alpha = 0.6f), Color.Transparent)))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(ScanColors.RoundButton)
                .clickable(role = Role.Button, onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                MedIcons.Close,
                contentDescription = stringResource(R.string.scan_close),
                modifier = Modifier.size(22.dp),
                tint = ScanColors.Title,
            )
        }
        Text(
            stringResource(R.string.scan_title),
            Modifier.semantics { heading() },
            color = ScanColors.Title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        if (torchShown) {
            // Выключатель: TalkBack читает «Фонарик, включено/выключено»; включённый залит светлой бирюзой.
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (torch) ScanColors.Reticle else ScanColors.RoundButton)
                    .toggleable(value = torch, role = Role.Switch, onValueChange = onTorch),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    ScanIcons.Flashlight,
                    contentDescription = stringResource(R.string.scan_torch),
                    modifier = Modifier.size(22.dp),
                    tint = if (torch) ScanColors.Bg else ScanColors.Title,
                )
            }
        } else {
            Spacer(Modifier.size(48.dp))
        }
    }
}

/** Пустое тёмное состояние: камеры нет — значок, что случилось и (если есть) кнопка. */
@Composable
private fun ScanMessage(
    icon: ImageVector,
    title: String,
    hint: String,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .padding(start = 20.dp, top = 72.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        EmptyState(icon, title, hint, Modifier.weight(1f))
        if (actionLabel != null) MedMainButton(actionLabel, onAction)
    }
}

// --- Карточка результата ---

/**
 * Карточка результата снизу (макет, экран 14): статус, название, срок, серия, номер, пояснение и действия. Цвета —
 * текущей темы (в светлой карточка белая, как в макете). Не помещается — прокручивается.
 */
@Composable
private fun ScanResultCard(
    code: PackCode,
    match: ScanMatch,
    texts: MedTexts,
    onAdd: () -> Unit,
    onChoose: () -> Unit,
    onNew: () -> Unit,
    onAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = AnvilTheme.palette
    val medicine = match.medicine
    val expired = remember(code) { code.expiry?.let { it < LocalDate.now() } == true }
    val region = stringResource(R.string.scan_result)
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(p.card)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, top = 10.dp, end = 20.dp, bottom = 20.dp)
            .semantics { paneTitle = region },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .size(width = 36.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(p.borderStrong),
        )
        // Повтор — цвет внимания и значок «внимание»; цвет не единственный носитель: сказано словами.
        val tone = if (match.duplicate) p.warning else p.accentText
        Row(
            Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (match.duplicate) TodayIcons.Alert else TodayIcons.Check,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = tone,
            )
            Text(
                stringResource(if (match.duplicate) R.string.scan_status_dup else R.string.scan_status_ok),
                color = tone,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (medicine == null) {
                    stringResource(R.string.scan_unknown_title)
                } else if (medicine.strength.isBlank()) {
                    medicine.name
                } else {
                    "${medicine.name} ${medicine.strength}"
                },
                color = p.text,
                style = screenTitleStyle(24),
            )
            Text(
                if (medicine == null) stringResource(R.string.scan_gtin, code.gtin) else texts.formName(medicine.form),
                color = p.weak,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScanFact(stringResource(R.string.scan_expires), expiryText(code, texts), Modifier.weight(1f), warn = expired)
            ScanFact(stringResource(R.string.scan_batch), code.batch.ifBlank { DASH }, Modifier.weight(1f))
            ScanFact(stringResource(R.string.scan_pack_no), serialTail(code.serial), Modifier.weight(1f))
        }
        Text(stringResource(R.string.scan_note), color = p.faint, style = MaterialTheme.typography.bodySmall)
        if (expired) {
            MedNotice(stringResource(R.string.scan_expired))
        } else if (code.expiry == null) {
            MedNotice(stringResource(R.string.scan_no_expiry))
        }
        // Одна главная кнопка: добавить; уже добавлена — только «Сканировать ещё».
        when {
            match.duplicate -> MedMainButton(stringResource(R.string.scan_again), onAgain)
            medicine != null -> {
                MedMainButton(stringResource(R.string.scan_add), onAdd)
                ScanQuietButton(stringResource(R.string.scan_again), onAgain)
            }
            else -> {
                MedMainButton(stringResource(R.string.scan_choose), onChoose)
                MedOutlineButton(stringResource(R.string.scan_new), onNew)
                ScanQuietButton(stringResource(R.string.scan_again), onAgain)
            }
        }
    }
}

/** Плитка «подпись — значение» (годен до, серия, номер упаковки); для TalkBack читается одной фразой. */
@Composable
private fun ScanFact(label: String, value: String, modifier: Modifier = Modifier, warn: Boolean = false) {
    val p = AnvilTheme.palette
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(p.bg)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, color = p.faint, style = MaterialTheme.typography.bodySmall)
        Text(
            value,
            color = if (warn) p.warning else p.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
        )
    }
}

/** Тихая кнопка без рамки и заливки, цель не меньше 48 dp. */
@Composable
private fun ScanQuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = p.accentText, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
    }
}

/** Срок как на упаковке: есть число — полной датой, нет — «08.2028» / «08/2028»; в коде срока нет — прочерк. */
private fun expiryText(code: PackCode, texts: MedTexts): String {
    val expiry = code.expiry ?: return DASH
    if (code.expiryHasDay) return texts.date(expiry)
    return expiry.format(DateTimeFormatter.ofPattern(texts.string(R.string.scan_expiry_month_pattern), texts.locale))
}

/** Номер упаковки в коде длинный; как в макете — последние четыре знака («…7K2Q»). */
private fun serialTail(serial: String): String = when {
    serial.isBlank() -> DASH
    serial.length <= 4 -> serial
    else -> "…" + serial.takeLast(4)
}

// --- Выбор лекарства ---

/** Лист «Какое это лекарство?»: лекарства из аптечки по алфавиту; пока их нет — только «Новое лекарство». */
@Composable
private fun ChooseMedicineSheet(
    medicines: List<Medicine>,
    onPick: (Medicine) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    val p = AnvilTheme.palette
    TodaySheet(onDismiss) { _ ->
        Text(
            stringResource(R.string.scan_choose_title),
            Modifier.semantics { heading() },
            color = p.text,
            style = screenTitleStyle(24),
        )
        if (medicines.isEmpty()) {
            Text(stringResource(R.string.scan_choose_empty), color = p.weak, style = MaterialTheme.typography.bodyLarge)
            MedMainButton(stringResource(R.string.scan_new), onNew)
        } else {
            medicines.sortedBy { it.name.lowercase() }.forEach { medicine ->
                ChooseRow(medicine) { onPick(medicine) }
            }
            MedOutlineButton(stringResource(R.string.scan_new), onNew)
        }
    }
}

/** Строка лекарства: плитка значка формы, название и дозировка; вся строка нажимается, цель не меньше 56 dp. */
@Composable
private fun ChooseRow(medicine: Medicine, onClick: () -> Unit) {
    val p = AnvilTheme.palette
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Ampulse.colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(MedIcons.of(medicine.form), contentDescription = null, modifier = Modifier.size(22.dp), tint = p.accentText)
        }
        Column(Modifier.weight(1f)) {
            Text(
                medicine.name,
                color = p.text,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            )
            if (medicine.strength.isNotBlank()) {
                Text(medicine.strength, color = p.weak, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Значки сканера: линии 1,8 на сетке 24, пути — набор Lucide, как в макете (docs/ui-guideline.md, §5). */
private object ScanIcons {
    /** Фонарик: кнопка в верхней полосе. */
    val Flashlight: ImageVector by lazy {
        icon(
            "scan.flashlight",
            "M18 6c0 2-2 2-2 4v10a2 2 0 0 1-2 2h-4a2 2 0 0 1-2-2V10c0-2-2-2-2-4V2h12z",
            "M6 6h12", "M12 12v2",
        )
    }

    /** Камера: «нет доступа к камере». */
    val Camera: ImageVector by lazy {
        icon(
            "scan.camera",
            "M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3l-2.5-3z",
            "M15 13a3 3 0 1 1-6 0a3 3 0 0 1 6 0Z",
        )
    }

    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
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
