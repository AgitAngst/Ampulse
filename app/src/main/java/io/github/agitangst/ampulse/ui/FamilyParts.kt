package io.github.agitangst.ampulse.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.LocalActivity
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
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import io.github.agitangst.ampulse.Ampulse
import io.github.agitangst.ampulse.Store
import io.github.agitangst.anvil.compose.AnvilTheme

// Общие части вкладки «Близкие», формы близкого и экранов QR (docs/ui-guideline.md, §6): цели нажатия ≥ 48 dp.

/** Значки «Близких»: линии 1,8 на сетке 24, пути — набор Lucide, как в макете (docs/ui-guideline.md, §5). */
internal object FamilyIcons {
    /** Крест: экстренная карта. */
    val Cross: ImageVector by lazy {
        icon(
            "family.cross",
            "M11 2a2 2 0 0 0-2 2v5H4a2 2 0 0 0-2 2v2c0 1.1.9 2 2 2h5v5c0 1.1.9 2 2 2h2a2 2 0 0 0 2-2v-5h5a2 2 0 0 0 2-2v-2a2 2 0 0 0-2-2h-5V4a2 2 0 0 0-2-2h-2z",
        )
    }

    /** QR-код: показать мой. */
    val Qr: ImageVector by lazy {
        icon(
            "family.qr",
            "M4 3h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z",
            "M15 3h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1h-5a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z",
            "M4 14h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-5a1 1 0 0 1 1-1z",
            "M14 14h3v3h-3z", "M17 20h4", "M20 17v3",
        )
    }

    /** Рамка сканера: прочитать чужой QR. */
    val Scan: ImageVector by lazy {
        icon(
            "family.scan",
            "M3 7V5a2 2 0 0 1 2-2h2", "M17 3h2a2 2 0 0 1 2 2v2", "M21 17v2a2 2 0 0 1-2 2h-2", "M7 21H5a2 2 0 0 1-2-2v-2", "M7 12h10",
        )
    }

    /** Фонарик: кнопка в окне камеры. */
    val Flashlight: ImageVector by lazy {
        icon(
            "family.flashlight",
            "M18 6c0 2-2 2-2 4v10a2 2 0 0 1-2 2h-4a2 2 0 0 1-2-2V10c0-2-2-2-2-4V2h12z",
            "M6 6h12", "M12 12v2",
        )
    }

    /** Сообщение: пример SMS. */
    val Message: ImageVector by lazy {
        icon("family.message", "M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z")
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

/** Строка-карточка: [leading] → заголовок → вторая строка → [trailing] (по умолчанию стрелка ›); вся строка нажимается. */
@Composable
internal fun FamilyRow(
    leading: @Composable () -> Unit,
    title: String,
    sub: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = { FamilyChevron() },
) {
    val p = AnvilTheme.palette
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(p.card)
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = p.text, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
            if (sub != null) Text(sub, color = p.weak, style = MaterialTheme.typography.bodyMedium)
        }
        trailing()
    }
}

/** Стрелка › справа в строке-переходе. */
@Composable
internal fun FamilyChevron() {
    Icon(MedIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp), tint = AnvilTheme.palette.weak)
}

/** Плитка значка 40×40; [warn] — цвета внимания (экстренная карта). */
@Composable
internal fun FamilyTile(icon: ImageVector, warn: Boolean = false) {
    val p = AnvilTheme.palette
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (warn) Ampulse.colors.warnSoft else p.raised),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = if (warn) p.warning else p.text)
    }
}

/** Кружок-инициал 40: у близкого вместо значка. */
@Composable
internal fun FamilyAvatar(name: String) {
    val p = AnvilTheme.palette
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(Ampulse.colors.accentSoft),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).uppercase(),
            color = p.accentText,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

/**
 * Баннер внимания (docs/ui-guideline.md, §6): мягкое внимание с рамкой, плитка значка на карточке, заголовок цветом
 * текста, пояснение цветом внимания, главная кнопка 48 dp.
 */
@Composable
internal fun FamilyAttention(
    title: String,
    text: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = AnvilTheme.palette
    val c = Ampulse.colors
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.warnSoft)
            .border(1.5.dp, c.warnLine, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(p.card), contentAlignment = Alignment.Center) {
                Icon(TodayIcons.Alert, contentDescription = null, modifier = Modifier.size(22.dp), tint = p.warning)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = p.text, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                Text(text, color = p.warning, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(CircleShape)
                .background(p.accent)
                .clickable(role = Role.Button, onClick = onAction),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                actionLabel,
                color = p.onAccent,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

/** Плашка мягкого акцента с галочкой: действие выполнено (4 с). Читается диктором сама. */
@Composable
internal fun FamilyDone(text: String, modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Ampulse.colors.accentSoft)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TodayIcons.Check, contentDescription = null, modifier = Modifier.size(20.dp), tint = p.accentText)
        Text(text, color = p.accentText, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
    }
}

/**
 * Поле ввода: рамка 1,5 dp, подпись над полем (читается TalkBack), цвета палитры. [error] — строка под полем со
 * значком «внимание»; красного в программе нет, поэтому рамка и подпись — цвета внимания.
 */
@Composable
internal fun FamilyField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    imeAction: ImeAction = ImeAction.Next,
    error: String? = null,
    /** Пароль: знаки скрыты, подсказки и запоминание клавиатуры не нужны. */
    password: Boolean = false,
    /** Рамка внимания без текста под полем: ошибку показывает плашка рядом. */
    invalid: Boolean = false,
) {
    val p = AnvilTheme.palette
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        label = { Text(label) },
        placeholder = { if (hint != null) Text(hint) },
        singleLine = true,
        isError = error != null || invalid,
        supportingText = if (error != null) {
            {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(TodayIcons.Alert, contentDescription = null, modifier = Modifier.size(16.dp), tint = p.warning)
                    Text(error, color = p.warning)
                }
            }
        } else {
            null
        },
        shape = RoundedCornerShape(14.dp),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            capitalization = capitalization,
            keyboardType = if (password) KeyboardType.Password else keyboardType,
            imeAction = imeAction,
            autoCorrectEnabled = if (password) false else null,
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = p.text,
            unfocusedTextColor = p.text,
            errorTextColor = p.text,
            focusedContainerColor = p.field,
            unfocusedContainerColor = p.field,
            errorContainerColor = p.field,
            cursorColor = p.accent,
            errorCursorColor = p.warning,
            focusedBorderColor = p.accent,
            unfocusedBorderColor = p.borderStrong,
            errorBorderColor = Ampulse.colors.warnLine,
            focusedLabelColor = p.accentText,
            unfocusedLabelColor = p.weak,
            errorLabelColor = p.warning,
            focusedPlaceholderColor = p.faint,
            unfocusedPlaceholderColor = p.faint,
        ),
    )
}

// --- Разрешение на SMS ---

/**
 * Можно ли слать SMS так, как задумано: в сборке, где SMS уходит сама ([Store.SILENT_SMS]), нужно разрешение
 * `SEND_SMS`; там, где SMS идёт через приложение сообщений, разрешение не нужно.
 */
internal fun smsReady(context: Context): Boolean =
    !Store.SILENT_SMS || context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

/** [smsReady] как состояние: перечитывается, когда человек вернулся на экран (мог включить разрешение в настройках). */
@Composable
internal fun rememberSmsReady(): MutableState<Boolean> {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val state = remember { mutableStateOf(smsReady(context)) }
    DisposableEffect(activity) {
        val lifecycle = (activity as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state.value = smsReady(context)
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    return state
}

/** Камера включена? */
internal fun familyCameraGranted(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/** Настройки программы в системе: оттуда разрешения включают, когда диалог уже не показывается. */
internal fun openFamilyAppSettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: ActivityNotFoundException) {
        Log.w("AmpulseFamily", "App settings not found", e)
    }
}
