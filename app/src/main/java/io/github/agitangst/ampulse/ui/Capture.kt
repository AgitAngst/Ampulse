package io.github.agitangst.ampulse.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Фото рецепта → текст (Tesseract на телефоне, русский и английский). Заготовка — задача 3.1 этапа 0.5. */
@Composable
fun PhotoTextButton(onText: (String) -> Unit, modifier: Modifier = Modifier) = Unit

/** Голос → текст (распознавание речи телефона, без сети, если скачан язык). Заготовка — задача 3.1. */
@Composable
fun VoiceTextButton(onText: (String) -> Unit, modifier: Modifier = Modifier) = Unit
