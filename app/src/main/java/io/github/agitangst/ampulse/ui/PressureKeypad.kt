package io.github.agitangst.ampulse.ui

import io.github.agitangst.ampulse.domain.ReadingError
import io.github.agitangst.ampulse.domain.maxDigits
import io.github.agitangst.ampulse.domain.validate

/** Поле ввода замера: порядок слева направо — верхнее, нижнее, пульс. */
internal enum class EntryField { Sys, Dia, Pulse }

/** Клавиша своей цифровой клавиатуры: цифра, «Стереть» или «Далее» (docs/ui-guideline.md, §6). */
internal sealed interface EntryKey {
    data class Digit(val digit: Char) : EntryKey
    data object Delete : EntryKey
    data object Next : EntryKey
}

/**
 * Набранное в трёх полях и поле, которое сейчас набирается. Чистая логика клавиатуры — без Android, чтобы проверять
 * тестами: цифра дописывается в активное поле, набранное поле само отдаёт ввод следующему, «Стереть» на пустом поле
 * возвращает к предыдущему и стирает его последнюю цифру.
 */
internal data class EntryDigits(
    val sys: String = "",
    val dia: String = "",
    val pulse: String = "",
    val active: EntryField = EntryField.Sys,
) {
    fun value(field: EntryField): String = when (field) {
        EntryField.Sys -> sys
        EntryField.Dia -> dia
        EntryField.Pulse -> pulse
    }

    private fun withValue(field: EntryField, value: String, active: EntryField = this.active): EntryDigits = when (field) {
        EntryField.Sys -> copy(sys = value, active = active)
        EntryField.Dia -> copy(dia = value, active = active)
        EntryField.Pulse -> copy(pulse = value, active = active)
    }

    /** Поле набрано: цифр столько, сколько допускает `maxDigits` (три — для первой цифры 1 или 2 у верхнего). */
    fun isFull(field: EntryField): Boolean {
        val v = value(field)
        return v.isNotEmpty() && v.length >= maxDigits(field.ordinal, v)
    }

    /** Касание поля — выбрать его; набранное остаётся. */
    fun pick(field: EntryField): EntryDigits = copy(active = field)

    fun press(key: EntryKey): EntryDigits = when (key) {
        is EntryKey.Digit -> type(key.digit)
        EntryKey.Delete -> delete()
        EntryKey.Next -> next()
    }

    private fun type(digit: Char): EntryDigits {
        val current = value(active)
        // Число не начинается с нуля; в набранное поле цифра не дописывается (нужно стереть или выбрать другое поле).
        if (digit == '0' && current.isEmpty()) return this
        val typed = current + digit
        if (current.length >= maxDigits(active.ordinal, typed)) return this
        val full = typed.length >= maxDigits(active.ordinal, typed)
        val goNext = full && active != EntryField.Pulse
        return withValue(active, typed, if (goNext) EntryField.entries[active.ordinal + 1] else active)
    }

    private fun delete(): EntryDigits {
        val current = value(active)
        if (current.isNotEmpty()) return withValue(active, current.dropLast(1))
        if (active == EntryField.Sys) return this
        val previous = EntryField.entries[active.ordinal - 1]
        return withValue(previous, value(previous).dropLast(1), previous)
    }

    private fun next(): EntryDigits =
        if (active == EntryField.Pulse) this else copy(active = EntryField.entries[active.ordinal + 1])

    companion object {
        /** Поля, заполненные принятым замером (фото, голос, тонометр); без пульса курсор встаёт на пульс. */
        fun filled(sys: Int, dia: Int, pulse: Int?) = EntryDigits(
            sys = sys.toString(),
            dia = dia.toString(),
            pulse = pulse?.toString().orEmpty(),
            active = if (pulse == null) EntryField.Pulse else EntryField.Sys,
        )
    }
}

/** Что можно сказать о набранном. */
internal sealed interface EntryCheck {
    /** Верхнее или нижнее ещё не набрано. */
    data object Incomplete : EntryCheck

    /** Числа не прошли проверку: верхнее не больше нижнего или неправдоподобные. */
    data class Invalid(val error: ReadingError) : EntryCheck

    /** Верхнее и нижнее набраны и правдоподобны; [pulseUnfinished] — пульс начат, но не дописан (сохранить нельзя). */
    data class Ready(val sys: Int, val dia: Int, val pulse: Int?, val pulseUnfinished: Boolean) : EntryCheck {
        val canSave: Boolean get() = !pulseUnfinished
    }
}

/** Проверка набранного (`validate` из домена) — когда верхнее и нижнее набраны. */
internal fun EntryDigits.check(): EntryCheck {
    if (!isFull(EntryField.Sys) || !isFull(EntryField.Dia)) return EntryCheck.Incomplete
    val pulseDone = isFull(EntryField.Pulse)
    val pulseValue = if (pulseDone) pulse.toInt() else null
    val error = validate(sys.toInt(), dia.toInt(), pulseValue)
    if (error != null) return EntryCheck.Invalid(error)
    return EntryCheck.Ready(sys.toInt(), dia.toInt(), pulseValue, pulseUnfinished = pulse.isNotEmpty() && !pulseDone)
}
