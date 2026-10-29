package io.github.agitangst.ampulse.sync

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.agitangst.ampulse.AppGraph
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64

/**
 * «Сервер» — необязательное одностороннее зеркало на свой сервер Ampulse (openspec: server-sync, docs/dev/server-sync.md).
 * Только сборка github ([Store.SERVER_SYNC]); выключено, пока человек не вошёл; ничего не скачивается.
 *
 * Запуск выгрузки: вручную («Отправить сейчас»), при открытии приложения и через [DELAY_MS] после изменения данных
 * (отметка приёма, правка лекарства) — одной отложенной задачей. Без WorkManager: закрытое приложение ничего не шлёт.
 * Пароль нигде не хранится; токен — в приватных `SharedPreferences` (`server_sync_secret`, при переносе на другой
 * телефон не едет). Соль идентификаторов и остальное — `server_sync`, состояние выгрузки — файл в `filesDir`.
 */
// Токен и соль пишутся через commit(): их потеря (убили процесс до записи) задвоила бы выложенное или разлогинила без причины.
@SuppressLint("ApplySharedPref")
object ServerSync {
    /** Через сколько после изменения данных уходит выгрузка (изменения за это время собираются в один запрос). */
    const val DELAY_MS = 30_000L

    /** Не чаще раза в минуту при открытии приложения (поворот экрана и возвращение не должны слать каждый раз). */
    private const val START_GAP_MS = 60_000L

    /** Что пошло не так при последней выгрузке; пока не пройдёт следующая, показывается на экране «Сервер». */
    enum class Problem { NoConnection, UnknownHost, Tls, RateLimited, Server, Other }

    /**
     * Состояние для экранов. [signedIn] = выгрузка включена. [sessionEnded] — сервер не узнал токен, мы вышли.
     * [lastAt] — когда выгрузка удалась (мс, 0 — ещё не удавалась), [stored] — записей на сервере по нашим сведениям,
     * [rejected] и [rejectedCodes] — записи, которые сервер не принял.
     */
    data class Status(
        val signedIn: Boolean = false,
        val server: String = "",
        val login: String = "",
        val busy: Boolean = false,
        val sessionEnded: Boolean = false,
        val lastAt: Long = 0,
        val stored: Int = 0,
        val rejected: Int = 0,
        val rejectedCodes: List<String> = emptyList(),
        val problem: Problem? = null,
        val problemCode: String? = null,
        val retryAfterSecs: Int = 0,
    )

    private const val TAG = "AmpulseServerSync"

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var secret: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> get() = _status

    private var pending: Job? = null
    @Volatile private var dirty = false
    @Volatile private var lastStartAt = 0L

    /** Есть ли в этой сборке «Сервер». */
    val available: Boolean get() = Store.SERVER_SYNC

    /** Зовётся один раз из `AppGraph.init`. */
    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        prefs = app.getSharedPreferences("server_sync", Context.MODE_PRIVATE)
        secret = app.getSharedPreferences("server_sync_secret", Context.MODE_PRIVATE)
        _status.value = readStatus()
    }

    private fun token(): String? = secret.getString("token", null)?.takeIf { it.isNotEmpty() }

    private fun readStatus(busy: Boolean = false, problem: Problem? = null, code: String? = null, retry: Int = 0): Status = Status(
        signedIn = token() != null,
        server = prefs.getString("server", "").orEmpty(),
        login = prefs.getString("login", "").orEmpty(),
        busy = busy,
        sessionEnded = prefs.getBoolean("session_ended", false),
        lastAt = prefs.getLong("last_at", 0),
        stored = prefs.getInt("stored", 0),
        rejected = prefs.getInt("rejected", 0),
        rejectedCodes = prefs.getString("rejected_codes", "").orEmpty().split(',').filter { it.isNotEmpty() },
        problem = problem,
        problemCode = code,
        retryAfterSecs = retry,
    )

    // --- Запуск ---

    /** Открытие приложения: если вошли — выгрузка (не чаще раза в минуту). */
    fun onStart() {
        if (!available || !::app.isInitialized || token() == null) return
        val now = System.currentTimeMillis()
        if (now - lastStartAt < START_GAP_MS) return
        lastStartAt = now
        scope.launch { runSync() }
    }

    /** Данные изменились (отметка приёма, правка лекарства): через [DELAY_MS] одна выгрузка за все изменения. */
    fun onChange() {
        if (!available || !::app.isInitialized || token() == null) return
        dirty = true
        synchronized(this) {
            if (pending?.isActive == true) return
            pending = scope.launch {
                delay(DELAY_MS)
                var rounds = 0
                // Изменение во время самой выгрузки — ещё один заход (но не бесконечно).
                while (dirty && rounds++ < 3) {
                    dirty = false
                    runSync()
                }
            }
        }
    }

    /** «Отправить сейчас». */
    fun syncNow() {
        if (!available || token() == null || _status.value.busy) return
        scope.launch { runSync() }
    }

    // --- Выгрузка ---

    private suspend fun runSync() = mutex.withLock {
        val token = token() ?: return@withLock
        _status.value = readStatus(busy = true)
        try {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val builder = MirrorBuilder(salt(), zone, app.getString(R.string.profile_me))
            val window = builder.window(today)
            val snapshot = AppGraph.repository.mirrorSnapshot(
                today.minusDays(MirrorBuilder.PAST_DAYS).atStartOfDay(),
                today.plusDays(MirrorBuilder.FUTURE_DAYS + 1).atStartOfDay(),
            )
            val set = builder.build(snapshot, today)
            val before = loadState()
            var latest = before
            val keepFrom = System.currentTimeMillis() / 1000 - SyncState.KEEP_DAYS * 86_400
            val report = SyncEngine(client()).sync(token, set, window, before, keepFrom) { latest = it; saveState(it) }
            val sticky = latest.rejected.size + report.rejected.count { "${it.kind}/${it.id}" !in latest.rejected }
            val codes = (latest.refusedCodes + report.rejected.map { it.code }).distinct()
            prefs.edit()
                .putLong("last_at", System.currentTimeMillis())
                .putInt("stored", report.stored)
                .putInt("rejected", sticky)
                .putString("rejected_codes", codes.joinToString(","))
                .apply()
            _status.value = readStatus()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncFailure) {
            Log.w(TAG, "Sync failed: ${e.kind} ${e.code.orEmpty()}")
            fail(e)
        } catch (e: Exception) {
            Log.w(TAG, "Sync failed: ${e.javaClass.simpleName}")
            _status.value = readStatus(problem = Problem.Other)
        }
    }

    private fun fail(e: SyncFailure) {
        when (e.kind) {
            SyncFailure.Kind.Unauthorized -> {
                // Сервер не узнал токен: выходим, адрес и логин оставляем — войти снова одним паролем.
                secret.edit().remove("token").commit()
                prefs.edit().putBoolean("session_ended", true).apply()
                _status.value = readStatus()
            }
            SyncFailure.Kind.RateLimited -> _status.value = readStatus(problem = Problem.RateLimited, retry = e.retryAfterSecs ?: 0)
            SyncFailure.Kind.NoConnection -> _status.value = readStatus(
                problem = when (e.code) { "host" -> Problem.UnknownHost; "tls" -> Problem.Tls; else -> Problem.NoConnection },
            )
            SyncFailure.Kind.BadAddress, SyncFailure.Kind.Insecure -> _status.value = readStatus(problem = Problem.Other)
            SyncFailure.Kind.Rejected, SyncFailure.Kind.Server -> _status.value = readStatus(problem = Problem.Server, code = e.code)
        }
    }

    // --- Вход и выход ---

    /**
     * Войти ([create] = `false`) или создать аккаунт на сервере. `null` — удалось, выгрузка пошла; иначе отказ.
     * Пароль живёт только в аргументе: ни в файл, ни в журнал не попадает.
     */
    suspend fun signIn(address: String, login: String, password: String, create: Boolean): SyncFailure? {
        if (!available) return SyncFailure(SyncFailure.Kind.Rejected, "unavailable")
        val failure = mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val client = MirrorClient(address)
                    val name = login.trim()
                    val session = if (create) client.register(name, password) else client.login(name, password)
                    val server = MirrorClient.normalizeAddress(address)
                    // Другой сервер или другой аккаунт — старое состояние выгрузки к нему не относится.
                    val key = "$server#${session.accountId}"
                    if (prefs.getString("state_key", "") != key) {
                        stateFile().delete()
                        prefs.edit().remove("last_at").remove("stored").remove("rejected").remove("rejected_codes").commit()
                    }
                    prefs.edit()
                        .putString("server", server).putString("login", name.lowercase()).putString("state_key", key)
                        .putBoolean("session_ended", false)
                        .commit()
                    secret.edit().putString("token", session.token).commit()
                    _status.value = readStatus()
                    null
                } catch (e: SyncFailure) {
                    e
                }
            }
        }
        if (failure == null) scope.launch { runSync() }
        return failure
    }

    /**
     * Выйти. [erase] — сначала стереть с сервера всё выложенное нами. `null` — вышли; иначе отказ: при стирании он
     * значит, что копия осталась и вход сохранён (кроме 401 — токен уже не годится, выход всё равно выполнен).
     */
    suspend fun signOut(erase: Boolean): SyncFailure? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val token = token() ?: return@withContext null
            val client = client()
            var failure: SyncFailure? = null
            if (erase) {
                try {
                    val report = SyncEngine(client).erase(token, loadState()) { saveState(it) }
                    if (report.rejected.isNotEmpty()) return@withContext SyncFailure(SyncFailure.Kind.Rejected, report.rejected.first().code)
                    stateFile().delete()
                } catch (e: SyncFailure) {
                    if (e.kind != SyncFailure.Kind.Unauthorized) return@withContext e
                    failure = e
                }
            }
            runCatching { client.logout(token) }
            secret.edit().remove("token").commit()
            prefs.edit().putInt("stored", if (erase && failure == null) 0 else prefs.getInt("stored", 0)).apply()
            _status.value = readStatus()
            failure
        }
    }

    // --- Хранение ---

    private fun client(): MirrorClient = MirrorClient(prefs.getString("server", "").orEmpty())

    /** Соль идентификаторов: 16 случайных байт, создаются один раз и никуда не уходят. */
    private fun salt(): ByteArray {
        prefs.getString("salt", null)?.let { return Base64.getDecoder().decode(it) }
        val fresh = SyncIds.newSalt()
        prefs.edit().putString("salt", Base64.getEncoder().encodeToString(fresh)).commit()
        return fresh
    }

    private fun stateFile() = File(app.filesDir, "server_sync_state.json")

    private fun loadState(): SyncState = SyncState.fromJson(stateFile().takeIf { it.exists() }?.readText())

    /** Запись через временный файл: оборванная запись не портит прежнее состояние. */
    private fun saveState(state: SyncState) {
        val file = stateFile()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(state.toJson())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
