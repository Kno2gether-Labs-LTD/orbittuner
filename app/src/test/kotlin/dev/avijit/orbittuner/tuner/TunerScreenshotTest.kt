package dev.avijit.orbittuner.tuner

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.avijit.orbittuner.settings.MeterStyle
import dev.avijit.orbittuner.settings.NoteNaming
import dev.avijit.orbittuner.settings.Prefs
import kotlinx.coroutines.runBlocking
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

/**
 * Renders the tuner at phone and tablet sizes (the UI screenshot pass in the spec). Always renders, as a smoke
 * test; writes PNGs to docs/design/app only when ORBIT_SCREENSHOTS=1.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TunerScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val instruments = Tunings.parse(File("src/main/assets/tunings.json").readText())

    private fun shoot(
        name: String, dark: Boolean, state: TunerState, tuned: Set<Int>, problem: MicProblem? = null,
        naming: NoteNaming = NoteNaming.Letters, meter: MeterStyle = MeterStyle.Guide,
        /** Shown (and settled) first, e.g. a reading that then fades to [state]. */
        before: TunerState? = null,
    ) {
        val engine = FakeTracker()
        val prefs = Prefs(ctx, "shot-" + UUID.randomUUID())
        if (naming != NoteNaming.Letters) runBlocking { prefs.setNoteNaming(naming) }
        if (meter != MeterStyle.Guide) runBlocking { prefs.setMeterStyle(meter) }
        val vm = TunerViewModel(engine, prefs, instruments, FakeTone())
        rule.setContent { OrbitTunerTheme(darkTheme = dark) { TunerScreen(vm, problem = problem, onTunedHaptic = {}) } }
        rule.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); vm.ui.value != null }
        if (before != null) {
            engine.state.value = before
            rule.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); vm.readout.value.hasPitch }
            rule.mainClock.advanceTimeBy(1_000)
            rule.waitForIdle()
        }
        engine.state.value = state
        engine.tunedStrings.value = tuned
        rule.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); vm.readout.value.error != null || vm.readout.value.hasPitch == (state is TunerState.Reading) }
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        // captureToImage() waits for a redraw the paused Robolectric looper never delivers; draw the window instead.
        val root = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { root.draw(Canvas(bmp)) }
        if (System.getenv("ORBIT_SCREENSHOTS") == "1") {
            val dir = File("../docs/design/app").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private val flat = TunerState.Reading("A", 2, -7.0, 109.56, 1, inTune = false)
    private val inTune = TunerState.Reading("A", 2, 1.0, 110.06, 1, inTune = true)
    private val sharp = TunerState.Reading("G", 3, 18.0, 197.98, 3, inTune = false)

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phoneDarkFlat() = shoot("tuner-phone-dark", true, flat, setOf(0))

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phoneLightSharp() = shoot("tuner-phone-light", false, sharp, setOf(0, 1, 2))

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phoneDarkInTune() = shoot("tuner-phone-dark-intune", true, inTune, setOf(0, 1))

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phoneDarkListening() = shoot("tuner-phone-dark-listening", true, TunerState.Listening, emptySet())

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phoneDarkPermission() =
        shoot("tuner-phone-dark-permission", true, TunerState.Error(MicError.PermissionDenied), emptySet())

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phoneDarkSolfege() = shoot("tuner-phone-dark-solfege", true, sharp, setOf(0, 1, 2), naming = NoteNaming.Solfege)

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phoneDarkDialFlat() = shoot("tuner-phone-dark-dial", true, flat, setOf(0), meter = MeterStyle.Dial)

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phoneLightDialInTune() = shoot("tuner-phone-light-dial-intune", false, inTune, setOf(0, 1), meter = MeterStyle.Dial)

    /** Fold cover screen above its bottom nav, nothing heard yet: no placeholder note, the meter sits up top. */
    @Test @Config(qualifiers = "w370dp-h830dp-xxhdpi")
    fun foldDarkListening() = shoot("tuner-fold-dark-listening", true, TunerState.Listening, emptySet())

    /** A flat note fades out: the last note stays faint, and the dot (and needle) rest at the centre. */
    @Test @Config(qualifiers = "w370dp-h830dp-xxhdpi")
    fun foldDarkPitchLost() = shoot("tuner-fold-dark-lost", true, TunerState.Listening, emptySet(), before = flat)

    @Test @Config(qualifiers = "w370dp-h830dp-xxhdpi")
    fun foldDarkDialPitchLost() =
        shoot("tuner-fold-dark-dial-lost", true, TunerState.Listening, emptySet(), meter = MeterStyle.Dial, before = flat)

    /** Fold cover screen class: short window above a bottom nav. */
    @Test @Config(qualifiers = "w360dp-h600dp-xxhdpi")
    fun shortDarkGuide() = shoot("tuner-short-dark", true, sharp, setOf(0, 1, 2))

    @Test @Config(qualifiers = "w360dp-h600dp-xxhdpi")
    fun shortDarkDial() = shoot("tuner-short-dark-dial", true, sharp, setOf(0, 1, 2), meter = MeterStyle.Dial)

    @Test @Config(qualifiers = "w800dp-h1100dp-xhdpi")
    fun tabletDarkInTune() = shoot("tuner-tablet-dark", true, inTune, setOf(0, 1))

    @Test @Config(qualifiers = "w800dp-h1100dp-xhdpi")
    fun tabletLightFlat() = shoot("tuner-tablet-light", false, flat, setOf(0))
}
