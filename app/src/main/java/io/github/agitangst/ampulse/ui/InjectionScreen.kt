package io.github.agitangst.ampulse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.agitangst.ampulse.Ampulse
import io.github.agitangst.ampulse.AppGraph
import io.github.agitangst.ampulse.Nav
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.Route
import io.github.agitangst.ampulse.data.Codec
import io.github.agitangst.ampulse.data.Repository
import io.github.agitangst.ampulse.domain.EventStatus
import io.github.agitangst.ampulse.domain.MarkCheck
import io.github.agitangst.ampulse.domain.Medicine
import io.github.agitangst.ampulse.domain.PlanKind
import io.github.agitangst.ampulse.domain.PlannedDose
import io.github.agitangst.ampulse.domain.Profile
import io.github.agitangst.ampulse.domain.ProfileKind
import io.github.agitangst.ampulse.domain.Site
import io.github.agitangst.ampulse.domain.SiteRest
import io.github.agitangst.ampulse.domain.plannedOn
import io.github.agitangst.ampulse.domain.recommended
import io.github.agitangst.ampulse.domain.rotation
import io.github.agitangst.ampulse.domain.sitesFor
import io.github.agitangst.ampulse.screenTitleStyle
import io.github.agitangst.anvil.compose.AnvilCard
import io.github.agitangst.anvil.compose.AnvilTheme
import io.github.agitangst.anvil.compose.Segmented
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Лекарство, приём по расписанию и то, что нужно для значений по умолчанию; читается один раз при открытии экрана. */
private data class InjectionLoaded(
    val medicine: Medicine,
    /** Приём по схеме на `plannedAt`; `null` — укол вне расписания (или схему с тех пор поменяли). */
    val planned: PlannedDose?,
    /** Доза по умолчанию: по приёму, иначе по схеме, иначе 1. */
    val dose: Double,
    /** Место последнего укола этим лекарством за полгода — подсказка вида укола. */
    val lastSite: Site?,
    /** Как записать укол вне расписания: «по необходимости» — принято, иначе лишний приём. */
    val unplannedStatus: EventStatus,
)

private sealed interface InjectionLoad {
    data object Loading : InjectionLoad
    data object Missing : InjectionLoad
    data class Ready(val data: InjectionLoaded) : InjectionLoad
}

private suspend fun loadInjection(repo: Repository, medicineId: Long, plannedAt: Long?): InjectionLoaded? {
    val med = repo.medicine(medicineId) ?: return null
    val schedules = repo.schedulesOf(medicineId)
    val planned = plannedAt?.let { ms ->
        val at = Codec.time(ms)
        plannedOn(at.toLocalDate(), schedules.map { it.id to (it.medicineId to it.plan) })
            .firstOrNull { it.medicineId == medicineId && it.at == at }
    }
    val plan = schedules.firstOrNull()?.plan
    val dose = planned?.amount
        ?: plan?.let { it.dosesOn(LocalDate.now()).firstOrNull()?.amount ?: it.times.firstOrNull()?.amount }
        ?: 1.0
    val now = LocalDateTime.now()
    val lastSite = repo.eventsNow(now.minusDays(180), now.plusDays(1))
        .filter { it.medicineId == medicineId && it.site != null && it.status != EventStatus.Skipped }
        .maxByOrNull { it.at }?.site
    val status = if (plan?.kind == PlanKind.AsNeeded) EventStatus.Taken else EventStatus.Extra
    return InjectionLoaded(med, planned, dose, lastSite, status)
}

/**
 * «Место укола» (макет, экраны 3, 17–18): схема тела с днями с прошлого укола в каждом месте, рекомендуемое место
 * (отдыхало дольше всех), доза и сахар крови по желанию. «Укол сделан · <место>» отмечает приём с местом и закрывает
 * экран; ссылка «Пошагово» ведёт к подсказке укола. `plannedAt` — приём по схеме (эпоха, мс), `null` — вне расписания.
 */
@Composable
fun InjectionScreen(medicineId: Long, plannedAt: Long?, nav: Nav) {
    val repo = AppGraph.repository
    val load by produceState<InjectionLoad>(InjectionLoad.Loading, medicineId, plannedAt) {
        value = loadInjection(repo, medicineId, plannedAt)?.let { InjectionLoad.Ready(it) } ?: InjectionLoad.Missing
    }
    val profiles by repo.profiles.collectAsState<List<Profile>, List<Profile>?>(initial = null)

    when (val state = load) {
        InjectionLoad.Loading -> InjectionWaiting(plannedAt != null, nav)
        // Лекарство удалили или открыли по старой ссылке — вернуться.
        InjectionLoad.Missing -> LaunchedEffect(Unit) { nav.back() }
        is InjectionLoad.Ready -> {
            val med = state.data.medicine
            val list = profiles
            when {
                med.profileId == 0L -> InjectionContent(state.data, null, plannedAt, nav)
                list == null -> InjectionWaiting(plannedAt != null, nav)
                else -> InjectionContent(state.data, list.firstOrNull { it.id == med.profileId }, plannedAt, nav)
            }
        }
    }
}

/** Пока данные читаются — только строка «назад». */
@Composable
private fun InjectionWaiting(fromToday: Boolean, nav: Nav) {
    Column(Modifier.fillMaxSize().padding(start = 20.dp, top = 8.dp, end = 20.dp)) {
        MedBackRow(backLabel(fromToday), nav::back, Modifier.offset(x = (-8).dp))
    }
}

@Composable
private fun backLabel(fromToday: Boolean): String =
    stringResource(if (fromToday) R.string.tab_today else R.string.settings_back)

@Composable
private fun InjectionContent(data: InjectionLoaded, profile: Profile?, plannedAt: Long?, nav: Nav) {
    val repo = AppGraph.repository
    val p = AnvilTheme.palette
    val texts = rememberMedTexts()
    val time = rememberTodayTime()
    val now = rememberTodayNow()
    val scope = rememberCoroutineScope()
    val med = data.medicine
    val kind = profile?.kind ?: ProfileKind.Person
    val pet = kind != ProfileKind.Person
    val today = now.toLocalDate()

    // Выбор человека; пока не выбрано — значения по умолчанию.
    var kindChoice by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    var sideChoice by rememberSaveable { mutableStateOf<String?>(null) }
    var dose by rememberSaveable { mutableStateOf(data.dose) }
    var glucose by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var alreadyAt by remember { mutableStateOf<LocalDateTime?>(null) }

    val intramuscular = kindChoice ?: defaultIntramuscular(med.name + " " + med.note, data.lastSite)
    val injections by remember(med.profileId) { repo.injections(med.profileId) }.collectAsState(initial = emptyList())
    val sites = remember(kind, intramuscular) { sitesFor(kind, intramuscular) }
    val rests = remember(sites, injections, today) { rotation(sites, injections, today) }
    val best = remember(rests) { recommended(rests) }
    val selected = picked?.let { name -> sites.firstOrNull { it.name == name } } ?: best ?: sites.first()
    val side = if (pet) (sideChoice?.let { PetSide.valueOf(it) } ?: selected.petSide() ?: PetSide.Left) else PetSide.Right

    val view = bodyViewFor(kind, intramuscular)
    val spots = visibleSites(sites, if (pet) side else null).map { site ->
        val rest = rests.first { it.site == site }
        val tag = siteTag(rest, best)
        BodySpot(site, spotNumber(rest.daysSince), tag, texts.spotDescription(rest, tag))
    }
    val selectedRest = rests.first { it.site == selected }
    val selectedTag = siteTag(selectedRest, best)

    val note = glucoseNumber(glucose)?.let { texts.string(R.string.injection_glucose_note, it) }.orEmpty()
    val step = remember(data.dose, med.unit) { doseStep(med.unit, data.dose) }

    val onDone: () -> Unit = {
        if (!busy) {
            busy = true
            scope.launch {
                val at = LocalDateTime.now()
                val planned = data.planned
                if (planned != null) {
                    // Доза может быть изменена на экране — в отметку и в списание запаса идёт выбранная.
                    when (val check = repo.mark(planned.copy(amount = dose), EventStatus.Taken, at, note, selected)) {
                        MarkCheck.Ok -> nav.back()
                        is MarkCheck.AlreadyTaken -> {
                            alreadyAt = check.at
                            busy = false
                        }
                    }
                } else {
                    repo.logExtra(med.id, dose, at, data.unplannedStatus, note, selected)
                    nav.back()
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                MedBackRow(backLabel(plannedAt != null), nav::back, Modifier.offset(x = (-8).dp))
                // Ссылка на другой экран — справа в строке «Назад» (docs/ui-guideline.md, §6).
                StepsLink(
                    onClick = { nav.open(Route.ShotGuide(med.id, plannedAt, selected.name)) },
                    modifier = Modifier.offset(x = 8.dp),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    todayMedName(med.name, med.strength),
                    Modifier.semantics { heading() },
                    color = p.text,
                    style = screenTitleStyle(30),
                )
                val whenText = texts.dayAndTime(data.planned?.at ?: now, now).replaceFirstChar { it.titlecase(texts.locale) }
                val route = texts.string(if (intramuscular) R.string.injection_sub_route_im else R.string.injection_sub_route_sc)
                Text(
                    listOfNotNull(texts.profileLine(profile), whenText, route).joinToString(" · "),
                    color = p.weak,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            AnvilCard {
                Segmented(
                    options = listOf(stringResource(R.string.injection_route_sc), stringResource(R.string.injection_route_im)),
                    selected = if (intramuscular) 1 else 0,
                    onSelect = { i ->
                        kindChoice = i == 1
                        picked = null
                        sideChoice = null
                    },
                )
                if (pet) {
                    Segmented(
                        options = listOf(stringResource(R.string.injection_side_left), stringResource(R.string.injection_side_right)),
                        selected = side.ordinal,
                        onSelect = { i ->
                            val target = PetSide.entries[i]
                            sideChoice = target.name
                            // Выбранное место переезжает на зеркальное, чтобы на показанной стороне оно не пропало.
                            val current = selected.petSide()
                            if (current != null && current != target) picked = mirrorSite(selected).name
                        },
                    )
                }
                BodyScheme(view, spots, selected, side, onPick = { picked = it.name })
                Legend()
                Text(
                    stringResource(if (pet) R.string.injection_hint_pet else R.string.injection_hint),
                    color = p.faint,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }

            SelectedSiteCard(
                label = texts.siteName(selected),
                last = texts.lastLine(selectedRest, today),
                tag = selectedTag,
                tagText = texts.tagName(selectedTag),
            )

            DoseCard(
                dose = texts.dose(dose, med.unit),
                onLess = { dose = stepDose(dose, step, -1) },
                onMore = { dose = stepDose(dose, step, 1) },
                lessEnabled = stepDose(dose, step, -1) < dose,
                moreEnabled = stepDose(dose, step, 1) > dose,
                glucose = glucose,
                onGlucose = { glucose = glucoseInput(it) },
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
        Column(Modifier.fillMaxWidth().background(p.bg).padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 20.dp)) {
            MedMainButton(
                stringResource(R.string.injection_done, texts.siteShort(selected)),
                onClick = onDone,
                enabled = !busy,
            )
        }
    }

    alreadyAt?.let { markedAt ->
        AlreadyTakenSheet(
            name = todayMedName(med.name, med.strength),
            planned = data.planned?.at ?: now,
            markedAt = markedAt,
            time = time,
            onKeep = { nav.back() },
            onLog = {
                scope.launch {
                    repo.logExtra(med.id, dose, LocalDateTime.now(), EventStatus.Extra, note, selected)
                    nav.back()
                }
            },
            onDismiss = { alreadyAt = null },
        )
    }
}

/** Ссылка «Пошагово» со значком чек-листа: тихая, 48 dp. */
@Composable
private fun StepsLink(onClick: () -> Unit, modifier: Modifier = Modifier) {
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
        Icon(InjectionIcons.ListChecks, contentDescription = null, modifier = Modifier.size(20.dp), tint = p.accentText)
        Text(
            stringResource(R.string.injection_steps),
            color = p.accentText,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
        )
    }
}

/** Легенда схемы: рекомендуем, можно, отдыхает — тот же вид, что у точек. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend() {
    val p = AnvilTheme.palette
    val c = Ampulse.colors
    FlowRow(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LegendItem(p.accent, p.accent, stringResource(R.string.injection_legend_best))
        LegendItem(p.card, p.accent, stringResource(R.string.injection_legend_ok))
        LegendItem(c.warnSoft, c.warnLine, stringResource(R.string.injection_legend_rest))
    }
}

@Composable
private fun LegendItem(fill: Color, line: Color, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(fill).border(2.dp, line, CircleShape))
        Text(text, color = AnvilTheme.palette.weak, fontSize = 13.sp)
    }
}

/** Выбранное место: название, когда кололи в прошлый раз и метка словом (цвет — не единственный носитель). */
@Composable
private fun SelectedSiteCard(label: String, last: String, tag: SiteTag, tagText: String) {
    val p = AnvilTheme.palette
    val c = Ampulse.colors
    val (fill, ink) = when (tag) {
        SiteTag.Recommended -> c.accentSoft to p.accentText
        SiteTag.Ok -> p.raised to p.text
        SiteTag.Resting -> c.warnSoft to p.warning
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(p.card)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(last, color = p.faint, fontSize = 13.sp, lineHeight = 17.sp)
        }
        Text(
            tagText,
            Modifier.clip(RoundedCornerShape(10.dp)).background(fill).padding(horizontal = 10.dp, vertical = 6.dp),
            color = ink,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Доза (шаговое поле) и «Сахар крови · по желанию» (поле числа; идёт в заметку отметки). */
@Composable
private fun DoseCard(
    dose: String,
    onLess: () -> Unit,
    onMore: () -> Unit,
    lessEnabled: Boolean,
    moreEnabled: Boolean,
    glucose: String,
    onGlucose: (String) -> Unit,
) {
    val p = AnvilTheme.palette
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(p.card)
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        MedStepRow(stringResource(R.string.injection_dose), dose, onLess, onMore, lessEnabled = lessEnabled, moreEnabled = moreEnabled)
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    stringResource(R.string.injection_glucose),
                    color = p.text,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                )
                Text(stringResource(R.string.injection_glucose_optional), color = p.faint, fontSize = 13.sp)
            }
            GlucoseField(glucose, onGlucose)
        }
    }
}

/** Поле числа 108×48 dp, значение справа, в пустом — единица «ммоль/л»; для TalkBack — «Сахар крови, ммоль/л». */
@Composable
private fun GlucoseField(value: String, onChange: (String) -> Unit) {
    val p = AnvilTheme.palette
    val unit = stringResource(R.string.injection_glucose_unit)
    val description = stringResource(R.string.injection_glucose) + ", " + unit
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val focus = LocalFocusManager.current
    val shape = RoundedCornerShape(12.dp)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.width(108.dp).height(48.dp).semantics { contentDescription = description },
        singleLine = true,
        textStyle = TextStyle(color = p.text, fontSize = 16.sp, textAlign = TextAlign.End),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        interactionSource = source,
        cursorBrush = SolidColor(p.accent),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(p.field)
                    .border(1.5.dp, if (focused) p.accent else p.borderStrong, shape)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (value.isEmpty()) Text(unit, color = p.faint, fontSize = 16.sp, maxLines = 1)
                inner()
            }
        },
    )
}

/**
 * Лист «Этот приём уже отмечен» (как на «Сегодня», макет, экран 15): двойная доза не записывается; «Понятно, не
 * принимаю» закрывает экран, «Вторая доза уже принята — записать» пишет лишний приём с местом для врача.
 */
@Composable
private fun AlreadyTakenSheet(
    name: String,
    planned: LocalDateTime,
    markedAt: LocalDateTime,
    time: DateTimeFormatter,
    onKeep: () -> Unit,
    onLog: () -> Unit,
    onDismiss: () -> Unit,
) {
    val p = AnvilTheme.palette
    TodaySheet(onDismiss) { _ ->
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Ampulse.colors.warnSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(TodayIcons.Alert, contentDescription = null, modifier = Modifier.size(26.dp), tint = p.warning)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.today_dd_title),
                    Modifier.semantics { heading() },
                    color = p.text,
                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold),
                )
                Text(
                    stringResource(R.string.today_dd_text, name, time.format(planned), time.format(markedAt)),
                    color = p.weak,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                )
            }
        }
        MedNotice(stringResource(R.string.today_dd_warning, stringResource(R.string.today_dd_emergency)))
        TodayButton(
            stringResource(R.string.today_dd_ok),
            onClick = onKeep,
            modifier = Modifier.fillMaxWidth(),
            container = p.accent,
            content = p.onAccent,
            height = 54,
            fontSize = 17,
        )
        TodayButton(stringResource(R.string.today_dd_log), onClick = onLog, modifier = Modifier.fillMaxWidth(), content = p.warning)
    }
}

// --- Тексты экрана по локали ---

private fun MedTexts.siteName(site: Site): String = string(
    when (site) {
        Site.AbdomenLeft -> R.string.injection_site_abdomen_left
        Site.AbdomenRight -> R.string.injection_site_abdomen_right
        Site.ThighLeft -> R.string.injection_site_thigh_left
        Site.ThighRight -> R.string.injection_site_thigh_right
        Site.ArmLeft -> R.string.injection_site_arm_left
        Site.ArmRight -> R.string.injection_site_arm_right
        Site.ButtockLeft -> R.string.injection_site_buttock_left
        Site.ButtockRight -> R.string.injection_site_buttock_right
        Site.OuterThighLeft -> R.string.injection_site_outer_thigh_left
        Site.OuterThighRight -> R.string.injection_site_outer_thigh_right
        Site.Scruff -> R.string.injection_site_scruff
        Site.FlankLeft -> R.string.injection_site_flank_left
        Site.FlankRight -> R.string.injection_site_flank_right
        Site.PetThighLeft -> R.string.injection_site_pet_thigh_left
        Site.PetThighRight -> R.string.injection_site_pet_thigh_right
    },
)

/** Короткое название для кнопки: «живот справа». */
private fun MedTexts.siteShort(site: Site): String = string(
    when (site) {
        Site.AbdomenLeft -> R.string.injection_site_abdomen_left_short
        Site.AbdomenRight -> R.string.injection_site_abdomen_right_short
        Site.ThighLeft -> R.string.injection_site_thigh_left_short
        Site.ThighRight -> R.string.injection_site_thigh_right_short
        Site.ArmLeft -> R.string.injection_site_arm_left_short
        Site.ArmRight -> R.string.injection_site_arm_right_short
        Site.ButtockLeft -> R.string.injection_site_buttock_left_short
        Site.ButtockRight -> R.string.injection_site_buttock_right_short
        Site.OuterThighLeft -> R.string.injection_site_outer_thigh_left_short
        Site.OuterThighRight -> R.string.injection_site_outer_thigh_right_short
        Site.Scruff -> R.string.injection_site_scruff_short
        Site.FlankLeft -> R.string.injection_site_flank_left_short
        Site.FlankRight -> R.string.injection_site_flank_right_short
        Site.PetThighLeft -> R.string.injection_site_pet_thigh_left_short
        Site.PetThighRight -> R.string.injection_site_pet_thigh_right_short
    },
)

private fun MedTexts.tagName(tag: SiteTag): String = string(
    when (tag) {
        SiteTag.Recommended -> R.string.injection_tag_best
        SiteTag.Ok -> R.string.injection_tag_ok
        SiteTag.Resting -> R.string.injection_tag_rest
    },
)

/** «сегодня», «вчера», «4 дня назад». */
private fun MedTexts.ago(days: Int): String = when (days) {
    0 -> string(R.string.injection_ago_today)
    1 -> string(R.string.injection_ago_yesterday)
    else -> plural(R.plurals.injection_days_ago, days, days)
}

/** «Последний укол сюда: 25 сент., вчера» или «Сюда ещё не кололи». */
private fun MedTexts.lastLine(rest: SiteRest, today: LocalDate): String {
    val days = rest.daysSince ?: return string(R.string.injection_never)
    val whenText = if (days >= 1) shortDate(today.minusDays(days.toLong())) + ", " + ago(days) else ago(days)
    return string(R.string.injection_last, whenText)
}

/** Подпись точки для TalkBack: место, когда кололи, метка словом. */
private fun MedTexts.spotDescription(rest: SiteRest, tag: SiteTag): String {
    val name = siteName(rest.site)
    val days = rest.daysSince ?: return string(R.string.injection_dot_desc_never, name, tagName(tag))
    return string(R.string.injection_dot_desc, name, ago(days), tagName(tag))
}

/** Кто колет: «Барсик, кошка, 4,2 кг», «Анна»; у «Я» строки нет. */
private fun MedTexts.profileLine(profile: Profile?): String? {
    if (profile == null) return null
    val kind = when (profile.kind) {
        ProfileKind.Person -> null
        ProfileKind.Cat -> string(R.string.injection_kind_cat)
        ProfileKind.Dog -> string(R.string.injection_kind_dog)
        ProfileKind.Other -> string(R.string.injection_kind_pet)
    }
    val weight = profile.weightKg?.let { string(R.string.injection_weight_kg, number(it)) }
    return listOfNotNull(profile.name.takeIf { it.isNotBlank() }, kind, weight).joinToString(", ").ifEmpty { null }
}

/** Значки экрана: линейные 1,8 на сетке 24, пути — набор Lucide, как в макете. */
private object InjectionIcons {
    /** Чек-лист: «Пошагово». */
    val ListChecks: ImageVector by lazy {
        ImageVector.Builder(
            name = "injection.list_checks",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            listOf("m3 17 2 2 4-4", "m3 7 2 2 4-4", "M13 6h8", "M13 12h8", "M13 18h8").forEach {
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
}
