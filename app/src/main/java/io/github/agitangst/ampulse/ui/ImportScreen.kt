package io.github.agitangst.ampulse.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.agitangst.ampulse.Ampulse
import io.github.agitangst.ampulse.AppGraph
import io.github.agitangst.ampulse.Nav
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.domain.FoodRule
import io.github.agitangst.ampulse.domain.MedForm
import io.github.agitangst.ampulse.domain.Parsed
import io.github.agitangst.ampulse.domain.Prescription
import io.github.agitangst.ampulse.domain.Routine
import io.github.agitangst.ampulse.domain.RoutineStore
import io.github.agitangst.ampulse.screenTitleStyle
import io.github.agitangst.anvil.compose.AnvilCard
import io.github.agitangst.anvil.compose.AnvilTheme
import io.github.agitangst.anvil.compose.SectionTitle
import io.github.agitangst.anvil.compose.Segmented
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Текст из «Поделиться»: экран разбора забирает его и обнуляет. Наблюдаемое — оболочка открывает разбор сама. */
object PendingImport {
    var text: String? by mutableStateOf(null)
}

private const val STEP_INPUT = 0
private const val STEP_REVIEW = 1
private const val STEP_PLAN = 2

/** Результат чтения файла с назначением. */
private sealed interface FileRead {
    data class Content(val text: String) : FileRead
    data object TooBig : FileRead
    data object Failed : FileRead
}

/**
 * Назначение → план (макет, экраны 7–9) тремя шагами в одном маршруте. Ввод: текст, «Из файла», фото и голос
 * (`Capture.kt`), текст из «Поделиться» (`PendingImport`); разбор на лету. Проверка: строка — карточка, из текста —
 * сплошной чип, додумано — пунктирный; нет частоты — вопрос, без ответа дальше нельзя; то же название, что в списке, —
 * «изменение дозы» (сохранится с id лекарства). План: время по режиму дня, курсы, сколько понадобится; «Добавить в
 * расписание» сохраняет всё и возвращает к списку. Дозы приложение не меняет — раскладывает то, что написал врач.
 */
@Composable
fun ImportScreen(nav: Nav) {
    val repo = AppGraph.repository
    val context = LocalContext.current
    val texts = rememberMedTexts()
    val scope = rememberCoroutineScope()
    val p = AnvilTheme.palette

    var step by rememberSaveable { mutableIntStateOf(STEP_INPUT) }
    var text by rememberSaveable { mutableStateOf("") }
    // Ответы «сколько раз в день» и убранные строки — по исходной строке текста: правка соседних строк их не сдвигает.
    var answers by rememberSaveable { mutableStateOf(HashMap<String, Int>()) }
    var removed by rememberSaveable { mutableStateOf(HashSet<String>()) }
    var lastRemoved by rememberSaveable { mutableStateOf<String?>(null) }
    var tomorrow by rememberSaveable { mutableStateOf(false) }
    var routineOpen by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var routine by remember { mutableStateOf(RoutineStore.load(context)) }
    // «Изменение дозы» — только среди лекарств выбранного профиля; новые лекарства — тоже ему.
    val all by remember { repo.medicines }.collectAsState(initial = emptyList())
    val medicines = remember(all, CurrentProfile.id) { all.filter { it.profileId == CurrentProfile.id } }

    val parsed = remember(text) { Prescription.parseAll(text) }
    val rows = remember(parsed, removed, answers, medicines) { reviewRows(parsed, removed, answers, medicines) }
    val open = rows.count { it.isOpen }
    val start = remember(tomorrow) { LocalDate.now().let { if (tomorrow) it.plusDays(1) else it } }
    val plans = remember(rows, start, routine, texts) { rows.mapNotNull { planFor(it, start, routine, texts::unitFor) } }

    // Текст из «Поделиться» (или пришедший, пока разбор открыт): дописать в поле и показать ввод.
    val shared = PendingImport.text
    LaunchedEffect(shared) {
        if (shared != null) {
            text = appendText(text, shared)
            PendingImport.text = null
            step = STEP_INPUT
        }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                when (val read = withContext(Dispatchers.IO) { readTextFile(context, uri) }) {
                    is FileRead.Content -> text = appendText(text, read.text)
                    FileRead.TooBig -> toast(context, R.string.import_file_big)
                    FileRead.Failed -> toast(context, R.string.import_file_failed)
                }
            }
        }
    }

    // «Назад» ведёт на шаг назад, с первого шага — с экрана; пока идёт сохранение — никуда.
    BackHandler(enabled = busy || step > STEP_INPUT) { if (!busy) step -= 1 }

    val scroll = rememberScrollState()
    LaunchedEffect(step) { scroll.scrollTo(0) }

    val save: () -> Unit = {
        busy = true
        scope.launch {
            try {
                // Список перечитывается заново: совпадение с лекарством должно быть по состоянию на сейчас.
                val profileId = CurrentProfile.id
                val current = repo.medicines.first().filter { it.profileId == profileId }
                val ready = reviewRows(parsed, removed, answers, current).mapNotNull { planFor(it, start, routine, texts::unitFor) }
                ready.forEach { repo.save(if (it.medicine.id == 0L) it.medicine.copy(profileId = profileId) else it.medicine, it.plan) }
                Toast.makeText(context, texts.plural(R.plurals.import_saved, ready.size, ready.size), Toast.LENGTH_LONG).show()
                nav.back()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                busy = false
                toast(context, R.string.import_save_failed)
            }
        }
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (step) {
                STEP_INPUT -> InputStep(
                    text = text,
                    onText = { text = it },
                    parsed = parsed,
                    onCancel = nav::back,
                    onPickFile = { pickFile.launch(arrayOf("text/plain")) },
                )
                STEP_REVIEW -> ReviewStep(
                    rows = rows,
                    parsed = parsed,
                    routine = routine,
                    texts = texts,
                    lastRemoved = lastRemoved,
                    onBack = { step = STEP_INPUT },
                    onAnswer = { source, n -> answers = HashMap(answers).apply { put(source, n) } },
                    onRemove = { source ->
                        removed = HashSet(removed).apply { add(source) }
                        lastRemoved = source
                    },
                    onUndoRemove = {
                        lastRemoved?.let { source -> removed = HashSet(removed).apply { remove(source) } }
                        lastRemoved = null
                    },
                )
                else -> PlanStep(
                    plans = plans,
                    routine = routine,
                    routineOpen = routineOpen,
                    tomorrow = tomorrow,
                    start = start,
                    texts = texts,
                    onBack = { step = STEP_REVIEW },
                    onToggleRoutine = { routineOpen = !routineOpen },
                    onRoutine = {
                        routine = it
                        RoutineStore.save(context, it)
                    },
                    onTomorrow = { tomorrow = it },
                )
            }
        }
        HorizontalDivider(color = p.border)
        Column(
            Modifier.fillMaxWidth().background(p.bg).padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (step) {
                STEP_INPUT -> MedMainButton(
                    stringResource(R.string.import_read),
                    onClick = {
                        lastRemoved = null
                        step = STEP_REVIEW
                    },
                    enabled = parsed.isNotEmpty(),
                )
                STEP_REVIEW -> if (rows.isEmpty()) {
                    MedMainButton(stringResource(R.string.import_edit_text), onClick = { step = STEP_INPUT })
                } else {
                    MedMainButton(
                        if (open > 0) pluralStringResource(R.plurals.import_answer_n, open, open) else stringResource(R.string.import_plan_btn),
                        onClick = { step = STEP_PLAN },
                        enabled = open == 0,
                    )
                }
                else -> {
                    Text(
                        stringResource(R.string.import_footer_note),
                        Modifier.fillMaxWidth(),
                        color = p.weak,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    MedMainButton(stringResource(R.string.import_save), onClick = save, enabled = !busy && plans.isNotEmpty())
                }
            }
        }
    }
}

/** Всплывающее сообщение об ошибке чтения или записи: оболочка не держит плашек (docs/ui-guideline.md, §6). */
private fun toast(context: Context, @StringRes id: Int) {
    Toast.makeText(context, id, Toast.LENGTH_LONG).show()
}

private fun readTextFile(context: Context, uri: Uri): FileRead = try {
    val input = context.contentResolver.openInputStream(uri)
    if (input == null) {
        FileRead.Failed
    } else {
        val bytes = input.use { readLimited(it, IMPORT_MAX_FILE_BYTES) }
        if (bytes == null) FileRead.TooBig else FileRead.Content(decodeText(bytes))
    }
} catch (e: IOException) {
    FileRead.Failed
} catch (e: SecurityException) {
    FileRead.Failed
}

/** Прочитать поток целиком, но не больше [max] байт; больше — `null`. */
private fun readLimited(input: InputStream, max: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        out.write(buffer, 0, n)
        if (out.size() > max) return null
    }
    return out.toByteArray()
}

// --- Шаг 1: ввод ---

@Composable
private fun InputStep(text: String, onText: (String) -> Unit, parsed: List<Parsed>, onCancel: () -> Unit, onPickFile: () -> Unit) {
    val p = AnvilTheme.palette
    MedBackRow(stringResource(R.string.meds_cancel), onCancel, Modifier.offset(x = (-8).dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.import_title),
            Modifier.semantics { heading() },
            color = p.text,
            style = screenTitleStyle(30),
        )
        Text(stringResource(R.string.import_sub), color = p.weak, style = MaterialTheme.typography.bodyLarge)
        ImportOfflinePill(Modifier.padding(top = 4.dp))
    }
    ImportTextField(text, onText, stringResource(R.string.import_text_label), stringResource(R.string.import_text_hint))
    FoundLine(parsed)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.import_sources), Modifier.padding(horizontal = 4.dp))
        // Фото и голос — кнопки из Capture.kt; распознанный текст дописывается в поле.
        PhotoTextButton({ onText(appendText(text, it)) }, Modifier.fillMaxWidth())
        VoiceTextButton({ onText(appendText(text, it)) }, Modifier.fillMaxWidth())
        MedOutlineButton(stringResource(R.string.import_file), onClick = onPickFile, icon = ImportIcons.File)
    }
    ImportHintCard(ImportIcons.Share, stringResource(R.string.import_share_hint))
}

/** Многострочное поле текста назначения: рамка 1,5 dp, подпись над полем, цвета палитры (как у `MedTextField`). */
@Composable
private fun ImportTextField(value: String, onChange: (String) -> Unit, label: String, hint: String) {
    val p = AnvilTheme.palette
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
        label = { Text(label) },
        placeholder = { Text(hint) },
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = p.text,
            unfocusedTextColor = p.text,
            focusedContainerColor = p.field,
            unfocusedContainerColor = p.field,
            cursorColor = p.accent,
            focusedBorderColor = p.accent,
            unfocusedBorderColor = p.borderStrong,
            focusedLabelColor = p.accentText,
            unfocusedLabelColor = p.weak,
            focusedPlaceholderColor = p.faint,
            unfocusedPlaceholderColor = p.faint,
        ),
    )
}

/** «Вижу 3 лекарства: …» или подсказка, что вставить; разбор идёт на лету. */
@Composable
private fun FoundLine(parsed: List<Parsed>) {
    val p = AnvilTheme.palette
    val ok = parsed.isNotEmpty()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(
            TodayIcons.Check,
            contentDescription = null,
            modifier = Modifier.padding(top = 1.dp).size(18.dp),
            tint = if (ok) p.accentText else p.weak,
        )
        Text(
            if (ok) {
                pluralStringResource(R.plurals.import_found, parsed.size, parsed.size, parsed.joinToString(", ") { it.name })
            } else {
                stringResource(R.string.import_found_none)
            },
            color = if (ok) p.accentText else p.weak,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

// --- Шаг 2: проверка ---

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewStep(
    rows: List<ImportRow>,
    parsed: List<Parsed>,
    routine: Routine,
    texts: MedTexts,
    lastRemoved: String?,
    onBack: () -> Unit,
    onAnswer: (String, Int) -> Unit,
    onRemove: (String) -> Unit,
    onUndoRemove: () -> Unit,
) {
    val p = AnvilTheme.palette
    MedBackRow(stringResource(R.string.import_edit_text), onBack, Modifier.offset(x = (-8).dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.import_review_title),
            Modifier.semantics { heading() },
            color = p.text,
            style = screenTitleStyle(30),
        )
        if (rows.isNotEmpty()) {
            Text(
                pluralStringResource(R.plurals.import_review_sub, rows.size, rows.size),
                color = p.weak,
                style = MaterialTheme.typography.bodyLarge,
            )
            // Легенда — пока на экране есть и то, и другое: «из текста» бывает всегда, додуманное — почти всегда.
            if (rows.any { row -> chipsOf(row, 0, texts).any { it.assumed } }) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ImportChip(ImportChipSpec(stringResource(R.string.import_legend_found)))
                    ImportChip(ImportChipSpec(stringResource(R.string.import_legend_guess), assumed = true))
                }
            }
        }
    }
    val removedName = lastRemoved?.let { source -> parsed.firstOrNull { it.source == source }?.name }
    if (removedName != null) {
        StockDoneBar(stringResource(R.string.import_removed, removedName), stringResource(R.string.import_undo), onUndoRemove)
    }
    if (rows.isEmpty()) {
        MedNotice(stringResource(R.string.import_empty_review))
    }
    rows.forEach { row ->
        val spread = row.perDay?.let { spreadHours(row.parsed, Prescription.timesFor(row.parsed, it, routine)) } ?: 0
        ReviewCard(
            row = row,
            chips = chipsOf(row, spread, texts),
            onAnswer = { onAnswer(row.parsed.source, it) },
            onRemove = { onRemove(row.parsed.source) },
        )
    }
}

/**
 * Карточка строки назначения (макет, экран 8): плитка формы, имя и состояние, чипы (из текста — сплошные, додумано —
 * пунктирные), вопрос о частоте на мягком внимании, пометка «изменение дозы» и строка текста, из которой всё взято.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewCard(row: ImportRow, chips: List<ImportChipSpec>, onAnswer: (Int) -> Unit, onRemove: () -> Unit) {
    val p = AnvilTheme.palette
    val parsed = row.parsed
    val existing = row.existing
    val steps = parsed.steps
    val shape = RoundedCornerShape(18.dp)
    val dosePhrase = existing != null && doseDiffers(existing, parsed)

    // Состояние под именем: внимание — нужен ответ или лекарство уже есть; иначе цвет акцента.
    val (status, warn) = when {
        row.isOpen -> R.string.import_status_ask to true
        existing != null -> (if (dosePhrase) R.string.import_status_change else R.string.import_status_known) to true
        steps != null && steps.last().amount < steps.first().amount -> R.string.import_status_taper_down to false
        steps != null && steps.last().amount > steps.first().amount -> R.string.import_status_taper_up to false
        else -> R.string.import_status_new to false
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.card)
            .border(1.5.dp, if (row.isOpen) Ampulse.colors.warnLine else p.card, shape)
            .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            StockTile(MedIcons.of(parsed.form))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    parsed.name,
                    color = p.text,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(status),
                    color = if (warn) p.warning else p.accentText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                )
            }
            ImportIconButton(
                MedIcons.Close,
                stringResource(R.string.import_remove, parsed.name),
                onRemove,
                Modifier.offset(x = 8.dp),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            chips.forEach { ImportChip(it) }
        }
        if (row.asked) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (row.isOpen) Ampulse.colors.warnSoft else p.bg)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(if (parsed.form == MedForm.Injection) R.string.import_question_inject else R.string.import_question_take),
                    color = if (row.isOpen) p.warning else p.text,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IMPORT_ANSWERS.forEach { v ->
                        MedChip(v.toString(), selected = row.perDay == v, onClick = { onAnswer(v) }, minWidth = 56.dp)
                    }
                }
            }
        }
        if (existing != null) {
            MedNotice(
                if (dosePhrase) {
                    stringResource(R.string.import_change_dose, existing.strength, parsed.strength.orEmpty())
                } else {
                    stringResource(R.string.import_change_same)
                },
            )
        }
        Text(
            stringResource(R.string.import_quote, parsed.source),
            color = p.weak,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// --- Шаг 3: готовый план ---

@Composable
private fun PlanStep(
    plans: List<ImportPlan>,
    routine: Routine,
    routineOpen: Boolean,
    tomorrow: Boolean,
    start: LocalDate,
    texts: MedTexts,
    onBack: () -> Unit,
    onToggleRoutine: () -> Unit,
    onRoutine: (Routine) -> Unit,
    onTomorrow: (Boolean) -> Unit,
) {
    val p = AnvilTheme.palette
    MedBackRow(stringResource(R.string.import_back_review), onBack, Modifier.offset(x = (-8).dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.import_plan_title),
            Modifier.semantics { heading() },
            color = p.text,
            style = screenTitleStyle(30),
        )
        val perDay = dosesPerDay(plans)
        Text(
            stringResource(
                R.string.import_plan_summary,
                pluralStringResource(R.plurals.import_n_medicines, plans.size, plans.size),
                pluralStringResource(R.plurals.import_doses_per_day, perDay, perDay),
                texts.shortDate(start),
            ),
            color = p.weak,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    if (plans.isEmpty()) {
        MedNotice(stringResource(R.string.import_empty_review))
        return
    }

    // Режим дня: чем задан и как поправить, не выходя из разбора (переход в настройки потерял бы введённое).
    AnvilCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.import_routine_title),
                    color = p.text,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    stringResource(
                        R.string.import_routine_summary,
                        texts.time(routine.wake),
                        texts.time(routine.breakfast),
                        texts.time(routine.lunch),
                        texts.time(routine.dinner),
                        texts.time(routine.sleep),
                    ),
                    color = p.weak,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            StockLinkButton(
                stringResource(if (routineOpen) R.string.import_routine_done else R.string.import_routine_change),
                onToggleRoutine,
            )
        }
        if (routineOpen) RoutineEditor(routine, onRoutine)
    }
    AnvilCard {
        Text(
            stringResource(R.string.import_start_label),
            color = p.text,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
        )
        Segmented(
            listOf(stringResource(R.string.import_start_today), stringResource(R.string.import_start_tomorrow)),
            selected = if (tomorrow) 1 else 0,
            onSelect = { onTomorrow(it == 1) },
        )
    }

    StockSectionHeader(stringResource(R.string.import_day_title), null)
    AnvilCard {
        daySlots(plans).forEachIndexed { i, slot ->
            if (i > 0) HorizontalDivider(color = p.border)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Text(
                    texts.time(slot.time),
                    Modifier.widthIn(min = 72.dp),
                    color = p.text,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    slot.entries.forEach { PlanEntry(it, texts) }
                }
            }
        }
    }

    StockSectionHeader(stringResource(R.string.import_courses_title, texts.shortDate(start)), null)
    AnvilCard {
        val lengths = plans.map { courseDays(it, start) }
        val longest = maxOf(30, lengths.maxOfOrNull { it ?: 0 } ?: 0)
        plans.forEachIndexed { i, ip ->
            val days = lengths[i]
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        ip.medicine.name,
                        Modifier.weight(1f),
                        color = p.text,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Text(
                        if (days == null) {
                            stringResource(R.string.import_chip_ongoing)
                        } else {
                            stringResource(R.string.import_course_days, texts.days(days), texts.shortDate(ip.plan.lastDay ?: start))
                        },
                        color = p.weak,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.End,
                    )
                }
                ImportCourseBar((days ?: longest).toFloat() / longest, ongoing = days == null)
            }
        }
    }

    StockSectionHeader(stringResource(R.string.import_total_title), null)
    AnvilCard {
        plans.forEachIndexed { i, ip ->
            if (i > 0) HorizontalDivider(color = p.border)
            val total = courseTotal(ip.plan)
            val value = totalText(ip, total, texts)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ip.medicine.name,
                    Modifier.weight(1f),
                    color = p.text,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                )
                Text(
                    stringResource(if (total.monthly) R.string.import_total_month else R.string.import_total_course, value),
                    Modifier.widthIn(max = 190.dp),
                    color = p.text,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

/** Дней в курсе от [start]; `null` — курс без конца. */
private fun courseDays(ip: ImportPlan, start: LocalDate): Int? =
    ip.plan.lastDay?.let { (ChronoUnit.DAYS.between(start, it) + 1).toInt() }

/** «21 капс.» или «10 уколов · 20 мл» — сколько понадобится на курс. */
private fun totalText(ip: ImportPlan, total: CourseTotal, texts: MedTexts): String {
    val med = ip.medicine
    return if (med.form == MedForm.Injection) {
        val count = texts.plural(R.plurals.import_injections, total.doses, total.doses)
        if (med.strength.isBlank()) count else count + " · " + texts.dose(total.amount, med.unit)
    } else {
        texts.dose(total.amount, med.unit)
    }
}

/** Приём в плане дня: значок формы, «имя · доза» и пометки (еда, через день, ступени, смена дозы). */
@Composable
private fun PlanEntry(ip: ImportPlan, texts: MedTexts) {
    val p = AnvilTheme.palette
    val med = ip.medicine
    val parsed = ip.row.parsed
    val steps = ip.plan.steps
    val amount = if (steps.isNotEmpty()) {
        texts.string(R.string.import_amount_first, texts.dose(steps.first().amount, med.unit), texts.days(steps.first().days))
    } else {
        texts.dose(ip.plan.times.firstOrNull()?.amount ?: parsed.perDose, med.unit)
    }
    val name = if (med.form == MedForm.Injection) med.name else todayMedName(med.name, med.strength)

    val tags = buildList {
        if (med.form != MedForm.Injection) {
            when (parsed.food) {
                FoodRule.None -> Unit
                FoodRule.EmptyStomach -> add(texts.string(R.string.import_tag_empty))
                FoodRule.Before -> add(texts.string(R.string.import_tag_before))
                FoodRule.With -> add(texts.string(R.string.import_tag_with))
                FoodRule.After -> add(texts.string(R.string.import_tag_after))
            }
        }
        if (parsed.everyOtherDay) add(texts.string(R.string.import_chip_every_other))
        if (steps.size > 1) {
            add(texts.string(R.string.import_tag_taper, steps.drop(1).joinToString(" → ") { texts.number(it.amount) } + " " + med.unit))
        }
    }
    val existing = ip.row.existing
    val change = if (existing != null && doseDiffers(existing, parsed)) {
        texts.string(R.string.import_tag_change, existing.strength, parsed.strength.orEmpty())
    } else {
        null
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Icon(MedIcons.of(med.form), contentDescription = null, modifier = Modifier.padding(top = 2.dp).size(20.dp), tint = p.accentText)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "$name · $amount",
                color = p.text,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            )
            if (tags.isNotEmpty()) {
                Text(tags.joinToString(" · "), color = p.weak, style = MaterialTheme.typography.bodyMedium)
            }
            if (change != null) {
                Text(change, color = p.warning, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
