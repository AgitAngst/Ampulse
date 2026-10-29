package io.github.agitangst.ampulse

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.agitangst.ampulse.sync.ServerSync
import io.github.agitangst.anvil.compose.AnvilSettings
import io.github.agitangst.anvil.update.UpdateBanner
import io.github.agitangst.anvil.update.UpdateConfig
import io.github.agitangst.anvil.update.Updater
import io.github.agitangst.anvil.update.UpdatesSection
import io.github.agitangst.anvil.update.rememberAboutUpdate

/**
 * Всё, что отличает сборку `github` от остальных магазинов: обновление из GitHub Releases. Общий код зовёт
 * только этот объект и [AboutState], а не `anvil-update` напрямую (anvil/docs/ANDROID.md, §2).
 */
object Store {
    fun updater(context: Context): Updater = Updater.shared(
        context,
        UpdateConfig(app = "ampulse", version = BuildConfig.VERSION_NAME, repo = "AgitAngst/Ampulse"),
    )

    /** Баннер обновления над содержимым; скрыт — места не занимает (отступы у самой карточки, в [modifier]). */
    @Composable
    fun Banner(modifier: Modifier = Modifier) {
        val context = LocalContext.current
        UpdateBanner(remember(context) { updater(context) }, modifier)
    }

    /** Раздел настроек «Обновления». */
    @Composable
    fun UpdatesSettings(settings: AnvilSettings, modifier: Modifier = Modifier) {
        val context = LocalContext.current
        UpdatesSection(settings, remember(context) { updater(context) }, modifier)
    }

    /** Состояние обновления для карточки «О программе». */
    @Composable
    fun about(): AboutState {
        val context = LocalContext.current
        val about = rememberAboutUpdate(remember(context) { updater(context) })
        return AboutState(about.status, about.tone, about.action, about.error)
    }

    /** Запуск программы: проверка обновления раз за процесс, если включено «Проверять обновления». */
    fun onStart(context: Context, settings: AnvilSettings) {
        updater(context).auto(settings.checkUpdates)
        ServerSync.onStart()
    }

    /** Свой сервер («зеркало», этап 0.10): только сборка github — в магазинах сети нет, экрана «Сервер» тоже. */
    const val SERVER_SYNC = true

    /** SMS уходит сама, без участия человека: в сборке github это можно (разрешение SEND_SMS). */
    const val SILENT_SMS = true

    /**
     * SMS близкому (пропуск важного приёма, открытая экстренная карта, этап 0.8). Нет разрешения или связи — `false`,
     * причина — в журнале. В магазинах, где SEND_SMS не дают, эта функция открывает приложение сообщений.
     */
    fun sendSms(context: Context, phone: String, text: String): Boolean {
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.w("AmpulseSms", "SEND_SMS is not granted")
            return false
        }
        return runCatching {
            val sms = context.getSystemService(SmsManager::class.java) ?: error("No SmsManager")
            sms.sendMultipartTextMessage(phone, null, sms.divideMessage(text), null, null)
        }.onFailure { Log.w("AmpulseSms", "SMS failed: ${it.message}") }.isSuccess
    }
}
