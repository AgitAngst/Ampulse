package io.github.agitangst.ampulse

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.agitangst.anvil.compose.AnvilSettings
import io.github.agitangst.anvil.compose.Tone

/**
 * Сборки `rustore` и `play` (общий набор исходников `src/stores/`): обновляет магазин, своей проверки обновлений нет;
 * SMS сама не уходит — открывается готовое сообщение, «Отправить» нажимает человек (anvil/docs/ANDROID.md, §2).
 */
object Store {
    /** Баннера обновления нет — обновляет магазин. */
    @Composable
    fun Banner(modifier: Modifier = Modifier) = Unit

    /** Раздела «Обновления» нет. */
    @Composable
    fun UpdatesSettings(settings: AnvilSettings, modifier: Modifier = Modifier) = Unit

    /** «О программе» без строки обновления. */
    @Composable
    fun about(): AboutState = AboutState(status = null, tone = Tone.Neutral, action = null, error = null)

    fun onStart(context: Context, settings: AnvilSettings) = Unit

    /** SMS сама не уходит: из фона — уведомление «Отправить SMS …», из окна — готовое сообщение (см. [sendSms]). */
    const val SILENT_SMS = false

    /**
     * Открыть приложение сообщений с готовым текстом. Работает из окна программы; из фона Android окно не откроет —
     * там вызывающий показывает уведомление, касание которого зовёт эту функцию.
     */
    fun sendSms(context: Context, phone: String, text: String): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(phone)))
            .putExtra("sms_body", text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }.onFailure { Log.w("AmpulseSms", "No SMS app: ${it.message}") }.isSuccess
}
