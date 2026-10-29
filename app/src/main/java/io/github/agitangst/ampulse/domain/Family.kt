package io.github.agitangst.ampulse.domain

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Близкий (макет, экраны 12–13, 16): кому уходит SMS. Без сервера — только SMS с телефона и QR, поэтому номер — всё,
 * что нужно.
 */
data class Contact(
    val id: Long,
    val name: String,
    val phone: String,
    /** SMS, если важный приём пропущен (после всех повторов напоминания). */
    val smsMissed: Boolean = true,
    /** SMS, если открыли экстренную карту. */
    val smsEmergency: Boolean = true,
)

/** Экстренная карта профиля (макет, экран 16): то, что нужно врачу скорой, когда человек не может сказать сам. */
data class EmergencyCard(
    val profileId: Long,
    val allergies: String = "",
    val diagnoses: String = "",
    val bloodType: String = "",
    val notes: String = "",
    /** Карта видна на экране блокировки (уведомление); открыть — без разблокировки. */
    val onLockScreen: Boolean = false,
) {
    val isEmpty: Boolean get() = allergies.isBlank() && diagnoses.isBlank() && bloodType.isBlank() && notes.isBlank()
}

/** Тексты SMS и карты — короткие, без лишнего: SMS платная, а карту читают в спешке. */
object FamilyTexts {
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")

    /** «Ampulse: Мама не отметила приём Метформин 850 мг в 08:00.» */
    fun missed(ru: Boolean, who: String, medicine: Medicine, at: LocalDateTime): String {
        val med = listOf(medicine.name, medicine.strength).filter { it.isNotBlank() }.joinToString(" ")
        return if (ru) "Ampulse: $who не отметил(а) приём $med в ${at.format(TIME)}." else "Ampulse: $who has not marked $med due at ${at.format(TIME)}."
    }

    /** «Ampulse: открыли экстренную карту Мама в 14:32.» */
    fun emergencyOpened(ru: Boolean, who: String, at: LocalDateTime): String =
        if (ru) "Ampulse: открыли экстренную карту — $who, ${at.format(TIME)}." else "Ampulse: $who's emergency card was opened at ${at.format(TIME)}."

    /**
     * Карта простым текстом — для QR (читает любая камера, приложение не нужно) и для экрана. Пустые строки не пишутся.
     * [lastDoses] — последний приём каждого лекарства.
     */
    fun card(
        ru: Boolean,
        name: String,
        card: EmergencyCard,
        medicines: List<Medicine>,
        lastDoses: Map<Long, LocalDateTime>,
        contacts: List<Contact>,
    ): String = buildString {
        val f = DateTimeFormatter.ofPattern("dd.MM HH:mm")
        appendLine(if (ru) "ЭКСТРЕННАЯ КАРТА — $name" else "EMERGENCY CARD — $name")
        fun line(ruLabel: String, enLabel: String, value: String) {
            if (value.isNotBlank()) appendLine("${if (ru) ruLabel else enLabel}: ${value.trim()}")
        }
        line("Аллергии", "Allergies", card.allergies)
        line("Диагнозы", "Conditions", card.diagnoses)
        line("Группа крови", "Blood type", card.bloodType)
        if (medicines.isNotEmpty()) {
            appendLine(if (ru) "Лекарства:" else "Medicines:")
            medicines.forEach { m ->
                val last = lastDoses[m.id]?.let { (if (ru) " (последний приём " else " (last taken ") + it.format(f) + ")" }.orEmpty()
                appendLine("- " + listOf(m.name, m.strength).filter { it.isNotBlank() }.joinToString(" ") + last)
            }
        }
        line("Заметки", "Notes", card.notes)
        contacts.forEach { appendLine((if (ru) "Звонить: " else "Call: ") + "${it.name} ${it.phone}") }
    }.trimEnd()
}
