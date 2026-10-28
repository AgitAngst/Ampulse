package io.github.agitangst.ampulse.reminders

import android.content.Context
import android.content.Intent
import io.github.agitangst.ampulse.data.Codec

/**
 * Намерения напоминаний: что кладётся в будильник, кнопки уведомления и экран «Напоминание», и как это читается.
 * Приём передаётся целиком (лекарство, схема, время, доза) — приёмнику не нужно угадывать, о чём речь.
 */
internal object ReminderIntents {
    /** Сработал будильник приёма (или отложенный). */
    const val ACTION_FIRE = "io.github.agitangst.ampulse.action.REMINDER"

    /** Раз в сутки: пересчитать будильники — окно в 48 часов двигается вперёд. */
    const val ACTION_REFRESH = "io.github.agitangst.ampulse.action.REFRESH"

    /** Кнопка «Принять» в уведомлении. */
    const val ACTION_TAKE = "io.github.agitangst.ampulse.action.TAKE"

    /** Кнопка «Отложить на 10 мин» в уведомлении. */
    const val ACTION_SNOOZE = "io.github.agitangst.ampulse.action.SNOOZE"

    private const val EXTRA_MEDICINE = "medicine"
    private const val EXTRA_SCHEDULE = "schedule"
    private const val EXTRA_PLANNED = "planned"
    private const val EXTRA_AMOUNT = "amount"
    private const val EXTRA_SLOT = "slot"

    /** Ключи, по которым в самом уведомлении лежит его приём: по ним пересчёт убирает уведомления отмеченных. */
    const val NOTIFICATION_MEDICINE = EXTRA_MEDICINE
    const val NOTIFICATION_PLANNED = EXTRA_PLANNED

    /** Записать приём и ячейку ([slot]: 0 — в срок, 1..3 — повтор, 9 — отложено) в намерение. */
    fun put(intent: Intent, dose: DoseRef, slot: Int = ReminderCodes.SLOT_ON_TIME): Intent = intent
        .putExtra(EXTRA_MEDICINE, dose.medicineId)
        .putExtra(EXTRA_SCHEDULE, dose.scheduleId)
        .putExtra(EXTRA_PLANNED, Codec.millis(dose.planned))
        .putExtra(EXTRA_AMOUNT, dose.amount)
        .putExtra(EXTRA_SLOT, slot)

    /** Приём из намерения; `null` — это не наше намерение или оно повреждено. */
    fun read(intent: Intent?): DoseRef? {
        if (intent == null || !intent.hasExtra(EXTRA_MEDICINE) || !intent.hasExtra(EXTRA_PLANNED)) return null
        return DoseRef(
            medicineId = intent.getLongExtra(EXTRA_MEDICINE, 0L),
            scheduleId = intent.getLongExtra(EXTRA_SCHEDULE, 0L),
            planned = Codec.time(intent.getLongExtra(EXTRA_PLANNED, 0L)),
            amount = intent.getDoubleExtra(EXTRA_AMOUNT, 0.0),
        )
    }

    /** Ячейка из намерения (см. [put]). */
    fun slot(intent: Intent?): Int = intent?.getIntExtra(EXTRA_SLOT, ReminderCodes.SLOT_ON_TIME) ?: ReminderCodes.SLOT_ON_TIME

    /** Намерение будильника приёма. Для поиска (отмены) хватает действия и приёмника — данные при сравнении не важны. */
    fun fire(context: Context, dose: DoseRef, slot: Int): Intent = put(base(context), dose, slot)

    /** Намерение без данных — для поиска и отмены будильника по коду. */
    fun base(context: Context): Intent = Intent(ACTION_FIRE).setClass(context, ReminderReceiver::class.java)

    /** Намерение «пересчитать будильники». */
    fun refresh(context: Context): Intent = Intent(ACTION_REFRESH).setClass(context, ReminderReceiver::class.java)

    /** Намерение кнопки уведомления. */
    fun action(context: Context, action: String, dose: DoseRef): Intent =
        put(Intent(action).setClass(context, ActionReceiver::class.java), dose)

    /** Намерение экрана «Напоминание». */
    fun screen(context: Context, dose: DoseRef, slot: Int): Intent =
        put(Intent(context, ReminderActivity::class.java), dose, slot)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
}
