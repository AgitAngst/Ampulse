package io.github.agitangst.ampulse.reminders

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.Store
import io.github.agitangst.anvil.compose.AnvilLocale

/**
 * SMS близкому из фона (пропуск важного приёма, открытая экстренная карта). Сборка github отправляет сама
 * ([Store.SILENT_SMS]); в сборках магазинов SMS сама не уходит, а окно из фона Android не откроет — приходит уведомление
 * «Отправьте SMS: Анна», касание открывает готовое сообщение, «Отправить» нажимает человек (anvil/docs/ANDROID.md, §2).
 */
internal object SmsOut {
    private const val CHANNEL = "family_sms"

    /** `true` — SMS ушла или уведомление о ней показано. */
    fun deliver(context: Context, name: String, phone: String, text: String): Boolean {
        if (Store.SILENT_SMS) return Store.sendSms(context, phone, text)
        val app = AnvilLocale.wrap(context.applicationContext)
        val manager = app.getSystemService(NotificationManager::class.java) ?: return false
        if (!manager.areNotificationsEnabled()) return false
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, app.getString(R.string.sms_channel), NotificationManager.IMPORTANCE_HIGH),
        )
        val compose = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(phone))).putExtra("sms_body", text)
        val id = (phone + '\n' + text).hashCode()
        val open = PendingIntent.getActivity(app, id, compose, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_dose)
            .setContentTitle(app.getString(R.string.sms_compose_title, name))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text + "\n" + app.getString(R.string.sms_compose_hint)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .build()
        manager.notify(id, notification)
        return true
    }
}
