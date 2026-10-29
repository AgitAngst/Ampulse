package io.github.agitangst.ampulse

import android.content.Intent
import android.os.Bundle
import io.github.agitangst.ampulse.ui.PendingImport
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.agitangst.anvil.compose.AnvilActivity
import io.github.agitangst.anvil.compose.AnvilSettings

/** Единственный экран-хозяин: тема и язык из настроек набора, дальше — оболочка с вкладками. */
class MainActivity : AnvilActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) takeShared(intent)
        enableEdgeToEdge()
        val settings = AnvilSettings(this)
        setContent {
            AmpulseTheme(settings.themeMode) { AppShell(settings) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeShared(intent)
    }

    /** Текст из «Поделиться» — в разбор назначения (оболочка открывает его сама). */
    private fun takeShared(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { PendingImport.text = it }
        }
    }
}
