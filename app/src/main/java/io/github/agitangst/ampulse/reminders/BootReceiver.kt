package io.github.agitangst.ampulse.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.agitangst.ampulse.AppGraph
import io.github.agitangst.ampulse.ui.EmergencyNotice

/**
 * Будильники системы пропадают при перезагрузке телефона, а при смене времени, часового пояса, обновлении программы и
 * выдаче права на точные будильники их надо ставить заново — приёмы привязаны к часам на стене, а не к секундам.
 *
 * Фильтры — только системные рассылки; чужое намерение с другим действием отбрасывается.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action !in ACTIONS) return
        Log.i(TAG, "Rescheduling after $action")
        goAsyncIo("Rescheduling after $action") {
            AppGraph.reminders.rescheduleNow()
            EmergencyNotice.syncNow(context)
        }
    }

    private companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            // AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED (Android 12+) — строкой, чтобы lint не ругался.
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}
