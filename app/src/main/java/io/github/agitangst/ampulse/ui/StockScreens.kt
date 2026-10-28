package io.github.agitangst.ampulse.ui

import androidx.compose.runtime.Composable
import io.github.agitangst.ampulse.Nav

/** Запас лекарства: упаковки (макет, экран 5). Заготовка — задача 2.1 этапа 0.4. */
@Composable
fun StockDetailScreen(medicineId: Long, nav: Nav) = Unit

/** Новая упаковка или правка: срок (месяц и год), остаток, вскрытие (макет, экран 41). Заготовка — задача 2.1. */
@Composable
fun PackEditScreen(medicineId: Long, packId: Long, fromScan: Boolean, nav: Nav) = Unit

/** «Сроки годности» (макет, экран 40). Заготовка — задача 2.2. */
@Composable
fun ExpiryScreen(nav: Nav) = Unit
