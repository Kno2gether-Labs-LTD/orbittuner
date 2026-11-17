package dev.avijit.orbittuner.songview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.avijit.orbittuner.data.FakeService
import dev.avijit.orbittuner.data.LibraryRepository
import dev.avijit.orbittuner.data.SongRepository
import dev.avijit.orbittuner.data.db.AppDb
import dev.avijit.orbittuner.net.Song
import dev.avijit.orbittuner.ui.theme.OrbitTunerTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/** The song view after Sing a line: the moved-key banner, the close-call chooser, the capo hint and guessed keys. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SungBannerTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val db: AppDb = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDb::class.java)
        .allowMainThreadQueries().build()
    private val api = FakeService().apply {
        songs["ag"] = amazingGrace()
        songs["nk"] = Song(id = "nk", title = "Keyless", chordpro = "[G]One [C]two [D]three [G]four", updatedAt = 1)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val songs = SongRepository(api, db, scope)
    private val lib = LibraryRepository(db, songs, identity = { null }, session = { null })

    @After fun tearDown() { scope.cancel(); db.close() }

    private fun show(id: String, sung: SungShift?, dark: Boolean = true): SongViewModel {
        val vm = SongViewModel(id, null, songs, lib, sung = sung)
        rule.setContent { OrbitTunerTheme(darkTheme = dark) { SongScreen(vm, onBack = {}) } }
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("sheet")).fetchSemanticsNodes().isNotEmpty() }
        // Chord shapes load on Dispatchers.IO; let them land before drawing (as SongScreenTest does).
        rule.waitUntil(5_000) { Thread.sleep(50); ChordShapes.isLoaded(DiagramInstrument.Guitar) }
        settle()
        return vm
    }

    private fun settle() = repeat(4) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)); rule.waitForIdle() }

    private fun ready(vm: SongViewModel) = (vm.state.value as SongState.Ready).ui

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun movedBannerAndUndo() {
        val vm = show("ag", SungShift(2))
        rule.onNodeWithText("Moved to A to match your singing").assertIsDisplayed()
        shoot("sing-song-banner-phone-dark")
        rule.onNodeWithText("Undo").performClick()
        settle()
        assertEquals(0, ready(vm).transpose)
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasText("Moved to A to match your singing")).fetchSemanticsNodes().isEmpty())
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun closeCallOffersTwoKeys() {
        val vm = show("ag", SungShift(2, alternative = -3))
        rule.onNodeWithText("Sounds like A or E").assertIsDisplayed()
        shoot("sing-song-close-call-phone-dark")
        rule.onNodeWithContentDescription("Play in E").performClick()
        settle()
        assertEquals(-3, ready(vm).transpose)
        rule.onNodeWithText("Moved to E to match your singing").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun anEbResultSuggestsACapo() {
        val vm = show("ag", SungShift(-4), dark = false)
        rule.onNodeWithText("Moved to Eb to match your singing").assertIsDisplayed()
        rule.onNodeWithText("Capo 1", substring = true).assertIsDisplayed()
        shoot("sing-song-capo-phone-light")
        rule.onNodeWithText("Use capo 1").performClick()
        settle()
        assertEquals(1, ready(vm).capo)
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun keylessSongShowsTheGuessedKey() {
        show("nk", null)
        rule.onNodeWithText("≈ G").assertIsDisplayed()
        rule.onNodeWithContentDescription("Key about G").assertExists()
    }

    private fun shoot(name: String) {
        if (System.getenv("ORBIT_SCREENSHOTS") != "1") return
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        val root = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { root.draw(Canvas(bmp)) }
        val dir = File("../docs/design/app").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
