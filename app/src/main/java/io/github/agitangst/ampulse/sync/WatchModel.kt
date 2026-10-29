package io.github.agitangst.ampulse.sync

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Модель «Просмотра чужих профилей» (openspec: server-watch, docs/dev/server-watch.md): то, что сервер отдаёт по
 * `GET /api/v1/sync` про профили, которыми с нами поделились. Чистая часть без Android, покрыта тестами JVM.
 * Время — секунды Unix, как у сервера.
 */

/** Профиль, которым с нами поделились. [role] — `guardian` (опекун) или `viewer` (наблюдатель); [kind] — `person` или `animal`. */
data class WatchProfile(
    val id: String,
    val name: String,
    val kind: String,
    val note: String,
    val role: String,
    val timezone: String,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("kind", kind).put("note", note)
        .put("role", role).put("tz", timezone)

    companion object {
        /** Разбор профиля из ответа сервера; без `id` — `null`. */
        fun parse(o: JSONObject): WatchProfile? {
            val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
            return WatchProfile(
                id = id,
                name = o.optString("name"),
                kind = o.optString("kind", "person"),
                note = o.optString("note"),
                role = o.optString("role"),
                timezone = o.optString("timezone", "UTC").ifEmpty { "UTC" },
            )
        }

        fun fromJson(o: JSONObject): WatchProfile? = parse(o.put("timezone", o.optString("tz", "UTC")))
    }
}

/** Препарат чужого профиля: [dose] — «850 мг» как её набрал владелец, [form] — `pill`, `injection` или `measure`. */
data class WatchMed(
    val id: String,
    val profileId: String,
    val name: String,
    val form: String,
    val dose: String,
    val note: String,
    val archived: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("p", profileId).put("name", name).put("form", form)
        .put("dose", dose).put("note", note).put("arch", archived)

    companion object {
        fun parse(o: JSONObject): WatchMed? {
            val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
            val profile = o.optString("profile_id").takeIf { it.isNotEmpty() } ?: return null
            return WatchMed(
                id, profile, o.optString("name"), o.optString("form", "pill"), o.optString("dose"), o.optString("note"),
                o.optBoolean("archived", false),
            )
        }

        fun fromJson(o: JSONObject): WatchMed? = parse(JSONObject(o.toString()).put("profile_id", o.optString("p")).put("archived", o.optBoolean("arch")))
    }
}

/** Состояния приёма на сервере. */
object WatchStatus {
    const val PENDING = "pending"
    const val TAKEN = "taken"
    const val SKIPPED = "skipped"
}

/** Приём: [plannedAt] — плановое время, [takenAt] — когда отмечен принятым (только у `taken`). */
data class WatchIntake(
    val id: String,
    val medId: String,
    val profileId: String,
    val plannedAt: Long,
    val status: String,
    val takenAt: Long?,
    val note: String,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("m", medId).put("p", profileId).put("at", plannedAt)
        .put("s", status).put("t", takenAt ?: JSONObject.NULL).put("note", note)

    companion object {
        fun parse(o: JSONObject): WatchIntake? {
            val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
            val med = o.optString("med_id").takeIf { it.isNotEmpty() } ?: return null
            val profile = o.optString("profile_id").takeIf { it.isNotEmpty() } ?: return null
            if (!o.has("planned_at") || o.isNull("planned_at")) return null
            val taken = if (o.has("taken_at") && !o.isNull("taken_at")) o.optLong("taken_at") else null
            return WatchIntake(id, med, profile, o.optLong("planned_at"), o.optString("status", WatchStatus.PENDING), taken, o.optString("note"))
        }

        fun fromJson(o: JSONObject): WatchIntake? = parse(
            JSONObject().put("id", o.optString("id")).put("med_id", o.optString("m")).put("profile_id", o.optString("p"))
                .put("planned_at", o.opt("at")).put("status", o.optString("s")).put("taken_at", o.opt("t")).put("note", o.optString("note")),
        )
    }
}

/**
 * Одна страница ответа `GET /api/v1/sync`. [profiles] — все профили, доступные аккаунту (и свои), каждый раз целиком;
 * [removedMeds] и [removedIntakes] — id исчезнувшего; расписания (`schedules`, `removed.schedules`) не читаются.
 */
data class WatchPage(
    val serverTime: Long,
    val profiles: List<WatchProfile>,
    val cursor: Long,
    val more: Boolean,
    val resync: Boolean,
    val meds: List<WatchMed>,
    val intakes: List<WatchIntake>,
    val removedMeds: List<String>,
    val removedIntakes: List<String>,
    /** Роли профилей, как их назвал сервер (в том числе `owner`): по ним отделяются чужие. */
    val roles: Map<String, String>,
) {
    companion object {
        fun parse(o: JSONObject): WatchPage {
            fun <T> list(name: String, read: (JSONObject) -> T?): List<T> {
                val a = o.optJSONArray(name) ?: return emptyList()
                return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(read) }
            }
            if (!o.has("cursor")) throw SyncFailure(SyncFailure.Kind.Server, "bad_reply")
            val removed = o.optJSONObject("removed")
            fun ids(name: String): List<String> {
                val a: JSONArray = removed?.optJSONArray(name) ?: return emptyList()
                return (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotEmpty() } }
            }
            val profiles = list("profiles", WatchProfile::parse)
            return WatchPage(
                serverTime = o.optLong("server_time", 0),
                profiles = profiles,
                cursor = o.optLong("cursor", 0),
                more = o.optBoolean("more", false),
                resync = o.optBoolean("resync", false),
                meds = list("meds", WatchMed::parse),
                intakes = list("intakes", WatchIntake::parse),
                removedMeds = ids("meds"),
                removedIntakes = ids("intakes"),
                roles = profiles.associate { it.id to it.role },
            )
        }
    }
}

/** Код приглашения: 12 знаков `ABCD-EFGH-JKMN`, регистр, дефисы и пробелы не важны. */
object WatchCodes {
    const val LENGTH = 12

    /** Только буквы и цифры, в верхнем регистре, не больше [LENGTH] знаков. */
    fun normalize(text: String): String = text.filter { it.isLetterOrDigit() && it.code < 128 }.uppercase().take(LENGTH)

    /** Код набран целиком. */
    fun isComplete(text: String): Boolean = normalize(text).length == LENGTH

    /** Для поля: `ABCD-EFGH-JKMN` по мере набора. */
    fun pretty(text: String): String = normalize(text).chunked(4).joinToString("-")
}

/**
 * Кэш чужих профилей на этом телефоне (файл `server_watch.json` в `filesDir`): профили, препараты, приёмы, курсор,
 * время последнего обновления и ошибка. Не Room, без миграций. Чужое — только то, что сервер назвал не `owner`.
 *
 * - [accountKey] — сервер и аккаунт, для которых кэш получен: другой вход — кэш выбрасывается.
 * - [pending] — профили, которым нужна выгрузка с начала (`since=0&profile=`): только что открытые.
 * - [hidden] — «убранные» с этого телефона: пока доступ на сервере есть, их не показываем и не храним.
 * - [serverTime] и [phoneAt] — часы сервера и часы телефона (мс) в момент последнего успешного обновления: по ним
 *   считается «сейчас», когда связи нет, и поправка на уход часов телефона.
 * - [error] — чем кончилась последняя попытка (`null` — удачно); данные при ошибке остаются прежними.
 */
data class WatchCache(
    val accountKey: String = "",
    val cursor: Long = 0,
    val profiles: Map<String, WatchProfile> = emptyMap(),
    val meds: Map<String, WatchMed> = emptyMap(),
    val intakes: Map<String, WatchIntake> = emptyMap(),
    val pending: Set<String> = emptySet(),
    val hidden: Set<String> = emptySet(),
    val serverTime: Long = 0,
    val phoneAt: Long = 0,
    val error: String? = null,
) {
    /** Профили, которые показываются: все чужие, кроме убранных. */
    val shown: List<WatchProfile> get() = profiles.values.filter { it.id !in hidden }.sortedBy { it.name.lowercase() }

    /** Обновление хоть раз удавалось. */
    val everRefreshed: Boolean get() = serverTime > 0

    /**
     * Применить страницу ответа. [profile] — страница про один профиль (выгрузка с начала): общий курсор и список
     * профилей она не трогает. Профили, которых в кэше ещё не было, попадают в [pending]: сняться с него они могут
     * только через [loaded] (когда их старые записи принесла выгрузка с нуля или отдельный запрос).
     */
    fun apply(page: WatchPage, profile: String?): WatchCache {
        val general = profile == null
        var profiles = this.profiles
        var hidden = this.hidden
        var pending = this.pending
        val known: Set<String>
        if (general) {
            val fresh = page.profiles.filter { it.role != ROLE_OWNER && it.id !in this.hidden }.associateBy { it.id }
            pending = (pending + fresh.keys.filter { it !in profiles }).filter { it in fresh }.toSet()
            profiles = fresh
            // «Убранные», которых сервер больше не называет, забываются: новый доступ вернёт профиль.
            hidden = hidden.filter { id -> page.profiles.any { it.id == id } }.toSet()
            known = fresh.keys
        } else {
            known = setOf(profile).filter { it in profiles }.toSet()
        }
        var meds = this.meds
        var intakes = this.intakes
        if (page.resync) {
            // Сервер забыл часть удалений: всё запрошенное выбрасывается, а эта страница — первая с самого начала.
            meds = if (general) emptyMap() else meds.filterValues { it.profileId != profile }
            intakes = if (general) emptyMap() else intakes.filterValues { it.profileId != profile }
        }
        val removedMeds = page.removedMeds.toSet()
        val removedIntakes = page.removedIntakes.toSet()
        meds = meds.filterValues { it.profileId in profiles && it.id !in removedMeds } +
            page.meds.filter { it.profileId in known }.associateBy { it.id }
        // Удалённый препарат уносит свои приёмы (сервер называет и их, но не лишне).
        intakes = intakes.filterValues { it.profileId in profiles && it.medId !in removedMeds && it.id !in removedIntakes } +
            page.intakes.filter { it.profileId in known }.associateBy { it.id }
        return copy(
            cursor = if (general) page.cursor else cursor,
            profiles = profiles,
            hidden = hidden,
            meds = meds,
            intakes = intakes,
            pending = pending,
        )
    }

    /** Выгрузка профиля с начала закончена. */
    fun loaded(profile: String): WatchCache = copy(pending = pending - profile)

    /** Этот профиль приняли по коду: показывать снова, выгрузить с начала. */
    fun accepted(profile: WatchProfile): WatchCache =
        if (profile.role == ROLE_OWNER) this
        else copy(profiles = profiles + (profile.id to profile), hidden = hidden - profile.id, pending = pending + profile.id)

    /** «Убрать» с этого телефона: данные стираются, id запоминается. */
    fun removed(id: String): WatchCache = copy(
        profiles = profiles - id,
        meds = meds.filterValues { it.profileId != id },
        intakes = intakes.filterValues { it.profileId != id },
        pending = pending - id,
        hidden = hidden + id,
    )

    /** Приёмы старше [keepFrom] (секунды) забываются: показывается только сегодняшний день. */
    fun pruned(keepFrom: Long): WatchCache = copy(intakes = intakes.filterValues { it.plannedAt >= keepFrom })

    fun succeeded(serverTime: Long, phoneAtMs: Long) = copy(serverTime = serverTime, phoneAt = phoneAtMs, error = null)

    fun failed(code: String) = copy(error = code)

    fun toJson(): String = JSONObject()
        .put("v", VERSION)
        .put("account", accountKey)
        .put("cursor", cursor)
        .put("server_time", serverTime)
        .put("phone_at", phoneAt)
        .put("error", error ?: JSONObject.NULL)
        .put("profiles", JSONArray().also { a -> profiles.values.forEach { a.put(it.toJson()) } })
        .put("meds", JSONArray().also { a -> meds.values.forEach { a.put(it.toJson()) } })
        .put("intakes", JSONArray().also { a -> intakes.values.forEach { a.put(it.toJson()) } })
        .put("pending", JSONArray(pending.toList()))
        .put("hidden", JSONArray(hidden.toList()))
        .toString()

    companion object {
        const val VERSION = 1
        const val ROLE_OWNER = "owner"

        /** Приёмы старше этого срока (дней) из кэша забываются. */
        const val KEEP_DAYS = 45L

        /** Прочесть кэш. Нет файла или он испорчен — пусто: тогда всё скачается заново (повтор безвреден). */
        fun fromJson(text: String?): WatchCache {
            if (text.isNullOrBlank()) return WatchCache()
            return try {
                val o = JSONObject(text)
                fun <T> read(name: String, parse: (JSONObject) -> T?): List<T> {
                    val a = o.optJSONArray(name) ?: return emptyList()
                    return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(parse) }
                }
                fun strings(name: String): Set<String> {
                    val a = o.optJSONArray(name) ?: return emptySet()
                    return (0 until a.length()).map { a.getString(it) }.toSet()
                }
                WatchCache(
                    accountKey = o.optString("account"),
                    cursor = o.optLong("cursor", 0),
                    profiles = read("profiles", WatchProfile::fromJson).associateBy { it.id },
                    meds = read("meds", WatchMed::fromJson).associateBy { it.id },
                    intakes = read("intakes", WatchIntake::fromJson).associateBy { it.id },
                    pending = strings("pending"),
                    hidden = strings("hidden"),
                    serverTime = o.optLong("server_time", 0),
                    phoneAt = o.optLong("phone_at", 0),
                    error = if (o.isNull("error")) null else o.optString("error").takeIf { it.isNotEmpty() },
                )
            } catch (e: JSONException) {
                WatchCache()
            }
        }
    }
}
