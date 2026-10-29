package io.github.agitangst.ampulse.ui

import androidx.annotation.StringRes
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.domain.Medicine
import io.github.agitangst.ampulse.domain.Pack
import io.github.agitangst.ampulse.domain.Site
import io.github.agitangst.ampulse.domain.goodUntil
import io.github.agitangst.ampulse.domain.timeLeft
import java.time.LocalDate

// Тексты шагов «Укола пошагово»: названия, итоги, тексты и проверки из данных приложения (макет, экраны 44–45).
// Ключи — в strings_shots.xml; чистая логика шагов — в ShotModel.kt.

/** Проверка из данных приложения: строка с галочкой на мягком акценте, а [warn] — на мягком внимании. */
internal data class ShotCheckLine(val text: String, val warn: Boolean = false)

/** Что показать на шаге. [dose] — крупно «В окошке ручки»; [diagramSite] — схема ягодиц с отмеченной стороной. */
internal data class ShotStepText(
    val title: String,
    val short: String,
    val body: String,
    val dose: String? = null,
    val diagramSite: Site? = null,
    val checks: List<ShotCheckLine> = emptyList(),
)

/** Данные укола, из которых собираются тексты шагов. */
internal class ShotCtx(
    val texts: MedTexts,
    val med: Medicine,
    val site: Site?,
    /** Доза укола по схеме; 0 — схемы нет, дозу приложение не придумывает. */
    val amount: Double,
    /** Упаковка, из которой пойдёт доза (порядок FEFO), либо просроченная; `null` — упаковок нет. */
    val pack: Pack?,
    val today: LocalDate,
    val timerSeconds: Int,
    /** Отсчёт пропущен кнопкой «Без отсчёта» — в итоге шага «держали N с» не пишется. */
    val timerSkipped: Boolean,
) {
    val doseText: String? get() = amount.takeIf { it > 0 }?.let { texts.dose(it, med.unit) }

    private fun capital(s: String): String = s.replaceFirstChar { it.titlecase(texts.locale) }

    /** Место в предложении: «живот справа» (строчными) и с заглавной в начале предложения. */
    val siteText: String? get() = site?.let { texts.string(siteLabel(it)) }

    val siteSentence: String? get() = siteText?.let(::capital)
}

@StringRes
internal fun siteLabel(site: Site): Int = when (site) {
    Site.AbdomenLeft -> R.string.shot_site_abdomen_left
    Site.AbdomenRight -> R.string.shot_site_abdomen_right
    Site.ThighLeft, Site.OuterThighLeft, Site.PetThighLeft -> R.string.shot_site_thigh_left
    Site.ThighRight, Site.OuterThighRight, Site.PetThighRight -> R.string.shot_site_thigh_right
    Site.ArmLeft -> R.string.shot_site_arm_left
    Site.ArmRight -> R.string.shot_site_arm_right
    Site.ButtockLeft -> R.string.shot_site_buttock_left
    Site.ButtockRight -> R.string.shot_site_buttock_right
    Site.Scruff -> R.string.shot_site_scruff
    Site.FlankLeft -> R.string.shot_site_flank_left
    Site.FlankRight -> R.string.shot_site_flank_right
}

/** Подпись под кольцом до конца отсчёта. */
@StringRes
internal fun timerNoteRes(kind: ShotKind): Int =
    if (kind == ShotKind.Pen) R.string.shot_timer_note_pen else R.string.shot_timer_note_muscle

/** Подпись под кольцом, когда отсчёт закончился. */
@StringRes
internal fun timerDoneRes(kind: ShotKind): Int =
    if (kind == ShotKind.Pen) R.string.shot_timer_done_pen else R.string.shot_timer_done_muscle

/** Тексты шага [step] по данным укола [c]. */
internal fun shotStepText(step: ShotStep, c: ShotCtx): ShotStepText {
    val t = c.texts
    return when (step) {
        ShotStep.Hands -> plain(c, R.string.shot_s_hands_t, R.string.shot_s_hands_s, R.string.shot_s_hands_b)
        ShotStep.CheckPen -> ShotStepText(
            t.string(R.string.shot_s_checkpen_t), t.string(R.string.shot_s_checkpen_s), t.string(R.string.shot_s_checkpen_b),
            checks = prescribedAndExpiry(c),
        )
        ShotStep.Needle -> plain(c, R.string.shot_s_needle_t, R.string.shot_s_needle_s, R.string.shot_s_needle_b)
        ShotStep.Prime -> plain(c, R.string.shot_s_prime_t, R.string.shot_s_prime_s, R.string.shot_s_prime_b)
        ShotStep.Dial -> ShotStepText(
            t.string(R.string.shot_s_dial_t), c.doseText ?: t.string(R.string.shot_s_dial_s), t.string(R.string.shot_s_dial_b),
            dose = c.doseText,
            checks = listOfNotNull(c.doseText?.let { ShotCheckLine(t.string(R.string.shot_check_dose, it)) }),
        )
        ShotStep.SitePen -> {
            val site = c.site
            val body = when {
                site == null -> t.string(R.string.shot_s_sitepen_none_b)
                isAbdomen(site) -> t.string(R.string.shot_s_sitepen_abdomen_b, c.siteSentence.orEmpty())
                else -> t.string(R.string.shot_s_sitepen_b, c.siteSentence.orEmpty())
            }
            ShotStepText(t.string(R.string.shot_s_sitepen_t), c.siteText ?: t.string(R.string.shot_s_sitepen_s), body)
        }
        ShotStep.InjectPen -> ShotStepText(
            t.string(R.string.shot_s_injectpen_t),
            if (c.timerSkipped) t.string(R.string.shot_short_no_timer) else t.string(R.string.shot_short_held, c.timerSeconds),
            t.string(R.string.shot_s_injectpen_b),
        )
        ShotStep.AfterPen -> plain(c, R.string.shot_s_afterpen_t, R.string.shot_s_afterpen_s, R.string.shot_s_afterpen_b)

        ShotStep.Prepare -> plain(c, R.string.shot_s_prepare_t, R.string.shot_s_prepare_s, R.string.shot_s_prepare_b)
        ShotStep.CheckAmpoule -> ShotStepText(
            t.string(R.string.shot_s_ampoule_t), t.string(R.string.shot_s_ampoule_s), t.string(R.string.shot_s_ampoule_b),
            checks = prescribedAndExpiry(c),
        )
        ShotStep.Draw -> ShotStepText(
            t.string(R.string.shot_s_draw_t),
            c.doseText ?: t.string(R.string.shot_s_draw_s),
            t.string(R.string.shot_s_draw_b, c.doseText ?: t.string(R.string.shot_dose_prescribed)),
            checks = listOfNotNull(c.doseText?.let { ShotCheckLine(t.string(R.string.shot_check_dose, it)) }),
        )
        ShotStep.SiteMuscle -> {
            val site = c.site
            val body = when {
                site == null -> t.string(R.string.shot_s_sitemuscle_none_b)
                isButtock(site) -> t.string(R.string.shot_s_sitemuscle_buttock_b, c.siteSentence.orEmpty())
                else -> t.string(R.string.shot_s_sitemuscle_thigh_b, c.siteSentence.orEmpty())
            }
            ShotStepText(
                t.string(R.string.shot_s_sitemuscle_t), c.siteText ?: t.string(R.string.shot_s_sitemuscle_s), body,
                diagramSite = site?.takeIf(::isButtock),
            )
        }
        ShotStep.Skin -> plain(c, R.string.shot_s_skin_t, R.string.shot_s_skin_s, R.string.shot_s_skin_b)
        ShotStep.InjectMuscle -> ShotStepText(
            t.string(R.string.shot_s_injectmuscle_t),
            if (c.timerSkipped) t.string(R.string.shot_short_no_timer) else t.string(R.string.shot_short_slow, c.timerSeconds),
            t.string(R.string.shot_s_injectmuscle_b),
        )
        ShotStep.AfterMuscle -> plain(c, R.string.shot_s_aftermuscle_t, R.string.shot_s_aftermuscle_s, R.string.shot_s_aftermuscle_b)
    }
}

private fun plain(c: ShotCtx, @StringRes title: Int, @StringRes short: Int, @StringRes body: Int) =
    ShotStepText(c.texts.string(title), c.texts.string(short), c.texts.string(body))

/** Проверки шагов «проверьте раствор / ампулу»: что назначено и срок годности упаковки, из которой пойдёт доза. */
private fun prescribedAndExpiry(c: ShotCtx): List<ShotCheckLine> {
    val t = c.texts
    val out = mutableListOf<ShotCheckLine>()
    out += ShotCheckLine(t.string(R.string.shot_check_prescribed, todayMedName(c.med.name, c.med.strength)))
    val pack = c.pack ?: return out
    val until = pack.goodUntil() ?: return out
    val opened = pack.openedAt
    val expiryText = if (opened != null && pack.afterOpenDays > 0) t.dayMonth(until) else t.packExpiry(pack) ?: t.date(until)
    out += when {
        until < c.today -> ShotCheckLine(t.string(R.string.shot_check_expired, expiryText), warn = true)
        opened != null && pack.afterOpenDays > 0 ->
            ShotCheckLine(t.string(R.string.shot_check_opened, t.dayMonth(opened), expiryText, t.timeLeftText(timeLeft(until, c.today))))
        else -> ShotCheckLine(t.string(R.string.shot_check_until, expiryText))
    }
    return out
}
