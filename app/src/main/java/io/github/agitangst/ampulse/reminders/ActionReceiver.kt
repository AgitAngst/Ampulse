package io.github.agitangst.ampulse.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.agitangst.ampulse.AppGraph
import io.github.agitangst.ampulse.domain.EventStatus
import java.time.LocalDateTime

/**
 * Кнопки уведомления: «Принять» отмечает приём, не открывая программу; «Отложить на 10 мин» ставит отдельный будильник.
 * После обеих уведомление убирается. Не экспортирован: нажимает система через `PendingIntent` самого уведомления.
 */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val dose = ReminderIntents.read(intent) ?: return
        val action = intent.action
        goAsyncIo("Notification action $action") {
            when (action) {
                // Уже принятый приём повторно не пишется (защита от двойной дозы) — для уведомления это тоже «готово».
                ReminderIntents.ACTION_TAKE ->
                    AppGraph.repository.mark(dose.toPlannedDose(), EventStatus.Taken, LocalDateTime.now())
                ReminderIntents.ACTION_SNOOZE -> AppGraph.reminders.snooze(dose)
                else -> Log.w(TAG, "Unknown action: $action")
            }
            Notifier.cancel(context, dose)
        }
    }
}
