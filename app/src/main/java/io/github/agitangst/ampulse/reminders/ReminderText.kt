package io.github.agitangst.ampulse.reminders

import android.content.Context
import android.text.format.DateFormat
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.data.Codec
import io.github.agitangst.ampulse.domain.FoodRule
import io.github.agitangst.ampulse.domain.Medicine
import java.text.NumberFormat
import java.time.LocalDateTime
import java.util.Date
import java.util.Locale

/** Тексты напоминаний: время, доза с единицей, подсказка про еду. Числа и время — по языку и настройкам телефона. */
internal object ReminderText {
    /** Время приёма: 12 или 24 часа — как настроено в телефоне (docs/ui-guideline.md, §8). */
    fun time(context: Context, planned: LocalDateTime): String =
        DateFormat.getTimeFormat(context).format(Date(Codec.millis(planned)))

    /** Число дозы без лишнего «.0» и с десятичным знаком языка: «1», «0,5» / «0.5». */
    fun number(value: Double, locale: Locale): String {
        val format = NumberFormat.getNumberInstance(locale)
        format.isGroupingUsed = false
        format.maximumFractionDigits = 2
        return format.format(value)
    }

    /** Доза с единицей: «1 табл.», «12 ЕД». */
    fun dose(context: Context, medicine: Medicine, amount: Double): String =
        "${number(amount, context.resources.configuration.locales[0])} ${medicine.unit}".trim()

    /** Подсказка про еду; `null` — без правила. */
    fun food(context: Context, rule: FoodRule): String? = when (rule) {
        FoodRule.None -> null
        FoodRule.EmptyStomach -> context.getString(R.string.rem_food_empty)
        FoodRule.Before -> context.getString(R.string.rem_food_before)
        FoodRule.With -> context.getString(R.string.rem_food_with)
        FoodRule.After -> context.getString(R.string.rem_food_after)
    }
}
