package io.github.agitangst.ampulse.sync

import org.json.JSONArray
import org.json.JSONObject

/** Запись, которую сервер не принял. */
data class Rejected(val kind: String, val id: String, val code: String)

/**
 * Итог выгрузки: сколько записей выложено и удалено, сколько сервер не принял ([rejected] — вид, id и код), сколько
 * записей лежит на сервере по нашим сведениям ([stored]).
 */
data class SyncReport(val sent: Int, val deleted: Int, val stored: Int, val rejected: List<Rejected>)

/**
 * Выгрузка: разница между телефоном и сервером → запросы `POST /mirror` по [batch] записей каждого вида → состояние.
 * Состояние меняется только по ответу сервера — для записей с `ok`; [save] зовётся после каждого запроса, поэтому
 * оборванная выгрузка не теряет сделанного. Блокирующая — звать на `Dispatchers.IO`.
 */
class SyncEngine(private val client: MirrorClient, private val batch: Int = BATCH) {
    /** Привести сервер к [set]. Бросает [SyncFailure] при отказе всего запроса (нет связи, 401, 429…). */
    fun sync(token: String, set: MirrorSet, window: MirrorWindow, state: SyncState, keepFrom: Long, save: (SyncState) -> Unit): SyncReport {
        val delta = SyncDiff.between(set, state, window)
        return run(token, delta, set, state, keepFrom, save)
    }

    /** Удалить с сервера всё, что мы выложили (по состоянию). Профили на сервере уносят с собой лекарства и приёмы. */
    fun erase(token: String, state: SyncState, save: (SyncState) -> Unit): SyncReport {
        val delta = SyncDiff.between(MirrorSet.EMPTY, state, MirrorWindow.ALL)
        return run(token, delta, MirrorSet.EMPTY, state, Long.MIN_VALUE, save)
    }

    private fun run(token: String, delta: Delta, set: MirrorSet, start: SyncState, keepFrom: Long, save: (SyncState) -> Unit): SyncReport {
        var state = start
        var sent = 0
        var deleted = 0
        val rejected = ArrayList<Rejected>()
        val lists = listOf(delta.profiles, delta.meds, delta.intakes, delta.deleteIntakes, delta.deleteMeds, delta.deleteProfiles)
        val rounds = lists.maxOf { (it.size + batch - 1) / batch }
        for (round in 0 until rounds) {
            fun <T> slice(list: List<T>) = list.drop(round * batch).take(batch)
            val profiles = slice(delta.profiles)
            val meds = slice(delta.meds)
            val intakes = slice(delta.intakes)
            val delIntakes = slice(delta.deleteIntakes)
            val delMeds = slice(delta.deleteMeds)
            val delProfiles = slice(delta.deleteProfiles)
            val body = JSONObject()
                .put("profiles", JSONArray(profiles.map { it.toJson() }))
                .put("meds", JSONArray(meds.map { it.toJson() }))
                .put("intakes", JSONArray(intakes.map { it.toJson() }))
                .put(
                    "delete",
                    JSONObject().put("profiles", JSONArray(delProfiles)).put("meds", JSONArray(delMeds)).put("intakes", JSONArray(delIntakes)),
                )
            val replies = client.mirror(token, body)
            // Сервер отвечает по записи в порядке обработки: профили, лекарства, приёмы, потом удаления приёмов, лекарств, профилей.
            val expected = profiles.size + meds.size + intakes.size + delIntakes.size + delMeds.size + delProfiles.size
            if (replies.size != expected) throw SyncFailure(SyncFailure.Kind.Server, "bad_reply")
            var i = 0
            fun next(kind: String, id: String): MirrorReply {
                val r = replies[i++]
                if (r.kind != kind || r.id != id) throw SyncFailure(SyncFailure.Kind.Server, "bad_reply")
                return r
            }
            for (p in profiles) {
                val r = next("profile", p.id)
                if (r.ok) { state = state.copy(profiles = state.profiles + (p.id to Stamp(p.hash)), rejected = state.rejected - "profile/${p.id}"); sent++ }
                else { rejected += Rejected("profile", p.id, r.error ?: "unknown"); state = refuse(state, "profile", p.id, p.hash, r.error) }
            }
            for (m in meds) {
                val r = next("med", m.id)
                if (r.ok) { state = state.copy(meds = state.meds + (m.id to Stamp(m.hash, 0, m.profileId)), rejected = state.rejected - "med/${m.id}"); sent++ }
                else { rejected += Rejected("med", m.id, r.error ?: "unknown"); state = refuse(state, "med", m.id, m.hash, r.error) }
            }
            for (t in intakes) {
                val r = next("intake", t.id)
                if (r.ok) { state = state.copy(intakes = state.intakes + (t.id to Stamp(t.hash, t.plannedAt, t.medId)), rejected = state.rejected - "intake/${t.id}"); sent++ }
                else { rejected += Rejected("intake", t.id, r.error ?: "unknown"); state = refuse(state, "intake", t.id, t.hash, r.error) }
            }
            for (id in delIntakes) {
                val r = next("intake", id)
                if (r.ok) { state = state.copy(intakes = state.intakes - id, rejected = state.rejected - "intake/$id"); deleted++ }
                else rejected += Rejected("intake", id, r.error ?: "unknown")
            }
            for (id in delMeds) {
                val r = next("med", id)
                if (r.ok) {
                    // Сервер уносит с лекарством и его приёмы.
                    state = state.copy(meds = state.meds - id, intakes = state.intakes.filterValues { it.parent != id }, rejected = state.rejected - "med/$id")
                    deleted++
                } else rejected += Rejected("med", id, r.error ?: "unknown")
            }
            for (id in delProfiles) {
                val r = next("profile", id)
                if (r.ok) {
                    val gone = state.meds.filterValues { it.parent == id }.keys
                    state = state.copy(
                        profiles = state.profiles - id,
                        meds = state.meds - gone,
                        intakes = state.intakes.filterValues { it.parent !in gone },
                        rejected = state.rejected - "profile/$id",
                    )
                    deleted++
                } else rejected += Rejected("profile", id, r.error ?: "unknown")
            }
            state = state.copy(rejected = state.rejected.filterKeys { stillKnown(it, set) }).pruned(keepFrom)
            save(state)
        }
        return SyncReport(sent, deleted, state.stored, rejected)
    }

    /** Запомнить отказ насовсем (код `bad_…` или `text_too_long`): то же содержимое больше не шлётся. Прочие отказы повторятся. */
    private fun refuse(state: SyncState, kind: String, id: String, hash: String, code: String?): SyncState =
        if (code != null && (code.startsWith("bad_") || code == "text_too_long")) state.copy(rejected = state.rejected + ("$kind/$id" to "$hash:$code")) else state

    /** Отказы записей, которых больше нет в выгрузке, забываются (при «стереть копию» [set] пуст — забываются все). */
    private fun stillKnown(key: String, set: MirrorSet): Boolean {
        val id = key.substringAfter('/')
        return when (key.substringBefore('/')) {
            "profile" -> id in set.profiles
            "med" -> id in set.meds
            "intake" -> id in set.intakes
            else -> false
        }
    }

    companion object {
        /** Сервер принимает до 500 записей каждого вида за запрос. */
        const val BATCH = 500
    }
}
