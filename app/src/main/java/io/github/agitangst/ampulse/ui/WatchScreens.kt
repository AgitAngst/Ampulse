package io.github.agitangst.ampulse.ui

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.agitangst.ampulse.Ampulse
import io.github.agitangst.ampulse.Nav
import io.github.agitangst.ampulse.PageLayout
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.Route
import io.github.agitangst.ampulse.SettingsButton
import io.github.agitangst.ampulse.TabHeader
import io.github.agitangst.ampulse.domain.MedForm
import io.github.agitangst.ampulse.screenTitleStyle
import io.github.agitangst.ampulse.sync.ServerSync
import io.github.agitangst.ampulse.sync.ServerWatch
import io.github.agitangst.ampulse.sync.WatchCache
import io.github.agitangst.ampulse.sync.WatchCodes
import io.github.agitangst.ampulse.sync.WatchDay
import io.github.agitangst.ampulse.sync.WatchProfile
import io.github.agitangst.ampulse.sync.WatchRow
import io.github.agitangst.ampulse.sync.WatchState
import io.github.agitangst.ampulse.sync.WatchToday
import io.github.agitangst.ampulse.todayText
import io.github.agitangst.anvil.compose.AnvilCard
import io.github.agitangst.anvil.compose.AnvilTheme
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Просмотр чужих профилей через свой сервер Ampulse (openspec: server-watch, docs/dev/server-watch.md; макет — экраны
 * 62–73, `mockup/Watch*.dc.html`): список «Профили других людей», ввод кода приглашения и «Сегодня» чужого профиля.
 * Только просмотр; только сборка github.
 */

/** Значки просмотра: линии 1,8 на сетке 24, путь — набор Lucide, как в макете. */
internal object WatchIcons {
    /** Глаз: «только просмотр». */
    val Eye: ImageVector by lazy {
        ImageVector.Builder(
            name = "watch.eye",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            listOf("M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7Z", "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6Z").forEach {
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

/** Чужие профили, которые показываются (без «убранных»); в сборках магазинов — пусто. */
@Composable
internal fun watchedProfiles(): List<WatchProfile> {
    val view by ServerWatch.state.collectAsState()
    return if (ServerWatch.available) view.cache.shown else emptyList()
}

/**
 * Выбранный на «Сегодня» чужой профиль или `null` (выбран свой). Выбранный пропал (убран, выход, доступ закрыт) —
 * выбор возвращается к своему профилю.
 */
@Composable
internal fun currentWatched(): WatchProfile? {
    val context = LocalContext.current
    val view by ServerWatch.state.collectAsState()
    val id = if (ServerWatch.available) CurrentProfile.watchId else null
    val found = if (id == null) null else view.cache.shown.firstOrNull { it.id == id }
    LaunchedEffect(id, found == null, view.busy) {
        if (id != null && found == null && !view.busy) CurrentProfile.set(context, CurrentProfile.id)
    }
    return found
}

/** Время обновления для списка: «в 13:50» сегодня, иначе «5 нояб., 14:07»; по часам телефона. */
@Composable
private fun updatedText(cache: WatchCache): String {
    if (!cache.everRefreshed) return stringResource(R.string.watch_not_updated)
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(cache.phoneAt).atZone(zone)
    return if (at.toLocalDate() == LocalDate.now(zone)) {
        stringResource(R.string.watch_updated_at, rememberTodayTime().withZone(zone).format(at))
    } else {
        stringResource(R.string.watch_updated_on, formatMoment(context, cache.phoneAt))
    }
}

private fun formatMoment(context: Context, millis: Long): String =
    DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)

/** Строка-переход «Профили других людей ›» в подключённом «Сервере» (макет, экраны 52–54, 58–60). */
@Composable
internal fun WatchPeopleRow(onClick: () -> Unit) {
    val p = AnvilTheme.palette
    val view by ServerWatch.state.collectAsState()
    val count = view.cache.shown.size
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(p.card)
            .heightIn(min = 68.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Ampulse.colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(io.github.agitangst.ampulse.AmpulseIcons.Users, contentDescription = null, modifier = Modifier.size(22.dp), tint = p.accentText)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.watch_title),
                color = p.text,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                if (count == 0) stringResource(R.string.watch_row_none) else pluralStringResource(R.plurals.watch_row_count, count, count),
                color = p.weak,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Icon(MedIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp), tint = p.faint)
    }
}

// --- Список (экран 62–63) ---

@Composable
fun WatchListScreen(fromToday: Boolean, nav: Nav) {
    val status by ServerSync.status.collectAsState()
    // Без входа на сервер и в сборках магазинов экрана нет: сюда можно попасть только по устаревшему маршруту.
    if (!ServerWatch.available || !status.signedIn) {
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    val p = AnvilTheme.palette
    val view by ServerWatch.state.collectAsState()
    val cache = view.cache
    val people = cache.shown
    var asking by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { ServerWatch.refresh(ServerWatch.SCREEN_GAP_MS) }
    val updated = updatedText(cache)

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MedBackRow(
                stringResource(if (fromToday) R.string.tab_today else R.string.server_title),
                nav::back,
                Modifier.offset(x = (-8).dp),
            )
            Text(
                stringResource(R.string.watch_title),
                Modifier.semantics { heading() },
                color = p.text,
                style = screenTitleStyle(30),
            )
            AnvilCard {
                Text(stringResource(R.string.watch_intro), color = p.text, style = MaterialTheme.typography.bodyMedium)
            }
            if (cache.error != null && people.isNotEmpty()) MedNotice(stringResource(R.string.watch_list_problem))
            if (people.isEmpty()) {
                EmptyCard()
            } else {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.card).padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    people.forEachIndexed { index, person ->
                        key(person.id) {
                            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
                            PersonRow(person, updated) { asking = person.id }
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
        Column(Modifier.fillMaxWidth().background(p.bg).padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 20.dp)) {
            MedMainButton(stringResource(R.string.watch_enter_code), onClick = { nav.open(Route.WatchCode) })
        }
    }

    val target = people.firstOrNull { it.id == asking }
    if (target != null) {
        AlertDialog(
            onDismissRequest = { asking = null },
            containerColor = p.card,
            titleContentColor = p.text,
            textContentColor = p.weak,
            title = { Text(stringResource(R.string.watch_remove_title, target.name)) },
            text = { Text(stringResource(R.string.watch_remove_text)) },
            confirmButton = {
                TextButton(onClick = { asking = null; ServerWatch.remove(target.id) }) {
                    Text(stringResource(R.string.watch_remove), color = p.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { asking = null }) { Text(stringResource(R.string.watch_cancel), color = p.accentText) }
            },
        )
    }
}

@Composable
private fun EmptyCard() {
    val p = AnvilTheme.palette
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.card).padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(Ampulse.colors.accentSoft), contentAlignment = Alignment.Center) {
            Icon(WatchIcons.Eye, contentDescription = null, modifier = Modifier.size(24.dp), tint = p.accentText)
        }
        Text(stringResource(R.string.watch_empty_title), color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(
            stringResource(R.string.watch_empty_text),
            color = p.faint,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun PersonRow(person: WatchProfile, updated: String, onRemove: () -> Unit) {
    val p = AnvilTheme.palette
    val role = stringResource(if (person.role == "guardian") R.string.watch_role_guardian else R.string.watch_role_viewer)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileAvatar(person.name, 40.dp)
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(person.name, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("$role · $updated", color = p.faint, fontSize = 13.sp, lineHeight = 17.sp)
        }
        val description = stringResource(R.string.watch_remove_named, person.name)
        TodayButton(
            stringResource(R.string.watch_remove),
            onClick = onRemove,
            modifier = Modifier.semantics { contentDescription = description },
            border = BorderStroke(1.5.dp, p.borderStrong),
            fontSize = 14,
            paddingH = 14,
        )
    }
}

// --- Код приглашения (экран 64–65) ---

@Composable
fun WatchCodeScreen(nav: Nav) {
    val status by ServerSync.status.collectAsState()
    if (!ServerWatch.available || !status.signedIn) {
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    val p = AnvilTheme.palette
    val scope = rememberCoroutineScope()
    var code by rememberSaveable { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ServerWatch.Accepted?>(null) }
    val ready = WatchCodes.isComplete(code)
    val added = result as? ServerWatch.Accepted.Added

    fun accept() {
        if (!ready || working) return
        working = true
        result = null
        scope.launch {
            val r = ServerWatch.accept(code)
            working = false
            result = r
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
            MedBackRow(stringResource(R.string.watch_title), nav::back, Modifier.offset(x = (-8).dp))
            Text(
                stringResource(R.string.watch_code_title),
                Modifier.semantics { heading() },
                color = p.text,
                style = screenTitleStyle(30),
            )
            if (added != null) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.card).padding(horizontal = 16.dp, vertical = 24.dp)
                        .semantics(mergeDescendants = true) {},
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(48.dp).clip(CircleShape).background(Ampulse.colors.accentSoft), contentAlignment = Alignment.Center) {
                        Icon(TodayIcons.Check, contentDescription = null, modifier = Modifier.size(24.dp), tint = p.accentText)
                    }
                    Text(
                        stringResource(R.string.watch_done_title, added.name),
                        color = p.text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Text(
                        stringResource(R.string.watch_done_text),
                        color = p.faint,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            } else {
                AnvilCard {
                    Text(stringResource(R.string.watch_code_intro), color = p.text, style = MaterialTheme.typography.bodyMedium)
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FamilyField(
                        value = code,
                        onChange = { code = it.take(24); result = null },
                        label = stringResource(R.string.watch_code_label),
                        hint = stringResource(R.string.watch_code_placeholder),
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Done,
                        invalid = result != null,
                    )
                    Text(
                        stringResource(R.string.watch_code_hint),
                        Modifier.padding(horizontal = 4.dp),
                        color = p.faint,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                result?.let { MedNotice(acceptText(LocalContext.current, it)) }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
        Column(Modifier.fillMaxWidth().background(p.bg).padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 20.dp)) {
            if (added != null) {
                MedMainButton(stringResource(R.string.watch_to_list), onClick = nav::back)
            } else {
                MedMainButton(
                    stringResource(if (working) R.string.watch_accepting else R.string.watch_accept),
                    enabled = ready && !working,
                    onClick = ::accept,
                )
            }
        }
    }
}

private fun acceptText(context: Context, r: ServerWatch.Accepted): String = when (r) {
    is ServerWatch.Accepted.Added -> ""
    ServerWatch.Accepted.NotFound -> context.getString(R.string.watch_e_not_found)
    ServerWatch.Accepted.AlreadyHave -> context.getString(R.string.watch_e_have)
    is ServerWatch.Accepted.TooMany -> context.getString(R.string.watch_e_too_many, maxOf(1, (r.retryAfterSecs + 59) / 60))
    ServerWatch.Accepted.NoConnection -> context.getString(R.string.watch_e_net)
    is ServerWatch.Accepted.Other ->
        if (r.code == "signed_out") context.getString(R.string.watch_e_signed_out) else context.getString(R.string.watch_e_other, r.code)
}

// --- «Сегодня» чужого профиля (экраны 66–67) ---

/**
 * «Сегодня» чужого профиля: те же кольцо и строки, что у своего, но только просмотр — справа чип состояния вместо кнопки.
 * День и время — в поясе профиля; без связи «сейчас» — время данных ([WatchToday.nowMillis]).
 */
@Composable
internal fun WatchTodayScreen(nav: Nav, profile: WatchProfile, modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    val view by ServerWatch.state.collectAsState()
    val cache = view.cache
    LaunchedEffect(profile.id) { ServerWatch.refresh(ServerWatch.SCREEN_GAP_MS) }
    val tick = rememberTodayNow()
    val phoneNow = remember(tick, cache.serverTime, cache.phoneAt, cache.error) { System.currentTimeMillis() }
    val nowMs = WatchToday.nowMillis(cache, phoneNow)
    val day = remember(cache, nowMs / 60_000) { WatchToday.build(cache, profile.id, nowMs) }
    val zone = day?.zone ?: WatchToday.zoneOf(profile)
    val time = rememberTodayTime().withZone(zone)
    val locale = LocalConfiguration.current.locales[0]
    val dateShort = DateTimeFormatter.ofPattern(stringResource(R.string.today_date_short), locale).withZone(zone)

    PageLayout(
        modifier,
        header = {
            TabHeader(
                stringResource(R.string.tab_today),
                date = todayText(day?.date ?: LocalDate.now(zone)),
                action = { SettingsButton { nav.open(Route.Settings) } },
            )
        },
    ) {
        ProfileSwitcher(nav, withWatched = true)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            WatchBanner(cache, nowMs, view.busy, time, dateShort, zone)
            if (day != null && cache.everRefreshed) {
                if (day.rows.isEmpty()) {
                    AnvilCard(Modifier.semantics(mergeDescendants = true) {}) {
                        Text(stringResource(R.string.today_empty_title), color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.today_free_hint), color = p.weak, fontSize = 14.sp)
                    }
                } else {
                    WatchSummary(day, time)
                    day.rows.forEach { row -> key(row.intake.id) { WatchDoseRow(row, day, time) } }
                }
            }
        }
    }
}

/** Плашка над лентой: «Только просмотр · обновлено в …»; без связи — внимание и время данных (экран 67). */
@Composable
private fun WatchBanner(cache: WatchCache, nowMs: Long, busy: Boolean, time: DateTimeFormatter, dateShort: DateTimeFormatter, zone: ZoneId) {
    if (!cache.everRefreshed) {
        if (cache.error != null && !busy) MedNotice(stringResource(R.string.watch_no_data)) else WatchInfo(stringResource(R.string.watch_loading))
        return
    }
    val at = Instant.ofEpochSecond(cache.serverTime)
    val sameDay = at.atZone(zone).toLocalDate() == Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val stamp = if (sameDay) time.format(at) else dateShort.format(at) + ", " + time.format(at)
    when {
        cache.error == null ->
            WatchInfo(stringResource(if (sameDay) R.string.watch_view_only else R.string.watch_view_only_day, stamp))
        cache.error.startsWith("NoConnection") -> MedNotice(stringResource(R.string.watch_stale, stamp))
        else -> MedNotice(stringResource(R.string.watch_stale_other, stamp))
    }
}

/** Плашка мягким акцентом со значком глаза — как плашка копии расписания (`CopyBanner`). */
@Composable
private fun WatchInfo(text: String) {
    val p = AnvilTheme.palette
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ampulse.colors.accentSoft).padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(WatchIcons.Eye, contentDescription = null, modifier = Modifier.size(20.dp), tint = p.accentText)
        Text(text, color = p.accentText, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Карточка итога: кольцо «принято из всего» и строка про следующий или неотмеченный приём. */
@Composable
private fun WatchSummary(day: WatchDay, time: DateTimeFormatter) {
    val p = AnvilTheme.palette
    val overdue = day.overdue
    val next = day.next
    val title = if (overdue == null && next == null) {
        stringResource(R.string.today_summary_all_marked)
    } else {
        stringResource(R.string.today_summary_taken, day.taken, day.total)
    }
    val line = when {
        overdue != null -> stringResource(R.string.today_unmarked, medName(overdue), time.format(overdue.planned))
        next != null -> stringResource(R.string.today_next, medName(next), time.format(next.planned))
        else -> stringResource(R.string.today_all_done)
    }
    AnvilCard(Modifier.semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            DayRing(day.taken, day.total)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(line, color = p.weak, fontSize = 14.sp, lineHeight = 19.sp)
            }
        }
    }
}

private fun medName(row: WatchRow): String = todayMedName(row.med.name, row.med.dose)

/** «1 ч 5 мин» / «8 мин». */
@Composable
private fun spanText(minutes: Long): String {
    val m = maxOf(0L, minutes)
    return if (m >= 60) stringResource(R.string.watch_span_h_min, m / 60, m % 60) else stringResource(R.string.watch_span_min, m)
}

/** Строка приёма чужого профиля: плитка → имя и вторичная строка → чип состояния; ни кнопок, ни меню. */
@Composable
private fun WatchDoseRow(row: WatchRow, day: WatchDay, time: DateTimeFormatter) {
    val p = AnvilTheme.palette
    val c = Ampulse.colors
    val name = medName(row)
    val planned = time.format(row.planned)
    val note = row.med.note.trim()
    val minutes = java.time.Duration.between(row.planned, day.now).toMinutes()
    val (sub, subColor) = when (row.state) {
        WatchState.Taken -> stringResource(R.string.today_sub_taken, time.format(row.takenAt ?: row.planned)) to p.accentText
        WatchState.Skipped -> (if (note.isEmpty()) planned else stringResource(R.string.watch_sub_note, planned, note)) to p.weak
        WatchState.Overdue -> stringResource(R.string.watch_sub_ago, planned, spanText(minutes)) to p.warning
        WatchState.Due ->
            (if (minutes >= 0) stringResource(R.string.watch_sub_ago, planned, spanText(minutes))
            else stringResource(R.string.watch_sub_in, planned, spanText(-minutes))) to p.text
        WatchState.Later -> (if (note.isEmpty()) planned else stringResource(R.string.watch_sub_note, planned, note)) to p.faint
    }
    val chip = when (row.state) {
        WatchState.Taken -> stringResource(R.string.watch_chip_taken)
        WatchState.Skipped -> stringResource(R.string.today_state_skipped)
        WatchState.Due -> stringResource(R.string.watch_chip_due)
        WatchState.Overdue -> stringResource(R.string.today_state_overdue)
        WatchState.Later -> stringResource(R.string.watch_chip_later)
    }
    val (chipBg, chipFg) = when (row.state) {
        WatchState.Taken, WatchState.Due -> c.accentSoft to p.accentText
        WatchState.Overdue -> c.warnSoft to p.warning
        WatchState.Skipped, WatchState.Later -> p.raised to p.weak
    }
    val shape = RoundedCornerShape(16.dp)
    val form = if (row.med.form == "injection") MedForm.Injection else MedForm.Tablet
    val description = stringResource(R.string.watch_state_named, "$name, $sub", chip)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .clip(shape)
            .background(p.card)
            .then(if (row.state == WatchState.Overdue) Modifier.border(1.5.dp, c.warnLine, shape) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(start = 14.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FormTile(form, accent = row.state == WatchState.Taken)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(name, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(sub, color = subColor, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(
            chip,
            Modifier.clip(RoundedCornerShape(12.dp)).background(chipBg).padding(horizontal = 10.dp, vertical = 5.dp),
            color = chipFg,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
