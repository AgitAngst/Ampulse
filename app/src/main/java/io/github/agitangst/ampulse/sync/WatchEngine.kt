package io.github.agitangst.ampulse.sync

/**
 * Обновление кэша чужих профилей по `GET /api/v1/sync` (openspec: server-watch). Чистая часть без Android: клиент
 * и часы передаются снаружи, кэш сохраняется через [onSave] после каждой страницы — оборванное обновление
 * продолжается с того места, где остановилось.
 *
 * 1. Общий журнал: `since=<курсор>`, пока `more`. `resync` — всё хранимое выбрасывается, страница — первая с нуля.
 * 2. Профиль, которого в кэше ещё не было, один раз выгружается с начала: `since=0&profile=<id>` (общий курсор от
 *    этого не меняется). Исключение — профили, чьи старые записи уже принесла общая выгрузка с нуля.
 * 3. Профиль, пропавший из списка сервера, удаляется вместе со всем, что к нему относится.
 */
class WatchEngine(
    private val client: MirrorClient,
    private val limit: Int = 500,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Обновить [start] и вернуть итог (с [WatchCache.error] `null`). При отказе бросает [SyncFailure]; сделанное до него
     * уже передано в [onSave].
     */
    fun refresh(token: String, start: WatchCache, onSave: (WatchCache) -> Unit): WatchCache {
        var cache = start
        val fromZero = cache.cursor == 0L
        var first = true
        var serverTime = cache.serverTime
        var pages = 0
        do {
            if (++pages > MAX_PAGES) throw SyncFailure(SyncFailure.Kind.Server, "too_many_pages")
            val page = client.syncPage(token, cache.cursor, null, limit)
            cache = cache.apply(page, null)
            // Выгрузка с нуля несёт все старые записи всех названных профилей: отдельный запрос им не нужен.
            if ((first && fromZero) || page.resync) {
                for (profile in page.profiles) if (profile.id in cache.pending) cache = cache.loaded(profile.id)
            }
            if (page.serverTime > 0) serverTime = page.serverTime
            first = false
            onSave(cache)
        } while (page.more)

        for (id in cache.pending.toList()) {
            if (id !in cache.profiles) {
                cache = cache.loaded(id)
                continue
            }
            var since = 0L
            try {
                var guard = 0
                do {
                    if (++guard > MAX_PAGES) throw SyncFailure(SyncFailure.Kind.Server, "too_many_pages")
                    val page = client.syncPage(token, since, id, limit)
                    cache = cache.apply(page, id)
                    since = page.cursor
                    onSave(cache)
                } while (page.more)
            } catch (e: SyncFailure) {
                // Доступ закрыли между двумя запросами: профиль исчезнет из общего списка при следующем обновлении.
                if (e.kind != SyncFailure.Kind.Rejected || e.code != "not_found") throw e
            }
            cache = cache.loaded(id)
            onSave(cache)
        }
        val done = if (serverTime > 0) serverTime else clock() / 1000
        cache = cache.succeeded(done, clock()).pruned(done - WatchCache.KEEP_DAYS * 86_400)
        onSave(cache)
        return cache
    }

    companion object {
        /** Страниц за одно обновление: защита от сервера, который никогда не кончает. */
        const val MAX_PAGES = 400

        /** Как ошибку записать в кэш: вид отказа и код сервера. */
        fun errorOf(e: SyncFailure): String = e.kind.name + (e.code?.let { ":$it" } ?: "")
    }
}
