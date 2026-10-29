package io.github.agitangst.ampulse.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import io.github.agitangst.ampulse.Ampulse
import io.github.agitangst.ampulse.AmpulseIcons
import io.github.agitangst.ampulse.AppGraph
import io.github.agitangst.ampulse.BuildConfig
import io.github.agitangst.ampulse.Nav
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.data.PressurePrefs
import io.github.agitangst.ampulse.screenTitleStyle
import io.github.agitangst.anvil.compose.AnvilCard
import io.github.agitangst.anvil.compose.AnvilTheme
import io.github.agitangst.anvil.compose.SectionTitle
import io.github.agitangst.anvil.compose.Segmented
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.NumberFormat
import java.time.LocalDateTime

/** Значки отчёта врачу: линии 1,8 на сетке 24, пути — набор Lucide, как в макете (docs/ui-guideline.md, §5). */
internal object ReportIcons {
    /** Стрелка вниз в лоток: «Отчёт врачу (PDF)» в «Истории» (макет, экран 6). */
    val Download: ImageVector by lazy {
        icon("report.download", "M12 15V3", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "m7 10 5 5 5-5")
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

/**
 * Вход в отчёт врачу внизу «Истории» (макет, экран 6): обычная кнопка со значком «скачать» и пояснение под ней.
 * Экран отчёта — [ReportScreen].
 */
@Composable
internal fun ReportEntry(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MedOutlineButton(stringResource(R.string.report_entry), onClick, icon = ReportIcons.Download)
        Text(
            stringResource(R.string.report_entry_hint),
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            color = p.weak,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Отчёт врачу в PDF (задача 3.1 этапа 0.8, макет — «История» и «Для врача»): период 14 / 30 / 90 дней, чей отчёт (текущий
 * профиль), что войдёт (приёмы, давление), сводка на экране и кнопка «Создать PDF»; готовый файл — «Поделиться…» и
 * «Открыть…» через FileProvider. Любое изменение параметров сбрасывает готовый файл. PDF собирает [ReportPdf] вне главного
 * потока.
 */
@Composable
fun ReportScreen(nav: Nav) {
    val repo = AppGraph.repository
    val context = LocalContext.current
    val activity = LocalActivity.current
    val texts = rememberMedTexts()
    val p = AnvilTheme.palette
    val scope = rememberCoroutineScope()
    val now = rememberTodayNow()
    val today = now.toLocalDate()
    val profileId = CurrentProfile.id

    var periodIndex by rememberSaveable { mutableStateOf(1) }
    var withDoses by rememberSaveable { mutableStateOf(true) }
    var withPressure by rememberSaveable { mutableStateOf(true) }
    var filePath by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val days = REPORT_PERIODS[periodIndex.coerceIn(REPORT_PERIODS.indices)]
    val range = remember(today, days) { reportRange(today, days) }
    val from = range.first
    val to = range.second

    val medicines by remember { repo.medicines }.collectAsState(initial = null)
    val schedules by remember { repo.schedules }.collectAsState(initial = emptyList())
    // Сутки с краёв: приём мог быть отмечен после полуночи или накануне.
    val events by remember(from, to) { repo.events(from.minusDays(1), to.plusDays(2)) }.collectAsState(initial = emptyList())
    val readings by remember(profileId, from, to) { repo.readings(profileId, from, to) }.collectAsState(initial = emptyList())
    val profiles by remember { repo.profiles }.collectAsState(initial = emptyList())
    val target = remember(profileId) { PressurePrefs.target(context, profileId) }

    val meName = stringResource(R.string.report_profile_me)
    val profileName = if (profileId == 0L) meName else profiles.firstOrNull { it.id == profileId }?.name ?: meName
    val mine = remember(medicines, profileId) { medicines?.filter { it.profileId == profileId } }
    val model = remember(mine, schedules, events, readings, target, now, withDoses, withPressure, profileName, from, to) {
        mine?.let {
            buildReport(profileName, from, to, it, schedules, events, if (withPressure) readings else null, target, now, withDoses)
        }
    }

    val file = filePath?.let { File(it) }?.takeIf { it.exists() }
    val version = BuildConfig.VERSION_NAME + (BuildConfig.BUILD_NUMBER.takeIf { it > 0 }?.let { ".$it" } ?: "")
    val reset = {
        filePath = null
        error = null
    }

    val create: () -> Unit = create@{
        val m = model ?: return@create
        if (busy || !m.hasContent) return@create
        busy = true
        reset()
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.Default) { ReportPdf.create(context, m, texts, LocalDateTime.now(), version) }
            }
            busy = false
            val failure = result.exceptionOrNull()
            if (failure is CancellationException) throw failure
            result.getOrNull()?.let { filePath = it.absolutePath }
            if (failure != null) {
                error = texts.string(R.string.report_error, failure.localizedMessage ?: failure.javaClass.simpleName)
            }
        }
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MedBackRow(stringResource(R.string.tab_history), nav::back, Modifier.offset(x = (-8).dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.report_title),
                    Modifier.semantics { heading() },
                    color = p.text,
                    style = screenTitleStyle(30),
                )
                Text(stringResource(R.string.report_intro), color = p.weak, style = MaterialTheme.typography.bodyMedium)
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(stringResource(R.string.report_period_h), Modifier.padding(horizontal = 4.dp))
                AnvilCard {
                    Segmented(
                        options = REPORT_PERIODS.map { texts.days(it) },
                        selected = periodIndex.coerceIn(REPORT_PERIODS.indices),
                        onSelect = {
                            periodIndex = it
                            reset()
                        },
                    )
                    Text(
                        stringResource(R.string.report_range, texts.date(from), texts.date(to)),
                        color = p.weak,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(stringResource(R.string.report_profile_h), Modifier.padding(horizontal = 4.dp))
                AnvilCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Ampulse.colors.accentSoft),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(AmpulseIcons.Users, contentDescription = null, modifier = Modifier.size(22.dp), tint = p.accentText)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                profileName,
                                color = p.text,
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(stringResource(R.string.report_profile_hint), color = p.weak, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(stringResource(R.string.report_content_h), Modifier.padding(horizontal = 4.dp))
                AnvilCard {
                    MedSwitchRow(
                        stringResource(R.string.report_sw_doses),
                        stringResource(R.string.report_sw_doses_hint),
                        withDoses,
                        onChange = {
                            withDoses = it
                            reset()
                        },
                    )
                    MedSwitchRow(
                        stringResource(R.string.report_sw_pressure),
                        stringResource(R.string.report_sw_pressure_hint),
                        withPressure,
                        onChange = {
                            withPressure = it
                            reset()
                        },
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(stringResource(R.string.report_preview_h), Modifier.padding(horizontal = 4.dp))
                AnvilCard {
                    if (model == null) {
                        Spacer(Modifier.height(48.dp))
                    } else {
                        ReportPreview(model, texts)
                    }
                }
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
        Column(
            Modifier.fillMaxWidth().background(p.bg).padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            error?.let { ReportMessage(it, warning = true) }
            when {
                busy -> Row(
                    Modifier.fillMaxWidth().height(54.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = p.accent, strokeWidth = 2.5.dp)
                    Text(
                        stringResource(R.string.report_creating),
                        color = p.weak,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
                file != null -> {
                    ReportMessage(stringResource(R.string.report_done, file.name), warning = false)
                    MedMainButton(
                        stringResource(R.string.report_share),
                        onClick = { error = shareReport(context, activity, file, texts) },
                    )
                    MedOutlineButton(
                        stringResource(R.string.report_open),
                        onClick = { error = openReport(context, activity, file, texts) },
                    )
                }
                else -> {
                    if (model != null && !model.hasContent) {
                        Text(
                            stringResource(R.string.report_nothing_hint),
                            Modifier.fillMaxWidth(),
                            color = p.weak,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                    MedMainButton(stringResource(R.string.report_create), onClick = create, enabled = model?.hasContent == true)
                }
            }
        }
    }
}

/** Сводка на экране: общий процент, по лекарствам, пропуски и лишние приёмы, давление. То же, что уйдёт в PDF. */
@Composable
private fun ReportPreview(model: ReportModel, texts: MedTexts) {
    val p = AnvilTheme.palette
    if (!model.doses && model.pressure == null) {
        Text(stringResource(R.string.report_preview_none_selected), color = p.weak, style = MaterialTheme.typography.bodyMedium)
        return
    }
    if (model.doses) {
        val percent = model.percent
        if (percent == null) {
            Text(stringResource(R.string.report_preview_no_doses), color = p.weak, style = MaterialTheme.typography.bodyMedium)
        } else {
            val text = remember(texts.locale, percent) { NumberFormat.getPercentInstance(texts.locale).format(percent / 100.0) }
            val taken = stringResource(R.string.report_taken_of, model.taken, model.planned)
            val spoken = stringResource(R.string.report_preview_a11y, text) + ". " + taken
            Row(
                Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken },
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text,
                    color = p.text,
                    style = MaterialTheme.typography.displaySmall.copy(fontSize = 48.sp, lineHeight = 48.sp, fontWeight = FontWeight.SemiBold),
                )
                Text(taken, Modifier.padding(bottom = 4.dp), color = p.weak, fontSize = 14.sp, lineHeight = 19.sp)
            }
            model.meds.filter { it.stats.planned > 0 }.forEach { ReportMedBar(it) }
            ReportStat(stringResource(R.string.report_stat_skipped), model.skipped.toString())
            ReportStat(stringResource(R.string.report_stat_missed), model.missed.toString())
            ReportStat(stringResource(R.string.report_stat_extra), model.extra.toString())
        }
        model.prn.forEach {
            ReportStat(
                stringResource(R.string.report_stat_prn, todayMedName(it.medicine.name, it.medicine.strength)),
                texts.times(it.count),
            )
        }
    }
    val bp = model.pressure
    if (bp != null) {
        if (model.doses) Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
        if (bp.total == 0) {
            Text(stringResource(R.string.report_preview_no_pressure), color = p.weak, style = MaterialTheme.typography.bodyMedium)
        } else {
            bp.morning?.let { ReportStat(stringResource(R.string.report_stat_bp_morning), "${it.sys}/${it.dia}") }
            bp.evening?.let { ReportStat(stringResource(R.string.report_stat_bp_evening), "${it.sys}/${it.dia}") }
            ReportStat(stringResource(R.string.report_stat_bp_count), texts.plural(R.plurals.report_pdf_bp_readings, bp.total, bp.total))
        }
    }
}

/** Лекарство со строкой «принято N из M» и полосой доли (макет «По препаратам»): число есть, полоса — для глаза. */
@Composable
private fun ReportMedBar(med: ReportMed) {
    val p = AnvilTheme.palette
    val s = med.stats
    val fraction = if (s.planned == 0) 0f else (s.taken.toFloat() / s.planned).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                todayMedName(s.medicine.name, s.medicine.strength),
                Modifier.weight(1f),
                color = p.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(stringResource(R.string.report_of, s.taken, s.planned), color = p.weak, fontSize = 14.sp)
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Ampulse.colors.track)) {
            Box(Modifier.fillMaxWidth(fraction).height(6.dp).clip(RoundedCornerShape(3.dp)).background(p.accent))
        }
    }
}

/** Строка «подпись — значение». */
@Composable
private fun ReportStat(label: String, value: String) {
    val p = AnvilTheme.palette
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, Modifier.weight(1f), color = p.weak, fontSize = 14.sp, lineHeight = 19.sp)
        Text(value, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Сообщение внизу: ошибка — цветом внимания со значком; «готово» — плашка мягкого акцента с галочкой. */
@Composable
private fun ReportMessage(text: String, warning: Boolean) {
    val p = AnvilTheme.palette
    val c = Ampulse.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (warning) c.warnSoft else c.accentSoft)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (warning) TodayIcons.Alert else TodayIcons.Check,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (warning) p.warning else p.accentText,
        )
        Text(
            text,
            Modifier.weight(1f),
            color = if (warning) p.warning else p.accentText,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Адрес файла отчёта для других программ: `cacheDir/reports` отдаётся через FileProvider (`res/xml/file_paths.xml`). */
private fun reportUri(context: Context, file: File) =
    FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)

/** «Поделиться…»: системный выбор программы для PDF. Возвращает текст ошибки или `null`. */
private fun shareReport(context: Context, activity: Activity?, file: File, texts: MedTexts): String? = try {
    val uri = reportUri(context, file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, texts.string(R.string.report_share_subject))
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startFrom(context, activity, Intent.createChooser(send, texts.string(R.string.report_share_title)))
    null
} catch (e: ActivityNotFoundException) {
    texts.string(R.string.report_no_share)
} catch (e: IllegalArgumentException) {
    texts.string(R.string.report_error, e.localizedMessage ?: e.javaClass.simpleName)
}

/** «Открыть…»: просмотр PDF в программе, которую выберет система. Возвращает текст ошибки или `null`. */
private fun openReport(context: Context, activity: Activity?, file: File, texts: MedTexts): String? = try {
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(reportUri(context, file), "application/pdf")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startFrom(context, activity, view)
    null
} catch (e: ActivityNotFoundException) {
    texts.string(R.string.report_no_viewer)
} catch (e: IllegalArgumentException) {
    texts.string(R.string.report_error, e.localizedMessage ?: e.javaClass.simpleName)
}

private fun startFrom(context: Context, activity: Activity?, intent: Intent) {
    if (activity != null) {
        activity.startActivity(intent)
    } else {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
