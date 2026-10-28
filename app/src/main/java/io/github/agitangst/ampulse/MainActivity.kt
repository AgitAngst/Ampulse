package io.github.agitangst.ampulse

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import io.github.agitangst.anvil.compose.AnvilActivity
import io.github.agitangst.anvil.compose.AnvilSettings
import io.github.agitangst.anvil.compose.AnvilTheme

/** Единственный экран-хозяин: тема и язык из настроек набора, дальше — оболочка с вкладками. */
class MainActivity : AnvilActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = AnvilSettings(this)
        setContent {
            AmpulseTheme(settings.themeMode) {
                // Временная заглушка до оболочки (задача 2.1).
                Box(Modifier.fillMaxSize().background(AnvilTheme.palette.bg)) { Text("Ampulse") }
            }
        }
    }
}
