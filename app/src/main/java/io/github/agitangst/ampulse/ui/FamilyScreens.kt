package io.github.agitangst.ampulse.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.agitangst.ampulse.Ampulse
import io.github.agitangst.ampulse.AmpulseIcons
import io.github.agitangst.ampulse.AppGraph
import io.github.agitangst.ampulse.Nav
import io.github.agitangst.ampulse.PageLayout
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.Route
import io.github.agitangst.ampulse.Store
import io.github.agitangst.ampulse.TabHeader
import io.github.agitangst.ampulse.domain.Contact
import io.github.agitangst.ampulse.domain.FamilyTexts
import io.github.agitangst.ampulse.domain.MedForm
import io.github.agitangst.ampulse.domain.Medicine
import io.github.agitangst.ampulse.screenTitleStyle
import io.github.agitangst.anvil.compose.AnvilCard
import io.github.agitangst.anvil.compose.AnvilTheme
import io.github.agitangst.anvil.compose.SectionTitle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Вкладка «Близкие» (макет, экраны Access и Care). Сверху — переходы (экстренная карта, отчёт врачу), затем близкие,
 * которым идут SMS (пока никого нет — пустое состояние с «Добавить близкого»), что именно уходит по SMS, QR
 * расписания (показать мой, сканировать чужой) и копии чужих расписаний. Касание копии делает её текущим профилем.
 */
@Composable
fun FamilyTab(nav: Nav, modifier: Modifier = Modifier) {
    val repo = AppGraph.repository
    val context = LocalContext.current
    val p = AnvilTheme.palette
    val texts = rememberMedTexts()
    val contacts by remember { repo.contacts }.collectAsState(initial = null)
    val profiles by remember { repo.profiles }.collectAsState(initial = emptyList())
    val smsOk = rememberSmsReady()
    val askSms = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { smsOk.value = it }
    var switched by rememberSaveable { mutableStateOf<String?>(null) }
    // «Открыт профиль …» гаснет через 4 с (общий гайдлайн, §7).
    LaunchedEffect(switched) {
        if (switched != null) {
            delay(4000)
            switched = null
        }
    }
    val add = stringResource(R.string.family_add)
    PageLayout(
        modifier,
        header = {
            TabHeader(
                stringResource(R.string.tab_family),
                action = { MedRoundButton(MedIcons.Plus, add, onClick = { nav.open(Route.ContactEdit(0)) }) },
            )
        },
    ) {
        val list = contacts
        if (list == null) {
            // Данные ещё читаются: пустое состояние не мигаем.
            Spacer(Modifier.weight(1f))
        } else {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                switched?.let { FamilyDone(stringResource(R.string.family_switched, it)) }
                Text(
                    stringResource(R.string.family_subtitle),
                    Modifier.padding(horizontal = 4.dp),
                    color = p.faint,
                    style = MaterialTheme.typography.bodyMedium,
                )
                FamilyRow(
                    leading = { FamilyTile(FamilyIcons.Cross, warn = true) },
                    title = stringResource(R.string.family_emergency_title),
                    sub = stringResource(R.string.family_emergency_sub),
                    onClick = { nav.open(Route.Emergency) },
                )
                FamilyRow(
                    leading = { FamilyTile(TodayIcons.FileText) },
                    title = stringResource(R.string.family_report_title),
                    sub = stringResource(R.string.family_report_sub),
                    onClick = { nav.open(Route.Report) },
                )

                // Близкие, которым идут SMS.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(stringResource(R.string.family_contacts_title), Modifier.padding(horizontal = 4.dp))
                    if (list.isEmpty()) {
                        EmptyContacts(onAdd = { nav.open(Route.ContactEdit(0)) })
                    } else {
                        list.forEach { c ->
                            key(c.id) {
                                FamilyRow(
                                    leading = { FamilyAvatar(c.name) },
                                    title = c.name,
                                    sub = c.phone + "\n" + smsSummary(c),
                                    onClick = { nav.open(Route.ContactEdit(c.id)) },
                                )
                            }
                        }
                        MedOutlineButton(add, onClick = { nav.open(Route.ContactEdit(0)) }, icon = MedIcons.Plus)
                        Text(
                            stringResource(R.string.family_add_hint),
                            Modifier.fillMaxWidth(),
                            color = p.faint,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                // SMS не уйдут без разрешения — говорим сразу, а не после пропущенного приёма.
                if (!smsOk.value && list.any { it.smsMissed || it.smsEmergency }) {
                    FamilyAttention(
                        stringResource(R.string.family_sms_banner_title),
                        stringResource(R.string.family_sms_banner_hint),
                        stringResource(R.string.family_sms_allow),
                        onAction = { askSms.launch(Manifest.permission.SEND_SMS) },
                    )
                }
                if (list.isNotEmpty()) SmsPreview()

                // QR расписания.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(stringResource(R.string.family_qr_title), Modifier.padding(horizontal = 4.dp))
                    FamilyRow(
                        leading = { FamilyTile(FamilyIcons.Qr) },
                        title = stringResource(R.string.family_qr_show),
                        sub = stringResource(R.string.family_qr_show_sub),
                        onClick = { nav.open(Route.ShareQr) },
                    )
                    FamilyRow(
                        leading = { FamilyTile(FamilyIcons.Scan) },
                        title = stringResource(R.string.family_qr_scan),
                        sub = stringResource(R.string.family_qr_scan_sub),
                        onClick = { nav.open(Route.CareScan) },
                    )
                }

                // Копии чужих расписаний: касание — сделать профиль текущим.
                val copies = profiles.filter { it.watchOnly }
                if (copies.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle(stringResource(R.string.family_copies_title), Modifier.padding(horizontal = 4.dp))
                        copies.forEach { profile ->
                            key(profile.id) {
                                val updated = FamilyPrefs.copyUpdated(context, profile.id)
                                val sub = if (updated == null) {
                                    stringResource(R.string.family_copy_plain)
                                } else {
                                    val day = if (updated == LocalDate.now()) stringResource(R.string.family_copy_updated_today) else texts.date(updated)
                                    stringResource(R.string.family_copy_updated, day)
                                }
                                val current = CurrentProfile.id == profile.id
                                FamilyRow(
                                    leading = { FamilyTile(AmpulseIcons.Users) },
                                    title = profile.name,
                                    sub = sub,
                                    onClick = {
                                        CurrentProfile.set(context, profile.id)
                                        switched = profile.name
                                    },
                                    trailing = { if (current) CurrentMark() else FamilyChevron() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Пустой список близких (общий гайдлайн, §7): значок, заголовок про этот список, что сделать и кнопка. */
@Composable
private fun EmptyContacts(onAdd: () -> Unit) {
    val p = AnvilTheme.palette
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.card).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(Ampulse.colors.accentSoft), contentAlignment = Alignment.Center) {
            Icon(AmpulseIcons.Users, contentDescription = null, modifier = Modifier.size(26.dp), tint = p.accentText)
        }
        Text(
            stringResource(R.string.family_empty_title),
            color = p.text,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.family_empty_hint),
            color = p.weak,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        MedMainButton(stringResource(R.string.family_add), onAdd)
        Text(
            stringResource(R.string.family_add_hint),
            color = p.faint,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

/** Отметка «открыта сейчас» вместо стрелки в строке копии: галочка и слово (цвет не единственный носитель). */
@Composable
private fun CurrentMark() {
    val p = AnvilTheme.palette
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(TodayIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp), tint = p.accentText)
        Text(
            stringResource(R.string.family_copy_current),
            color = p.accentText,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

/** Какие SMS включены у близкого — второй строкой в списке. */
@Composable
private fun smsSummary(c: Contact): String = stringResource(
    when {
        c.smsMissed && c.smsEmergency -> R.string.family_sub_both
        c.smsMissed -> R.string.family_sub_missed
        c.smsEmergency -> R.string.family_sub_card
        else -> R.string.family_sub_none
    },
)

/**
 * «Что уходит по SMS» (макет, экран Access): два настоящих текста — такие же, какие получит близкий, — и строка про то,
 * когда и кому они уходят. Лекарство в примере выдуманное.
 */
@Composable
private fun SmsPreview() {
    val p = AnvilTheme.palette
    val ru = LocalConfiguration.current.locales[0].language == "ru"
    val who = stringResource(R.string.family_sms_me)
    val sample = Medicine(
        0, stringResource(R.string.family_sample_name), stringResource(R.string.family_sample_strength), MedForm.Tablet, "",
    )
    val missed = FamilyTexts.missed(ru, who, sample, LocalDateTime.of(2026, 9, 26, 8, 0))
    val opened = FamilyTexts.emergencyOpened(ru, who, LocalDateTime.of(2026, 9, 26, 14, 32))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.family_sms_title), Modifier.padding(horizontal = 4.dp))
        AnvilCard {
            SmsSample(stringResource(R.string.family_sms_missed_title), missed)
            SmsSample(stringResource(R.string.family_sms_card_title), opened)
            Text(stringResource(R.string.family_sms_note), color = p.faint, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Пример одного SMS: плитка значка, название случая и сам текст на подложке «пузырём». */
@Composable
private fun SmsSample(title: String, text: String) {
    val p = AnvilTheme.palette
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        FamilyTile(FamilyIcons.Message)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = p.text, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
            Text(
                text,
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.bg).padding(horizontal = 12.dp, vertical = 8.dp),
                color = p.weak,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * Близкий (макет, экран Access): имя, телефон и два переключателя — «SMS о пропуске важного приёма» и «SMS, если открыли
 * экстренную карту». «Сохранить» недоступно, пока нет имени или похожего на телефон номера; у существующего внизу
 * «Удалить близкого» с вопросом. Первое включение SMS просит у системы разрешение `SEND_SMS` (только там, где SMS уходит
 * сама); отказ объясняется под переключателями.
 */
@Composable
fun ContactEditScreen(id: Long, nav: Nav) {
    val repo = AppGraph.repository
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = AnvilTheme.palette
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var smsMissed by rememberSaveable { mutableStateOf(true) }
    var smsCard by rememberSaveable { mutableStateOf(true) }
    var loaded by rememberSaveable(id) { mutableStateOf(id == 0L) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    // Разрешение на SMS спрашивается один раз за открытие экрана; после отказа — объяснение, а не новый диалог.
    var smsAsked by rememberSaveable { mutableStateOf(false) }
    var smsDenied by rememberSaveable { mutableStateOf(false) }
    var saveAfterAsk by rememberSaveable { mutableStateOf(false) }
    val smsOk = rememberSmsReady()

    LaunchedEffect(id) {
        if (loaded) return@LaunchedEffect
        val contact = repo.contactsNow().firstOrNull { it.id == id }
        if (contact == null) {
            nav.back()
        } else {
            name = contact.name
            phone = contact.phone
            smsMissed = contact.smsMissed
            smsCard = contact.smsEmergency
            loaded = true
        }
    }

    fun save() {
        busy = true
        val contact = Contact(id, name.trim(), phone.trim(), smsMissed, smsCard)
        scope.launch {
            repo.saveContact(contact)
            // Есть ли кому слать SMS, влияет на «проверку» после повторов напоминания.
            AppGraph.reminders.reschedule()
            nav.back()
        }
    }

    val askSms = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        smsOk.value = ok
        smsDenied = !ok
        if (saveAfterAsk) {
            saveAfterAsk = false
            save()
        }
    }

    val wantsSms = smsMissed || smsCard
    val needsAsk = Store.SILENT_SMS && !smsOk.value && !smsAsked
    /** Включили SMS, а разрешения нет — спросить (один раз). */
    fun askIfNeeded(afterAsk: Boolean) {
        if (needsAsk) {
            smsAsked = true
            saveAfterAsk = afterAsk
            askSms.launch(Manifest.permission.SEND_SMS)
        }
    }

    val nameError = name.isBlank()
    val phoneError = !FamilyPhone.valid(phone)
    val problem = when {
        nameError -> R.string.family_need_name
        phoneError -> R.string.family_need_phone
        else -> null
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
            // Сдвиг влево на поля строки: стрелка и слово встают на общее поле 20 dp, а цель нажатия остаётся широкой.
            MedBackRow(
                stringResource(if (id == 0L) R.string.family_cancel else R.string.tab_family),
                nav::back,
                Modifier.offset(x = (-8).dp),
            )
            Text(
                stringResource(if (id == 0L) R.string.family_new_title else R.string.family_edit_title),
                Modifier.semantics { heading() },
                color = p.text,
                style = screenTitleStyle(30),
            )
            if (loaded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FamilyField(
                        value = name,
                        onChange = { name = it.take(40) },
                        label = stringResource(R.string.family_name),
                        hint = stringResource(R.string.family_name_hint),
                        capitalization = KeyboardCapitalization.Words,
                    )
                    FamilyField(
                        value = phone,
                        onChange = { phone = FamilyPhone.typed(it) },
                        label = stringResource(R.string.family_phone),
                        hint = stringResource(R.string.family_phone_hint),
                        keyboardType = KeyboardType.Phone,
                        imeAction = ImeAction.Done,
                        // Ошибка — только когда человек уже что-то ввёл; пустое поле объясняет строка над «Сохранить».
                        error = if (phone.isNotBlank() && phoneError) stringResource(R.string.family_phone_error) else null,
                    )
                }
                AnvilCard {
                    MedSwitchRow(
                        stringResource(R.string.family_sw_missed_title),
                        stringResource(R.string.family_sw_missed_hint),
                        smsMissed,
                        { v ->
                            smsMissed = v
                            if (v) askIfNeeded(afterAsk = false)
                        },
                    )
                    MedSwitchRow(
                        stringResource(R.string.family_sw_card_title),
                        stringResource(R.string.family_sw_card_hint),
                        smsCard,
                        { v ->
                            smsCard = v
                            if (v) askIfNeeded(afterAsk = false)
                        },
                    )
                }
                if (Store.SILENT_SMS && wantsSms && smsDenied && !smsOk.value) {
                    MedNotice(stringResource(R.string.family_sms_denied))
                    MedOutlineButton(stringResource(R.string.scan_perm_settings), onClick = { openFamilyAppSettings(context) })
                }
                if (id != 0L) {
                    MedOutlineButton(
                        stringResource(R.string.family_delete),
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
                    stringResource(problem),
                    Modifier.fillMaxWidth(),
                    color = p.weak,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
            MedMainButton(
                stringResource(R.string.family_save),
                enabled = loaded && problem == null && !busy,
                onClick = {
                    if (wantsSms && needsAsk) askIfNeeded(afterAsk = true) else save()
                },
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = p.card,
            titleContentColor = p.text,
            textContentColor = p.weak,
            title = { Text(stringResource(R.string.family_delete_title, name.trim())) },
            text = { Text(stringResource(R.string.family_delete_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        busy = true
                        scope.launch {
                            repo.deleteContact(id)
                            AppGraph.reminders.reschedule()
                            nav.back()
                        }
                    },
                ) { Text(stringResource(R.string.family_delete_confirm), color = p.danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.family_cancel), color = p.accentText) }
            },
        )
    }
}

/**
 * Имя владельца, если текущий профиль — копия чужого расписания (`Profile.watchOnly`); иначе `null`. «Сегодня» по нему
 * прячет отметки и показывает [CopyBanner].
 */
@Composable
internal fun watchOnlyOwner(): String? {
    val profiles by remember { AppGraph.repository.profiles }.collectAsState(initial = emptyList())
    val current = CurrentProfile.id
    return profiles.firstOrNull { it.id == current && it.watchOnly }?.name
}

/** Плашка над лентой «Сегодня» у копии: чьё расписание и почему здесь нет отметок и напоминаний. */
@Composable
internal fun CopyBanner(owner: String, modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Ampulse.colors.accentSoft)
            .padding(14.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(AmpulseIcons.Users, contentDescription = null, modifier = Modifier.size(20.dp), tint = p.accentText)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.family_copy_banner, owner),
                color = p.accentText,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(stringResource(R.string.family_copy_banner_hint), color = p.accentText, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
