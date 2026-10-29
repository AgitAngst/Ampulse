package io.github.agitangst.ampulse.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import io.github.agitangst.ampulse.AmpulseTheme
import io.github.agitangst.ampulse.AppGraph
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.Store
import io.github.agitangst.ampulse.ampulsePalette
import io.github.agitangst.ampulse.domain.FamilyTexts
import io.github.agitangst.anvil.compose.AnvilActivity
import io.github.agitangst.anvil.compose.AnvilSettings
import io.github.agitangst.anvil.compose.AnvilTheme
import io.github.agitangst.anvil.compose.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime

/**
 * Экстренная карта поверх экрана блокировки (макет, экран 16; задача 2.2): та же карта только для чтения — без
 * «Изменить» и без перехода в программу. Открывает её уведомление [EmergencyNotice]; из-за `showWhenLocked` система
 * не требует разблокировки. Как и экран «Напоминание», сам включает экран и держит его включённым, пока карту читают;
 * в отличие от него следует теме программы (светлая, тёмная, как в системе). Не экспортирован. Открытие карты
 * отправляет SMS близким с включённым «SMS об экстренной карте» ([EmergencySms]).
 */
class EmergencyActivity : AnvilActivity() {
    private var profileId by mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val settings = AnvilSettings(this)
        // Фон окна — цвет экрана: без вспышки другого цвета, пока рисуется Compose.
        window.setBackgroundDrawable(ColorDrawable(ampulsePalette(isDark(settings.themeMode)).bg.toArgb()))
        enableEdgeToEdge()
        accept(intent)
        EmergencySms.sendOnOpen(this, profileId)
        setContent {
            AmpulseTheme(settings.themeMode) {
                Surface(Modifier.fillMaxSize(), color = AnvilTheme.palette.bg, contentColor = AnvilTheme.palette.text) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        val data = rememberEmergencyData(profileId)
                        if (data != null) {
                            EmergencyCardView(
                                data = data,
                                readOnly = true,
                                top = {
                                    EmergencyHeaderLink(
                                        stringResource(R.string.emergency_close), MedIcons.Close, onClick = { finishAndRemoveTask() },
                                    )
                                },
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }

    /** Нажали уведомление ещё раз (карта другого профиля или та же), пока экран открыт. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        accept(intent)
        EmergencySms.sendOnOpen(this, profileId)
    }

    private fun accept(intent: Intent) {
        profileId = intent.getLongExtra(EXTRA_PROFILE, 0L)
    }

    /** Тёмная ли тема сейчас: выбор в настройках, «как в системе» — по системе. */
    private fun isDark(mode: ThemeMode): Boolean = when (mode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System ->
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    companion object {
        /** Чья карта открывается: id профиля (0 — «Я»). */
        const val EXTRA_PROFILE = "io.github.agitangst.ampulse.extra.EMERGENCY_PROFILE"
    }
}

/** Не чаще раза в [EmergencySms.MIN_GAP_MS] от прошлой отправки (или если часы ушли назад). */
internal fun emergencySmsDue(lastSentAt: Long, now: Long): Boolean = lastSentAt <= 0L || now < lastSentAt || now - lastSentAt >= EmergencySms.MIN_GAP_MS

/**
 * SMS близким при открытии карты на экране блокировки: «Ampulse: открыли экстренную карту — Мама, 14:32.». Получатели —
 * близкие с включённым «SMS об экстренной карте»; не чаще раза в 30 минут (метка времени в `SharedPreferences("ampulse")`,
 * ключ `emergency_sms_at`), чтобы нескольких касаний подряд хватило одного сообщения. Без разрешения SMS или связи
 * ничего не уходит и метка не ставится (причина — в журнале `AmpulseSms`).
 */
internal object EmergencySms {
    const val KEY_SENT_AT = "emergency_sms_at"
    const val MIN_GAP_MS = 30 * 60 * 1000L

    private const val TAG = "AmpulseEmergency"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Два открытия подряд не должны отправить два SMS: проверка и метка — под одним замком. */
    private val lock = Mutex()

    fun sendOnOpen(context: Context, profileId: Long) {
        val app = context.applicationContext
        scope.launch {
            try {
                send(app, profileId)
            } catch (e: Exception) {
                Log.w(TAG, "Emergency SMS failed", e)
            }
        }
    }

    private suspend fun send(app: Context, profileId: Long) {
        lock.withLock { sendLocked(app, profileId) }
    }

    private suspend fun sendLocked(app: Context, profileId: Long) {
        val prefs = app.getSharedPreferences("ampulse", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!emergencySmsDue(prefs.getLong(KEY_SENT_AT, 0L), now)) return
        val repo = AppGraph.repository
        val contacts = repo.contactsNow().filter { it.smsEmergency && it.phone.isNotBlank() }
        if (contacts.isEmpty()) return
        val fallback = app.getString(R.string.emergency_owner_sms)
        val who = if (profileId == 0L) {
            fallback
        } else {
            repo.profiles.first().firstOrNull { it.id == profileId }?.name?.takeIf { it.isNotBlank() } ?: fallback
        }
        val ru = app.resources.configuration.locales[0].language == "ru"
        val text = FamilyTexts.emergencyOpened(ru, who, LocalDateTime.now())
        var sent = false
        for (contact in contacts) {
            if (Store.sendSms(app, contact.phone, text)) sent = true
        }
        if (sent) prefs.edit { putLong(KEY_SENT_AT, now) }
    }
}
