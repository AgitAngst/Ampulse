package io.github.agitangst.ampulse.ui

import io.github.agitangst.ampulse.data.Codec
import io.github.agitangst.ampulse.domain.ProfileKind
import io.github.agitangst.ampulse.domain.Site
import io.github.agitangst.ampulse.domain.SiteRest
import kotlin.math.roundToLong

/**
 * Чистая логика экрана «Место укола» (макет, экраны 3, 17–18): метка места, вид укола по умолчанию, сторона питомца,
 * положение точек на схеме, шаг дозы и сахар крови. Без Android — проверяется тестами на JVM.
 */

/** Метка места: рекомендуем (отдыхало дольше всех), можно, пусть отдохнёт (кололи 3 дня назад и меньше). */
internal enum class SiteTag { Recommended, Ok, Resting }

/** Рекомендуемое — всегда «Рекомендуем», даже если отдыхали все места; иначе «отдыхает» по `SiteRest.resting`. */
internal fun siteTag(rest: SiteRest, recommended: Site?): SiteTag = when {
    rest.site == recommended -> SiteTag.Recommended
    rest.resting -> SiteTag.Resting
    else -> SiteTag.Ok
}

private val InMuscle = Regex(
    """в\s*/\s*м(?!\p{L})|в\s*мышц|внутримыш|(?<!\p{L})i\s*/\s*m(?!\p{L})|(?<!\p{L})im(?!\p{L})|intramuscul""",
    RegexOption.IGNORE_CASE,
)
private val UnderSkin = Regex(
    """п\s*/\s*к(?!\p{L})|подкож|(?<!\p{L})s\s*/\s*c(?!\p{L})|(?<!\p{L})sc(?!\p{L})|subcut""",
    RegexOption.IGNORE_CASE,
)

/**
 * Вид укола по умолчанию: в мышцу или подкожно. У лекарства нет надёжного поля, поэтому порядок такой: место
 * последнего укола этим лекарством (ягодица и бедро снаружи — в мышцу) → подсказка в названии или заметке («в/м»,
 * «внутримышечно», «IM») → подкожно. На экране вид переключается вручную.
 */
internal fun defaultIntramuscular(hint: String, lastSite: Site?): Boolean {
    if (lastSite != null) return lastSite.intramuscular
    return InMuscle.containsMatchIn(hint) && !UnderSkin.containsMatchIn(hint)
}

/** Сторона тела питомца на схеме: «Левый бок» или «Правый бок». */
internal enum class PetSide { Left, Right }

/** Сторона места у питомца; у холки её нет — она видна с обеих сторон. */
internal fun Site.petSide(): PetSide? = when (this) {
    Site.FlankLeft, Site.PetThighLeft -> PetSide.Left
    Site.FlankRight, Site.PetThighRight -> PetSide.Right
    else -> null
}

/** То же место с другой стороны: правое бедро ↔ левое; холка остаётся. */
internal fun mirrorSite(site: Site): Site = when (site) {
    Site.AbdomenLeft -> Site.AbdomenRight
    Site.AbdomenRight -> Site.AbdomenLeft
    Site.ThighLeft -> Site.ThighRight
    Site.ThighRight -> Site.ThighLeft
    Site.ArmLeft -> Site.ArmRight
    Site.ArmRight -> Site.ArmLeft
    Site.ButtockLeft -> Site.ButtockRight
    Site.ButtockRight -> Site.ButtockLeft
    Site.OuterThighLeft -> Site.OuterThighRight
    Site.OuterThighRight -> Site.OuterThighLeft
    Site.Scruff -> Site.Scruff
    Site.FlankLeft -> Site.FlankRight
    Site.FlankRight -> Site.FlankLeft
    Site.PetThighLeft -> Site.PetThighRight
    Site.PetThighRight -> Site.PetThighLeft
}

/** Какие места видны при показанной стороне: у питомца — холка и места этой стороны, у человека — все. */
internal fun visibleSites(sites: List<Site>, side: PetSide?): List<Site> =
    if (side == null) sites else sites.filter { it.petSide() == null || it.petSide() == side }

/** Схема тела: человек спереди (подкожно) или сзади (в мышцу), кошка и собака сбоку. */
internal enum class BodyView { Front, Back, Cat, Dog }

internal fun bodyViewFor(kind: ProfileKind, intramuscular: Boolean): BodyView = when (kind) {
    ProfileKind.Person -> if (intramuscular) BodyView.Back else BodyView.Front
    ProfileKind.Dog -> BodyView.Dog
    ProfileKind.Cat, ProfileKind.Other -> BodyView.Cat
}

/** Размер сетки рисунка: человек 200×320, питомец 320×220 (морда вправо — это правый бок). */
internal fun bodyGrid(view: BodyView): Pair<Float, Float> = when (view) {
    BodyView.Front, BodyView.Back -> 200f to 320f
    BodyView.Cat, BodyView.Dog -> 320f to 220f
}

/** Центр точки места на сетке рисунка [bodyGrid]; у питомца — для правого бока, левый зеркалится при показе. */
internal fun spotOf(view: BodyView, site: Site): Pair<Float, Float>? = when (view) {
    BodyView.Front -> when (site) {
        Site.ArmRight -> 34f to 112f
        Site.ArmLeft -> 166f to 112f
        Site.AbdomenRight -> 75f to 168f
        Site.AbdomenLeft -> 125f to 168f
        Site.ThighRight -> 75f to 262f
        Site.ThighLeft -> 125f to 262f
        else -> null
    }
    // Сзади правая сторона человека — справа от зрителя.
    BodyView.Back -> when (site) {
        Site.ButtockLeft -> 75f to 204f
        Site.ButtockRight -> 125f to 204f
        Site.OuterThighLeft -> 70f to 270f
        Site.OuterThighRight -> 130f to 270f
        else -> null
    }
    BodyView.Cat -> when (site) {
        Site.Scruff -> 212f to 76f
        Site.FlankLeft, Site.FlankRight -> 140f to 112f
        Site.PetThighLeft, Site.PetThighRight -> 92f to 118f
        else -> null
    }
    BodyView.Dog -> when (site) {
        Site.Scruff -> 216f to 70f
        Site.FlankLeft, Site.FlankRight -> 140f to 108f
        Site.PetThighLeft, Site.PetThighRight -> 94f to 112f
        else -> null
    }
}

private const val MAX_DOSE = 1000.0

/**
 * Шаг дозы по начальной дозе и единице: мл — 0,1; дробная доза — 0,5; от 100 — 10; иначе 1 (ЕД, мг). Считается один раз
 * от дозы по схеме, чтобы шаг не менялся на ходу.
 */
internal fun doseStep(unit: String, amount: Double): Double {
    val u = unit.trim().lowercase().trimEnd('.')
    return when {
        u == "мл" || u == "ml" -> 0.1
        amount % 1.0 != 0.0 -> 0.5
        amount >= 100.0 -> 10.0
        else -> 1.0
    }
}

/** Доза на шаг вверх (`direction` = 1) или вниз (−1): не меньше шага и не больше 1000, без накопления погрешности. */
internal fun stepDose(amount: Double, step: Double, direction: Int): Double {
    val next = ((amount + step * direction) * 100).roundToLong() / 100.0
    return next.coerceIn(step, MAX_DOSE)
}

/** Ввод сахара: цифры и один разделитель (точка или запятая), не длиннее 5 знаков. */
internal fun glucoseInput(text: String): String {
    var sep = false
    val out = StringBuilder()
    for (c in text) {
        when {
            c.isDigit() -> out.append(c)
            (c == '.' || c == ',') && !sep && out.isNotEmpty() -> {
                sep = true
                out.append(c)
            }
        }
        if (out.length >= 5) break
    }
    return out.toString()
}

/** Сахар крови, ммоль/л: число от 0,1 до 60; иначе `null` — пустое или невозможное значение в отметку не пишется. */
internal fun glucoseValue(text: String): Double? =
    text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0.1 && it <= 60.0 }

/** Число сахара для заметки отметки: «6.4», «6» — точкой, как в базе. */
internal fun glucoseNumber(text: String): String? = glucoseValue(text)?.let(Codec::num)

/** Цифра на точке: дни с укола; «–» — сюда ещё не кололи; больше 99 — «99+». */
internal fun spotNumber(daysSince: Int?): String = when {
    daysSince == null -> "–"
    daysSince > 99 -> "99+"
    else -> daysSince.toString()
}
