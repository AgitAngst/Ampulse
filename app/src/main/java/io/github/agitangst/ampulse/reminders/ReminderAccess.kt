package io.github.agitangst.ampulse.reminders

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Разрешения напоминаний: что разрешено сейчас и как открыть нужную настройку системы. Отказ ничего не ломает —
 * без точных будильников напоминание может опоздать, без «на весь экран» важное придёт обычным уведомлением
 * (anvil/docs/ANDROID.md, §10).
 */
object ReminderAccess {
    /** Что разрешено: уведомления, точные будильники, напоминание на весь экран. */
    data class State(val notifications: Boolean, val exact: Boolean, val fullScreen: Boolean)

    private const val PREFS = "ampulse_reminders_access"
    private const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"

    fun read(context: Context) = State(notificationsOn(context), canScheduleExact(context), canUseFullScreen(context))

    /** Уведомления программы показываются (разрешение на Android 13+ и общий выключатель в системе). */
    fun notificationsOn(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    /** Точные будильники: до Android 12 разрешение не нужно, дальше — настройка «Будильники и напоминания». */
    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** Экран на весь экран для важных: до Android 14 разрешение выдано при установке. */
    fun canUseFullScreen(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    /** Диалог разрешения уведомлений есть только на Android 13+ и только пока система его ещё показывает. */
    fun canAskNotifications(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return false
        // До первого запроса системе нечего «объяснять»; после отказа можно спросить ещё раз, а после «больше не
        // спрашивать» диалог уже не покажется — тогда только настройки.
        val asked = prefs(activity).getBoolean(KEY_ASKED_NOTIFICATIONS, false)
        return !asked || activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Запомнить, что диалог уже показывали (см. [canAskNotifications]). */
    fun markNotificationsAsked(context: Context) {
        prefs(context).edit().putBoolean(KEY_ASKED_NOTIFICATIONS, true).apply()
    }

    /** Настройки уведомлений программы. */
    fun openNotificationSettings(context: Context) = launchSettings(
        context,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    )

    /** Настройка «Будильники и напоминания» (Android 12+); раньше точные будильники и так разрешены. */
    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        launchSettings(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
    }

    /** Настройка «Уведомления на весь экран» (Android 14+); раньше разрешено при установке. */
    fun openFullScreenSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        launchSettings(context, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}")))
    }

    /** Открыть экран настроек; нет такого на телефоне — общие настройки программы. */
    private fun launchSettings(context: Context, intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Log.w("AmpulseReminders", "Settings screen not found: ${intent.action}", e)
            try {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e2: ActivityNotFoundException) {
                Log.w("AmpulseReminders", "App settings not found", e2)
            }
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
