package io.github.agitangst.ampulse.sync

import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

/** Пределы сервера (ampulse-core): имя — 80 знаков, свободный текст — 1000. Лишнее приложение обрезает само. */
internal const val MAX_NAME = 80
internal const val MAX_TEXT = 1000

internal fun cut(text: String, limit: Int): String {
    val t = text.trim()
    if (t.codePointCount(0, t.length) <= limit) return t
    return t.substring(0, t.offsetByCodePoints(0, limit)).trim()
}

/**
 * Отпечаток содержимого записи: SHA-256 по полям с длинами (чтобы «ab»+«c» и «a»+«bc» различались), Base64URL.
 * По отпечатку выгрузка узнаёт, изменилась ли запись с прошлого раза.
 */
internal fun fingerprint(vararg parts: Any?): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (part in parts) {
        val bytes = (part?.toString() ?: "\u0000").toByteArray(Charsets.UTF_8)
        digest.update(byteArrayOf((bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(), (bytes.size ushr 8).toByte(), bytes.size.toByte()))
        digest.update(bytes)
    }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest())
}

/** Профиль на сервере: `kind` — `person` или `animal`. */
data class MirrorProfile(val id: String, val name: String, val kind: String, val note: String, val timezone: String) {
    val hash: String get() = fingerprint("profile", id, name, kind, note, timezone)

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("kind", kind).put("note", note).put("timezone", timezone)
}

/** Лекарство на сервере: `form` — `pill`, `injection` или `measure`. */
data class MirrorMed(
    val id: String,
    val profileId: String,
    val name: String,
    val form: String,
    val dose: String,
    val note: String,
    val archived: Boolean,
) {
    val hash: String get() = fingerprint("med", id, profileId, name, form, dose, note, archived)

    fun toJson(): JSONObject = JSONObject().put("id", id).put("profile_id", profileId).put("name", name).put("form", form)
        .put("dose", dose).put("note", note).put("archived", archived)
}

/**
 * Приём на сервере. [plannedAt] — плановое время, секунды Unix; у внепланового — время приёма. [takenAt] — только у
 * `taken`. [planned] = `false` — внеплановый: сервер не напоминает о нём и не считает пропущенным.
 */
data class MirrorIntake(
    val id: String,
    val medId: String,
    val plannedAt: Long,
    val status: String,
    val takenAt: Long?,
    val note: String,
    val planned: Boolean,
) {
    val hash: String get() = fingerprint("intake", id, medId, plannedAt, status, takenAt, note, planned)

    fun toJson(): JSONObject {
        val json = JSONObject().put("id", id).put("med_id", medId).put("planned_at", plannedAt).put("status", status)
            .put("note", note).put("planned", planned)
        if (takenAt != null) json.put("taken_at", takenAt)
        return json
    }
}

/** Окно, за которое выкладываются приёмы: `[from, to)` в секундах Unix. Приёмы за его пределами не трогаются. */
data class MirrorWindow(val from: Long, val to: Long) {
    operator fun contains(second: Long): Boolean = second in from until to

    companion object {
        /** Всё: для «стереть копию». */
        val ALL = MirrorWindow(Long.MIN_VALUE, Long.MAX_VALUE)
    }
}

/** Что должно лежать на сервере сейчас. Порядок — как в базе телефона: так тело выгрузки и тесты читаются одинаково. */
data class MirrorSet(
    val profiles: Map<String, MirrorProfile>,
    val meds: Map<String, MirrorMed>,
    val intakes: Map<String, MirrorIntake>,
) {
    val size: Int get() = profiles.size + meds.size + intakes.size

    companion object {
        val EMPTY = MirrorSet(emptyMap(), emptyMap(), emptyMap())
    }
}
