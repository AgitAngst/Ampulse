package io.github.agitangst.ampulse.reminders

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.agitangst.ampulse.AmpulseIcons
import io.github.agitangst.ampulse.AmpulseTheme
import io.github.agitangst.ampulse.AppGraph
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.domain.EventStatus
import io.github.agitangst.ampulse.domain.MedForm
import io.github.agitangst.ampulse.domain.Medicine
import io.github.agitangst.anvil.compose.AnvilActivity
import io.github.agitangst.anvil.compose.ThemeMode
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * Экран «Напоминание» (макет, экран 2): время, название крупно, доза, еда и три кнопки — «Принять», «Отложить на
 * 10 мин», «Пропустить» (с причиной). Тёмный в любой теме (docs/ui-guideline.md, §3), поверх блокировки и сам
 * включает экран. После отметки закрывается. Не экспортирован: открывает его уведомление важного лекарства.
 */
class ReminderActivity : AnvilActivity() {
    private var dose by mutableStateOf<DoseRef?>(null)
    private var slot by mutableIntStateOf(ReminderCodes.SLOT_ON_TIME)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Фон окна — цвет экрана: без белой вспышки, пока рисуется Compose.
        window.setBackgroundDrawable(ColorDrawable(0xFF0F3531.toInt()))
        enableEdgeToEdge()
        accept(intent)
        if (dose == null) {
            Log.w(TAG, "ReminderActivity opened without a dose, closing")
            finish()
            return
        }
        setContent {
            AmpulseTheme(ThemeMode.Dark) {
                dose?.let { ReminderScreen(it, slot, onClose = { finishAndRemoveTask() }) }
            }
        }
    }

    /** Пришло ещё одно напоминание, пока экран открыт, — показать последнее. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        accept(intent)
    }

    private fun accept(intent: Intent) {
        ReminderIntents.read(intent)?.let {
            dose = it
            slot = ReminderIntents.slot(intent)
        }
    }
}

/** Цвета экрана «Напоминание» — из макета (`mockup/Reminder.dc.html`), не зависят от темы. */
private object ReminderColors {
    val Bg = Color(0xFF0F3531)
    val Text = Color(0xFFF4F2EC)
    val Dim = Color(0xFFB9CFCB)
    val Soft = Color(0xFFD5E3E0)
    val Tile = Color(0xFF1C4A45)
    val Card = Color(0xFF174440)
    val Accent = Color(0xFF9FE0D6)
    val Outline = Color(0xFF4E7C76)
}

@Composable
private fun ReminderScreen(dose: DoseRef, slot: Int, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var medicine by remember(dose) { mutableStateOf<Medicine?>(null) }
    var owner by remember(dose) { mutableStateOf<String?>(null) }
    var skipping by remember(dose) { mutableStateOf(false) }
    var busy by remember(dose) { mutableStateOf(false) }

    // Лекарство удалили или приём уже отмечен (в программе) — показывать нечего.
    LaunchedEffect(dose) {
        val loaded = AppGraph.repository.medicine(dose.medicineId)
        if (loaded == null || Notifier.isHandled(dose)) {
            Notifier.cancel(context, dose)
            onClose()
        } else {
            owner = ReminderText.owner(loaded)
            medicine = loaded
        }
    }

    /** Одно действие над приёмом; потом уведомление убирается и экран закрывается. Второе нажатие, пока идёт первое, игнорируется. */
    fun finishWith(what: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "$what failed", e)
            }
            Notifier.cancel(context, dose)
            onClose()
        }
    }

    Box(Modifier.fillMaxSize().background(ReminderColors.Bg)) {
        val current = medicine
        if (current != null) {
            ReminderContent(
                medicine = current,
                owner = owner,
                dose = dose,
                slot = slot,
                skipping = skipping,
                onTake = {
                    finishWith("Take") { AppGraph.repository.mark(dose.toPlannedDose(), EventStatus.Taken, LocalDateTime.now()) }
                },
                onSnooze = { finishWith("Snooze") { AppGraph.reminders.snooze(dose) } },
                onSkipStart = { skipping = true },
                onSkip = { reason ->
                    finishWith("Skip") {
                        AppGraph.repository.mark(dose.toPlannedDose(), EventStatus.Skipped, LocalDateTime.now(), note = reason)
                    }
                },
                onBack = { skipping = false },
            )
        }
    }
}

@Composable
private fun ReminderContent(
    medicine: Medicine,
    owner: String?,
    dose: DoseRef,
    slot: Int,
    skipping: Boolean,
    onTake: () -> Unit,
    onSnooze: () -> Unit,
    onSkipStart: () -> Unit,
    onSkip: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val injection = medicine.form == MedForm.Injection
    val doseLine = listOfNotNull(
        ReminderText.dose(context, medicine, dose.amount),
        ReminderText.food(context, medicine.food),
    ).joinToString(" · ")

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(start = 24.dp, top = 20.dp, end = 24.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Шапка: колокольчик и «Напоминание» слева, время приёма справа.
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(ReminderIcons.Bell, contentDescription = null, modifier = Modifier.size(20.dp), tint = ReminderColors.Dim)
                Text(
                    stringResource(R.string.rem_screen_title),
                    color = ReminderColors.Dim,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                )
            }
            Text(
                ReminderText.time(context, dose.planned),
                color = ReminderColors.Text,
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
            )
        }

        // Середина: значок формы в кольцах, статус, название, доза. Прокручивается, если места мало (крупный шрифт).
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val minHeight = maxHeight
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = minHeight),
                verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        // Отступ сверху — под внешнее кольцо (30 dp), чтобы прокрутка его не срезала.
                        .padding(top = 30.dp)
                        .size(112.dp)
                        .drawBehind {
                            drawCircle(ReminderColors.Accent.copy(alpha = 0.04f), radius = size.minDimension / 2 + 30.dp.toPx())
                            drawCircle(ReminderColors.Accent.copy(alpha = 0.07f), radius = size.minDimension / 2 + 14.dp.toPx())
                        }
                        .clip(CircleShape)
                        .background(ReminderColors.Tile),
                    contentAlignment = Alignment.Center,
                ) {
                    val icon = when (medicine.form) {
                        MedForm.Injection -> ReminderIcons.Syringe
                        MedForm.Tablet, MedForm.Capsule -> AmpulseIcons.Pill
                        else -> AmpulseIcons.Ampoule
                    }
                    Icon(icon, contentDescription = null, modifier = Modifier.size(52.dp), tint = ReminderColors.Accent)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(if (injection) R.string.rem_status_inject else R.string.rem_status_take).uppercase(),
                    color = ReminderColors.Accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                )
                Text(
                    ReminderText.title(medicine.name, owner),
                    color = ReminderColors.Text,
                    style = MaterialTheme.typography.headlineLarge.copy(fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold),
                    textAlign = TextAlign.Center,
                )
                Text(doseLine, color = ReminderColors.Soft, fontSize = 18.sp, textAlign = TextAlign.Center)
            }
        }

        if (skipping) {
            SkipReasons(onSkip = onSkip, onBack = onBack)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Повторы у важного идут, пока не отмечено, — но не бесконечно: после последнего об этом уже не пишем.
                if (medicine.important && slot < ReminderPlan.MAX_REPEATS) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(ReminderColors.Card)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(ReminderIcons.Bell, contentDescription = null, modifier = Modifier.size(20.dp), tint = ReminderColors.Accent)
                        Text(stringResource(R.string.rem_important_note), color = ReminderColors.Soft, fontSize = 14.sp, lineHeight = 20.sp)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onTake,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ReminderColors.Text, contentColor = ReminderColors.Bg),
                    ) {
                        Icon(ReminderIcons.Check, contentDescription = null, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.rem_action_take), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ScreenOutlinedButton(stringResource(R.string.rem_action_snooze), onSnooze, Modifier.weight(1f))
                        ScreenOutlinedButton(stringResource(R.string.rem_action_skip), onSkipStart, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** Вторичная кнопка экрана: рамка 1,5 dp, текст цвета экрана, две строки при нужде. */
@Composable
private fun ScreenOutlinedButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 52.dp),
        shape = RoundedCornerShape(26.dp),
        border = BorderStroke(1.5.dp, ReminderColors.Outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = ReminderColors.Text),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 2)
    }
}

/** «Почему пропускаете?» — короткий выбор причины, она попадает в отчёт для врача. Выбор сразу отмечает пропуск. */
@Composable
private fun SkipReasons(onSkip: (String) -> Unit, onBack: () -> Unit) {
    val reasons = listOf(
        stringResource(R.string.rem_skip_none),
        stringResource(R.string.rem_skip_unwell),
        stringResource(R.string.rem_skip_doctor),
        stringResource(R.string.rem_skip_other),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.rem_skip_title), color = ReminderColors.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.rem_skip_hint), color = ReminderColors.Dim, fontSize = 14.sp)
        }
        for (pair in reasons.chunked(2)) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (reason in pair) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .heightIn(min = 52.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(ReminderColors.Tile)
                            .clickable(role = Role.Button, onClick = { onSkip(reason) })
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(reason, color = ReminderColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                    }
                }
            }
        }
        TextButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = ReminderColors.Dim),
        ) {
            Text(stringResource(R.string.rem_back), fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** Значки экрана (Lucide, как в макете): колокольчик, шприц, галочка. Цвет задаёт `tint`. */
private object ReminderIcons {
    val Bell: ImageVector by lazy {
        icon("reminder.bell", 1.8f, "M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9", "M10.3 21a1.94 1.94 0 0 0 3.4 0")
    }

    val Syringe: ImageVector by lazy {
        icon(
            "reminder.syringe", 1.8f,
            "m18 2 4 4", "m17 7 3-3", "M19 9 8.7 19.3c-1 1-2.5 1-3.4 0l-.6-.6c-1-1-1-2.5 0-3.4L15 5",
            "m9 11 4 4", "m5 19-3 3", "m14 4 6 6",
        )
    }

    /** Галочка «принято» — жирнее остальных, как в макете (линия 2,4). */
    val Check: ImageVector by lazy { icon("reminder.check", 2.4f, "M20 6 9 17l-5-5") }

    private fun icon(name: String, width: Float, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach {
                addPath(
                    pathData = addPathNodes(it),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = width,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
}
