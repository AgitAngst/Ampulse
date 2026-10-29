package io.github.agitangst.ampulse.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.agitangst.ampulse.AppGraph
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.domain.Profile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Постоянное уведомление «Экстренная карта — <имя>» на экране блокировки (макет, экран 16; задача 2.2). Своё на каждый
 * профиль, у карты которого включено «Показывать на экране блокировки»: канал низкой важности без звука, содержимое
 * видно и на заблокированном экране (`VISIBILITY_PUBLIC`), касание открывает [EmergencyActivity] без разблокировки.
 *
 * Уведомление ставится при сохранении карты, при запуске программы и после перезагрузки телефона (`BootReceiver`) —
 * [sync] приводит набор уведомлений в соответствие с картами в базе.
 */
object EmergencyNotice {
    private const val TAG = "AmpulseEmergency"
    private const val CHANNEL = "emergency"

    /** Тег уведомлений: номер — id профиля, поэтому с напоминаниями о приёмах (без тега) они не пересекаются. */
    private const val NOTICE_TAG = "emergency_card"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Канал: тихий, без значка на иконке программы, содержимое видно на экране блокировки. Повторный вызов безопасен. */
    private fun ensureChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.emergency_channel), NotificationManager.IMPORTANCE_LOW)
        channel.description = context.getString(R.string.emergency_channel_desc)
        channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        channel.setShowBadge(false)
        channel.enableVibration(false)
        channel.setSound(null, null)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Показать (обновить) уведомление карты профиля [profileId] с именем [name]. Уведомления выключены — молча нет. */
    fun show(context: Context, profileId: Long, name: String) {
        val app = context.applicationContext
        val manager = app.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) {
            Log.w(TAG, "Notifications are off, the emergency card notice is not shown")
            return
        }
        ensureChannel(app)
        val intent = Intent(app, EmergencyActivity::class.java)
            .putExtra(EmergencyActivity.EXTRA_PROFILE, profileId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open = PendingIntent.getActivity(
            app, profileId.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notice = Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_emergency)
            .setContentTitle(app.getString(R.string.emergency_notice_title, name))
            .setContentText(app.getString(R.string.emergency_notice_text))
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .build()
        manager.notify(NOTICE_TAG, profileId.toInt(), notice)
    }

    /** Убрать уведомление карты профиля [profileId]. */
    fun hide(context: Context, profileId: Long) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTICE_TAG, profileId.toInt())
    }

    /** Привести уведомления в соответствие с картами и подождать. Для приёмников и экрана правки. */
    suspend fun syncNow(context: Context) {
        val app = context.applicationContext
        val repo = AppGraph.repository
        val profiles = repo.profiles.first()
        val ids = listOf(0L) + profiles.map { it.id }
        val wanted = mutableSetOf<Int>()
        for (id in ids) {
            if (repo.emergencyNow(id).onLockScreen) {
                show(app, id, nameOf(app, id, profiles))
                wanted += id.toInt()
            } else {
                hide(app, id)
            }
        }
        // Профиль удалили вместе с картой — его уведомление не должно висеть.
        val manager = app.getSystemService(NotificationManager::class.java)
        try {
            for (item in manager.activeNotifications) {
                if (item.tag == NOTICE_TAG && item.id !in wanted) manager.cancel(NOTICE_TAG, item.id)
            }
        } catch (e: RuntimeException) {
            // Часть телефонов отказывает в списке показанных уведомлений — тогда лишнее уберёт следующий пересчёт.
            Log.w(TAG, "Could not read active notifications", e)
        }
    }

    /** То же, но в фоне и без ожидания — для запуска программы. */
    fun sync(context: Context) {
        val app = context.applicationContext
        scope.launch {
            try {
                syncNow(app)
            } catch (e: Exception) {
                Log.w(TAG, "Emergency card notice sync failed", e)
            }
        }
    }

    /** Имя владельца карты: «Я» — «Владелец телефона» (имени самого владельца программа не знает). */
    internal fun nameOf(context: Context, profileId: Long, profiles: List<Profile>): String =
        if (profileId == 0L) {
            context.getString(R.string.emergency_owner)
        } else {
            profiles.firstOrNull { it.id == profileId }?.name?.takeIf { it.isNotBlank() } ?: context.getString(R.string.emergency_owner)
        }
}
