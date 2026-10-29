package io.github.agitangst.ampulse.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

class CaptureLogicTest {
    @Test
    fun hyphenatedWordIsJoined() {
        assertEquals("Амоксициллин 500 мг", cleanRecognizedText("Амокси-\nциллин 500 мг"))
        // Пробелы вокруг переноса и мягкий перенос.
        assertEquals("Омепразол 20 мг", cleanRecognizedText("Омепра- \n  зол 20 мг"))
        assertEquals("Преднизолон 5 мг", cleanRecognizedText("Предни­\nзолон 5 мг"))
        assertEquals("Metformin 850 mg", cleanRecognizedText("Metfor-\nmin 850 mg"))
    }

    @Test
    fun hyphenIsKeptWhereItIsNotAWordBreak() {
        // После дефиса — цифра или заглавная: это не перенос.
        assertEquals("Мексидол-\n2 мл в/м", cleanRecognizedText("Мексидол-\n2 мл в/м"))
        assertEquals("Вит-\nB12", cleanRecognizedText("Вит-\nB12"))
        // Перед дефисом пробел: тире, а не перенос.
        assertEquals("Таблетки -\nутром", cleanRecognizedText("Таблетки -\nутром"))
        // Дефис внутри строки не трогаем.
        assertEquals("Кальция-D3 1 таб.", cleanRecognizedText("Кальция-D3 1 таб."))
    }

    @Test
    fun spacesAreCollapsed() {
        assertEquals("Мексидол 2 мл в/м, 10 дней", cleanRecognizedText("  Мексидол   2 мл\tв/м,  10 дней  "))
    }

    @Test
    fun blankAndNoiseLinesAreDropped() {
        val raw = "1. Амоксициллин 500 мг\n\n   \n|\n— — —\n2. Омепразол 20 мг\n\u000C"
        assertEquals("1. Амоксициллин 500 мг\n2. Омепразол 20 мг", cleanRecognizedText(raw))
    }

    @Test
    fun lineEndingsAreUnified() {
        assertEquals("Один 1\nДва 2\nТри 3", cleanRecognizedText("Один 1\r\nДва 2\rТри 3\n"))
    }

    @Test
    fun invisibleCharactersAreRemoved() {
        assertEquals("Мексидол 2 мл", cleanRecognizedText("Мекси​дол 2 мл﻿"))
    }

    @Test
    fun nothingRecognizedGivesEmptyText() {
        assertEquals("", cleanRecognizedText(""))
        assertEquals("", cleanRecognizedText("\u000C"))
        assertEquals("", cleanRecognizedText("  |  \n — \n . . ."))
        // Меньше трёх букв и цифр — шум, а не текст.
        assertEquals("", cleanRecognizedText("l\n.\n"))
        assertEquals("Аб1", cleanRecognizedText("Аб1"))
    }

    @Test
    fun sampleSizeKeepsLongSideAtLeastMax() {
        assertEquals(1, captureSampleSize(1000, 800))
        assertEquals(1, captureSampleSize(3000, 2000))
        assertEquals(2, captureSampleSize(4000, 3000))
        assertEquals(4, captureSampleSize(8000, 6000))
        // Портретный снимок: длинная сторона — высота.
        assertEquals(2, captureSampleSize(3000, 4000))
        assertEquals(1, captureSampleSize(0, 0))
    }

    @Test
    fun scaleOnlyShrinks() {
        assertEquals(1f, captureScale(1500, 1000))
        assertEquals(1f, captureScale(2000, 1500))
        assertEquals(0.5f, captureScale(4000, 3000))
        assertEquals(0.5f, captureScale(3000, 4000))
        assertEquals(1f, captureScale(0, 0))
    }

    @Test
    fun speechLanguageFollowsAppLanguage() {
        assertEquals("ru-RU", speechLanguageTag(Locale.forLanguageTag("ru")))
        assertEquals("ru-RU", speechLanguageTag(Locale.forLanguageTag("ru-UA")))
        assertEquals("en-US", speechLanguageTag(Locale.forLanguageTag("en-GB")))
        assertEquals("en-US", speechLanguageTag(Locale.forLanguageTag("de")))
    }

    @Test
    fun recognizerErrorsAreGrouped() {
        assertEquals(VoiceProblem.NoMatch, voiceProblemOf(7))
        assertEquals(VoiceProblem.NoMatch, voiceProblemOf(6))
        assertEquals(VoiceProblem.Offline, voiceProblemOf(2))
        assertEquals(VoiceProblem.Offline, voiceProblemOf(13))
        assertEquals(VoiceProblem.Audio, voiceProblemOf(3))
        assertEquals(VoiceProblem.Permission, voiceProblemOf(9))
        assertEquals(VoiceProblem.Other, voiceProblemOf(8))
        assertEquals(VoiceProblem.Other, voiceProblemOf(5))
    }
}
