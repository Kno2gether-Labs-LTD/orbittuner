package dev.avijit.orbittuner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.avijit.orbittuner.settings.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import dev.avijit.orbittuner.ui.shell.AppShell
import dev.avijit.orbittuner.ui.theme.OrbitTunerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as OrbitTunerApp).graph
        // Every install has an account: create it now (or keep retrying while offline). Idempotent across recreation.
        graph.start()
        // Sign-out is confirmed by a tap: ignore touches that pass through another app's overlay.
        window.decorView.filterTouchesWhenObscured = true
        // Read the theme once up front (a tiny DataStore read) so a forced light/dark theme doesn't flash the system one.
        val initialTheme = runBlocking { graph.prefs.theme.first() }
        setContent {
            val theme by graph.prefs.theme.collectAsState(initial = initialTheme)
            val dark = when (theme) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Dark -> true
                ThemeMode.Light -> false
            }
            OrbitTunerTheme(darkTheme = dark) { AppShell(graph) }
        }
    }
}
