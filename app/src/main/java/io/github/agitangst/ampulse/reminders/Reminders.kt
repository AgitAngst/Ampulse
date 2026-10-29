package io.github.agitangst.ampulse.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.util.Log
import io.github.agitangst.ampulse.AppGraph
import io.github.agitangst.ampulse.Store
import io.github.agitangst.ampulse.data.Codec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

internal const val TAG = "AmpulseReminders"

/** Фоновые дела напоминаний живут дольше любого экрана: свои корутины на потоках ввода-вывода. */
private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Работа приёмника в корутине: `goAsync` держит процесс, пока [block] не закончится (у приёмника на всё около 10 секунд,
 * поэтому в [block] — только быстрое: база и уведомление).
 */
internal fun BroadcastReceiver.goAsyncIo(what: String, block: suspend () -> Unit) {
    val result = goAsync()
    ioScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "$what failed", e)
        } finally {
            result.finish()
        }
    }
}

/**
 * Напоминания о приёмах: будильники системы, уведомления, повтор важного.
 *
 * [reschedule] зовётся после каждой записи в базу и при запуске ([io.github.agitangst.ampulse.AppGraph]): берёт схемы
 * и отметки, считает ближайшие напоминания ([ReminderPlan]) и ставит их `AlarmManager`-ом; прежние, которых в новом
 * плане нет, отменяет. Коды поставленных будильников лежат в `SharedPreferences` — по ним находится, что отменять.
 */
class Reminders(context: Context) {
    private val app = context.applicationContext
    private val alarms = app.getSystemService(AlarmManager::class.java)
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Пересчёты идут по очереди: два одновременных перетёрли бы друг другу список кодов. */
    private val lock = Mutex()

    /** Пересчитать будильники в фоне и не ждать. */
    fun reschedule() {
        ioScope.launch { rescheduleNow() }
    }

    /** Пересчитать будильники и дождаться конца (для приёмников с `goAsync`). */
    suspend fun rescheduleNow() {
        withContext(Dispatchers.IO) {
            lock.withLock {
                try {
                    rebuild()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Rescheduling failed", e)
                }
            }
        }
    }

    /**
     * Отложить напоминание о приёме на [minutes] минут: свой будильник, который не трогает пересчёт, пока приём не
     * отмечен. Сработав, он снова покажет уведомление.
     */
    suspend fun snooze(dose: DoseRef, minutes: Int = ReminderPlan.SNOOZE_MINUTES) {
        withContext(Dispatchers.IO) {
            lock.withLock {
                val fireAt = System.currentTimeMillis() + minutes * 60_000L
                if (arm(dose, ReminderCodes.SLOT_SNOOZE, fireAt)) {
                    val code = dose.code(ReminderCodes.SLOT_SNOOZE)
                    val entries = HashSet<String>()
                    for (entry in prefs.getStringSet(KEY_SNOOZES, null).orEmpty()) {
                        if (Snooze.parse(entry)?.code != code) entries += entry
                    }
                    entries += Snooze(code, dose.medicineId, Codec.millis(dose.planned), fireAt).encode()
                    prefs.edit().putStringSet(KEY_SNOOZES, entries).apply()
                    Log.i(TAG, "Snoozed medicine ${dose.medicineId} for $minutes min")
                }
            }
        }
    }

    private suspend fun rebuild() {
        val repository = AppGraph.repository
        val now = LocalDateTime.now()
        val nowMs = System.currentTimeMillis()
        // Отметки за двое суток назад: приём, отмеченный заранее или с опозданием, всё равно найдётся.
        val events = repository.eventsNow(now.minusDays(2), now.plusDays(1))
        // Копии чужого расписания не напоминаются; «проверка» для SMS о пропуске — только если есть кому и чем слать.
        val silent = repository.profiles.first().filter { it.watchOnly }.map { it.id }.toSet()
        val missedCheck = Store.SILENT_SMS && repository.contactsNow().any { it.smsMissed && it.phone.isNotBlank() }
        val plan = ReminderPlan.upcoming(repository.schedulesNow(), repository.medicines.first(), events, now, silent, missedCheck)
        Notifier.ensureChannels(app)

        // Будильники нового плана.
        val armed = HashSet<Int>()
        for (reminder in plan) {
            if (arm(reminder.dose, reminder.slot, Codec.millis(reminder.fireAt))) armed += reminder.requestCode
        }
        // Прежние, которых в плане уже нет (приём отмечен, схема изменена, лекарство удалено), — отменить.
        for (text in prefs.getStringSet(KEY_CODES, null).orEmpty()) {
            val code = text.toIntOrNull() ?: continue
            if (code !in armed) cancelAlarm(code)
        }
        // Отложенные: сработавшие и отмеченные убрать, остальные оставить.
        val keptSnoozes = HashSet<String>()
        for (entry in prefs.getStringSet(KEY_SNOOZES, null).orEmpty()) {
            val snooze = Snooze.parse(entry) ?: continue
            val handled = ReminderPlan.isHandled(snooze.medicineId, Codec.time(snooze.plannedMs), events)
            if (snooze.fireMs <= nowMs || handled) cancelAlarm(snooze.code) else keptSnoozes += entry
        }
        armRefresh(nowMs)
        val armedText = HashSet<String>()
        for (code in armed) armedText += code.toString()
        prefs.edit()
            .putStringSet(KEY_CODES, armedText)
            .putStringSet(KEY_SNOOZES, keptSnoozes)
            .apply()
        // Уведомления о приёмах, отмеченных в программе, больше не нужны.
        Notifier.cancelHandled(app, events)
        Log.i(TAG, "Rescheduled: ${armed.size} reminders, ${keptSnoozes.size} snoozed, exact=${ReminderAccess.canScheduleExact(app)}")
    }

    /**
     * Поставить будильник на [triggerAt] (мс). Точный — если система разрешает, иначе неточный (может опоздать на
     * несколько минут). `false` — не получилось (запись в журнале, программа не падает).
     */
    private fun arm(dose: DoseRef, slot: Int, triggerAt: Long): Boolean {
        val pending = PendingIntent.getBroadcast(
            app, dose.code(slot), ReminderIntents.fire(app, dose, slot), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            if (ReminderAccess.canScheduleExact(app)) {
                try {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
                    return true
                } catch (e: SecurityException) {
                    Log.w(TAG, "Exact alarm refused, using an inexact one", e)
                }
            }
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Could not set an alarm", e)
            return false
        }
    }

    /** Отменить будильник по коду: находим `PendingIntent` без создания и снимаем. */
    private fun cancelAlarm(code: Int) {
        val pending = PendingIntent.getBroadcast(
            app, code, ReminderIntents.base(app), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarms.cancel(pending)
        pending.cancel()
    }

    /** Раз в сутки — пересчёт: окно в 48 часов двигается вперёд, даже если программу не открывали. */
    private fun armRefresh(nowMs: Long) {
        val pending = PendingIntent.getBroadcast(
            app, REFRESH_CODE, ReminderIntents.refresh(app), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nowMs + ReminderPlan.REFRESH_HOURS * 3_600_000L, pending)
        } catch (e: Exception) {
            Log.w(TAG, "Could not set the refresh alarm", e)
        }
    }

    /** Отложенное напоминание в `SharedPreferences`: код будильника, приём (лекарство и время) и время срабатывания. */
    private data class Snooze(val code: Int, val medicineId: Long, val plannedMs: Long, val fireMs: Long) {
        fun encode() = "$code|$medicineId|$plannedMs|$fireMs"

        companion object {
            fun parse(text: String): Snooze? {
                val p = text.split('|')
                if (p.size != 4) return null
                return try {
                    Snooze(p[0].toInt(), p[1].toLong(), p[2].toLong(), p[3].toLong())
                } catch (e: NumberFormatException) {
                    null
                }
            }
        }
    }

    private companion object {
        const val PREFS = "ampulse_reminders"
        const val KEY_CODES = "codes"
        const val KEY_SNOOZES = "snoozes"

        /** Код будильника пересчёта — отдельный от кодов приёмов. */
        const val REFRESH_CODE = 1
    }
}
