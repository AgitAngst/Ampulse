package io.github.agitangst.ampulse.domain

import android.content.Context
import androidx.core.content.edit
import java.time.LocalTime

/** Режим дня человека — по нему разбор назначения ставит время приёмов («Настройки → Режим дня»). */
object RoutineStore {
    private const val PREFS = "ampulse"

    fun load(context: Context): Routine {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = Routine()
        fun t(key: String, def: LocalTime) = p.getString(key, null)?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: def
        return Routine(t("routine_wake", d.wake), t("routine_breakfast", d.breakfast), t("routine_lunch", d.lunch), t("routine_dinner", d.dinner), t("routine_sleep", d.sleep))
    }

    fun save(context: Context, r: Routine) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString("routine_wake", r.wake.toString())
            putString("routine_breakfast", r.breakfast.toString())
            putString("routine_lunch", r.lunch.toString())
            putString("routine_dinner", r.dinner.toString())
            putString("routine_sleep", r.sleep.toString())
        }
    }
}
