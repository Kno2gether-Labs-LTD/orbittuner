package dev.avijit.orbittuner.metronome

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
import dev.avijit.orbittuner.settings.Prefs
import dev.avijit.orbittuner.ui.theme.OrbitTunerTheme
import kotlinx.coroutines.flow.update
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

/** Renders the metronome (smoke test); writes PNGs to docs/design/app only when ORBIT_SCREENSHOTS=1. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MetronomeScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    private fun shoot(name: String, dark: Boolean, playing: Boolean, beat: Int = -1, compact: Boolean = false) {
        val engine = FakeMetronome(bpm = 72)
        val vm = MetronomeViewModel(engine, Prefs(ctx, "shot-" + UUID.randomUUID()), { 0L })
        rule.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); vm.loaded }
        engine.set(72, 4, true)
        engine.state.update { it.copy(playing = playing, beat = beat) }
        rule.setContent {
            OrbitTunerTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    MetronomeScreen(vm, Modifier.fillMaxSize(), compact = compact)
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
            File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phoneDarkStopped() = shoot("metronome-phone-dark", dark = true, playing = false)

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phoneLightPlaying() = shoot("metronome-phone-light", dark = false, playing = true, beat = 0)

    @Test @Config(qualifiers = "w360dp-h640dp-xxhdpi")
    fun smallDarkPlaying() = shoot("metronome-small-dark", dark = true, playing = true, beat = 2)

    @Test @Config(qualifiers = "w411dp-h500dp-xxhdpi")
    fun compactDark() = shoot("metronome-compact-dark", dark = true, playing = false, compact = true)
}
