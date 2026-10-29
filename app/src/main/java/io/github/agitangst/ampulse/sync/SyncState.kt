package io.github.agitangst.ampulse.sync

import org.json.JSONArray
import org.json.JSONObject

/**
 * Что про запись известно после прошлой выгрузки: [hash] — отпечаток выложенного содержимого, [at] — время приёма
 * (секунды Unix; у профилей и лекарств 0), [parent] — id лекарства (у приёма) или профиля (у лекарства) на сервере.
 */
data class Stamp(val hash: String, val at: Long = 0, val parent: String = "")

/**
 * Состояние выгрузки: что уже лежит на сервере, по видам и id (файл в `filesDir`, строка JSON). Выкладывается только
 * новое и изменённое; пропавшее — удаляется. [rejected] — записи, которые сервер отклонил насовсем (`bad_name`,
 * `text_too_long`…), по `вид/id` → «отпечаток:код» отклонённого содержимого: пока запись не изменилась, её не шлют снова.
 */
data class SyncState(
    val profiles: Map<String, Stamp> = emptyMap(),
    val meds: Map<String, Stamp> = emptyMap(),
    val intakes: Map<String, Stamp> = emptyMap(),
    val rejected: Map<String, String> = emptyMap(),
) {
    /** Отказана ли сервером именно такая запись (тот же отпечаток). */
    fun refused(kind: String, id: String, hash: String): Boolean = rejected["$kind/$id"]?.substringBefore(':') == hash

    /** Коды отказов насовсем, без повторов. */
    val refusedCodes: List<String> get() = rejected.values.map { it.substringAfter(':', "unknown") }.distinct()

    /** Сколько записей сейчас лежит на сервере по нашим сведениям. */
    val stored: Int get() = profiles.size + meds.size + intakes.size

    fun toJson(): String {
        fun stamps(map: Map<String, Stamp>): JSONObject = JSONObject().also { o ->
            map.forEach { (id, s) -> o.put(id, JSONArray().put(s.hash).put(s.at).put(s.parent)) }
        }
        return JSONObject()
            .put("v", VERSION)
            .put("profiles", stamps(profiles))
            .put("meds", stamps(meds))
            .put("intakes", stamps(intakes))
            .put("rejected", JSONObject().also { o -> rejected.forEach { (k, v) -> o.put(k, v) } })
            .toString()
    }

    /** Приёмы старше [keepFrom] из состояния убираются: на сервере они остаются, а список не растёт без конца. */
    fun pruned(keepFrom: Long): SyncState = copy(intakes = intakes.filterValues { it.at == 0L || it.at >= keepFrom })

    companion object {
        const val VERSION = 1

        /** Приёмы старше этого срока (дней) из файла состояния забываются. */
        const val KEEP_DAYS = 400L

        /** Прочесть состояние. Нет файла или он испорчен — пусто: тогда всё выложится заново (повтор безвреден). */
        fun fromJson(text: String?): SyncState {
            if (text.isNullOrBlank()) return SyncState()
            return runCatching {
                val o = JSONObject(text)
                fun stamps(name: String): Map<String, Stamp> {
                    val src = o.optJSONObject(name) ?: return emptyMap()
                    val out = LinkedHashMap<String, Stamp>()
                    for (id in src.keys()) {
                        val a = src.getJSONArray(id)
                        out[id] = Stamp(a.getString(0), a.optLong(1, 0), a.optString(2, ""))
                    }
                    return out
                }
                val rej = LinkedHashMap<String, String>()
                o.optJSONObject("rejected")?.let { r -> for (k in r.keys()) rej[k] = r.getString(k) }
                SyncState(stamps("profiles"), stamps("meds"), stamps("intakes"), rej)
            }.getOrDefault(SyncState())
        }
    }
}

/** Разница между тем, что должно лежать на сервере, и тем, что лежит: что выложить и что удалить. */
data class Delta(
    val profiles: List<MirrorProfile>,
    val meds: List<MirrorMed>,
    val intakes: List<MirrorIntake>,
    val deleteIntakes: List<String>,
    val deleteMeds: List<String>,
    val deleteProfiles: List<String>,
) {
    val isEmpty: Boolean
        get() = profiles.isEmpty() && meds.isEmpty() && intakes.isEmpty() &&
            deleteIntakes.isEmpty() && deleteMeds.isEmpty() && deleteProfiles.isEmpty()
}

object SyncDiff {
    /**
     * Что выложить и что удалить. Выкладывается запись, которой нет в состоянии или у которой другой отпечаток (и
     * сервер не отклонял ровно это содержимое). Удаляется то, что есть в состоянии, но пропало из [set]; приёмы —
     * только внутри [window]: те, что со временем вышли за его начало, остаются на сервере.
     */
    fun between(set: MirrorSet, state: SyncState, window: MirrorWindow): Delta {
        fun refused(kind: String, id: String, hash: String) = state.refused(kind, id, hash)

        val profiles = set.profiles.values.filter { state.profiles[it.id]?.hash != it.hash && !refused("profile", it.id, it.hash) }
        // Лекарство профиля, отклонённого сервером, и приём такого лекарства сервер всё равно не примет — не шлём.
        val blockedProfiles = set.profiles.values.filter { refused("profile", it.id, it.hash) }.map { it.id }.toSet()
        val meds = set.meds.values.filter {
            it.profileId !in blockedProfiles && state.meds[it.id]?.hash != it.hash && !refused("med", it.id, it.hash)
        }
        val blockedMeds = set.meds.values.filter { it.profileId in blockedProfiles || refused("med", it.id, it.hash) }.map { it.id }.toSet()
        val intakes = set.intakes.values.filter {
            it.medId !in blockedMeds && state.intakes[it.id]?.hash != it.hash && !refused("intake", it.id, it.hash)
        }
        return Delta(
            profiles = profiles,
            meds = meds,
            intakes = intakes,
            deleteIntakes = state.intakes.filter { (id, s) -> id !in set.intakes && s.at in window }.keys.toList(),
            deleteMeds = state.meds.keys.filter { it !in set.meds },
            deleteProfiles = state.profiles.keys.filter { it !in set.profiles },
        )
    }
}
