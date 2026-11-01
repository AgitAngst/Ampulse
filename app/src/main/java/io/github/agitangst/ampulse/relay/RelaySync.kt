package io.github.agitangst.ampulse.relay

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.agitangst.ampulse.MainActivity
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.Store
import io.github.agitangst.ampulse.domain.Medicine
import io.github.agitangst.ampulse.reminders.DoseRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Ретранслятор на телефоне (openspec: relay-client, docs/dev/relay.md): состояние пары в файле
 * `relay_state.json` в `filesDir`, шаг — [RelayEngine] (отправка очереди и приём), опрос — неточный
 * будильник каждые 15 минут, при открытии приложения и после каждого шага (WorkManager не добавляли —
 * обходимся знакомым AlarmManager-ом, решение исполнителя, ждёт владельца). Уведомление о пропуске —
 * тихий канал рядом с напоминаниями; сами SMS о пропуске остаются как были.
 *
 * Только сборка `github` ([Store.SERVER_SYNC]) и только после знакомства по QR; в остальных сборках
 * этого кода в работе нет.
 */
object RelaySync {
    private const val TAG = "AmpulseRelay"
    private const val FILE = "relay_state.json"
    private const val CHANNEL = "family"
    private const val POLL_MS = 15 * 60 * 1000L

    data class View(val state: RelayState = RelayState(), val busy: Boolean = false)

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> get() = _view

    /** Есть ли ретранслятор в этой сборке. */
    val available: Boolean get() = Store.SERVER_SYNC

    /** Зовётся из `AppGraph.init`; в сборках без сети не делает ничего. */
    fun init(context: Context) {
        if (!available) return
        if (::app.isInitialized) return
        app = context.applicationContext
        _view.value = View(RelayState.fromJson(file().takeIf { it.exists() }?.readText()))
        scheduleNext()
        tick()
    }

    private fun file() = File(app.filesDir, FILE)

    /** Запись через временный файл: оборванная запись не портит прежнее состояние. */
    private fun save(state: RelayState) {
        val file = file()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(state.toJson())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun publish(state: RelayState, busy: Boolean) {
        save(state)
        _view.value = View(state, busy)
    }

    /** Один шаг: движок сам решает, что делать; новое в «ящике» поднимает уведомление. */
    fun tick() {
        if (!available || !::app.isInitialized) return
        if (!_view.value.state.paired) return
        scope.launch {
            mutex.withLock {
                val start = _view.value.state
                if (!start.paired) return@withLock
                publish(start, busy = true)
                val engine = RelayEngine(RelayClient(start.address, start.fingerprint))
                val done = engine.tick(start) { publish(it, busy = true) }
                publish(done, busy = false)
                if (done.inbox.isNotEmpty() && done.inbox.first().id != start.inbox.firstOrNull()?.id) notifyNew(done)
                scheduleNext()
            }
        }
    }

    /** Пропущен важный приём (`MissedSms` тем же событием): в очередь владельцу пары. */
    fun onMissed(medicine: Medicine, dose: DoseRef, who: String) {
        if (!available || !::app.isInitialized) return
        val current = _view.value.state
        if (!current.paired || current.role != RelayState.ROLE_OWNER) return
        scope.launch {
            mutex.withLock {
                val state = _view.value.state
                val ts = dose.planned.atZone(ZoneId.systemDefault()).toEpochSecond()
                val updated = state.enqueued(
                    RelayState.missedMessage("${medicine.id}-$ts", ts, medicine.name, who),
                    System.currentTimeMillis() / 1000,
                )
                publish(updated, _view.value.busy)
            }
            tick()
        }
    }

    /** Забыть пару: состояние стирается, на сервере ящики умрут сами по сроку (90 дней без обращений). */
    fun forget() {
        if (!available || !::app.isInitialized) return
        scope.launch {
            mutex.withLock {
                _view.value = View()
                runCatching { file().delete() }
            }
        }
    }

    /** Знакомство владельца: адрес и отпечаток сервера из его настроек; ящики создаются сразу. */
    fun pairOwner(address: String, fingerprint: String, onDone: (ok: Boolean) -> Unit) {
        if (!available || !::app.isInitialized) return onDone(false)
        val clean = address.trim().let { if (it.startsWith("https://")) it else "https://" + it.trimStart('/') }
        val fp = fingerprint.trim().replace(" ", "").lowercase()
        if (!clean.startsWith("https://") || fp.length != 64) return onDone(false)
        val state = RelayState(
            address = clean, fingerprint = fp,
            boxTo = RelayCrypto.newBoxId(), boxFrom = RelayCrypto.newBoxId(),
            pairKey = RelayBase64.url(RelayCrypto.newPairKey()), role = RelayState.ROLE_OWNER,
        )
        scope.launch {
            val ok = mutex.withLock {
                runCatching {
                    RelayEngine(RelayClient(state.address, state.fingerprint)).pair(state)
                    publish(state, _view.value.busy)
                    true
                }.getOrDefault(false)
            }
            if (ok) tick()
            onDone(ok)
        }
    }

    /** Знакомство близкого: QR от владельца. Чужой или битый код — `false`. */
    fun pairListener(qr: String, onDone: (ok: Boolean) -> Unit = {}) {
        val shared = PairingQr.decode(qr)
        if (!available || !::app.isInitialized || shared == null) return onDone(false)
        scope.launch {
            mutex.withLock {
                publish(shared, _view.value.busy)
            }
            tick()
            onDone(true)
        }
    }

    // --- опрос ---

    private fun scheduleNext() {
        if (!_view.value.state.paired) return
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return
        val intent = PendingIntent.getBroadcast(
            app, 0, Intent(app, PollReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Неточный будильник: точность не нужна (задержка до 15 минут записана в справке), батарею бережёт.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + POLL_MS, intent)
    }

    /** Шаг опроса по будильнику; в манифесте, чтобы пережить перезапуск процесса. */
    class PollReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = tick()
    }

    // --- уведомление ---

    private fun notifyNew(state: RelayState) {
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, app.getString(R.string.relay_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = app.getString(R.string.relay_channel_desc)
            },
        )
        val latest = state.inbox.first()
        val ru = app.resources.configuration.locales[0].language == "ru"
        val time = LocalDateTime.ofInstant(Instant.ofEpochSecond(latest.ts), ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern(if (ru) "d MMMM, HH:mm" else "MMM d, HH:mm"))
        val text = app.getString(R.string.relay_notify_text, latest.who, latest.med, time)
        val open = PendingIntent.getActivity(
            app, 0, Intent(app, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notice = Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_dose)
            .setContentTitle(app.getString(R.string.relay_notify_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFY_ID, notice) }
            .onFailure { Log.w(TAG, "Relay notification failed: ${it.message}") }
    }

    private const val NOTIFY_ID = 40
}

/** QR знакомства: `ampulse:relay:1:` + Base64URL сжатой строки «адрес, отпечаток, ящик владелец→близкий, обратный, ключ». */
object PairingQr {
    const val PREFIX = "ampulse:relay:1:"
    private const val MAGIC = "AMPULSER1"

    /** QR показывает владелец; близкому в QR ящики перевёрнуты (он пишет в обратный, читает прямой). */
    fun encode(state: RelayState): String? {
        if (!state.paired) return null
        val line = listOf(MAGIC, state.address, state.fingerprint, state.boxTo, state.boxFrom, state.pairKey).joinToString("\t")
        return PREFIX + RelayBase64.url(deflate(line.toByteArray(Charsets.UTF_8)))
    }

    fun decode(code: String): RelayState? = runCatching {
        if (!code.startsWith(PREFIX)) return null
        val parts = String(inflate(RelayBase64.url(code.removePrefix(PREFIX))), Charsets.UTF_8).split('\t')
        if (parts.size != 6 || parts[0] != MAGIC) return null
        RelayState(
            address = parts[1], fingerprint = parts[2],
            boxTo = parts[4], boxFrom = parts[3],
            pairKey = parts[5], role = RelayState.ROLE_LISTENER,
        ).takeIf { it.paired && it.boxTo != it.boxFrom }
    }.getOrNull()

    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(bytes)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) {
            val n = deflater.deflate(buffer)
            if (n > 0) out.write(buffer, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun inflate(bytes: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(bytes)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val n = inflater.inflate(buffer)
            if (n > 0) out.write(buffer, 0, n)
            if (n == 0 || inflater.finished()) break
        }
        inflater.end()
        return out.toByteArray()
    }
}
