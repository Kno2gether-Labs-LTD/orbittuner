package dev.avijit.orbittuner.ui.shell

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.avijit.orbittuner.metronome.FakeMetronome
import dev.avijit.orbittuner.metronome.MetronomeScreen
import dev.avijit.orbittuner.metronome.MetronomeViewModel
import dev.avijit.orbittuner.settings.Prefs
import dev.avijit.orbittuner.settings.ToolsPane
import dev.avijit.orbittuner.ui.theme.OrbitTunerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

/** Renders the Tools hub (switch over the metronome); writes a PNG to docs/design/app only when ORBIT_SCREENSHOTS=1. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToolsHubScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun hubDark() {
        val engine = FakeMetronome(bpm = 72)
        val vm = MetronomeViewModel(engine, Prefs(ctx, "shot-" + UUID.randomUUID()), { 0L })
        rule.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); vm.loaded }
        rule.setContent {
            OrbitTunerTheme(darkTheme = true) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    ToolsFrame(ToolsPane.Metronome, onPane = {}) { MetronomeScreen(vm, Modifier.fillMaxSize(), heading = false) }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        val root = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { root.draw(Canvas(bmp)) }
        if (System.getenv("ORBIT_SCREENSHOTS") == "1") {
            val dir = File("../docs/design/app").apply { mkdirs() }
            File(dir, "tools-hub-phone-dark.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
