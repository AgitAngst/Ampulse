package io.github.agitangst.ampulse.ui

import android.content.Context
import androidx.core.content.edit
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.agitangst.ampulse.domain.Profile
import java.time.LocalDate

/** Телефон близкого: что человек может ввести и что уходит в SMS. */
internal object FamilyPhone {
    /** Меньше цифр телефоном не считается (короткие сервисные номера для SMS близкому не годятся). */
    private const val MIN_DIGITS = 5

    private const val MAX_DIGITS = 15

    /** Что остаётся в поле при вводе: цифры, пробел, скобки, дефис и «+» первым знаком; остальное отбрасывается. */
    fun typed(raw: String): String {
        val kept = raw.filter { it.isDigit() || it == '+' || it == ' ' || it == '-' || it == '(' || it == ')' }
        // «+» только в начале.
        val plus = kept.trimStart().startsWith("+")
        return (if (plus) "+" else "") + kept.filter { it != '+' }.take(24)
    }

    /** Номер для отправки: только цифры и ведущий «+». */
    fun clean(raw: String): String {
        val trimmed = raw.trim()
        val digits = trimmed.filter { it.isDigit() }
        return if (trimmed.startsWith("+")) "+$digits" else digits
    }

    /** Похоже на телефон: от 5 до 15 цифр. */
    fun valid(raw: String): Boolean = clean(raw).count { it.isDigit() } in MIN_DIGITS..MAX_DIGITS
}

/**
 * Имя, под которым расписание придёт близкому: у профиля кроме «Я» — его имя; у «Я» — то, что человек вписал на
 * экране QR, а пусто — [fallback]. Одинаковое имя при повторной передаче → у близкого копия обновляется, а не
 * заводится вторая (`Repository.importShared` ищет копию по имени).
 */
internal fun shareOwner(profile: Profile?, typed: String, fallback: String): String =
    if (profile != null && profile.id != 0L) profile.name.trim().ifEmpty { fallback } else typed.trim().ifEmpty { fallback }.take(40)

/** Мелкие настройки вкладки «Близкие» в `SharedPreferences("ampulse")`: подпись для QR и дата обновления копий. */
internal object FamilyPrefs {
    private const val FILE = "ampulse"
    private const val KEY_LABEL = "family_share_label"
    private const val KEY_UPDATED = "family_copy_updated_"

    fun shareLabel(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_LABEL, null).orEmpty()

    fun setShareLabel(context: Context, label: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString(KEY_LABEL, label.trim()) }
    }

    /** Когда копию расписания последний раз обновили по QR; `null` — не записано. */
    fun copyUpdated(context: Context, profileId: Long): LocalDate? {
        val day = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getLong(KEY_UPDATED + profileId, Long.MIN_VALUE)
        return if (day == Long.MIN_VALUE) null else LocalDate.ofEpochDay(day)
    }

    fun markCopyUpdated(context: Context, profileId: Long, day: LocalDate = LocalDate.now()) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putLong(KEY_UPDATED + profileId, day.toEpochDay()) }
    }
}

/** Результат построения QR. */
internal sealed interface QrResult {
    /** Квадрат модулей: `true` — тёмный. Без полей тишины — их рисует экран. */
    class Ok(val modules: Array<BooleanArray>) : QrResult {
        val size: Int get() = modules.size
    }

    /** Текст не помещается в один код. */
    data object TooBig : QrResult
}

/** QR-код через ZXing (только генерация, без сети). Уровень исправления ошибок L — чтобы поместилось расписание. */
internal object QrModules {
    /** Больше байтов в один QR не входит (версия 40, уровень L). */
    const val MAX_BYTES = 2953

    fun build(text: String): QrResult {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return QrResult.TooBig
        return try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 0,
            )
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
            QrResult.Ok(Array(matrix.height) { y -> BooleanArray(matrix.width) { x -> matrix[x, y] } })
        } catch (e: WriterException) {
            QrResult.TooBig
        } catch (e: IllegalArgumentException) {
            QrResult.TooBig
        }
    }
}
