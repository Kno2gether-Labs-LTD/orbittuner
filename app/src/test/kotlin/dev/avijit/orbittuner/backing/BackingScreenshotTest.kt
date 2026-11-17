package dev.avijit.orbittuner.backing

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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

/** Renders the lesson list and the player (smoke test); writes PNGs to docs/design/app only when ORBIT_SCREENSHOTS=1. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackingScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    private fun shoot(name: String, dark: Boolean, setUp: (BackingViewModel, FakeBacking) -> Unit = { _, _ -> }) {
        val engine = FakeBacking()
        val vm = BackingViewModel(engine, Prefs(ctx, "shot-" + UUID.randomUUID()))
        rule.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); vm.loaded }
        setUp(vm, engine)
        rule.setContent {
            OrbitTunerTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    BackingHost(vm, Modifier.fillMaxSize())
                }
            }
        }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        // Diagram shapes load off the main thread.
        repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        val root = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { root.draw(Canvas(bmp)) }
        if (System.getenv("ORBIT_SCREENSHOTS") == "1") {
            val dir = File("../docs/design/app").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun playing(id: String, bar: Int, beat: Int, chord: String, next: String, key: Int = 0): (BackingViewModel, FakeBacking) -> Unit =
        { vm, engine ->
            vm.open(id)
            if (key != 0) vm.shiftKey(key)
            vm.toggle()
            engine.state.value = engine.state.value.copy(bar = bar, beat = beat, chord = chord, nextChord = next, countIn = bar < 0)
        }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun listDark() = shoot("backing-list-phone-dark", dark = true)

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun listLight() = shoot("backing-list-phone-light", dark = false)

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun playerPlayingDark() = shoot("backing-player-phone-dark", dark = true, playing("four-chords", 1, 3, "D", "Em"))

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun playerLight() = shoot("backing-player-phone-light", dark = false, playing("slow-minor", 0, 1, "Bbm", "Ebm", key = 1))

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun countInDark() = shoot("backing-countin-phone-dark", dark = true, playing("bengali-6-8", -1, 2, "D", "G"))

    @Test @Config(qualifiers = "w360dp-h640dp-xxhdpi")
    fun playerSmallDark() = shoot("backing-player-small-dark", dark = true, playing("seven-eight", 0, 4, "A", "G"))

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun droneStoppedDark() = shoot("backing-drone-phone-dark", dark = true) { vm, _ -> vm.open("drone") }
}
