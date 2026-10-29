package io.github.agitangst.ampulse.sync

import android.content.Context
import android.util.Log
import io.github.agitangst.ampulse.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * «Профили других людей» — просмотр чужих профилей через свой сервер Ampulse (openspec: server-watch,
 * docs/dev/server-watch.md). Только сборка github ([Store.SERVER_SYNC]) и только когда вошли на сервер ([ServerSync]).
 * Только просмотр: ничего на сервер не пишется, кроме приёма кода приглашения.
 *
 * Кэш — файл `server_watch.json` в `filesDir` (не Room, без миграций). Обновление — при открытии приложения, при входе
 * на экраны чужих профилей и после «Отправить сейчас»; без WorkManager и без уведомлений (их шлёт сервер). Ошибка сети
 * экран не роняет: данные остаются, ошибка лежит в [WatchCache.error].
 */
object ServerWatch {
    /** Состояние для экранов: кэш и идёт ли обновление. */
    data class View(val cache: WatchCache = WatchCache(), val busy: Boolean = false)

    /** Итог приёма кода приглашения. */
    sealed interface Accepted {
        data class Added(val name: String) : Accepted
        data object NotFound : Accepted
        data object AlreadyHave : Accepted
        data class TooMany(val retryAfterSecs: Int) : Accepted
        data object NoConnection : Accepted
        data class Other(val code: String) : Accepted
    }

    private const val TAG = "AmpulseServerWatch"

    /** Не чаще раза в полминуты при входе на экраны (возврат и поворот не должны слать каждый раз). */
    const val SCREEN_GAP_MS = 30_000L

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(View())
    val state: StateFlow<View> get() = _state

    @Volatile private var lastAttemptAt = 0L

    /** Есть ли в этой сборке просмотр чужих профилей. */
    val available: Boolean get() = Store.SERVER_SYNC

    /** Зовётся один раз из `AppGraph.init` (после `ServerSync.init`). */
    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        if (!available) return
        val key = ServerSync.accountKey()
        val cache = load()
        _state.value = View(if (key != null && cache.accountKey == key) cache else WatchCache())
    }

    private fun file() = File(app.filesDir, "server_watch.json")

    private fun load(): WatchCache = WatchCache.fromJson(file().takeIf { it.exists() }?.readText())

    /** Запись через временный файл: оборванная запись не портит прежний кэш. */
    private fun save(cache: WatchCache) {
        val file = file()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(cache.toJson())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** Забыть всё чужое (выход, смена сервера или аккаунта). */
    fun clear() {
        if (!::app.isInitialized) return
        _state.value = View()
        runCatching { file().delete() }
    }

    /** Кэш для аккаунта [key]: от другого аккаунта или сервера он не годится. */
    private fun forAccount(cache: WatchCache, key: String): WatchCache = if (cache.accountKey == key) cache else WatchCache(accountKey = key)

    /**
     * Обновить кэш. [minGapMs] — не чаще: с экранов 30 секунд, при открытии приложения и после выгрузки без паузы.
     * Результат — в [state]; ничего не бросает.
     */
    fun refresh(minGapMs: Long = 0) {
        if (!available || !::app.isInitialized || ServerSync.accountKey() == null) return
        val now = System.currentTimeMillis()
        if (_state.value.busy || now - lastAttemptAt < minGapMs) return
        lastAttemptAt = now
        scope.launch { runRefresh() }
    }

    private suspend fun runRefresh() = mutex.withLock {
        val creds = ServerSync.credentials() ?: return@withLock
        val start = forAccount(_state.value.cache, creds.accountKey)
        _state.value = View(start, busy = true)
        var latest = start
        try {
            val done = WatchEngine(MirrorClient(creds.server)).refresh(creds.token, start) {
                // Вышли или сменили аккаунт посреди обновления: кэш уже очищен, прежний возвращать нельзя.
                if (ServerSync.accountKey() != creds.accountKey) throw CancellationException("account changed")
                latest = it
                publish(it, busy = true)
            }
            _state.value = View(done)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncFailure) {
            Log.w(TAG, "Watch refresh failed: ${e.kind} ${e.code.orEmpty()}")
            if (e.kind == SyncFailure.Kind.Unauthorized) ServerSync.sessionExpired()
            finish(latest.failed(WatchEngine.errorOf(e)))
        } catch (e: Exception) {
            Log.w(TAG, "Watch refresh failed: ${e.javaClass.simpleName}")
            finish(latest.failed("Other"))
        }
    }

    private fun publish(cache: WatchCache, busy: Boolean) {
        save(cache)
        _state.value = View(cache, busy)
    }

    private fun finish(cache: WatchCache) {
        // Выход мог случиться посреди обновления: тогда кэш уже очищен и возвращать его нельзя.
        if (ServerSync.accountKey() == null) return
        publish(cache, busy = false)
    }

    /**
     * Принять код приглашения. Успех — профиль добавлен (и сразу выгружен, если связь позволила), прежняя пометка
     * «убран» снимается.
     */
    suspend fun accept(code: String): Accepted {
        if (!available) return Accepted.Other("unavailable")
        val result = mutex.withLock {
            withContext(Dispatchers.IO) {
                val creds = ServerSync.credentials() ?: return@withContext Accepted.Other("signed_out")
                try {
                    val profile = MirrorClient(creds.server).acceptInvite(creds.token, code)
                    val cache = forAccount(_state.value.cache, creds.accountKey).accepted(profile)
                    publish(cache, busy = false)
                    Accepted.Added(profile.name)
                } catch (e: SyncFailure) {
                    when {
                        e.kind == SyncFailure.Kind.Unauthorized -> {
                            ServerSync.sessionExpired()
                            Accepted.Other("unauthorized")
                        }
                        e.kind == SyncFailure.Kind.RateLimited -> Accepted.TooMany(e.retryAfterSecs ?: 0)
                        e.kind == SyncFailure.Kind.NoConnection -> Accepted.NoConnection
                        e.kind == SyncFailure.Kind.Rejected && e.code == "not_found" -> Accepted.NotFound
                        e.kind == SyncFailure.Kind.Rejected && e.code == "conflict" -> Accepted.AlreadyHave
                        else -> Accepted.Other(e.code ?: e.kind.name)
                    }
                }
            }
        }
        if (result is Accepted.Added) {
            lastAttemptAt = 0
            refresh()
        }
        return result
    }

    /** «Убрать» с этого телефона: данные стираются, на сервере ничего не меняется. */
    fun remove(profileId: String) {
        if (!available || !::app.isInitialized) return
        // Список сразу без профиля, а запись в файл — когда освободится обновление (оно может идти по сети).
        _state.value = _state.value.let { it.copy(cache = it.cache.removed(profileId)) }
        scope.launch {
            mutex.withLock {
                val cache = _state.value.cache.removed(profileId)
                publish(cache, busy = _state.value.busy)
            }
        }
    }
}
