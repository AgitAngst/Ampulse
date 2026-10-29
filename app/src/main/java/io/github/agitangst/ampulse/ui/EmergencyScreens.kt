package io.github.agitangst.ampulse.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.agitangst.ampulse.AmpulseIcons
import io.github.agitangst.ampulse.Ampulse
import io.github.agitangst.ampulse.AppGraph
import io.github.agitangst.ampulse.Nav
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.Route
import io.github.agitangst.ampulse.domain.Contact
import io.github.agitangst.ampulse.domain.DoseEvent
import io.github.agitangst.ampulse.domain.EmergencyCard
import io.github.agitangst.ampulse.domain.EventStatus
import io.github.agitangst.ampulse.domain.FamilyTexts
import io.github.agitangst.ampulse.domain.Medicine
import io.github.agitangst.ampulse.domain.Plan
import io.github.agitangst.ampulse.domain.Profile
import io.github.agitangst.ampulse.domain.Schedule
import io.github.agitangst.ampulse.reminders.ReminderAccess
import io.github.agitangst.ampulse.screenTitleStyle
import io.github.agitangst.anvil.compose.AnvilCard
import io.github.agitangst.anvil.compose.AnvilTheme
import io.github.agitangst.anvil.compose.SectionTitle
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

private const val TAG = "AmpulseEmergency"

/** За сколько дней назад ищется последний приём лекарства для карты. */
private const val LAST_DOSE_DAYS = 30L

/** Самое длинное поле карты: дальше не влезет в QR-код (читается любой камерой). */
private const val MAX_FIELD = 500

// ---------------------------------------------------------------------------------------------------------------------
// Данные карты
// ---------------------------------------------------------------------------------------------------------------------

/** Лекарство профиля для карты: схема (строка «как принимает») и последний приём за ~30 дней. */
internal data class EmergencyMed(val medicine: Medicine, val plan: Plan?, val last: LocalDateTime?)

/** Всё, что показывает карта: чья она, сами поля, лекарства с последним приёмом и кому звонить. */
internal data class EmergencyData(
    val profileId: Long,
    val name: String,
    val card: EmergencyCard,
    val medicines: List<EmergencyMed>,
    val contacts: List<Contact>,
)

/**
 * Собрать данные карты. Лекарства — действующие лекарства профиля (схема на паузе — не принимается сейчас); последний
 * приём — самая поздняя отметка «принято» или «лишний приём» (пропуск приёмом не считается).
 */
internal fun buildEmergencyData(
    profileId: Long,
    ownerName: String,
    card: EmergencyCard,
    medicines: List<Medicine>,
    schedules: List<Schedule>,
    events: List<DoseEvent>,
    contacts: List<Contact>,
    profiles: List<Profile>,
): EmergencyData {
    val planByMedicine = schedules.groupBy { it.medicineId }.mapValues { (_, list) -> list.firstOrNull()?.plan }
    val taken = events.filter { it.status != EventStatus.Skipped }.groupBy { it.medicineId }
    val meds = medicines
        .filter { it.profileId == profileId && planByMedicine[it.id]?.paused != true }
        .sortedBy { it.name.lowercase() }
        .map { EmergencyMed(it, planByMedicine[it.id], taken[it.id]?.maxOfOrNull { e -> e.at }) }
    val name = if (profileId == 0L) {
        ownerName
    } else {
        profiles.firstOrNull { it.id == profileId }?.name?.takeIf { it.isNotBlank() } ?: ownerName
    }
    return EmergencyData(profileId, name, card, meds, contacts)
}

/** Данные карты профиля [profileId] из базы; `null`, пока читаются. Обновляются сами при любой правке. */
@Composable
internal fun rememberEmergencyData(profileId: Long): EmergencyData? {
    val repo = AppGraph.repository
    val owner = stringResource(R.string.emergency_owner)
    val today = remember { LocalDate.now() }
    val card by remember(profileId) { repo.emergency(profileId) }.collectAsState(initial = null)
    val medicines by remember { repo.medicines }.collectAsState(initial = null)
    val schedules by remember { repo.schedules }.collectAsState(initial = null)
    val events by remember { repo.events(today.minusDays(LAST_DOSE_DAYS), today.plusDays(1)) }.collectAsState(initial = null)
    val contacts by remember { repo.contacts }.collectAsState(initial = null)
    val profiles by remember { repo.profiles }.collectAsState(initial = null)
    val c = card
    val m = medicines
    val s = schedules
    val e = events
    val k = contacts
    val pr = profiles
    return if (c != null && m != null && s != null && e != null && k != null && pr != null) {
        remember(profileId, owner, c, m, s, e, k, pr) { buildEmergencyData(profileId, owner, c, m, s, e, k, pr) }
    } else {
        null
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Разбор и сборка полей (чистые функции — под тестами)
// ---------------------------------------------------------------------------------------------------------------------

/** Строки списка («Аллергии», «Диагнозы»): по одной на строку или через «;»; пустые отбрасываются. */
internal fun cardLines(text: String): List<String> = text.split('\n', ';').map { it.trim() }.filter { it.isNotEmpty() }

private val ALLERGY_SPLIT = Regex("""\s+[—–-]\s+|:\s+""")

/** «Лидокаин — отёк» → («Лидокаин», «отёк»); без разделителя — одно название. */
internal fun parseAllergy(line: String): Pair<String, String> {
    val m = ALLERGY_SPLIT.find(line) ?: return line.trim() to ""
    return line.substring(0, m.range.first).trim() to line.substring(m.range.last + 1).trim()
}

/** Группа крови в карте: «A(II) Rh+» — буква, римская цифра и резус (как в макете); чипы правки собирают и читают её. */
internal object BloodType {
    private val ROMAN = Regex("""\b(IV|III|II|I)\b""")
    private val LETTERS = Regex("""^\s*(AB|A|B|O|0)\b""", RegexOption.IGNORE_CASE)

    /** (группа 1–4, 0 — не задана; резус 1 — «+», 2 — «−», 0 — не задан). Понимает и «II+», и «A+». */
    fun parse(text: String): Pair<Int, Int> {
        val group = ROMAN.find(text)?.value?.let {
            when (it) {
                "I" -> 1
                "II" -> 2
                "III" -> 3
                else -> 4
            }
        } ?: LETTERS.find(text)?.value?.trim()?.uppercase()?.let {
            when (it) {
                "O", "0" -> 1
                "A" -> 2
                "B" -> 3
                else -> 4
            }
        } ?: 0
        val rh = when {
            text.contains('+') -> 1
            text.contains('−') || text.contains('-') || text.contains('–') -> 2
            else -> 0
        }
        return group to rh
    }

    fun format(group: Int, rh: Int): String {
        val abo = when (group) {
            1 -> "O(I)"
            2 -> "A(II)"
            3 -> "B(III)"
            4 -> "AB(IV)"
            else -> ""
        }
        val sign = when (rh) {
            1 -> "Rh+"
            2 -> "Rh−"
            else -> ""
        }
        return listOf(abo, sign).filter { it.isNotEmpty() }.joinToString(" ")
    }
}

/** Карта для простого текста QR: списки — в одну строку через «; », заметки — без переносов. */
internal fun EmergencyCard.forText(): EmergencyCard = copy(
    allergies = cardLines(allergies).joinToString("; "),
    diagnoses = cardLines(diagnoses).joinToString("; "),
    notes = notes.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" "),
)

private val QR_DARK = 0xFF16211F.toInt()
private val QR_LIGHT = 0xFFFFFFFF.toInt()

/**
 * QR-код с текстом [text]: один пиксель на модуль, без рамки (белую рамку рисует экран), тёмный на белом в любой теме.
 * Сначала уровень коррекции M, не влезло — L; не влезло и так — `null`.
 */
internal fun emergencyQr(text: String): Bitmap? {
    for (level in listOf(ErrorCorrectionLevel.M, ErrorCorrectionLevel.L)) {
        try {
            val hints = mapOf<EncodeHintType, Any>(
                EncodeHintType.MARGIN to 0,
                EncodeHintType.ERROR_CORRECTION to level,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 1, 1, hints)
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h) { i -> if (matrix.get(i % w, i / w)) QR_DARK else QR_LIGHT }
            return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        } catch (e: WriterException) {
            Log.w(TAG, "Card does not fit a QR code at level $level: ${e.message}")
        }
    }
    return null
}

// ---------------------------------------------------------------------------------------------------------------------
// Значки
// ---------------------------------------------------------------------------------------------------------------------

/** Значки экстренной карты: линии 1,8 на сетке 24, пути — набор Lucide, как в макете (docs/ui-guideline.md, §5). */
internal object EmergencyIcons {
    /** Медицинский крест — знак карты. */
    val Cross: ImageVector by lazy {
        icon(
            "emergency.cross",
            "M11 2a2 2 0 0 0-2 2v5H4a2 2 0 0 0-2 2v2c0 1.1.9 2 2 2h5v5c0 1.1.9 2 2 2h2a2 2 0 0 0 2-2v-5h5a2 2 0 0 0 2-2v-2a2 2 0 0 0-2-2h-5V4a2 2 0 0 0-2-2h-2z",
        )
    }

    val Phone: ImageVector by lazy {
        icon(
            "emergency.phone",
            "M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z",
        )
    }

    val Qr: ImageVector by lazy {
        icon(
            "emergency.qr",
            "M4 3h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z",
            "M15 3h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1h-5a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z",
            "M4 14h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-5a1 1 0 0 1 1-1z",
            "M14 14h3v3h-3z", "M17 20h4", "M20 17v3",
        )
    }

    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
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

// ---------------------------------------------------------------------------------------------------------------------
// Карта (общая для экрана программы и экрана блокировки)
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Цвета шапки карты. Шапка — тёмная панель в любой теме (docs/ui-guideline.md, §3): в тёмной теме цвета карточки и
 * плитки `raised`, в светлой — `#16211F` и плитки `#24322F` из макета.
 */
private class HeaderColors(val bg: Color, val tile: Color, val text: Color, val sub: Color, val label: Color, val link: Color)

@Composable
private fun headerColors(): HeaderColors {
    val p = AnvilTheme.palette
    return if (p.dark) {
        HeaderColors(bg = p.card, tile = p.raised, text = p.text, sub = p.weak, label = p.faint, link = p.accentText)
    } else {
        HeaderColors(
            bg = Color(0xFF16211F), tile = Color(0xFF24322F), text = Color.White,
            sub = Color(0xFFC9D3D1), label = Color(0xFFB9CFCB), link = Color(0xFF9FE0D6),
        )
    }
}

/** Ссылка в шапке: «назад» (экран программы) или «закрыть» (экран блокировки); цель нажатия не меньше 48 dp. */
@Composable
internal fun EmergencyHeaderLink(label: String, icon: ImageVector, onClick: () -> Unit) {
    val c = headerColors()
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = c.link)
        Text(label, color = c.link, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
    }
}

/**
 * Карта целиком (макет, экран 16): шапка с именем и группой крови, крупно аллергии, диагнозы, лекарства с последним
 * приёмом, кому звонить, QR. [top] — верхняя строка шапки («назад» или «закрыть»). [readOnly] — экран блокировки:
 * без подсказок про правку. Прокручивается сама.
 */
@Composable
internal fun EmergencyCardView(
    data: EmergencyData,
    readOnly: Boolean,
    top: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val card = data.card
    val ru = LocalConfiguration.current.locales[0].language == "ru"
    var showQr by rememberSaveable { mutableStateOf(false) }
    Column(modifier.verticalScroll(rememberScrollState())) {
        CardHeader(data.name, card.bloodType, top)
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (card.isEmpty) {
                EmptyNote(readOnly)
            } else {
                AllergiesSection(card.allergies)
                ConditionsSection(card.diagnoses)
            }
            MedicinesSection(data.medicines)
            if (card.notes.isNotBlank()) NotesSection(card.notes.trim())
            ContactsSection(data.contacts, readOnly)
            if (!card.isEmpty || data.medicines.isNotEmpty() || data.contacts.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MedOutlineButton(
                        stringResource(if (showQr) R.string.emergency_qr_hide else R.string.emergency_qr_show),
                        onClick = { showQr = !showQr },
                        icon = EmergencyIcons.Qr,
                    )
                    if (showQr) QrBlock(data, ru)
                    Text(
                        stringResource(R.string.emergency_qr_hint),
                        Modifier.fillMaxWidth(),
                        color = AnvilTheme.palette.faint,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun CardHeader(name: String, blood: String, top: @Composable () -> Unit) {
    val c = headerColors()
    val p = AnvilTheme.palette
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
            .background(c.bg)
            .padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Сдвиг влево на поля строки: стрелка и слово встают на общее поле 20 dp, а цель нажатия остаётся широкой.
        Box(Modifier.offset(x = (-8).dp)) { top() }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Ampulse.colors.warnSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(EmergencyIcons.Cross, contentDescription = null, modifier = Modifier.size(28.dp), tint = p.warning)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.emergency_title),
                    Modifier.semantics { heading() },
                    color = c.text,
                    style = screenTitleStyle(28),
                )
                Text(stringResource(R.string.emergency_subtitle), color = c.sub, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HeaderTile(stringResource(R.string.emergency_tile_name), name, c, Modifier.weight(1f))
            HeaderTile(stringResource(R.string.emergency_tile_blood), blood.ifBlank { "—" }, c, Modifier.weight(1f))
        }
    }
}

@Composable
private fun HeaderTile(label: String, value: String, c: HeaderColors, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(c.tile).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, color = c.label, fontSize = 12.sp)
        Text(value, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Подпись раздела карты: прописные, разрядка 0,6, 13 sp; для TalkBack — заголовок. */
@Composable
private fun CardTitle(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier.semantics { heading() },
        color = color,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
    )
}

/** Карточка раздела: фон `card`, скругление 20, поля 16. */
@Composable
private fun CardBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(AnvilTheme.palette.card)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun EmptyNote(readOnly: Boolean) {
    val p = AnvilTheme.palette
    CardBox {
        if (readOnly) {
            Text(stringResource(R.string.emergency_empty_readonly), color = p.weak, style = MaterialTheme.typography.bodyLarge)
        } else {
            Text(
                stringResource(R.string.emergency_empty_title),
                color = p.text,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(stringResource(R.string.emergency_empty_hint), color = p.weak, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Аллергии — крупно, мягким вниманием с рамкой, значком и словом (цвет не единственный носитель смысла). Не указаны —
 * спокойная карточка «Не указаны»: отсутствие записи не значит, что аллергии нет.
 */
@Composable
private fun AllergiesSection(text: String) {
    val p = AnvilTheme.palette
    val lines = remember(text) { cardLines(text) }
    val none = lines.isEmpty()
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (none) p.card else Ampulse.colors.warnSoft)
            .then(if (none) Modifier else Modifier.border(1.5.dp, Ampulse.colors.warnLine, shape))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!none) Icon(MedIcons.Warning, contentDescription = null, modifier = Modifier.size(18.dp), tint = p.warning)
            CardTitle(stringResource(R.string.emergency_allergies), if (none) p.faint else p.warning)
        }
        if (none) {
            Text(stringResource(R.string.emergency_allergies_none), color = p.weak, fontSize = 16.sp)
        } else {
            lines.forEach { line ->
                val (name, reaction) = parseAllergy(line)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Text(name, Modifier.weight(1f), color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    if (reaction.isNotEmpty()) {
                        Text(reaction, Modifier.widthIn(max = 170.dp), color = p.warning, fontSize = 16.sp, textAlign = TextAlign.End)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConditionsSection(text: String) {
    val p = AnvilTheme.palette
    val lines = remember(text) { cardLines(text) }
    if (lines.isEmpty()) return
    CardBox {
        CardTitle(stringResource(R.string.emergency_conditions), p.faint)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            lines.forEach { line ->
                Text(
                    line,
                    Modifier.clip(RoundedCornerShape(12.dp)).background(p.raised).padding(horizontal = 12.dp, vertical = 6.dp),
                    color = p.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/** «Принимает сейчас»: лекарства профиля с последним приёмом; список и время берутся из расписания и отметок. */
@Composable
private fun MedicinesSection(meds: List<EmergencyMed>) {
    val p = AnvilTheme.palette
    val texts = rememberMedTexts()
    val now = remember { LocalDateTime.now() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(p.card)
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                CardTitle(stringResource(R.string.emergency_medicines), p.faint)
                Text(stringResource(R.string.emergency_last_dose), color = p.faint, fontSize = 12.sp)
            }
            if (meds.isEmpty()) {
                Text(
                    stringResource(R.string.emergency_medicines_none),
                    Modifier.padding(vertical = 10.dp),
                    color = p.weak,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            meds.forEachIndexed { index, m ->
                if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
                val sub = listOf(m.medicine.strength.trim(), m.plan?.let { texts.summary(it, m.medicine.unit) }.orEmpty())
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(m.medicine.name, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        if (sub.isNotEmpty()) Text(sub, color = p.weak, fontSize = 13.sp)
                    }
                    Text(
                        m.last?.let { texts.dayAndTime(it, now) } ?: stringResource(R.string.emergency_last_none),
                        Modifier.widthIn(max = 130.dp),
                        color = if (m.last == null) p.faint else p.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
        Text(
            stringResource(R.string.emergency_medicines_auto),
            Modifier.padding(horizontal = 4.dp),
            color = p.faint,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun NotesSection(text: String) {
    val p = AnvilTheme.palette
    CardBox {
        CardTitle(stringResource(R.string.emergency_notes), p.faint)
        Text(text, color = p.text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** «Кому звонить»: близкие из программы; кнопка «Позвонить» открывает набор номера — звонок без разрешений. */
@Composable
private fun ContactsSection(contacts: List<Contact>, readOnly: Boolean) {
    val p = AnvilTheme.palette
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CardTitle(stringResource(R.string.emergency_contacts), p.faint, Modifier.padding(horizontal = 4.dp))
        if (contacts.isEmpty()) {
            CardBox {
                Text(
                    stringResource(if (readOnly) R.string.emergency_contacts_none_readonly else R.string.emergency_contacts_none),
                    color = p.weak,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            contacts.forEach { ContactRow(it) }
        }
    }
}

@Composable
private fun ContactRow(contact: Contact) {
    val p = AnvilTheme.palette
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(p.card)
            .padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Ampulse.colors.accentSoft), contentAlignment = Alignment.Center) {
            Text(
                contact.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                color = p.accentText,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(contact.name, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(contact.phone, color = p.weak, fontSize = 13.sp)
        }
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(p.accent)
                .clickable(role = Role.Button) { dial(context, contact.phone) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                EmergencyIcons.Phone,
                contentDescription = stringResource(R.string.emergency_call, contact.name),
                modifier = Modifier.size(20.dp),
                tint = p.onAccent,
            )
        }
    }
}

/** Открыть набор номера (`ACTION_DIAL`): сам звонок человек начинает кнопкой в телефоне — разрешение не нужно. */
private fun dial(context: Context, phone: String) {
    val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone.trim(), null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "No dialer to call a contact", e)
    }
}

/** QR карты: белая плита с отступом (рамка нужна камере), код — тёмный на белом в любой теме. */
@Composable
private fun QrBlock(data: EmergencyData, ru: Boolean) {
    val bitmap = remember(data, ru) {
        val text = FamilyTexts.card(
            ru = ru,
            name = data.name,
            card = data.card.forText(),
            medicines = data.medicines.map { it.medicine },
            lastDoses = data.medicines.mapNotNull { m -> m.last?.let { m.medicine.id to it } }.toMap(),
            contacts = data.contacts,
        )
        emergencyQr(text)
    }
    if (bitmap == null) {
        MedNotice(stringResource(R.string.emergency_qr_too_long))
        return
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White)
            .padding(16.dp),
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.emergency_qr_desc),
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            filterQuality = FilterQuality.None,
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Экраны программы
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Экстренная карта текущего профиля (макет, экран 16). Внизу одна главная кнопка: «Изменить» (пустая карта —
 * «Заполнить»). Доступна из «Настроек» и «Близких».
 */
@Composable
fun EmergencyScreen(nav: Nav) {
    val p = AnvilTheme.palette
    val data = rememberEmergencyData(CurrentProfile.id)
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
        if (data == null) {
            Box(Modifier.weight(1f).fillMaxWidth())
        } else {
            EmergencyCardView(
                data = data,
                readOnly = false,
                top = { EmergencyHeaderLink(stringResource(R.string.emergency_back), AmpulseIcons.ChevronLeft, nav::back) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
        Column(Modifier.fillMaxWidth().background(p.bg).padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 20.dp)) {
            MedMainButton(
                stringResource(if (data?.card?.isEmpty == true) R.string.emergency_fill else R.string.emergency_edit),
                enabled = data != null,
                onClick = { nav.open(Route.EmergencyEdit) },
            )
        }
    }
}

/**
 * Правка карты текущего профиля: аллергии и диагнозы — по строке на пункт, группа крови — чипами (I–IV, Rh+/Rh−),
 * заметки, «Показывать на экране блокировки». «Сохранить» пишет карту и сразу включает или убирает уведомление.
 */
@Composable
fun EmergencyEditScreen(nav: Nav) {
    val repo = AppGraph.repository
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val p = AnvilTheme.palette
    val profileId = rememberSaveable { CurrentProfile.id }
    var loaded by rememberSaveable { mutableStateOf(false) }
    var allergies by rememberSaveable { mutableStateOf("") }
    var diagnoses by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf(0) }
    var rh by rememberSaveable { mutableStateOf(0) }
    // Группа крови, записанная не по образцу (старая запись): пока чипы не тронуты, она сохраняется как есть.
    var rawBlood by rememberSaveable { mutableStateOf("") }
    var bloodTouched by rememberSaveable { mutableStateOf(false) }
    var lock by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (loaded) return@LaunchedEffect
        val card = repo.emergencyNow(profileId)
        allergies = card.allergies
        diagnoses = card.diagnoses
        notes = card.notes
        val (g, r) = BloodType.parse(card.bloodType)
        group = g
        rh = r
        rawBlood = if (g == 0 && r == 0) card.bloodType else ""
        lock = card.onLockScreen
        loaded = true
    }

    // Уведомления: состояние перечитывается, когда человек вернулся из настроек системы.
    var resumes by remember { mutableIntStateOf(0) }
    DisposableEffect(activity) {
        val lifecycle = (activity as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) resumes++ }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    val notificationsOn = remember(resumes) { ReminderAccess.notificationsOn(context) }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumes++ }
    fun allowNotifications() {
        if (activity != null && ReminderAccess.canAskNotifications(activity)) {
            ReminderAccess.markNotificationsAsked(context)
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // Запрещено насовсем или выключено в системе — диалога не будет, только настройки.
            ReminderAccess.openNotificationSettings(context)
        }
    }

    fun save() {
        if (busy) return
        busy = true
        val blood = if (group == 0 && rh == 0 && rawBlood.isNotBlank() && !bloodTouched) rawBlood else BloodType.format(group, rh)
        val card = EmergencyCard(profileId, allergies.trim(), diagnoses.trim(), blood, notes.trim(), lock)
        scope.launch {
            try {
                repo.saveEmergency(card)
                EmergencyNotice.syncNow(context)
                nav.back()
            } catch (e: Exception) {
                Log.e(TAG, "Saving the emergency card failed", e)
                busy = false
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Сдвиг влево на поля строки: стрелка и слово встают на общее поле 20 dp, а цель нажатия остаётся широкой.
            MedBackRow(stringResource(R.string.emergency_cancel), nav::back, Modifier.offset(x = (-8).dp))
            Text(
                stringResource(R.string.emergency_edit_title),
                Modifier.semantics { heading() },
                color = p.text,
                style = screenTitleStyle(30),
            )
            if (loaded) {
                EmergencyField(
                    allergies, { allergies = it.take(MAX_FIELD) },
                    stringResource(R.string.emergency_field_allergies), stringResource(R.string.emergency_field_allergies_hint), 3,
                )
                EmergencyField(
                    diagnoses, { diagnoses = it.take(MAX_FIELD) },
                    stringResource(R.string.emergency_field_conditions), stringResource(R.string.emergency_field_conditions_hint), 3,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(stringResource(R.string.emergency_blood), Modifier.padding(horizontal = 4.dp))
                    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("I", "II", "III", "IV").forEachIndexed { index, label ->
                            MedChip(
                                label = label,
                                selected = group == index + 1,
                                onClick = {
                                    group = if (group == index + 1) 0 else index + 1
                                    bloodTouched = true
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(R.string.emergency_blood_rh_plus, R.string.emergency_blood_rh_minus).forEachIndexed { index, label ->
                            MedChip(
                                label = stringResource(label),
                                selected = rh == index + 1,
                                onClick = {
                                    rh = if (rh == index + 1) 0 else index + 1
                                    bloodTouched = true
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                EmergencyField(
                    notes, { notes = it.take(MAX_FIELD) },
                    stringResource(R.string.emergency_field_notes), stringResource(R.string.emergency_field_notes_hint), 2,
                )
                AnvilCard {
                    MedSwitchRow(
                        stringResource(R.string.emergency_lock_title),
                        stringResource(R.string.emergency_lock_hint),
                        lock,
                        { on ->
                            lock = on
                            if (on && !notificationsOn) allowNotifications()
                        },
                    )
                }
                if (lock) {
                    MedNotice(stringResource(R.string.emergency_lock_warning))
                    if (!notificationsOn) {
                        MedNotice(stringResource(R.string.emergency_notifications_off))
                        MedOutlineButton(stringResource(R.string.emergency_allow), onClick = { allowNotifications() })
                    }
                }
                Text(
                    stringResource(R.string.emergency_sms_hint),
                    Modifier.padding(horizontal = 4.dp),
                    color = p.faint,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
        Column(Modifier.fillMaxWidth().background(p.bg).padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 20.dp)) {
            MedMainButton(stringResource(R.string.emergency_save), enabled = loaded && !busy, onClick = { save() })
        }
    }
}

/** Многострочное поле карты: рамка 1,5 dp, подпись плавает (читается TalkBack), подсказка — пример записи. */
@Composable
private fun EmergencyField(value: String, onChange: (String) -> Unit, label: String, hint: String, minLines: Int) {
    val p = AnvilTheme.palette
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = { Text(hint) },
        minLines = minLines,
        maxLines = 8,
        shape = RoundedCornerShape(14.dp),
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
