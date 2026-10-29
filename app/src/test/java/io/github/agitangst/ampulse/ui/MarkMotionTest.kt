package io.github.agitangst.ampulse.ui

import io.github.agitangst.ampulse.ui.MarkMotion.Scene
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarkMotionTest {
    private val eps = 0.01f

    @Test
    fun launchStartsEmptyAndEndsAtRest() {
        val start = MarkMotion.frame(Scene.LAUNCH, 0)
        assertEquals(MarkMotion.EMPTY_LEVEL, start.level, eps)
        assertEquals(0f, start.amp, eps)
        assertEquals(MarkMotion.REST, MarkMotion.frame(Scene.LAUNCH, Scene.LAUNCH.durationMs))
        // Сразу перед концом — уже почти покой, без скачка.
        val almost = MarkMotion.frame(Scene.LAUNCH, Scene.LAUNCH.durationMs - 1)
        assertEquals(MarkMotion.REST_LEVEL, almost.level, eps)
        assertEquals(1f, almost.amp, 0.05f)
    }

    @Test
    fun launchLiquidRisesBeforeThePulseGrows() {
        // К 70 % времени жидкость на уровне, пика ещё нет.
        val at70 = MarkMotion.frame(Scene.LAUNCH, (Scene.LAUNCH.durationMs * 0.7).toLong())
        assertEquals(MarkMotion.REST_LEVEL, at70.level, 1f)
        assertTrue(at70.amp < 0.05f)
        // Уровень не растёт вверх по y (жидкость только поднимается).
        var last = Float.MAX_VALUE
        for (t in 0..910 step 10) {
            val level = MarkMotion.frame(Scene.LAUNCH, t.toLong()).level
            assertTrue(level <= last + 0.001f)
            last = level
        }
    }

    @Test
    fun waitRepeatsAndSettlesBetweenBeats() {
        val period = Scene.WAIT.durationMs
        val a = MarkMotion.frame(Scene.WAIT, 100)
        val b = MarkMotion.frame(Scene.WAIT, 100 + period)
        assertEquals(a, b)
        // Между ударами — дежурный пик 0,35; уровень не меняется никогда.
        assertEquals(0.35f, MarkMotion.frame(Scene.WAIT, (period * 0.7).toLong()).amp, eps)
        for (t in 0 until period step 50) {
            assertEquals(MarkMotion.REST_LEVEL, MarkMotion.frame(Scene.WAIT, t).level, eps)
        }
        // Пик удара — полный.
        val peak = (0..period).maxOf { MarkMotion.frame(Scene.WAIT, it).amp }
        assertEquals(1f, peak, 0.02f)
    }

    @Test
    fun doneLiftsAndReturnsToRest() {
        val mid = MarkMotion.frame(Scene.DONE, Scene.DONE.durationMs / 2)
        assertEquals(MarkMotion.REST_LEVEL - 24f, mid.level, 0.5f)
        assertEquals(MarkMotion.REST, MarkMotion.frame(Scene.DONE, Scene.DONE.durationMs))
        val flash = (0 until Scene.DONE.durationMs).maxOf { MarkMotion.frame(Scene.DONE, it).amp }
        assertTrue(flash in 1.2f..1.3f)
    }

    @Test
    fun afterAnOneShotSceneTheMarkIsAtRest() {
        for (scene in listOf(Scene.LAUNCH, Scene.DONE)) {
            assertEquals(MarkMotion.REST, MarkMotion.frame(scene, scene.durationMs + 5000))
        }
    }

    @Test
    fun amplitudeNeverShrinksTheLiquidBelowTheBottom() {
        // Уровень всегда выше «пустого» и не выше горлышка (плечи ампулы — около 520).
        for (scene in Scene.values()) {
            for (t in 0L..scene.durationMs step 20) {
                val f = MarkMotion.frame(scene, t)
                assertTrue(f.level in 560f..MarkMotion.EMPTY_LEVEL + 0.01f)
                assertTrue(f.amp in 0f..1.3f)
            }
        }
    }
}
