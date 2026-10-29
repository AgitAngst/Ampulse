package io.github.agitangst.ampulse.ui

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * Движение знака Ampulse (openspec: ampulse-mark-motion; эталон — reference/mark-motion.html).
 * Двигается только жидкость в ампуле: уровень [Frame.level] и сила пика пульса [Frame.amp] на её поверхности.
 * Чистый код без Compose: кадр по времени сцены.
 */
object MarkMotion {
    /** Уровень жидкости в покое, единицы плитки 1024. */
    const val REST_LEVEL = 590f

    /** Уровень «пустой ампулы»: ниже дна (дно ампулы — 830). */
    const val EMPTY_LEVEL = 900f

    /** Кадр: [level] — y поверхности жидкости, [amp] — сила пика (1 — как в покое). */
    data class Frame(val level: Float, val amp: Float)

    val REST = Frame(REST_LEVEL, 1f)

    /** Сцены: запуск и «готово» играются один раз, ожидание — по кругу, пока идёт операция. */
    enum class Scene(val durationMs: Long, val loops: Boolean) {
        LAUNCH(1300, false),
        WAIT(1600, true),
        DONE(900, false),
    }

    /** Кадр сцены в момент [tMs] от её начала; после конца неповторяемой сцены — покой. */
    fun frame(scene: Scene, tMs: Long): Frame {
        if (tMs < 0) return REST
        if (!scene.loops && tMs >= scene.durationMs) return REST
        val t = (tMs % scene.durationMs).toFloat() / scene.durationMs
        return when (scene) {
            // Жидкость поднимается за 70 % времени, затем пик вырастает с небольшим перелётом.
            Scene.LAUNCH -> Frame(
                level = EMPTY_LEVEL - (EMPTY_LEVEL - REST_LEVEL) * easeOut((t / 0.7f).coerceIn(0f, 1f)),
                amp = if (t < 0.7f) 0f else easeOutBack(((t - 0.7f) / 0.3f).coerceIn(0f, 1f)),
            )
            // Удар: пик быстро растёт и спадает до «дежурного» 0,35, дальше покой до конца цикла.
            Scene.WAIT -> {
                val beat = when {
                    t < 0.12f -> easeOut(t / 0.12f)
                    t < 0.4f -> 1f - easeOut((t - 0.12f) / 0.28f)
                    else -> 0f
                }
                Frame(REST_LEVEL, WAIT_BASE + (1f - WAIT_BASE) * beat)
            }
            // Уровень приподнимается на 24 единицы и возвращается; пик на мгновение выше обычного.
            Scene.DONE -> Frame(
                level = REST_LEVEL - DONE_LIFT * sin(PI.toFloat() * t),
                amp = if (t < 0.5f) 1f + DONE_FLASH * sin(PI.toFloat() * t * 2f) else 1f,
            )
        }
    }

    private const val WAIT_BASE = 0.35f
    private const val DONE_LIFT = 24f
    private const val DONE_FLASH = 0.25f

    private fun easeOut(t: Float): Float = 1f - (1f - t).pow(3)

    private fun easeOutBack(t: Float): Float {
        val c = 1.70158f + 1f
        return 1f + c * (t - 1f).pow(3) + 1.70158f * (t - 1f).pow(2)
    }
}
