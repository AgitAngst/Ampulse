package io.github.agitangst.ampulse

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.agitangst.anvil.compose.AnvilActivity
import io.github.agitangst.anvil.compose.AnvilSettings

/** Единственный экран-хозяин: тема и язык из настроек набора, дальше — оболочка с вкладками. */
class MainActivity : AnvilActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = AnvilSettings(this)
        setContent {
            AmpulseTheme(settings.themeMode) { AppShell(settings) }
        }
    }
}
