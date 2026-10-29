package io.github.agitangst.ampulse.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.agitangst.ampulse.domain.DeviceReading

/** «Принять с тонометра по Bluetooth»: поиск, соединение, замер. Заготовка — задача 3.1 этапа 0.7. */
@Composable
fun TonometerButton(onReading: (DeviceReading) -> Unit, modifier: Modifier = Modifier) = Unit
