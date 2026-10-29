package io.github.agitangst.ampulse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.agitangst.ampulse.Ampulse
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.domain.FoodRule
import io.github.agitangst.ampulse.domain.MedForm
import io.github.agitangst.ampulse.domain.TimeHint
import io.github.agitangst.anvil.compose.AnvilTheme
import io.github.agitangst.ampulse.domain.Route as InjectionRoute

// Части экрана «Назначение → план» (docs/ui-guideline.md, §6): значки, чип «из текста / додумано», карточка входа.

/** Значки разбора: линии 1,8 на сетке 24, пути — набор Lucide, как в макете (экраны 7–9). */
internal object ImportIcons {
    /** Планшет со списком: вход «Из назначения». */
    val Clipboard: ImageVector by lazy {
        icon(
            "import.clipboard",
            "M9 2h6a1 1 0 0 1 1 1v2a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1Z",
            "M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2",
            "M12 11h4", "M12 16h4", "M8 11h.01", "M8 16h.01",
        )
    }

    /** Лист с загнутым углом: «Из файла». */
    val File: ImageVector by lazy {
        icon("import.file", "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z", "M14 2v4a2 2 0 0 0 2 2h4")
    }

    /** Перечёркнутая сеть: «Работает без интернета». */
    val WifiOff: ImageVector by lazy {
        icon(
            "import.wifi_off",
            "M12 20h.01", "M8.5 16.429a5 5 0 0 1 7 0", "M5 12.859a10 10 0 0 1 5.17-2.69",
            "M19 12.859a10 10 0 0 0-2.007-1.523", "M2 8.82a15 15 0 0 1 4.177-2.643",
            "M22 8.82a15 15 0 0 0-11.288-3.764", "m2 2 20 20",
        )
    }

    /** Три точки, соединённые линиями: «Поделиться». */
    val Share: ImageVector by lazy {
        icon(
            "import.share",
            "M21 5a3 3 0 1 1-6 0a3 3 0 0 1 6 0Z", "M9 12a3 3 0 1 1-6 0a3 3 0 0 1 6 0Z", "M21 19a3 3 0 1 1-6 0a3 3 0 0 1 6 0Z",
            "m8.59 13.51 6.83 3.98", "m15.41 6.51-6.82 3.98",
        )
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
 * Строка-вход «Есть назначение врача?» на экране нового лекарства (макет, экран 4): плотная карточка цвета текста со
 * строкой цвета фона — в обеих темах она заметнее соседних полей; справа стрелка ›.
 */
@Composable
internal fun ImportEntryCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    val quiet = p.bg.copy(alpha = 0.8f)
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(p.text)
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 14.dp, top = 14.dp, end = 12.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StockTile(ImportIcons.Clipboard, soft = true)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.import_entry_title),
                color = p.bg,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(stringResource(R.string.import_entry_hint), color = quiet, style = MaterialTheme.typography.bodyMedium)
        }
        Icon(MedIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp), tint = quiet)
    }
}

/** Плашка «Работает без интернета» на мягком акценте. */
@Composable
internal fun ImportOfflinePill(modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Ampulse.colors.accentSoft)
            .padding(start = 8.dp, top = 5.dp, end = 10.dp, bottom = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ImportIcons.WifiOff, contentDescription = null, modifier = Modifier.size(16.dp), tint = p.accentText)
        Text(
            stringResource(R.string.import_offline),
            color = p.accentText,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
        )
    }
}

/** Карточка-подсказка: значок и пояснение на фоне карточки. */
@Composable
internal fun ImportHintCard(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(p.card)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(top = 1.dp).size(20.dp), tint = p.accentText)
        Text(text, color = p.weak, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Кнопка-значок 48 dp без заливки: «Убрать из разбора». [description] читает TalkBack. */
@Composable
internal fun ImportIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp), tint = AnvilTheme.palette.weak)
    }
}

/** Чип карточки: [assumed] — додумано (пунктир на карточном фоне), иначе из текста (сплошная заливка). */
internal data class ImportChipSpec(val label: String, val assumed: Boolean = false)

/**
 * Чип разбора (docs/ui-guideline.md, §6): из текста — сплошная заливка «кнопок», додумано — пунктирная рамка на фоне
 * карточки. Пунктир — не единственный носитель: для TalkBack к подписи добавлено «додумано».
 */
@Composable
internal fun ImportChip(chip: ImportChipSpec, modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    val line = p.faint
    val desc = if (chip.assumed) stringResource(R.string.import_chip_guess_desc, chip.label) else chip.label
    Text(
        chip.label,
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (chip.assumed) p.card else p.raised)
            .then(
                if (chip.assumed) {
                    Modifier.drawBehind {
                        val width = 1.5.dp.toPx()
                        drawRoundRect(
                            color = line,
                            topLeft = Offset(width / 2, width / 2),
                            size = Size(size.width - width, size.height - width),
                            cornerRadius = CornerRadius(10.dp.toPx()),
                            style = Stroke(width = width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f))),
                        )
                    }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics { contentDescription = desc },
        color = p.text,
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    )
}

/** Полоса срока курса: заливка акцентом от начала до конца курса; у курса без конца — полная нейтральная. */
@Composable
internal fun ImportCourseBar(fraction: Float, ongoing: Boolean, modifier: Modifier = Modifier) {
    val p = AnvilTheme.palette
    val shape = RoundedCornerShape(3.dp)
    Box(modifier.fillMaxWidth().height(6.dp).clip(shape).background(Ampulse.colors.track)) {
        Box(
            Modifier
                .fillMaxWidth(if (ongoing) 1f else fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(shape)
                .background(if (ongoing) p.borderStrong else p.accent),
        )
    }
}

/**
 * Чипы карточки по разобранной строке: что взято из текста — сплошные, что додумано — [ImportChipSpec.assumed]. [spread] —
 * промежуток между приёмами в часах, если разбор поставил его сам ([spreadHours]); 0 — не показывать.
 */
internal fun chipsOf(row: ImportRow, spread: Int, texts: MedTexts): List<ImportChipSpec> = buildList {
    val p = row.parsed
    fun put(label: String, assumed: Boolean = false) {
        add(ImportChipSpec(label, assumed))
    }
    val injection = p.form == MedForm.Injection
    val unit = texts.unitFor(p.form)

    p.strength?.let { put(it) }
    when (p.route) {
        InjectionRoute.Subcutaneous -> put(texts.string(R.string.import_chip_sc))
        InjectionRoute.Intramuscular -> put(texts.string(R.string.import_chip_im))
        null -> Unit
    }
    if (!injection) {
        val steps = p.steps
        if (steps != null) {
            put(steps.joinToString(" → ") { texts.number(it.amount) } + " " + unit)
        } else {
            put(texts.string(R.string.import_chip_per_dose, texts.dose(p.perDose, unit)), assumed = !p.found.count)
        }
    }
    row.perDay?.let { put(texts.plural(R.plurals.meds_sum_per_day, it, it)) }
    if (p.everyOtherDay) put(texts.string(R.string.import_chip_every_other))
    when (p.hint) {
        TimeHint.Night -> put(texts.string(R.string.import_chip_night))
        TimeHint.Morning -> put(texts.string(R.string.import_chip_morning))
        TimeHint.Evening -> put(texts.string(R.string.import_chip_evening))
        TimeHint.Both -> put(texts.string(R.string.import_chip_both))
        null -> Unit
    }
    if (!injection) {
        put(
            texts.string(
                when (p.food) {
                    FoodRule.None -> R.string.import_chip_food_none
                    FoodRule.EmptyStomach -> R.string.import_chip_food_empty
                    FoodRule.Before -> R.string.import_chip_food_before
                    FoodRule.With -> R.string.import_chip_food_with
                    FoodRule.After -> R.string.import_chip_food_after
                },
            ),
            assumed = p.food == FoodRule.None,
        )
    }
    if (spread > 0) put(texts.string(R.string.import_chip_every_h, spread), assumed = true)
    val days = p.days
    if (days != null) put(texts.days(days)) else put(texts.string(R.string.import_chip_ongoing), assumed = !p.found.duration)
    val steps = p.steps
    if (steps != null && steps.all { it.days == steps.first().days }) {
        put(texts.plural(R.plurals.import_chip_step_every, steps.first().days, steps.first().days))
    }
}
