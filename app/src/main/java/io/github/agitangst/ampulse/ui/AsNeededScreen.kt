package io.github.agitangst.ampulse.ui

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.agitangst.ampulse.Ampulse
import io.github.agitangst.ampulse.AppGraph
import io.github.agitangst.ampulse.Nav
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.Route
import io.github.agitangst.ampulse.domain.EventStatus
import io.github.agitangst.ampulse.domain.Plan
import io.github.agitangst.ampulse.domain.PlanKind
import io.github.agitangst.ampulse.domain.PrnCheck
import io.github.agitangst.ampulse.domain.checkPrn
import io.github.agitangst.ampulse.screenTitleStyle
import io.github.agitangst.anvil.compose.AnvilCard
import io.github.agitangst.anvil.compose.AnvilTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime

/**
 * «По необходимости» (макет, экран 11): кольцо-таймер до следующего разрешённого приёма, счёт за сутки, последний
 * приём. «Принять» пишет приём; раньше интервала — только через вопрос, сверх лимита — кнопка недоступна с причиной
 * и временем, когда снова можно. «Сейчас» обновляется раз в минуту.
 */
@Composable
fun AsNeededScreen(medicineId: Long, nav: Nav) {
    val repo = AppGraph.repository
    val texts = rememberMedTexts()
    val scope = rememberCoroutineScope()
    val p = AnvilTheme.palette

    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        // Обновляться на границе минуты, чтобы таймер не отставал от часов.
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            now = LocalDateTime.now()
        }
    }

    val medicines by remember { repo.medicines }.collectAsState(initial = null)
    val schedules by remember { repo.schedules }.collectAsState(initial = null)
    // Отметки за последние сутки: хватает вчера и сегодня; checkPrn сам отбирает 24 часа.
    val day = now.toLocalDate()
    val events by remember(day) { repo.events(day.minusDays(1), day.plusDays(1)) }.collectAsState(initial = null)

    var confirmEarly by rememberSaveable { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }

    val list = medicines
    val plans = schedules
    val log = events
    val med = list?.firstOrNull { it.id == medicineId }
    // Лекарство удалили (или открыли по старой ссылке) — вернуться.
    LaunchedEffect(list != null, med == null) { if (list != null && med == null) nav.back() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MedBackRow(stringResource(R.string.tab_medicines), nav::back, Modifier.offset(x = (-8).dp))
        if (med == null || plans == null || log == null) return@Column

        val plan: Plan? = plans.firstOrNull { it.medicineId == medicineId }?.plan
        val title = if (med.strength.isBlank()) med.name else "${med.name} ${med.strength}"
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, Modifier.semantics { heading() }, color = p.text, style = screenTitleStyle(30))
            Text(texts.summary(plan, med.unit), color = p.weak, style = MaterialTheme.typography.bodyLarge)
        }
        if (plan == null || plan.kind != PlanKind.AsNeeded) {
            // Схема другая — таймер не нужен; ведём править схему.
            Text(stringResource(R.string.meds_prn_not), color = p.weak, style = MaterialTheme.typography.bodyLarge)
            MedOutlineButton(stringResource(R.string.meds_prn_edit_plan), onClick = { nav.open(Route.MedicineEdit(medicineId)) })
            return@Column
        }

        val amount = plan.times.firstOrNull()?.amount ?: 1.0
        val verdict = checkPrn(plan, medicineId, log, now)
        val recent = log.filter {
            it.medicineId == medicineId && it.at > now.minusHours(24) && it.at <= now &&
                (it.status == EventStatus.Taken || it.status == EventStatus.Extra)
        }.sortedBy { it.at }
        val last = recent.lastOrNull()
        val taken = recent.size
        val limit = verdict is PrnCheck.LimitReached
        val tooSoon = verdict is PrnCheck.TooSoon

        // Время, когда можно, и доля пройденного пути для кольца.
        val next: LocalDateTime? = when (verdict) {
            is PrnCheck.Allowed -> null
            is PrnCheck.TooSoon -> verdict.nextAllowed
            is PrnCheck.LimitReached -> verdict.nextAllowed
        }
        val total: Duration = if (limit) Duration.ofHours(24) else Duration.ofHours(plan.prnIntervalHours.toLong().coerceAtLeast(1))
        val progress = if (next == null) 1f else {
            val left = Duration.between(now, next).seconds.toFloat().coerceAtLeast(0f)
            (1f - left / total.seconds.toFloat()).coerceIn(0f, 1f)
        }
        val warn = Ampulse.colors.warnLine
        val ringColor = when {
            limit -> warn
            next == null -> p.accent
            else -> p.accent.copy(alpha = 0.55f)
        }
        val big = if (next == null) stringResource(R.string.meds_prn_can) else texts.duration(Duration.between(now, next))
        val small = stringResource(if (next == null) R.string.meds_prn_can_now else R.string.meds_prn_until_next)
        val status = when {
            limit && next != null -> stringResource(R.string.meds_prn_status_limit, texts.after(next, now))
            tooSoon && next != null -> stringResource(R.string.meds_prn_status_wait, texts.after(next, now))
            last != null -> stringResource(R.string.meds_prn_status_passed)
            else -> stringResource(R.string.meds_prn_status_first)
        }

        AnvilCard(Modifier.semantics(mergeDescendants = true) {}) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(168.dp), contentAlignment = Alignment.Center) {
                    Ring(progress, p.border, ringColor)
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            big,
                            color = p.text,
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                            textAlign = TextAlign.Center,
                        )
                        Text(small, color = p.weak, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    }
                }
                Text(
                    status,
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = if (limit) p.warning else p.text,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    textAlign = TextAlign.Center,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Точки — украшение: число словами рядом.
                    if (plan.prnMaxPerDay in 1..8) {
                        Row(Modifier.clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            repeat(plan.prnMaxPerDay) { i ->
                                val on = i < taken
                                Box(
                                    Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(if (on) p.accent else Color.Transparent)
                                        .border(2.dp, if (on) p.accent else p.borderStrong, CircleShape),
                                )
                            }
                        }
                    }
                    Text(
                        if (plan.prnMaxPerDay > 0) {
                            stringResource(R.string.meds_prn_count, taken, plan.prnMaxPerDay)
                        } else {
                            stringResource(R.string.meds_prn_count_free, taken)
                        },
                        color = p.weak,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    if (last != null) {
                        stringResource(R.string.meds_prn_last, texts.dayAndTime(last.at, now))
                    } else {
                        stringResource(R.string.meds_prn_none)
                    },
                    color = p.weak,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Text(
            stringResource(R.string.meds_prn_dose, texts.dose(amount, med.unit)),
            Modifier.padding(horizontal = 4.dp),
            color = p.weak,
            style = MaterialTheme.typography.bodyMedium,
        )

        val record = {
            pending = true
            confirmEarly = false
            // «Сейчас» обновляем сразу: проверка берёт отметки не позже него, иначе новый приём не засчитается до смены минуты.
            val at = LocalDateTime.now()
            now = at
            scope.launch {
                repo.logExtra(medicineId, amount, at, EventStatus.Taken)
                // Отметка придёт потоком; до тех пор кнопка занята, чтобы второе касание не записало приём дважды.
                delay(800)
                pending = false
            }
            Unit
        }
        when {
            limit -> {
                MedMainButton(stringResource(R.string.meds_prn_take), onClick = {}, enabled = false)
                MedNotice(stringResource(R.string.meds_prn_limit_note, next?.let { texts.after(it, now) }.orEmpty()))
            }
            tooSoon && confirmEarly -> {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                        .background(Ampulse.colors.warnSoft)
                        .padding(16.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.meds_prn_early_title), color = p.warning, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                    Text(
                        stringResource(R.string.meds_prn_early_text, plan.prnIntervalHours),
                        color = p.warning,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ConfirmButton(stringResource(R.string.meds_prn_early_yes), filled = true) { if (!pending) record() }
                        ConfirmButton(stringResource(R.string.meds_prn_early_no), filled = false) { confirmEarly = false }
                    }
                }
            }
            tooSoon -> MedOutlineButton(
                stringResource(R.string.meds_prn_take),
                onClick = { confirmEarly = true },
                danger = true,
            )
            else -> MedMainButton(stringResource(R.string.meds_prn_take), onClick = { record() }, enabled = !pending)
        }
    }
}

/** Кнопка в блоке внимания: заливка цветом текста («Всё равно записать») или тихая («Подожду»); 48 dp. */
@Composable
private fun ConfirmButton(text: String, filled: Boolean, onClick: () -> Unit) {
    val p = AnvilTheme.palette
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(if (filled) p.text else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (filled) p.bg else p.warning,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

/** Кольцо 168 dp: дорожка и дуга от верха по часовой стрелке на [progress] (0…1), края дуги скруглены. */
@Composable
private fun Ring(progress: Float, track: Color, arc: Color) {
    Canvas(Modifier.size(168.dp)) {
        val stroke = 10.dp.toPx()
        val inset = stroke / 2
        val box = Size(size.width - stroke, size.height - stroke)
        drawArc(track, 0f, 360f, useCenter = false, topLeft = Offset(inset, inset), size = box, style = Stroke(stroke))
        if (progress > 0f) {
            drawArc(arc, -90f, 360f * progress, useCenter = false, topLeft = Offset(inset, inset), size = box, style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}
