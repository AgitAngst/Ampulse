package io.github.agitangst.ampulse

import io.github.agitangst.anvil.compose.AboutAction
import io.github.agitangst.anvil.compose.Tone

/**
 * Состояние обновления для карточки «О программе»: строка состояния, её тон, одна кнопка и текст ошибки.
 * Общий код знает только этот тип; откуда он берётся — решает [Store] своей сборки (anvil/docs/ANDROID.md, §2).
 */
data class AboutState(
    val status: String?,
    val tone: Tone,
    val action: AboutAction?,
    val error: String?,
)
