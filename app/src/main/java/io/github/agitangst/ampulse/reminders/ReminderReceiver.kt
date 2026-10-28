package io.github.agitangst.ampulse.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.agitangst.ampulse.AppGraph

/**
 * Принимает будильники: сработал приём — показать уведомление (важное — на весь экран); сработал суточный будильник
 * пересчёта — пересчитать, чтобы окно в 48 часов двигалось вперёд. Не экспортирован: будят его только свои
 * `PendingIntent` в `AlarmManager`.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ReminderIntents.ACTION_FIRE -> {
                val dose = ReminderIntents.read(intent) ?: return
                val slot = ReminderIntents.slot(intent)
                goAsyncIo("Showing a reminder") { Notifier.show(context, dose, slot) }
            }
            ReminderIntents.ACTION_REFRESH -> goAsyncIo("Daily refresh") { AppGraph.reminders.rescheduleNow() }
            else -> Log.w(TAG, "Unknown action: ${intent.action}")
        }
    }
}
