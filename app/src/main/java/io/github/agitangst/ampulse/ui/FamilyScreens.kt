package io.github.agitangst.ampulse.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.agitangst.ampulse.Nav

/** Вкладка «Близкие» (макет, экраны 12–13). Заготовка — задача 2.1 этапа 0.8. */
@Composable
fun FamilyTab(nav: Nav, modifier: Modifier = Modifier) = Unit

/** Близкий: имя, телефон, SMS. Заготовка — задача 2.1. */
@Composable
fun ContactEditScreen(id: Long, nav: Nav) = Unit

/** QR с моим расписанием. Заготовка — задача 2.1. */
@Composable
fun ShareQrScreen(nav: Nav) = Unit

/** Сканер QR чужого расписания. Заготовка — задача 2.1. */
@Composable
fun CareScanScreen(nav: Nav) = Unit
