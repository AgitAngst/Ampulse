package io.github.agitangst.ampulse.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import io.github.agitangst.ampulse.Nav

/** Чьи лекарства сейчас на экранах: 0 — «Я». Хранится в `SharedPreferences("ampulse")`, ключ `profile_current`. */
object CurrentProfile {
    private const val KEY = "profile_current"

    var id by mutableLongStateOf(0L)
        private set

    fun load(context: Context) {
        id = context.getSharedPreferences("ampulse", Context.MODE_PRIVATE).getLong(KEY, 0L)
    }

    fun set(context: Context, profileId: Long) {
        id = profileId
        context.getSharedPreferences("ampulse", Context.MODE_PRIVATE).edit { putLong(KEY, profileId) }
    }
}

/** Профили (близкие, питомцы). Заготовка — задача 2.2. */
@Composable
fun ProfilesScreen(nav: Nav) = Unit

/** Новый профиль или правка. Заготовка — задача 2.2. */
@Composable
fun ProfileEditScreen(id: Long, nav: Nav) = Unit
