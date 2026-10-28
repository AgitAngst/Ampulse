package io.github.agitangst.ampulse.reminders

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Bundle
import android.util.Log
import io.github.agitangst.ampulse.AppGraph
import io.github.agitangst.ampulse.MainActivity
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.data.Codec
import io.github.agitangst.ampulse.domain.DoseEvent
import java.time.LocalDateTime

/** Уведомления о приёмах: каналы «Приёмы» и «Важные приёмы», показ с кнопками, снятие. */
internal object Notifier {
    private const val CHANNEL_DOSES = "doses"
    private const val CHANNEL_IMPORTANT = "doses_important"

    private const val IMMUTABLE_UPDATE = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    /** Создать каналы (повторный вызов безопасен: обновляет только названия — язык мог смениться). */
    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val doses = NotificationChannel(CHANNEL_DOSES, context.getString(R.string.rem_channel_doses), NotificationManager.IMPORTANCE_HIGH)
        doses.description = context.getString(R.string.rem_channel_doses_desc)
        doses.enableVibration(true)

        // Важные — звуком будильника: обычный сигнал уведомления легко пропустить.
        val important = NotificationChannel(CHANNEL_IMPORTANT, context.getString(R.string.rem_channel_important), NotificationManager.IMPORTANCE_HIGH)
        important.description = context.getString(R.string.rem_channel_important_desc)
        important.enableVibration(true)
        important.vibrationPattern = longArrayOf(0, 500, 250, 500)
        val alarmSound = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        important.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), alarmSound)

        manager.createNotificationChannels(listOf(doses, important))
    }

    /**
     * Приём уже отмечен («принято» или «пропущено»)? Проверяем по базе в момент срабатывания: будильник мог уйти
     * вперёд отметки (отметили в программе, а пересчёт ещё не дошёл).
     */
    suspend fun isHandled(dose: DoseRef): Boolean {
        val events = AppGraph.repository.eventsNow(dose.planned.minusDays(1), LocalDateTime.now().plusMinutes(1))
        return ReminderPlan.isHandled(dose.medicineId, dose.planned, events)
    }

    /**
     * Показать уведомление о приёме [dose]; [slot] — какое по счёту (0 — в срок, 1..3 — повтор, 9 — отложенное).
     * Приём отмечен или лекарство удалено — вместо показа убрать то, что висит.
     */
    suspend fun show(context: Context, dose: DoseRef, slot: Int) {
        val app = context.applicationContext
        val medicine = AppGraph.repository.medicine(dose.medicineId)
        if (medicine == null || isHandled(dose)) {
            cancel(app, dose)
            return
        }
        ensureChannels(app)
        val manager = app.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) {
            Log.w(TAG, "Notifications are off, reminder for medicine ${dose.medicineId} not shown")
            return
        }

        val line = "${ReminderText.dose(app, medicine, dose.amount)} · ${ReminderText.time(app, dose.planned)}"
        val food = ReminderText.food(app, medicine.food)
        val plannedMs = Codec.millis(dose.planned)
        val extras = Bundle()
        extras.putLong(ReminderIntents.NOTIFICATION_MEDICINE, dose.medicineId)
        extras.putLong(ReminderIntents.NOTIFICATION_PLANNED, plannedMs)

        val builder = Notification.Builder(app, if (medicine.important) CHANNEL_IMPORTANT else CHANNEL_DOSES)
            .setSmallIcon(R.drawable.ic_stat_dose)
            .setContentTitle(medicine.name)
            .setContentText(line)
            .setStyle(Notification.BigTextStyle().bigText(listOfNotNull(line, food).joinToString("\n")))
            .setCategory(if (medicine.important) Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER)
            .setWhen(plannedMs)
            .setShowWhen(true)
            .setAutoCancel(true)
            .addExtras(extras)
            .setContentIntent(openApp(app, dose))
            .addAction(action(app, R.string.rem_action_take, ReminderIntents.ACTION_TAKE, dose, ReminderCodes.SLOT_TAKE))
            .addAction(action(app, R.string.rem_action_snooze, ReminderIntents.ACTION_SNOOZE, dose, ReminderCodes.SLOT_SNOOZE_ACTION))
        // Важное — на весь экран, если система разрешает; нет — обычным уведомлением со звуком будильника.
        if (medicine.important && ReminderAccess.canUseFullScreen(app)) {
            val screen = PendingIntent.getActivity(
                app, dose.code(ReminderCodes.SLOT_SCREEN), ReminderIntents.screen(app, dose, slot), IMMUTABLE_UPDATE,
            )
            builder.setFullScreenIntent(screen, true)
        }
        manager.notify(dose.notificationId, builder.build())
        Log.i(TAG, "Reminder shown: medicine ${dose.medicineId}, slot $slot, important=${medicine.important}")
    }

    /** Убрать уведомление о приёме. */
    fun cancel(context: Context, dose: DoseRef) {
        context.getSystemService(NotificationManager::class.java).cancel(dose.notificationId)
    }

    /** Убрать висящие уведомления о приёмах, у которых уже есть отметка (отметили в программе). */
    fun cancelHandled(context: Context, events: List<DoseEvent>) {
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            for (item in manager.activeNotifications) {
                val extras = item.notification.extras ?: continue
                if (!extras.containsKey(ReminderIntents.NOTIFICATION_MEDICINE) || !extras.containsKey(ReminderIntents.NOTIFICATION_PLANNED)) continue
                val medicineId = extras.getLong(ReminderIntents.NOTIFICATION_MEDICINE)
                val planned = Codec.time(extras.getLong(ReminderIntents.NOTIFICATION_PLANNED))
                if (ReminderPlan.isHandled(medicineId, planned, events)) manager.cancel(item.id)
            }
        } catch (e: RuntimeException) {
            // Часть телефонов отказывает в списке показанных уведомлений — тогда лишнее уберёт само касание.
            Log.w(TAG, "Could not read active notifications", e)
        }
    }

    /** Кнопка уведомления: широковещательное намерение в [ActionReceiver]. */
    private fun action(context: Context, label: Int, action: String, dose: DoseRef, slot: Int): Notification.Action {
        val pending = PendingIntent.getBroadcast(context, dose.code(slot), ReminderIntents.action(context, action, dose), IMMUTABLE_UPDATE)
        return Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_dose), context.getString(label), pending).build()
    }

    /** Касание уведомления — открыть программу. */
    private fun openApp(context: Context, dose: DoseRef): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, dose.code(ReminderCodes.SLOT_OPEN), intent, IMMUTABLE_UPDATE)
    }
}
