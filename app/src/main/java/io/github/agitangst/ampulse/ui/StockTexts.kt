package io.github.agitangst.ampulse.ui

import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.domain.Pack
import io.github.agitangst.ampulse.domain.TimeLeft
import java.time.LocalDate
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import kotlin.math.roundToLong

/** Тексты запаса и сроков по локали: срок как на упаковке, остаток срока, количество. Даты и числа — как в [MedTexts]. */

/** Срок как на упаковке: RU `08.2028`, EN `08/2028`. */
internal fun MedTexts.monthYear(d: LocalDate): String =
    d.format(DateTimeFormatter.ofPattern(string(R.string.stock_month_year_pattern), locale))

/** День с месяцем: RU `05.09`, EN `Sep 5`. */
internal fun MedTexts.dayMonth(d: LocalDate): String =
    d.format(DateTimeFormatter.ofPattern(string(R.string.stock_day_month_pattern), locale))

/** Срок упаковки: с числом на упаковке — полная дата, без числа — месяц и год. */
internal fun MedTexts.packExpiry(p: Pack): String? =
    p.expiry?.let { if (p.expiryHasDay) date(it) else monthYear(it) }

/** Название месяца целиком для TalkBack: «август». */
internal fun MedTexts.monthFull(m: Int): String = Month.of(m).getDisplayName(TextStyle.FULL_STANDALONE, locale)

/** Название месяца коротко для сетки: «авг». */
internal fun MedTexts.monthShort(m: Int): String =
    Month.of(m).getDisplayName(TextStyle.SHORT_STANDALONE, locale).removeSuffix(".")

/** Остаток срока с числом: «19 дней», «3 мес.», «2 года». */
internal fun MedTexts.timeLeftText(t: TimeLeft): String = when (t) {
    is TimeLeft.Days -> days(t.n)
    is TimeLeft.Months -> string(R.string.stock_months_short, t.n)
    is TimeLeft.Years -> plural(R.plurals.stock_years, t.n, t.n)
}

/** Остаток срока для крупного числа: число отдельно, единица отдельно («19» и «дней»). */
internal fun MedTexts.timeLeftParts(t: TimeLeft): Pair<String, String> {
    val n = when (t) {
        is TimeLeft.Days -> t.n
        is TimeLeft.Months -> t.n
        is TimeLeft.Years -> t.n
    }
    return splitNumber(timeLeftText(t), n)
}

/** «17 дней» → («17», «дней»): число из склонения отдельно, чтобы поставить его крупно. */
internal fun splitNumber(text: String, n: Int): Pair<String, String> {
    val num = n.toString()
    return num to (if (text.startsWith(num)) text.removePrefix(num) else text.replace(num, "")).trim()
}

/** Количество, округлённое до целого, для прогноза: «≈ 8 табл.». */
internal fun MedTexts.approx(amount: Double, unit: String): String = dose(amount.roundToLong().toDouble(), unit)
