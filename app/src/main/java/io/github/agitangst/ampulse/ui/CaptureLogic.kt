package io.github.agitangst.ampulse.ui

import java.util.Locale

// Чистая часть ввода назначения фото и голосом (задача 3.1): чистка распознанного текста, размеры снимка, язык речи,
// разбор кодов ошибок распознавателя. Без Android — проверяется юнит-тестом.

/** Длинная сторона снимка перед распознаванием, пикселей: больше Tesseract только медленнее, не точнее. */
internal const val CAPTURE_MAX_SIDE = 2000

/** Меньше стольких букв и цифр во всём тексте — считаем, что ничего не прочитано (шум вроде «|||», «—»). */
private const val MIN_MEANINGFUL_CHARS = 3

/** Перенос слова: буква, дефис, конец строки, строчная буква — «амокси-» / «циллин». */
private val HYPHEN_BREAK = Regex("(\\p{L})[\\u2010\\u2011-] *\\n *(\\p{Ll})")

/** Мягкий перенос в конце строки склеивается всегда. */
private val SOFT_HYPHEN_BREAK = Regex("\\u00AD *\\n *")

private val LINE_SEPARATORS = Regex("[\\r\\u000C\\u0085\\u2028\\u2029]")
private val INVISIBLE = Regex("[\\u200B-\\u200D\\uFEFF\\u00AD]")
private val SPACE_LIKE = Regex("[\\t\\u00A0\\u2007\\u202F]")
private val MANY_SPACES = Regex(" {2,}")

/**
 * Приводит текст, который вернул Tesseract, к виду для поля «Текст назначения»: единые переводы строк (Tesseract
 * дописывает в конец страницы `\f`), склеенные переносы слов («амокси-\nциллин» → «амоксициллин»), один пробел вместо
 * нескольких, ни пустых строк, ни строк из одних знаков («|», «—»). Дефис склеивается, только если до него буква, а
 * после переноса — строчная: «таблетки -\nутром» и «Мексидол-\n2 мл» остаются как есть. Прочитано меньше трёх
 * букв и цифр — пустая строка.
 */
internal fun cleanRecognizedText(raw: String): String {
    val unified = raw
        .replace("\r\n", "\n")
        .replace(LINE_SEPARATORS, "\n")
        .replace(SPACE_LIKE, " ")
        .replace(SOFT_HYPHEN_BREAK, "")
        .replace(INVISIBLE, "")
        .replace(HYPHEN_BREAK, "\$1\$2")
    val lines = unified.split('\n')
        .map { MANY_SPACES.replace(it, " ").trim() }
        .filter { line -> line.any { it.isLetterOrDigit() } }
    val text = lines.joinToString("\n")
    return if (text.count { it.isLetterOrDigit() } < MIN_MEANINGFUL_CHARS) "" else text
}

/**
 * Во сколько раз (степень двойки) уменьшить снимок при чтении файла, чтобы длинная сторона осталась не меньше [max]:
 * снимок камеры 8000×6000 разворачивается в 2000×1500, а не в 192 МБ.
 */
internal fun captureSampleSize(width: Int, height: Int, max: Int = CAPTURE_MAX_SIDE): Int {
    val longest = maxOf(width, height)
    var sample = 1
    while (longest / (sample * 2) >= max) sample *= 2
    return sample
}

/** Масштаб до [max] по длинной стороне; снимок поменьше не растягивается. */
internal fun captureScale(width: Int, height: Int, max: Int = CAPTURE_MAX_SIDE): Float {
    val longest = maxOf(width, height)
    return if (longest <= max || longest <= 0) 1f else max.toFloat() / longest
}

/** Язык распознавания речи по языку программы: русский — `ru-RU`, всё остальное — `en-US`. */
internal fun speechLanguageTag(locale: Locale): String =
    if (locale.language.equals("ru", ignoreCase = true)) "ru-RU" else "en-US"

/** Что сказать человеку, когда распознаватель речи вернул ошибку. */
internal enum class VoiceProblem {
    /** Ничего не расслышано или молчание. */
    NoMatch,

    /** Нет сети, а язык для работы без интернета не скачан (или не поддерживается). */
    Offline,

    /** Микрофон занят или недоступен. */
    Audio,

    /** Нет права на микрофон. */
    Permission,

    /** Остальное: служба занята, ошибка клиента. */
    Other,
}

/** Код ошибки `SpeechRecognizer.ERROR_*` → [VoiceProblem]. Числа записаны прямо: часть констант появилась в API 31. */
internal fun voiceProblemOf(error: Int): VoiceProblem = when (error) {
    6, 7 -> VoiceProblem.NoMatch // SPEECH_TIMEOUT, NO_MATCH
    1, 2, 4, 11, 12, 13 -> VoiceProblem.Offline // NETWORK_TIMEOUT, NETWORK, SERVER, SERVER_DISCONNECTED, LANGUAGE_NOT_SUPPORTED, LANGUAGE_UNAVAILABLE
    3 -> VoiceProblem.Audio // AUDIO
    9 -> VoiceProblem.Permission // INSUFFICIENT_PERMISSIONS
    else -> VoiceProblem.Other // CLIENT, RECOGNIZER_BUSY, TOO_MANY_REQUESTS и т. д.
}
