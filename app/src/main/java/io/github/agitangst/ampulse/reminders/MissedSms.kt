package io.github.agitangst.ampulse.reminders

import android.content.Context
import android.util.Log
import io.github.agitangst.anvil.compose.AnvilLocale
import io.github.agitangst.ampulse.AppGraph
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.data.Codec
import io.github.agitangst.ampulse.domain.FamilyTexts
import io.github.agitangst.ampulse.ui.FamilyPhone
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime

/**
 * SMS близким о пропуске важного приёма (этап 0.8). Зовётся будильником «проверка» ([ReminderCodes.SLOT_CHECK]) через
 * [ReminderPlan.CHECK_AFTER] после времени приёма — когда основное напоминание и все повторы уже прозвучали. Отмеченный
 * приём («принято» или «пропущено») SMS не вызывает: тишина значит «принято».
 */
internal object MissedSms {
    private const val PREFS = "ampulse_reminders"
    private const val KEY_SENT = "sms_sent"

    /** Сколько помним отправленное: дольше приём «проверкой» уже не окажется. */
    private const val KEEP_DAYS = 3L

    /** Проверить приём [dose]; не отмечен — отправить SMS каждому близкому с включённым «SMS о пропуске». */
    suspend fun check(context: Context, dose: DoseRef) {
        val app = context.applicationContext
        val repo = AppGraph.repository
        val medicine = repo.medicine(dose.medicineId) ?: return
        if (!medicine.important || Notifier.isHandled(dose)) return
        val profile = repo.profiles.first().firstOrNull { it.id == medicine.profileId }
        // Копия чужого расписания не наша забота: за неё отвечает сам владелец.
        if (profile?.watchOnly == true) return
        val recipients = repo.contactsNow().filter { it.smsMissed && FamilyPhone.valid(it.phone) }
        if (recipients.isEmpty()) return
        if (!markSent(app, dose)) return

        // Язык — как в программе (ниже Android 13 он живёт только в контексте экрана).
        val localized = AnvilLocale.wrap(app)
        val ru = localized.resources.configuration.locales[0].language == "ru"
        val who = profile?.name?.takeIf { it.isNotBlank() } ?: localized.getString(R.string.family_sms_me)
        val text = FamilyTexts.missed(ru, who, medicine, dose.planned)
        var sent = 0
        for (contact in recipients) {
            if (SmsOut.deliver(app, contact.name, FamilyPhone.clean(contact.phone), text)) sent++
        }
        Log.i(TAG, "Missed-dose SMS for medicine ${dose.medicineId}: $sent of ${recipients.size} sent")
    }

    /**
     * Запомнить, что SMS по этому приёму уходит; `false` — уже уходила (будильник сработал дважды). Помнится
     * по коду ячейки «проверка» этого приёма; записи старше [KEEP_DAYS] суток забываются.
     */
    private fun markSent(context: Context, dose: DoseRef): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = dose.code(ReminderCodes.SLOT_CHECK).toString()
        val plannedMs = Codec.millis(dose.planned)
        val old = prefs.getStringSet(KEY_SENT, null).orEmpty()
        if (old.any { it.substringBefore('|') == key }) return false
        val limit = Codec.millis(LocalDateTime.now().minusDays(KEEP_DAYS))
        val kept = old.filterTo(HashSet()) { (it.substringAfter('|').toLongOrNull() ?: 0L) >= limit }
        kept += "$key|$plannedMs"
        prefs.edit().putStringSet(KEY_SENT, kept).apply()
        return true
    }
}
