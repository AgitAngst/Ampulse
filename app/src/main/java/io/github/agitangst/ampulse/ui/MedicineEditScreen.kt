package io.github.agitangst.ampulse.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.agitangst.ampulse.AmpulseIcons
import io.github.agitangst.ampulse.AppGraph
import io.github.agitangst.ampulse.Nav
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.domain.DoseTime
import io.github.agitangst.ampulse.domain.FoodRule
import io.github.agitangst.ampulse.domain.MedForm
import io.github.agitangst.ampulse.domain.Medicine
import io.github.agitangst.ampulse.domain.Plan
import io.github.agitangst.ampulse.domain.PlanKind
import io.github.agitangst.ampulse.domain.TaperStep
import io.github.agitangst.ampulse.screenTitleStyle
import io.github.agitangst.anvil.compose.AnvilCard
import io.github.agitangst.anvil.compose.AnvilTheme
import io.github.agitangst.anvil.compose.SectionTitle
import io.github.agitangst.anvil.compose.Segmented
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** Черновик формы: всё, что человек правит на экране. Переживает пересоздание экрана ([MedDraftSaver]). */
internal data class MedDraft(
    val name: String = "",
    val strength: String = "",
    val form: MedForm = MedForm.Tablet,
    val unit: String = "",
    /** Единицу поправили руками — смена формы её больше не подставляет. */
    val unitEdited: Boolean = false,
    val food: FoodRule = FoodRule.None,
    val important: Boolean = false,
    val note: String = "",
    val kind: PlanKind = PlanKind.Daily,
    val times: List<DoseTime> = listOf(DoseTime(LocalTime.of(8, 0), 1.0)),
    val weekdays: Set<DayOfWeek> = emptySet(),
    val everyN: Int = 2,
    val cycleOn: Int = 21,
    val cycleOff: Int = 7,
    val steps: List<TaperStep> = emptyList(),
    val prnHours: Int = 6,
    val prnMax: Int = 3,
    val prnAmount: Double = 1.0,
    val start: LocalDate = LocalDate.now(),
    val endless: Boolean = true,
    val days: Int = 7,
    val paused: Boolean = false,
    /** Чьё лекарство: 0 — «Я», иначе профиль (этап 0.6). */
    val profileId: Long = 0,
)

/** Чего не хватает, чтобы сохранить; по порядку на экране. */
private enum class Problem(@StringRes val reason: Int) {
    Name(R.string.meds_need_name),
    Time(R.string.meds_need_time),
    Weekday(R.string.meds_need_weekday),
    Step(R.string.meds_need_step),
}

private fun MedDraft.problem(): Problem? = when {
    name.isBlank() -> Problem.Name
    kind != PlanKind.AsNeeded && times.isEmpty() -> Problem.Time
    kind == PlanKind.Weekly && weekdays.isEmpty() -> Problem.Weekday
    kind == PlanKind.Taper && steps.isEmpty() -> Problem.Step
    else -> null
}

private const val MAX_TIMES = 12
private const val MAX_STEPS = 12
private const val MAX_DAYS = 365
private const val MAX_AMOUNT = 100.0
private const val DOSE_STEP = 0.5
private const val CLOSED = -2
private const val NEW_TIME = -1

@Suppress("UNCHECKED_CAST")
private val MedDraftSaver: Saver<MedDraft, Any> = listSaver<MedDraft, Any>(
    save = { d ->
        listOf<Any>(
            d.name, d.strength, d.form.name, d.unit, d.unitEdited, d.food.name, d.important, d.note, d.kind.name,
            ArrayList(d.times.map { "${it.time}|${it.amount}" }),
            ArrayList(d.weekdays.map { it.name }),
            d.everyN, d.cycleOn, d.cycleOff,
            ArrayList(d.steps.map { "${it.amount}|${it.days}" }),
            d.prnHours, d.prnMax, d.prnAmount, d.start.toString(), d.endless, d.days, d.paused, d.profileId,
        )
    },
    restore = { l ->
        MedDraft(
            name = l[0] as String,
            strength = l[1] as String,
            form = MedForm.valueOf(l[2] as String),
            unit = l[3] as String,
            unitEdited = l[4] as Boolean,
            food = FoodRule.valueOf(l[5] as String),
            important = l[6] as Boolean,
            note = l[7] as String,
            kind = PlanKind.valueOf(l[8] as String),
            times = (l[9] as List<String>).map { s -> s.split('|').let { DoseTime(LocalTime.parse(it[0]), it[1].toDouble()) } },
            weekdays = (l[10] as List<String>).map { DayOfWeek.valueOf(it) }.toSet(),
            everyN = l[11] as Int,
            cycleOn = l[12] as Int,
            cycleOff = l[13] as Int,
            steps = (l[14] as List<String>).map { s -> s.split('|').let { TaperStep(it[0].toDouble(), it[1].toInt()) } },
            prnHours = l[15] as Int,
            prnMax = l[16] as Int,
            prnAmount = l[17] as Double,
            start = LocalDate.parse(l[18] as String),
            endless = l[19] as Boolean,
            days = l[20] as Int,
            paused = l[21] as Boolean,
            profileId = l[22] as Long,
        )
    },
)

/** Черновик из сохранённого лекарства и его схемы. */
private fun draftOf(med: Medicine, plan: Plan?, texts: MedTexts): MedDraft {
    val base = MedDraft(
        name = med.name,
        strength = med.strength,
        form = med.form,
        unit = med.unit,
        unitEdited = med.unit != texts.unitFor(med.form),
        food = med.food,
        important = med.important,
        note = med.note,
        profileId = med.profileId,
    )
    if (plan == null) return base
    return base.copy(
        kind = plan.kind,
        // У «по необходимости» в `times` лежит одна запись — доза за приём, времени у неё нет.
        times = if (plan.kind == PlanKind.AsNeeded || plan.times.isEmpty()) base.times else plan.times.sortedBy { it.time },
        prnAmount = if (plan.kind == PlanKind.AsNeeded) plan.times.firstOrNull()?.amount ?: base.prnAmount else base.prnAmount,
        weekdays = plan.weekdays,
        everyN = plan.everyN.takeIf { it >= 2 } ?: base.everyN,
        cycleOn = plan.cycleOn.takeIf { it > 0 } ?: base.cycleOn,
        cycleOff = plan.cycleOff.takeIf { it > 0 } ?: base.cycleOff,
        steps = plan.steps,
        prnHours = plan.prnIntervalHours.takeIf { it > 0 } ?: base.prnHours,
        prnMax = plan.prnMaxPerDay.takeIf { it > 0 } ?: base.prnMax,
        start = plan.start,
        endless = plan.end == null,
        days = plan.end?.let { (ChronoUnit.DAYS.between(plan.start, it) + 1).toInt().coerceIn(1, MAX_DAYS) } ?: base.days,
        paused = plan.paused,
    )
}

private fun MedDraft.toMedicine(id: Long, texts: MedTexts) = Medicine(
    id = id,
    name = name.trim(),
    strength = strength.trim(),
    form = form,
    unit = unit.trim().ifEmpty { texts.unitFor(form) },
    important = important,
    food = food,
    note = note,
    profileId = profileId,
)

private fun MedDraft.toPlan(): Plan {
    val ordered = times.sortedBy { it.time }
    val doses = when (kind) {
        PlanKind.AsNeeded -> listOf(DoseTime(LocalTime.MIDNIGHT, prnAmount))
        PlanKind.Taper -> ordered.map { it.copy(amount = steps.firstOrNull()?.amount ?: 1.0) }
        else -> ordered
    }
    return Plan(
        kind = kind,
        times = doses,
        start = start,
        end = if (kind == PlanKind.Taper || kind == PlanKind.AsNeeded || endless) null else start.plusDays(days - 1L),
        weekdays = if (kind == PlanKind.Weekly) weekdays else emptySet(),
        everyN = if (kind == PlanKind.EveryNDays) everyN else 1,
        cycleOn = if (kind == PlanKind.Cycle) cycleOn else 0,
        cycleOff = if (kind == PlanKind.Cycle) cycleOff else 0,
        steps = if (kind == PlanKind.Taper) steps else emptyList(),
        prnIntervalHours = if (kind == PlanKind.AsNeeded) prnHours else 0,
        prnMaxPerDay = if (kind == PlanKind.AsNeeded) prnMax else 0,
        paused = paused,
    )
}

/** Время для новой записи: через 4 часа после самого позднего, а занято — следующее свободное. */
private fun nextFreeTime(times: List<DoseTime>): LocalTime {
    if (times.isEmpty()) return LocalTime.of(8, 0)
    var t = times.maxOf { it.time }.plusHours(4)
    repeat(24) {
        if (times.none { it.time == t }) return t
        t = t.plusHours(1)
    }
    return t
}

private fun List<DoseTime>.withTime(index: Int, time: LocalTime): List<DoseTime> =
    if (index == NEW_TIME) {
        (this + DoseTime(time, lastOrNull()?.amount ?: 1.0)).sortedBy { it.time }
    } else {
        mapIndexed { i, d -> if (i == index) d.copy(time = time) else d }.sortedBy { it.time }
    }

private fun Double.stepped(delta: Double): Double = (this + delta).coerceIn(DOSE_STEP, MAX_AMOUNT)

private typealias Edit = (MedDraft.() -> MedDraft) -> Unit

/**
 * Новое лекарство или правка (макет, экраны 4 и 10): название, дозировка, форма, еда, «важное», схема приёма всех
 * видов, начало и длительность, пауза. «Сохранить» недоступно, пока нет названия или времени; у существующего внизу
 * «Удалить лекарство» с вопросом.
 */
@Composable
fun MedicineEditScreen(id: Long, nav: Nav) {
    val repo = AppGraph.repository
    val texts = rememberMedTexts()
    val scope = rememberCoroutineScope()
    val p = AnvilTheme.palette
    var draft by rememberSaveable(stateSaver = MedDraftSaver) { mutableStateOf(MedDraft(unit = texts.unitFor(MedForm.Tablet), profileId = CurrentProfile.id)) }
    var loaded by rememberSaveable(id) { mutableStateOf(id == 0L) }
    var timeDialog by rememberSaveable { mutableStateOf(CLOSED) }
    var dateDialog by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val edit: Edit = { change -> draft = draft.change() }

    LaunchedEffect(id) {
        if (loaded) return@LaunchedEffect
        val med = repo.medicine(id)
        if (med == null) {
            nav.back()
        } else {
            draft = draftOf(med, repo.schedulesOf(id).firstOrNull()?.plan, texts)
            loaded = true
        }
    }

    val problem = draft.problem()
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
            MedBackRow(
                stringResource(if (id == 0L) R.string.meds_cancel else R.string.tab_medicines),
                nav::back,
                Modifier.offset(x = (-8).dp),
            )
            Text(
                stringResource(if (id == 0L) R.string.meds_new_title else R.string.meds_edit_title),
                Modifier.semantics { heading() },
                color = p.text,
                style = screenTitleStyle(30),
            )
            if (loaded) {
                ProfilePicker(draft.profileId, onSelect = { v -> edit { copy(profileId = v) } })
                BasicsSection(draft, texts, edit)
                PlanSection(draft, texts, edit, onPickTime = { timeDialog = it }, onPickDate = { dateDialog = true })
                AnvilCard {
                    MedSwitchRow(
                        stringResource(R.string.meds_pause),
                        stringResource(R.string.meds_pause_hint),
                        draft.paused,
                        { v -> edit { copy(paused = v) } },
                    )
                }
                if (id != 0L) {
                    MedOutlineButton(
                        stringResource(R.string.meds_delete),
                        onClick = { confirmDelete = true },
                        icon = MedIcons.Trash,
                        danger = true,
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
        Column(
            Modifier.fillMaxWidth().background(p.bg).padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (loaded && problem != null) {
                Text(
                    stringResource(problem.reason),
                    Modifier.fillMaxWidth(),
                    color = p.weak,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
            MedMainButton(
                stringResource(R.string.meds_save),
                enabled = loaded && problem == null && !busy,
                onClick = {
                    busy = true
                    val d = draft
                    scope.launch {
                        repo.save(d.toMedicine(id, texts), d.toPlan())
                        nav.back()
                    }
                },
            )
        }
    }

    if (timeDialog != CLOSED) {
        val index = timeDialog
        MedTimeDialog(
            initial = if (index == NEW_TIME) nextFreeTime(draft.times) else draft.times.getOrNull(index)?.time ?: LocalTime.of(8, 0),
            is24 = texts.is24,
            taken = draft.times.filterIndexed { i, _ -> i != index }.map { it.time }.toSet(),
            onConfirm = { t ->
                edit { copy(times = times.withTime(index, t)) }
                timeDialog = CLOSED
            },
            onDismiss = { timeDialog = CLOSED },
        )
    }
    if (dateDialog) {
        MedDateDialog(
            initial = draft.start,
            onConfirm = { d ->
                edit { copy(start = d) }
                dateDialog = false
            },
            onDismiss = { dateDialog = false },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = p.card,
            titleContentColor = p.text,
            textContentColor = p.weak,
            title = { Text(stringResource(R.string.meds_delete_title, draft.name.trim())) },
            text = { Text(stringResource(R.string.meds_delete_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        busy = true
                        scope.launch {
                            repo.delete(id)
                            nav.back()
                        }
                    },
                ) { Text(stringResource(R.string.meds_delete_confirm), color = p.danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.meds_cancel), color = p.accentText) }
            },
        )
    }
}

/** Подпись раздела и его содержимое с промежутком 8 dp. */
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(title, Modifier.padding(horizontal = 4.dp))
        content()
    }
}

/** Название, дозировка, форма с единицей, еда и «Важное лекарство». */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BasicsSection(d: MedDraft, texts: MedTexts, edit: Edit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MedTextField(d.name, { v -> edit { copy(name = v) } }, stringResource(R.string.meds_field_name))
            MedTextField(
                d.strength,
                { v -> edit { copy(strength = v) } },
                stringResource(R.string.meds_field_strength),
                hint = stringResource(R.string.meds_field_strength_hint),
                capitalization = KeyboardCapitalization.None,
            )
        }
        Section(stringResource(R.string.meds_section_form)) {
            FlowRow(
                Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MedForm.entries.forEach { f ->
                    MedChip(
                        texts.formName(f),
                        selected = d.form == f,
                        onClick = { edit { copy(form = f, unit = if (unitEdited) unit else texts.unitFor(f)) } },
                        icon = MedIcons.of(f),
                    )
                }
            }
            MedTextField(
                d.unit,
                { v -> edit { copy(unit = v, unitEdited = true) } },
                stringResource(R.string.meds_field_unit),
                capitalization = KeyboardCapitalization.None,
                imeAction = ImeAction.Done,
            )
        }
        Section(stringResource(R.string.meds_section_food)) {
            FlowRow(
                Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FoodRule.entries.forEach { f ->
                    MedChip(texts.foodName(f), selected = d.food == f, onClick = { edit { copy(food = f) } })
                }
            }
        }
        AnvilCard {
            MedSwitchRow(
                stringResource(R.string.meds_important),
                stringResource(R.string.meds_important_hint),
                d.important,
                { v -> edit { copy(important = v) } },
            )
        }
    }
}

/** Схема: вид, поля вида, начало и длительность. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanSection(d: MedDraft, texts: MedTexts, edit: Edit, onPickTime: (Int) -> Unit, onPickDate: () -> Unit) {
    val unit = d.unit.ifBlank { texts.unitFor(d.form) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Section(stringResource(R.string.meds_section_plan)) {
            FlowRow(
                Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PlanKind.entries.forEach { k ->
                    MedChip(
                        texts.kindName(k),
                        selected = d.kind == k,
                        onClick = {
                            // Для ступеней сразу одна ступень, чтобы список не был пустым.
                            edit { copy(kind = k, steps = if (k == PlanKind.Taper && steps.isEmpty()) listOf(TaperStep(1.0, 7)) else steps) }
                        },
                    )
                }
            }
        }
        when (d.kind) {
            PlanKind.Daily -> TimesSection(d, unit, texts, edit, onPickTime, showDose = true)
            PlanKind.Weekly -> {
                WeekdaysSection(d, texts, edit)
                TimesSection(d, unit, texts, edit, onPickTime, showDose = true)
            }
            PlanKind.EveryNDays -> {
                AnvilCard {
                    MedStepRow(
                        stringResource(R.string.meds_every_n_label),
                        texts.days(d.everyN),
                        onLess = { edit { copy(everyN = (everyN - 1).coerceAtLeast(2)) } },
                        onMore = { edit { copy(everyN = (everyN + 1).coerceAtMost(60)) } },
                        lessEnabled = d.everyN > 2,
                        moreEnabled = d.everyN < 60,
                    )
                }
                TimesSection(d, unit, texts, edit, onPickTime, showDose = true)
            }
            PlanKind.Cycle -> {
                AnvilCard {
                    MedStepRow(
                        stringResource(R.string.meds_cycle_on_label),
                        texts.days(d.cycleOn),
                        onLess = { edit { copy(cycleOn = (cycleOn - 1).coerceAtLeast(1)) } },
                        onMore = { edit { copy(cycleOn = (cycleOn + 1).coerceAtMost(90)) } },
                        lessEnabled = d.cycleOn > 1,
                        moreEnabled = d.cycleOn < 90,
                    )
                    MedDivider()
                    MedStepRow(
                        stringResource(R.string.meds_cycle_off_label),
                        texts.days(d.cycleOff),
                        onLess = { edit { copy(cycleOff = (cycleOff - 1).coerceAtLeast(1)) } },
                        onMore = { edit { copy(cycleOff = (cycleOff + 1).coerceAtMost(90)) } },
                        lessEnabled = d.cycleOff > 1,
                        moreEnabled = d.cycleOff < 90,
                    )
                }
                TimesSection(d, unit, texts, edit, onPickTime, showDose = true)
            }
            PlanKind.Taper -> {
                TimesSection(d, unit, texts, edit, onPickTime, showDose = false)
                StepsSection(d, unit, texts, edit)
            }
            PlanKind.AsNeeded -> AnvilCard {
                MedStepRow(
                    stringResource(R.string.meds_prn_dose_label),
                    texts.dose(d.prnAmount, unit),
                    onLess = { edit { copy(prnAmount = prnAmount.stepped(-DOSE_STEP)) } },
                    onMore = { edit { copy(prnAmount = prnAmount.stepped(DOSE_STEP)) } },
                    lessEnabled = d.prnAmount > DOSE_STEP,
                    moreEnabled = d.prnAmount < MAX_AMOUNT,
                )
                MedDivider()
                MedStepRow(
                    stringResource(R.string.meds_prn_interval_label),
                    texts.hours(d.prnHours),
                    onLess = { edit { copy(prnHours = (prnHours - 1).coerceAtLeast(1)) } },
                    onMore = { edit { copy(prnHours = (prnHours + 1).coerceAtMost(24)) } },
                    lessEnabled = d.prnHours > 1,
                    moreEnabled = d.prnHours < 24,
                )
                MedDivider()
                MedStepRow(
                    stringResource(R.string.meds_prn_max_label),
                    texts.times(d.prnMax),
                    onLess = { edit { copy(prnMax = (prnMax - 1).coerceAtLeast(1)) } },
                    onMore = { edit { copy(prnMax = (prnMax + 1).coerceAtMost(12)) } },
                    lessEnabled = d.prnMax > 1,
                    moreEnabled = d.prnMax < 12,
                )
            }
        }
        if (d.kind != PlanKind.AsNeeded) DurationSection(d, texts, edit, onPickDate)
    }
}

/** Тонкая линия между строками внутри карточки. */
@Composable
private fun MedDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(AnvilTheme.palette.border))
}

/** Кнопка 48 dp без рамки со значком: убрать время или ступень. */
@Composable
private fun MedIconButton(description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(MedIcons.Close, contentDescription = description, modifier = Modifier.size(20.dp), tint = AnvilTheme.palette.weak)
    }
}

/** Времена приёма: у каждого — кнопка времени, доза (шаг 0,5) и «убрать»; внизу «Добавить время». */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimesSection(d: MedDraft, unit: String, texts: MedTexts, edit: Edit, onPickTime: (Int) -> Unit, showDose: Boolean) {
    Section(stringResource(R.string.meds_section_times)) {
        d.times.forEachIndexed { i, t ->
            val clock = texts.time(t.time)
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                MedValueButton(clock, MedIcons.Clock, stringResource(R.string.meds_time_change, clock), onClick = { onPickTime(i) })
                if (showDose) {
                    MedStepper(
                        texts.dose(t.amount, unit),
                        stringResource(R.string.meds_dose_label),
                        onLess = { edit { copy(times = times.mapIndexed { k, x -> if (k == i) x.copy(amount = x.amount.stepped(-DOSE_STEP)) else x }) } },
                        onMore = { edit { copy(times = times.mapIndexed { k, x -> if (k == i) x.copy(amount = x.amount.stepped(DOSE_STEP)) else x }) } },
                        lessEnabled = t.amount > DOSE_STEP,
                        moreEnabled = t.amount < MAX_AMOUNT,
                    )
                }
                MedIconButton(stringResource(R.string.meds_time_remove, clock)) {
                    edit { copy(times = times.filterIndexed { k, _ -> k != i }) }
                }
            }
        }
        if (d.times.size < MAX_TIMES) {
            MedAddButton(stringResource(R.string.meds_time_add), onClick = { onPickTime(NEW_TIME) })
        }
    }
}

/** Дни недели чипами (выбрать можно несколько). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekdaysSection(d: MedDraft, texts: MedTexts, edit: Edit) {
    Section(stringResource(R.string.meds_section_weekdays)) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DayOfWeek.entries.forEach { day ->
                MedChip(
                    texts.weekday(day),
                    selected = day in d.weekdays,
                    onClick = { edit { copy(weekdays = if (day in weekdays) weekdays - day else weekdays + day) } },
                    multi = true,
                    minWidth = 56.dp,
                )
            }
        }
    }
}

/** Ступени: у каждой доза на приём и сколько дней она держится; «Добавить ступень» копирует последнюю. */
@Composable
private fun StepsSection(d: MedDraft, unit: String, texts: MedTexts, edit: Edit) {
    val p = AnvilTheme.palette
    Section(stringResource(R.string.meds_section_steps)) {
        AnvilCard {
            d.steps.forEachIndexed { i, step ->
                if (i > 0) MedDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.meds_step_title, i + 1) + " · " + texts.dose(step.amount, unit) + " × " + texts.days(step.days),
                        Modifier.weight(1f),
                        color = p.text,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                    MedIconButton(stringResource(R.string.meds_step_remove, i + 1)) {
                        edit { copy(steps = steps.filterIndexed { k, _ -> k != i }) }
                    }
                }
                MedStepRow(
                    stringResource(R.string.meds_dose_label),
                    texts.dose(step.amount, unit),
                    onLess = { edit { copy(steps = steps.mapIndexed { k, s -> if (k == i) s.copy(amount = s.amount.stepped(-DOSE_STEP)) else s }) } },
                    onMore = { edit { copy(steps = steps.mapIndexed { k, s -> if (k == i) s.copy(amount = s.amount.stepped(DOSE_STEP)) else s }) } },
                    lessEnabled = step.amount > DOSE_STEP,
                    moreEnabled = step.amount < MAX_AMOUNT,
                )
                MedStepRow(
                    stringResource(R.string.meds_step_days_label),
                    texts.days(step.days),
                    onLess = { edit { copy(steps = steps.mapIndexed { k, s -> if (k == i) s.copy(days = (s.days - 1).coerceAtLeast(1)) else s }) } },
                    onMore = { edit { copy(steps = steps.mapIndexed { k, s -> if (k == i) s.copy(days = (s.days + 1).coerceAtMost(MAX_DAYS)) else s }) } },
                    lessEnabled = step.days > 1,
                    moreEnabled = step.days < MAX_DAYS,
                )
            }
            if (d.steps.size < MAX_STEPS) {
                MedAddButton(stringResource(R.string.meds_step_add), onClick = {
                    edit { copy(steps = steps + (steps.lastOrNull() ?: TaperStep(1.0, 7))) }
                })
            }
        }
        val total = d.steps.sumOf { it.days }
        if (total > 0) {
            Text(
                texts.plural(R.plurals.meds_steps_total, total, total, texts.date(d.start.plusDays(total - 1L))),
                Modifier.padding(horizontal = 4.dp),
                color = p.weak,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Начало (дата) и длительность: «без конца» или N дней; у ступеней конец — по сумме дней ступеней. */
@Composable
private fun DurationSection(d: MedDraft, texts: MedTexts, edit: Edit, onPickDate: () -> Unit) {
    val p = AnvilTheme.palette
    val startText = texts.date(d.start)
    Section(stringResource(R.string.meds_section_start)) {
        AnvilCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.meds_start_label),
                    Modifier.weight(1f),
                    color = p.text,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                )
                MedValueButton(startText, AmpulseIcons.Calendar, stringResource(R.string.meds_start_change, startText), onClick = onPickDate)
            }
            if (d.kind == PlanKind.Taper) {
                Text(
                    stringResource(R.string.meds_dur_taper),
                    color = p.weak,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Segmented(
                    listOf(stringResource(R.string.meds_dur_endless), stringResource(R.string.meds_dur_course)),
                    selected = if (d.endless) 0 else 1,
                    onSelect = { i -> edit { copy(endless = i == 0) } },
                )
                if (!d.endless) {
                    MedStepRow(
                        stringResource(R.string.meds_dur_days_label),
                        texts.days(d.days),
                        onLess = { edit { copy(days = (days - 1).coerceAtLeast(1)) } },
                        onMore = { edit { copy(days = (days + 1).coerceAtMost(MAX_DAYS)) } },
                        lessEnabled = d.days > 1,
                        moreEnabled = d.days < MAX_DAYS,
                    )
                    Text(
                        stringResource(R.string.meds_dur_until, texts.date(d.start.plusDays(d.days - 1L))),
                        color = p.weak,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
