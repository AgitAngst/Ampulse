package io.github.agitangst.ampulse.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.agitangst.ampulse.Ampulse
import io.github.agitangst.ampulse.AppGraph
import io.github.agitangst.ampulse.Nav
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.Route
import io.github.agitangst.ampulse.domain.EventStatus
import io.github.agitangst.ampulse.domain.MarkCheck
import io.github.agitangst.ampulse.domain.Medicine
import io.github.agitangst.ampulse.domain.Pack
import io.github.agitangst.ampulse.domain.Plan
import io.github.agitangst.ampulse.domain.PlanKind
import io.github.agitangst.ampulse.domain.PlannedDose
import io.github.agitangst.ampulse.domain.Schedule
import io.github.agitangst.ampulse.domain.Site
import io.github.agitangst.ampulse.domain.dayItems
import io.github.agitangst.ampulse.domain.fefo
import io.github.agitangst.ampulse.domain.goodUntil
import io.github.agitangst.ampulse.domain.maxIntramuscularMl
import io.github.agitangst.ampulse.screenTitleStyle
import io.github.agitangst.anvil.compose.AnvilCard
import io.github.agitangst.anvil.compose.AnvilTheme
import io.github.agitangst.anvil.compose.SectionTitle
import io.github.agitangst.anvil.compose.Segmented
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Вид, на котором открыть памятку: «Укол пошагово» ставит его перед переходом, чтобы «Правила» открылись на том же
 * виде укола. Путь `Route.ShotRules` аргументов не несёт.
 */
internal object ShotMemo {
    var kind by mutableStateOf(ShotKind.Pen)
}

private fun millisOf(t: LocalDateTime): Long = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun timeOf(ms: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())

/**
 * Укол пошагово (макет, экраны 44–45): подкожно шприц-ручкой или в мышцу — вид по месту и единице дозы лекарства
 * (если неоднозначно — переключатель наверху). Шаги с проверками из данных приложения, отсчёт на шаге «Укол», в конце —
 * отметка приёма с местом (по расписанию — `mark`, вне него — `logExtra`) и «Отменить запись». Дозу приложение не меняет
 * и повторную не предлагает.
 */
@Composable
fun ShotGuideScreen(medicineId: Long, plannedAt: Long?, site: String?, nav: Nav) {
    val repo = AppGraph.repository
    val medicines by remember { repo.medicines }.collectAsState(initial = null)
    val schedules by remember { repo.schedules }.collectAsState(initial = null)
    val packs by remember { repo.packs }.collectAsState(initial = null)
    val list = medicines
    val plans = schedules
    val allPacks = packs
    val med = list?.firstOrNull { it.id == medicineId }
    // Лекарство удалили (или открыли по старой ссылке) — вернуться.
    LaunchedEffect(list != null, med == null) { if (list != null && med == null) nav.back() }

    val siteValue = remember(site) { site?.let { name -> runCatching { Site.valueOf(name) }.getOrNull() } }
    val backLabel = stringResource(if (site != null) R.string.shot_back_site else R.string.shot_back)
    if (med == null || plans == null || allPacks == null) {
        Column(Modifier.fillMaxSize().padding(start = 20.dp, top = 8.dp, end = 20.dp)) {
            MedBackRow(backLabel, nav::back, Modifier.offset(x = (-8).dp))
        }
        return
    }
    ShotGuideContent(
        med = med,
        plans = plans.filter { it.medicineId == medicineId },
        packs = allPacks.filter { it.medicineId == medicineId },
        plannedAt = plannedAt?.let(::timeOf),
        site = siteValue,
        backLabel = backLabel,
        nav = nav,
    )
}

@Composable
private fun ShotGuideContent(
    med: Medicine,
    plans: List<Schedule>,
    packs: List<Pack>,
    plannedAt: LocalDateTime?,
    site: Site?,
    backLabel: String,
    nav: Nav,
) {
    val repo = AppGraph.repository
    val texts = rememberMedTexts()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val p = AnvilTheme.palette

    // Ход переживает пересоздание экрана (поворот, смену языка); запись в базе — один раз за проход.
    var step by rememberSaveable { mutableStateOf(0) }
    var kindName by rememberSaveable { mutableStateOf<String?>(null) }
    var timerStart by rememberSaveable { mutableStateOf(0L) }
    var timerDone by rememberSaveable { mutableStateOf(false) }
    var timerOk by rememberSaveable { mutableStateOf(false) }
    var probOpen by rememberSaveable { mutableStateOf(false) }
    var prob by rememberSaveable { mutableStateOf(-1) }
    var eventId by rememberSaveable { mutableStateOf(0L) }
    var alreadyAt by rememberSaveable { mutableStateOf(0L) }
    var courseCount by rememberSaveable { mutableStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var nowMs by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    val requester = remember { BringIntoViewRequester() }

    val today = LocalDate.now()
    val plan: Plan? = plans.firstOrNull()?.plan
    val guess = remember(site, med.unit) { guessShotKind(site, med.unit) }
    val kind = kindName?.let { name -> runCatching { ShotKind.valueOf(name) }.getOrNull() } ?: guess.kind
    val steps = shotSteps(kind)
    val n = steps.size
    val finished = step >= n

    // Приём по расписанию, к которому относится укол (как «Сегодня»); не нашли — укол запишется сверх схемы.
    val planned: PlannedDose? = remember(med, plans, plannedAt) {
        plannedAt?.let { at -> dayItems(at.toLocalDate(), listOf(med), plans, emptyList(), at).firstOrNull { it.planned.at == at }?.planned }
    }
    val amount = planned?.amount ?: shotAmount(plan, plannedAt?.toLocalDate() ?: today)
    val seconds = shotTimerSeconds(kind, med.unit, amount)
    // Упаковка, из которой пойдёт доза (порядок расхода), иначе просроченная с остатком — для проверки срока.
    val pack: Pack? = remember(packs) {
        fefo(packs).firstOrNull() ?: packs.filter { it.left > 0 }.minByOrNull { it.goodUntil() ?: LocalDate.MAX }
    }
    val ctx = ShotCtx(texts, med, site, amount, pack, today, seconds, timerSkipped = !timerOk)

    fun go(i: Int) {
        step = i
        timerStart = 0L
        timerDone = false
    }

    val dur = if (!finished && steps[step].timed) seconds else 0
    LaunchedEffect(timerStart, dur) {
        if (timerStart == 0L || dur == 0) return@LaunchedEffect
        while (true) {
            val t = SystemClock.elapsedRealtime()
            nowMs = t
            if (t - timerStart >= dur * 1000L) {
                timerDone = true
                timerOk = true
                break
            }
            delay(100)
        }
    }
    // Карточка текущего шага (или итога) уезжает вниз за сделанными шагами — подвести её в видимую часть.
    LaunchedEffect(step) {
        if (step > 0) {
            withFrameNanos { }
            withFrameNanos { }
            requester.bringIntoView()
        }
    }

    val record: () -> Unit = {
        if (!saving) {
            saving = true
            scope.launch {
                try {
                    val now = LocalDateTime.now()
                    // «Подтекло» из «Что-то пошло не так?» (подкожно) попадает в запись пометкой.
                    val note = if (kind == ShotKind.Pen && prob == LEAKED_PROBLEM) context.getString(R.string.shot_note_leaked) else ""
                    if (planned != null) {
                        val check = repo.mark(planned, EventStatus.Taken, now, note, site)
                        if (check is MarkCheck.AlreadyTaken) {
                            alreadyAt = millisOf(check.at)
                        } else {
                            eventId = repo.eventsNow(now.minusSeconds(2), now.plusSeconds(2))
                                .lastOrNull { it.medicineId == med.id && it.status == EventStatus.Taken && it.planned == planned.at }?.id ?: 0L
                        }
                    } else {
                        val status = if (plan?.kind == PlanKind.AsNeeded) EventStatus.Taken else EventStatus.Extra
                        eventId = repo.logExtra(med.id, amount, now, status, note, site)
                    }
                    if (alreadyAt == 0L && kind == ShotKind.Muscle && plan != null && courseTotal(plan) != null) {
                        courseCount = courseDone(repo.eventsNow(plan.start.atStartOfDay(), now.plusDays(1)), med.id, plan)
                    }
                    go(n)
                } finally {
                    saving = false
                }
            }
        }
    }
    val undo: () -> Unit = {
        val id = eventId
        scope.launch {
            if (id != 0L) repo.undo(id)
            eventId = 0L
            courseCount = 0
            go(n - 1)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            MedBackRow(backLabel, nav::back, Modifier.offset(x = (-8).dp))
            // Ссылка на другой экран — справа в строке «Назад» (docs/ui-guideline.md, §6); сдвиг на её поле.
            ShotHeaderLink(
                ShotIcons.Book,
                stringResource(R.string.shot_rules),
                onClick = {
                    ShotMemo.kind = kind
                    nav.open(Route.ShotRules)
                },
                modifier = Modifier.offset(x = 8.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.shot_title), Modifier.semantics { heading() }, color = p.text, style = screenTitleStyle(30))
            Text(subtitle(ctx, kind, plan, planned?.at ?: plannedAt, today), color = p.weak, style = MaterialTheme.typography.bodyMedium)
        }
        if (!guess.sure && !finished) {
            val label = stringResource(R.string.shot_kind_a)
            Segmented(
                options = listOf(stringResource(R.string.shot_kind_pen), stringResource(R.string.shot_kind_muscle)),
                selected = kind.ordinal,
                onSelect = { i ->
                    kindName = ShotKind.entries[i].name
                    prob = -1
                    go(0)
                },
                modifier = Modifier.semantics { contentDescription = label },
            )
        }

        val shown = step.coerceAtMost(n)
        ShotProgress(
            if (finished) stringResource(R.string.shot_all_done, n) else stringResource(R.string.shot_step_of, step + 1, n),
            total = n,
            done = shown,
            current = step,
        )

        if (shown > 0) {
            ShotDoneRows(
                steps.take(shown).map { s -> shotStepText(s, ctx).let { it.title to it.short } },
                onOpen = { go(it) },
            )
        }

        if (!finished) {
            val cur = steps[step]
            val tx = shotStepText(cur, ctx)
            val blocked = cur.timed && !timerDone
            ShotStepCard(step + 1, tx.title, Modifier.bringIntoViewRequester(requester)) {
                ShotBody(tx.body)
                tx.dose?.let { ShotDoseTile(stringResource(R.string.shot_window), it) }
                tx.diagramSite?.let { ShotButtockDiagram(it) }
                tx.checks.forEach { ShotCheckRow(it) }
                if (cur.timed) {
                    val elapsedMs = if (timerStart == 0L) 0L else (nowMs - timerStart).coerceIn(0L, dur * 1000L)
                    val remaining = (dur - elapsedMs / 1000L).toInt().coerceIn(0, dur)
                    val caption = stringResource(if (timerDone) timerDoneRes(kind) else timerNoteRes(kind))
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ShotTimerRing(
                            big = if (timerDone) stringResource(R.string.shot_go) else remaining.toString(),
                            small = if (timerDone) "" else stringResource(R.string.shot_sec),
                            fraction = if (timerDone) 1f else elapsedMs / (dur * 1000f),
                            description = if (timerDone) caption else stringResource(R.string.shot_timer_left, remaining),
                            bigSize = if (timerDone) 28 else 44,
                        )
                        ShotTimerCaption(caption, timerDone)
                        if (timerStart == 0L && !timerDone) {
                            ShotOutlineButton(
                                stringResource(R.string.shot_timer_start),
                                onClick = {
                                    timerOk = false
                                    timerStart = SystemClock.elapsedRealtime()
                                    nowMs = timerStart
                                },
                            )
                        }
                    }
                }
                MedMainButton(
                    stringResource(if (step == n - 1) R.string.shot_finish else R.string.shot_done),
                    onClick = { if (step == n - 1) record() else go(step + 1) },
                    enabled = !blocked && !saving,
                )
                Row(Modifier.fillMaxWidth().offset(y = (-6).dp), verticalAlignment = Alignment.CenterVertically) {
                    if (step > 0) StockLinkButton(stringResource(R.string.shot_back), onClick = { go(step - 1) }, color = p.weak)
                    Spacer(Modifier.weight(1f))
                    if (blocked) {
                        StockLinkButton(
                            stringResource(R.string.shot_no_timer),
                            onClick = {
                                timerOk = false
                                timerDone = true
                            },
                            color = p.weak,
                        )
                    }
                }
            }
        } else if (alreadyAt != 0L) {
            ShotResultCard(stringResource(R.string.shot_already_title), Modifier.bringIntoViewRequester(requester)) {
                Text(
                    stringResource(R.string.shot_already_text, texts.time(timeOf(alreadyAt).toLocalTime())),
                    color = p.text,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp),
                )
                MedMainButton(stringResource(R.string.shot_to_main), onClick = { nav.home() })
            }
        } else {
            val nameDose = listOfNotNull(med.name.takeIf { it.isNotBlank() }, ctx.doseText).joinToString(" ")
            val what = listOfNotNull(nameDose.takeIf { it.isNotBlank() }, ctx.siteText).joinToString(", ")
            val secondsText = texts.plural(R.plurals.shot_seconds, seconds, seconds)
            val held = when {
                !timerOk -> null
                kind == ShotKind.Pen -> stringResource(R.string.shot_final_held, secondsText)
                else -> stringResource(R.string.shot_final_injected, secondsText)
            }
            val total = plan?.let(::courseTotal)
            val course = if (kind == ShotKind.Muscle && total != null && courseCount > 0) {
                texts.plural(R.plurals.shot_course_done, courseCount, courseCount, total)
            } else {
                null
            }
            val next = remember(finished) {
                nextPlanned(plan, listOfNotNull(planned?.at, LocalDateTime.now()).max())
            }
            ShotResultCard(stringResource(R.string.shot_final_title), Modifier.bringIntoViewRequester(requester)) {
                Text(
                    listOfNotNull(stringResource(R.string.shot_final_line, what), held, course).joinToString(" "),
                    color = p.text,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp),
                )
                if (next != null) {
                    Text(
                        stringResource(R.string.shot_next_site, whenText(texts, next, today)),
                        color = p.weak,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                    )
                }
                MedMainButton(stringResource(R.string.shot_to_main), onClick = { nav.home() })
                if (eventId != 0L) ShotQuietButton(stringResource(R.string.shot_undo), onClick = undo)
            }
        }

        if (!finished && step < n - 1) {
            ShotNextRows(steps.drop(step + 1).mapIndexed { i, s -> (step + 2 + i) to shotStepText(s, ctx).title })
        }

        val problems = problemTexts(kind)
        ShotProblems(
            open = probOpen,
            onToggle = { probOpen = !probOpen },
            titles = problems.map { stringResource(it.first) },
            selected = prob,
            onPick = { prob = if (prob == it) -1 else it },
            advice = problems.getOrNull(prob)?.let { stringResource(it.second) },
        )
        Text(
            stringResource(R.string.shot_disclaimer),
            Modifier.padding(horizontal = 4.dp),
            color = p.faint,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 19.sp),
        )
    }
}

/** Номер проблемы «Подтекло или игла вышла раньше» у подкожного укола (с нуля). */
private const val LEAKED_PROBLEM = 1

private fun problemTexts(kind: ShotKind): List<Pair<Int, Int>> = if (kind == ShotKind.Pen) {
    listOf(
        R.string.shot_prob_pen_1_t to R.string.shot_prob_pen_1_x,
        R.string.shot_prob_pen_2_t to R.string.shot_prob_pen_2_x,
        R.string.shot_prob_pen_3_t to R.string.shot_prob_pen_3_x,
    )
} else {
    listOf(
        R.string.shot_prob_muscle_1_t to R.string.shot_prob_muscle_1_x,
        R.string.shot_prob_muscle_2_t to R.string.shot_prob_muscle_2_x,
        R.string.shot_prob_muscle_3_t to R.string.shot_prob_muscle_3_x,
    )
}

/** Вторая строка шапки: «Инсулин гларгин 100 ЕД/мл · 12 ЕД · живот справа · 22:00 · курс: день 3 из 10». */
private fun subtitle(c: ShotCtx, kind: ShotKind, plan: Plan?, at: LocalDateTime?, today: LocalDate): String {
    val t = c.texts
    val course = if (kind == ShotKind.Muscle && plan != null) courseDay(plan, today) else null
    return listOfNotNull(
        todayMedName(c.med.name, c.med.strength),
        c.doseText,
        c.siteText,
        at?.let { t.time(it.toLocalTime()) },
        course?.let { t.string(R.string.shot_course_day, it.first, it.second) },
    ).joinToString(" · ")
}

/** «сегодня в 22:00», «завтра в 09:00» или дата со временем. */
private fun whenText(t: MedTexts, at: LocalDateTime, today: LocalDate): String {
    val time = t.time(at.toLocalTime())
    return when (at.toLocalDate()) {
        today -> t.string(R.string.today_when_today, time)
        today.plusDays(1) -> t.string(R.string.today_when_tomorrow, time)
        else -> t.string(R.string.today_when_date, t.shortDate(at.toLocalDate()), time)
    }
}

/** Тихая ссылка со значком в строке «Назад»: 48 dp, цвет акцентного текста. */
@Composable
private fun ShotHeaderLink(icon: ImageVector, text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = p.accentText)
        Text(text, color = p.accentText, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
    }
}

/**
 * Правила уколов (макет, экран 46): памятка по виду укола — разрез кожи, игла, угол, места, объём, скорость; «Всегда» и
 * «Когда нужен врач». Не заменяет обучения и инструкции к лекарству. Вид укола открывается тот, с которого пришли.
 */
@Composable
fun ShotRulesScreen(nav: Nav) {
    val p = AnvilTheme.palette
    val texts = rememberMedTexts()
    var kindName by rememberSaveable { mutableStateOf(ShotMemo.kind.name) }
    val kind = runCatching { ShotKind.valueOf(kindName) }.getOrDefault(ShotKind.Pen)
    val pen = kind == ShotKind.Pen

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MedBackRow(stringResource(R.string.shot_back), nav::back, Modifier.offset(x = (-8).dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.shot_rules_title), Modifier.semantics { heading() }, color = p.text, style = screenTitleStyle(30))
            Text(stringResource(R.string.shot_rules_sub), color = p.weak, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp))
        }
        val kindLabel = stringResource(R.string.shot_kind_a)
        Segmented(
            options = listOf(stringResource(R.string.shot_kind_pen), stringResource(R.string.shot_kind_muscle)),
            selected = kind.ordinal,
            onSelect = { kindName = ShotKind.entries[it].name },
            modifier = Modifier.semantics { contentDescription = kindLabel },
        )

        AnvilCard {
            ShotSkinDiagram(
                kind,
                stringResource(if (pen) R.string.shot_rules_pen_diagram_a else R.string.shot_rules_muscle_diagram_a),
            )
            Text(
                stringResource(if (pen) R.string.shot_rules_pen_caption else R.string.shot_rules_muscle_caption),
                color = p.weak,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
            )
        }

        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.card).padding(horizontal = 16.dp, vertical = 4.dp)) {
            ruleRows(kind).forEachIndexed { i, (label, text) ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        stringResource(label),
                        color = p.faint,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                    )
                    Text(
                        // Объём в мышцу — из `maxIntramuscularMl` (ягодица и бедро) и предела для плеча.
                        if (text == R.string.shot_rule_muscle_4_t) {
                            texts.string(text, texts.number(maxIntramuscularMl(Site.ButtockLeft) ?: 5.0), texts.number(ARM_MAX_ML))
                        } else {
                            texts.string(text)
                        },
                        color = p.text,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp),
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(stringResource(R.string.shot_always_h), Modifier.padding(horizontal = 4.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.card).padding(horizontal = 16.dp, vertical = 6.dp)) {
                ALWAYS.forEachIndexed { i, res ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(TodayIcons.Check, contentDescription = null, modifier = Modifier.padding(top = 1.dp).size(20.dp), tint = p.accent)
                        Text(stringResource(res), color = p.text, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp))
                    }
                }
            }
        }

        val docShape = RoundedCornerShape(20.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(docShape)
                .background(Ampulse.colors.warnSoft)
                .border(1.5.dp, skinColor(), docShape)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(MedIcons.Warning, contentDescription = null, modifier = Modifier.size(22.dp), tint = p.warning)
                Text(
                    stringResource(R.string.shot_doc_h),
                    Modifier.semantics { heading() },
                    color = p.text,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                )
            }
            DoctorLine(R.string.shot_doc_urgent_h, R.string.shot_doc_urgent)
            DoctorLine(R.string.shot_doc_soon_h, R.string.shot_doc_soon)
        }
    }
}

/** Подпись и текст в блоке «Когда нужен врач»: подпись цветом внимания. */
@Composable
private fun DoctorLine(head: Int, text: Int) {
    val p = AnvilTheme.palette
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            stringResource(head),
            color = p.warning,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
        )
        Text(stringResource(text), color = p.text, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp))
    }
}

/** Разделы «Куда», «Угол» и прочие памятки по виду: подпись и текст (строки `shot_rule_*`). */
private fun ruleRows(kind: ShotKind): List<Pair<Int, Int>> = if (kind == ShotKind.Pen) {
    listOf(
        R.string.shot_rule_pen_1_l to R.string.shot_rule_pen_1_t,
        R.string.shot_rule_pen_2_l to R.string.shot_rule_pen_2_t,
        R.string.shot_rule_pen_3_l to R.string.shot_rule_pen_3_t,
        R.string.shot_rule_pen_4_l to R.string.shot_rule_pen_4_t,
        R.string.shot_rule_pen_5_l to R.string.shot_rule_pen_5_t,
    )
} else {
    listOf(
        R.string.shot_rule_muscle_1_l to R.string.shot_rule_muscle_1_t,
        R.string.shot_rule_muscle_2_l to R.string.shot_rule_muscle_2_t,
        R.string.shot_rule_muscle_3_l to R.string.shot_rule_muscle_3_t,
        R.string.shot_rule_muscle_4_l to R.string.shot_rule_muscle_4_t,
        R.string.shot_rule_muscle_5_l to R.string.shot_rule_muscle_5_t,
        R.string.shot_rule_muscle_6_l to R.string.shot_rule_muscle_6_t,
    )
}

private val ALWAYS = listOf(
    R.string.shot_always_1,
    R.string.shot_always_2,
    R.string.shot_always_3,
    R.string.shot_always_4,
    R.string.shot_always_5,
)
