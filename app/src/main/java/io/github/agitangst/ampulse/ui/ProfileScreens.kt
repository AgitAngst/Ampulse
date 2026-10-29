package io.github.agitangst.ampulse.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import io.github.agitangst.ampulse.Ampulse
import io.github.agitangst.ampulse.AmpulseIcons
import io.github.agitangst.ampulse.AppGraph
import io.github.agitangst.ampulse.Nav
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.Route
import io.github.agitangst.ampulse.domain.Profile
import io.github.agitangst.ampulse.domain.ProfileKind
import io.github.agitangst.ampulse.screenTitleStyle
import io.github.agitangst.anvil.compose.AnvilTheme
import io.github.agitangst.anvil.compose.SectionTitle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

/** Чьи лекарства сейчас на экранах: 0 — «Я». Хранится в `SharedPreferences("ampulse")`, ключ `profile_current`. */
object CurrentProfile {
    private const val KEY = "profile_current"

    var id by mutableLongStateOf(0L)
        private set

    fun load(context: Context) {
        id = context.getSharedPreferences("ampulse", Context.MODE_PRIVATE).getLong(KEY, 0L)
    }

    fun set(context: Context, profileId: Long) {
        id = profileId
        context.getSharedPreferences("ampulse", Context.MODE_PRIVATE).edit { putLong(KEY, profileId) }
    }
}

/** Самый большой вес питомца, кг: больше — скорее опечатка. */
internal const val MAX_WEIGHT_KG = 200.0

/** Самое длинное имя профиля. */
private const val MAX_PROFILE_NAME = 40

private const val PROFILE_SEP = " · "

/** Вес из поля: запятая или точка, больше нуля и не больше [MAX_WEIGHT_KG]; иначе `null`. */
internal fun parseWeightKg(text: String): Double? =
    text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 && it <= MAX_WEIGHT_KG }

/** Вес числом по языку, без лишних нулей: «4,2» / «4.2», «18». */
internal fun weightNumber(kg: Double, locale: Locale): String {
    val format = NumberFormat.getNumberInstance(locale)
    format.isGroupingUsed = false
    format.maximumFractionDigits = 2
    return format.format(kg)
}

/** Значки профилей: линии 1,8 на сетке 24, пути — набор Lucide (docs/ui-guideline.md, §5). Цвет задаёт вызывающий. */
private object ProfileGlyphs {
    val User: ImageVector by lazy {
        icon("profile.user", "M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2", "M16 7a4 4 0 1 1-8 0a4 4 0 0 1 8 0Z")
    }

    val Cat: ImageVector by lazy {
        icon(
            "profile.cat",
            "M12 5c.67 0 1.35.09 2 .26 1.78-2 5.03-2.84 6.42-2.26 1.4.58-.42 7-.42 7 .57 1.07 1 2.24 1 3.44" +
                "C21 17.9 16.97 21 12 21s-9-3-9-7.56c0-1.25.5-2.4 1-3.44 0 0-1.89-6.42-.5-7 1.39-.58 4.72.23 6.5 2.23" +
                "A9.04 9.04 0 0 1 12 5Z",
            "M8 14v.5", "M16 14v.5", "M11.25 16.25h1.5L12 17l-.75-.75Z",
        )
    }

    val Dog: ImageVector by lazy {
        icon(
            "profile.dog",
            "M11.25 16.25h1.5L12 17z", "M16 14v.5", "M8 14v.5",
            "M4.42 11.247A13.152 13.152 0 0 0 4 14.556C4 18.728 7.582 21 12 21s8-2.272 8-6.444" +
                "a11.702 11.702 0 0 0-.493-3.309",
            "M10 5.172C10 3.782 8.423 2.679 6.5 3c-2.823.47-4.113 6.006-4 7 .08.703 1.725 1.722 3.656 1 " +
                "1.261-.472 1.96-1.45 2.344-2.5",
            "M14.267 5.172c0-1.39 1.577-2.493 3.5-2.172 2.823.47 4.113 6.006 4 7-.08.703-1.725 1.722-3.656 1 " +
                "-1.261-.472-1.855-1.45-2.239-2.5",
        )
    }

    val Check: ImageVector by lazy { icon("profile.check", "M20 6 9 17l-5-5") }

    val ChevronDown: ImageVector by lazy { icon("profile.chevron_down", "m6 9 6 6 6-6") }

    fun of(kind: ProfileKind): ImageVector = when (kind) {
        ProfileKind.Person -> User
        ProfileKind.Cat, ProfileKind.Other -> Cat
        ProfileKind.Dog -> Dog
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

@Composable
private fun profileKindName(kind: ProfileKind): String = stringResource(
    when (kind) {
        ProfileKind.Person -> R.string.profile_kind_person
        ProfileKind.Cat -> R.string.profile_kind_cat
        ProfileKind.Dog -> R.string.profile_kind_dog
        ProfileKind.Other -> R.string.profile_kind_other
    },
)

@Composable
private fun profileMedsCount(count: Int): String =
    if (count == 0) stringResource(R.string.profile_meds_none) else pluralStringResource(R.plurals.profile_meds_count, count, count)

/** Первая буква имени заглавной (с учётом пар суррогатов); пустое имя — «?». */
private fun profileInitial(name: String): String {
    val text = name.trim()
    if (text.isEmpty()) return "?"
    return String(Character.toChars(text.codePointAt(0))).uppercase()
}

/**
 * Кружок с первой буквой имени на мягком акценте (макет, экраны питомцев: кружок-инициал); размер — [size]. Для
 * TalkBack — украшение: имя читается рядом.
 */
@Composable
internal fun ProfileAvatar(name: String, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(CircleShape).background(Ampulse.colors.accentSoft).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            profileInitial(name),
            color = AnvilTheme.palette.accentText,
            fontSize = (size.value * 0.42f).sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Переключатель профиля (docs/ui-guideline.md, §6): кнопка 48 dp с кружком-инициалом, именем и стрелкой ▾; по касанию
 * — меню профилей (текущий с галочкой) и «Профили…». Если, кроме «Я», профилей нет — ничего не рисует. Текущий профиль
 * удалён — возвращает «Я».
 */
@Composable
internal fun ProfileSwitcher(nav: Nav, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val p = AnvilTheme.palette
    val profiles by remember { AppGraph.repository.profiles }.collectAsState(initial = null)
    val others = profiles
    val currentId = CurrentProfile.id
    LaunchedEffect(others, currentId) {
        if (others != null && currentId != 0L && others.none { it.id == currentId }) CurrentProfile.set(context, 0L)
    }
    if (others.isNullOrEmpty()) return

    val meName = stringResource(R.string.profile_me)
    val all = remember(others, meName) { listOf(Profile(0L, meName, ProfileKind.Person)) + others }
    val current = all.firstOrNull { it.id == currentId } ?: all.first()
    var open by remember { mutableStateOf(false) }
    val description = stringResource(R.string.profile_switcher, current.name)
    val shape = RoundedCornerShape(24.dp)

    Box(modifier) {
        Row(
            Modifier
                .heightIn(min = 48.dp)
                .clip(shape)
                .background(p.card)
                .border(1.5.dp, p.borderStrong, shape)
                .clickable(onClickLabel = stringResource(R.string.profile_switch_action), role = Role.Button) { open = true }
                .semantics { contentDescription = description }
                .padding(start = 8.dp, end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfileAvatar(current.name, 32.dp)
            Text(
                current.name,
                Modifier.widthIn(max = 220.dp),
                color = p.text,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(ProfileGlyphs.ChevronDown, contentDescription = null, modifier = Modifier.size(18.dp), tint = p.weak)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            all.forEach { profile ->
                val isCurrent = profile.id == current.id
                DropdownMenuItem(
                    text = {
                        Text(
                            profile.name,
                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = {
                        open = false
                        CurrentProfile.set(context, profile.id)
                    },
                    modifier = Modifier.semantics { selected = isCurrent },
                    leadingIcon = { ProfileAvatar(profile.name, 32.dp) },
                    trailingIcon = {
                        if (isCurrent) Icon(ProfileGlyphs.Check, contentDescription = null, modifier = Modifier.size(20.dp), tint = p.accentText)
                    },
                )
            }
            HorizontalDivider(color = p.border)
            DropdownMenuItem(
                text = { Text(stringResource(R.string.profile_manage)) },
                onClick = {
                    open = false
                    nav.open(Route.Profiles)
                },
                leadingIcon = { Icon(AmpulseIcons.Users, contentDescription = null, modifier = Modifier.size(22.dp), tint = p.text) },
            )
        }
    }
}

/**
 * «Для кого» в карточке лекарства: чипы «Я» и профилей со значком вида. Если, кроме «Я», профилей нет — ничего не
 * рисует (лекарство остаётся своим).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProfilePicker(selectedId: Long, onSelect: (Long) -> Unit, modifier: Modifier = Modifier) {
    val profiles by remember { AppGraph.repository.profiles }.collectAsState(initial = null)
    val others = profiles
    if (others.isNullOrEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.profile_for_whom), Modifier.padding(horizontal = 4.dp))
        FlowRow(
            Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MedChip(
                stringResource(R.string.profile_me),
                selected = selectedId == 0L,
                onClick = { onSelect(0L) },
                icon = ProfileGlyphs.User,
            )
            others.forEach { profile ->
                MedChip(
                    profile.name,
                    selected = selectedId == profile.id,
                    onClick = { onSelect(profile.id) },
                    icon = ProfileGlyphs.of(profile.kind),
                )
            }
        }
    }
}

/**
 * Профили (docs/ui-guideline.md, §6): «Я» первым, ниже близкие и питомцы — кружок-инициал, имя, вид, вес и число
 * лекарств; касание строки открывает правку, «Добавить профиль» внизу. «Я» не правится и не удаляется.
 */
@Composable
fun ProfilesScreen(nav: Nav) {
    val repo = AppGraph.repository
    val p = AnvilTheme.palette
    val locale = LocalConfiguration.current.locales[0]
    val profiles by remember { repo.profiles }.collectAsState(initial = null)
    val medicines by remember { repo.medicines }.collectAsState(initial = emptyList())
    val list = profiles

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
            MedBackRow(stringResource(R.string.settings_back), nav::back, Modifier.offset(x = (-8).dp))
            Text(
                stringResource(R.string.profiles_title),
                Modifier.semantics { heading() },
                color = p.text,
                style = screenTitleStyle(30),
            )
            if (list != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfileRow(
                        stringResource(R.string.profile_me),
                        listOf(stringResource(R.string.profile_me_hint), profileMedsCount(medicines.count { it.profileId == 0L }))
                            .joinToString(PROFILE_SEP),
                        onClick = null,
                    )
                    list.forEach { profile ->
                        val weight = profile.weightKg?.let { stringResource(R.string.profile_weight_kg, weightNumber(it, locale)) }
                        val sub = listOfNotNull(
                            profileKindName(profile.kind),
                            weight,
                            profileMedsCount(medicines.count { it.profileId == profile.id }),
                        )
                        ProfileRow(profile.name, sub.joinToString(PROFILE_SEP), onClick = { nav.open(Route.ProfileEdit(profile.id)) })
                    }
                }
                if (list.isEmpty()) {
                    Text(
                        stringResource(R.string.profiles_empty_hint),
                        Modifier.padding(horizontal = 4.dp),
                        color = p.weak,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
        Column(Modifier.fillMaxWidth().background(p.bg).padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 20.dp)) {
            MedMainButton(stringResource(R.string.profiles_add), onClick = { nav.open(Route.ProfileEdit(0)) })
        }
    }
}

/** Строка списка: кружок-инициал 40 → имя → вторичная строка → стрелка ›; без [onClick] («Я») не нажимается. */
@Composable
private fun ProfileRow(name: String, sub: String, onClick: (() -> Unit)?) {
    val p = AnvilTheme.palette
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(p.card)
            .heightIn(min = 64.dp)
            .then(
                if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick)
                else Modifier.semantics(mergeDescendants = true) {},
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileAvatar(name, 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                name,
                color = p.text,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(sub, color = p.weak, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (onClick != null) {
            Icon(MedIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp), tint = p.faint)
        }
    }
}

/**
 * Новый профиль (`id` = 0) или правка: имя, вид (человек, кошка, собака), у питомца — вес в кг. «Сохранить» недоступно,
 * пока нет имени или вес не число; у существующего внизу «Удалить профиль» с вопросом, где названо число лекарств,
 * которые удалятся вместе с ним. Удалили выбранный на экранах профиль — выбирается «Я».
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileEditScreen(id: Long, nav: Nav) {
    val repo = AppGraph.repository
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = AnvilTheme.palette
    val locale = LocalConfiguration.current.locales[0]
    var name by rememberSaveable { mutableStateOf("") }
    var kindKey by rememberSaveable { mutableStateOf(ProfileKind.Person.name) }
    var weight by rememberSaveable { mutableStateOf("") }
    var loaded by rememberSaveable(id) { mutableStateOf(id == 0L) }
    // Сколько лекарств удалится вместе с профилем; –1 — вопроса на экране нет.
    var deleteCount by rememberSaveable { mutableStateOf(-1) }
    var busy by remember { mutableStateOf(false) }
    val kind = ProfileKind.entries.firstOrNull { it.name == kindKey } ?: ProfileKind.Person
    val pet = kind != ProfileKind.Person

    LaunchedEffect(id) {
        if (loaded) return@LaunchedEffect
        val profile = repo.profiles.first().firstOrNull { it.id == id }
        if (profile == null) {
            nav.back()
        } else {
            name = profile.name
            kindKey = profile.kind.name
            weight = profile.weightKg?.let { weightNumber(it, locale) }.orEmpty()
            loaded = true
        }
    }

    val nameMissing = name.isBlank()
    val weightBad = pet && weight.isNotBlank() && parseWeightKg(weight) == null
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
                stringResource(if (id == 0L) R.string.profile_cancel else R.string.profiles_title),
                nav::back,
                Modifier.offset(x = (-8).dp),
            )
            Text(
                stringResource(if (id == 0L) R.string.profile_new_title else R.string.profile_edit_title),
                Modifier.semantics { heading() },
                color = p.text,
                style = screenTitleStyle(30),
            )
            if (loaded) {
                MedTextField(
                    name,
                    { v -> name = v.take(MAX_PROFILE_NAME) },
                    stringResource(R.string.profile_field_name),
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = if (pet) ImeAction.Next else ImeAction.Done,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(stringResource(R.string.profile_section_kind), Modifier.padding(horizontal = 4.dp))
                    FlowRow(
                        Modifier.fillMaxWidth().selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // «Другой питомец» выбрать нельзя, но у уже сохранённого он остаётся выбранным.
                        val kinds = listOf(ProfileKind.Person, ProfileKind.Cat, ProfileKind.Dog) +
                            if (kind == ProfileKind.Other) listOf(ProfileKind.Other) else emptyList()
                        kinds.forEach { k ->
                            MedChip(profileKindName(k), selected = kind == k, onClick = { kindKey = k.name }, icon = ProfileGlyphs.of(k))
                        }
                    }
                }
                if (pet) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProfileWeightField(
                            weight,
                            { v -> weight = v.filter { it.isDigit() || it == '.' || it == ',' }.take(7) },
                            error = weightBad,
                        )
                        Text(
                            stringResource(R.string.profile_weight_note),
                            Modifier.padding(horizontal = 4.dp),
                            color = p.weak,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (id != 0L) {
                    MedOutlineButton(
                        stringResource(R.string.profile_delete),
                        onClick = {
                            scope.launch { deleteCount = repo.medicines.first().count { it.profileId == id } }
                        },
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
            if (loaded && nameMissing) {
                Text(
                    stringResource(R.string.profile_need_name),
                    Modifier.fillMaxWidth(),
                    color = p.weak,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
            MedMainButton(
                stringResource(R.string.profile_save),
                enabled = loaded && !nameMissing && !weightBad && !busy,
                onClick = {
                    busy = true
                    val saved = Profile(id, name.trim(), kind, if (pet) parseWeightKg(weight) else null)
                    scope.launch {
                        repo.saveProfile(saved)
                        nav.back()
                    }
                },
            )
        }
    }

    if (deleteCount >= 0) {
        val count = deleteCount
        AlertDialog(
            onDismissRequest = { deleteCount = -1 },
            containerColor = p.card,
            titleContentColor = p.text,
            textContentColor = p.weak,
            title = { Text(stringResource(R.string.profile_delete_title, name.trim())) },
            text = {
                Text(
                    if (count == 0) {
                        stringResource(R.string.profile_delete_text_none)
                    } else {
                        pluralStringResource(R.plurals.profile_delete_text, count, count)
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteCount = -1
                        busy = true
                        scope.launch {
                            repo.deleteProfile(id)
                            if (CurrentProfile.id == id) CurrentProfile.set(context, 0L)
                            nav.back()
                        }
                    },
                ) { Text(stringResource(R.string.profile_delete_confirm), color = p.danger) }
            },
            dismissButton = {
                TextButton(onClick = { deleteCount = -1 }) { Text(stringResource(R.string.profile_cancel), color = p.accentText) }
            },
        )
    }
}

/**
 * Поле веса питомца: цифровая клавиатура, «кг» справа; неверное — рамка и строка со значком под полем (красного в
 * программе нет — цвет внимания).
 */
@Composable
private fun ProfileWeightField(value: String, onChange: (String) -> Unit, error: Boolean, modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        label = { Text(stringResource(R.string.profile_field_weight)) },
        placeholder = { Text(stringResource(R.string.profile_weight_hint)) },
        suffix = { Text(stringResource(R.string.profile_weight_unit)) },
        trailingIcon = if (error) {
            { Icon(MedIcons.Warning, contentDescription = null, modifier = Modifier.size(20.dp), tint = p.warning) }
        } else {
            null
        },
        supportingText = if (error) {
            { Text(stringResource(R.string.profile_need_weight), color = p.warning) }
        } else {
            null
        },
        isError = error,
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
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
            errorPlaceholderColor = p.faint,
        ),
    )
}
