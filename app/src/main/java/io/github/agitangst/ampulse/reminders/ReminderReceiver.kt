package io.github.agitangst.ampulse.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.agitangst.ampulse.AppGraph

/**
 * Принимает будильники: сработал приём — показать уведомление (важное — на весь экран); сработала «проверка»
 * после повторов — SMS близким, если приём не отмечен; сработал суточный будильник
 * пересчёта — пересчитать, чтобы окно в 48 часов двигалось вперёд. Не экспортирован: будят его только свои
 * `PendingIntent` в `AlarmManager`.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ReminderIntents.ACTION_FIRE -> {
                val dose = ReminderIntents.read(intent) ?: return
                val slot = ReminderIntents.slot(intent)
                if (slot == ReminderCodes.SLOT_CHECK) {
                    // Проверка после всех повторов: уведомления нет, при неотмеченном приёме — SMS близким.
                    goAsyncIo("Missed-dose SMS") { MissedSms.check(context, dose) }
                } else {
                    goAsyncIo("Showing a reminder") { Notifier.show(context, dose, slot) }
                }
            }
            ReminderIntents.ACTION_REFRESH -> goAsyncIo("Daily refresh") { AppGraph.reminders.rescheduleNow() }
            else -> Log.w(TAG, "Unknown action: ${intent.action}")
        }
    }
}
