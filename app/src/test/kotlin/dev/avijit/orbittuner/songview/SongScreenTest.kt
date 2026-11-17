package dev.avijit.orbittuner.songview

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.avijit.orbittuner.data.FakeService
import dev.avijit.orbittuner.data.LibraryRepository
import dev.avijit.orbittuner.data.SongRepository
import dev.avijit.orbittuner.data.db.AppDb
import dev.avijit.orbittuner.data.db.SongPrefs
import android.content.Context
import androidx.room.Room
import dev.avijit.orbittuner.net.Song
import dev.avijit.orbittuner.ui.theme.OrbitTunerTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
class SongScreenTest {
    @get:Rule val rule = createEmptyComposeRule()

    // Real query executors: the inline test DB would run flow re-queries inside the writer's transaction on the main thread.
    private val db: AppDb = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDb::class.java)
        .allowMainThreadQueries().build()
    private val api = FakeService()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val songs = SongRepository(api, db, scope)
    private val lib = LibraryRepository(db, songs, identity = { null }, session = { null })
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private var callbacks: Application.ActivityLifecycleCallbacks? = null
    private var scenario: ActivityScenario<ComponentActivity>? = null
    private val flagged = ArrayList<Triple<String, dev.avijit.orbittuner.net.FlagReason, String?>>()

    @After fun tearDown() {
        scenario?.close()
        callbacks?.let(app::unregisterActivityLifecycleCallbacks)
        scope.cancel()
        db.close()
    }

    /** Sets the song screen as content of every (re)created activity, like the app's route would. */
    private fun launch(songId: String, dark: Boolean = true, metronome: (() -> dev.avijit.orbittuner.metronome.MetronomeViewModel)? = null): ActivityScenario<ComponentActivity> {
        val cb = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, saved: Bundle?) {
                (activity as? ComponentActivity)?.setContent {
                    OrbitTunerTheme(darkTheme = dark) {
                        val vm = viewModel(key = "song:$songId") { SongViewModel(songId, null, songs, lib, portalHost = { "tuner.example" }, flagger = { id, r, n -> flagged += Triple(id, r, n) }) }
                        SongScreen(vm, onBack = {}, metronome = metronome)
                    }
                }
            }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        callbacks = cb
        app.registerActivityLifecycleCallbacks(cb)
        return ActivityScenario.launch(ComponentActivity::class.java).also { scenario = it }
    }

    private fun awaitSheet() = rule.waitUntil(5_000) {
        rule.onAllNodesWithTagExists("sheet")
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTagExists(tag: String): Boolean =
        onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun scrollOffset(): Float =
        rule.onNodeWithTag("sheet").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun chordStripShowsTransposedChordsAndOpensDiagram() {
        // In F: F, Gm, C7. Saved +2 -> G, Am, D7.
        api.songs["f"] = Song(id = "f", title = "In F", originalKey = "F", chordpro = "{key: F}\n[F]One [Gm]two [C7]three [F]four", updatedAt = 1)
        runBlocking { songs.open("f"); songs.setPrefs(SongPrefs("f", transpose = 2, capo = 0, scrollSpeed = 0.0, textScale = 1f)) }
        launch("f")
        awaitSheet()
        val strip = hasAnyAncestor(hasTestTag("chord-strip"))
        listOf("G", "Am", "D7").forEach { rule.onNode(hasText(it) and strip).assertIsDisplayed() }
        assertTrue(rule.onAllNodes(hasText("Gm") and strip).fetchSemanticsNodes().isEmpty())

        rule.onNode(hasText("Am") and strip).performClick()
        rule.onNode(hasText("Am") and hasAnyAncestor(hasTestTag("diagram-sheet"))).assertIsDisplayed()
    }

    private fun metronomeVm(engine: dev.avijit.orbittuner.metronome.Metronome) = {
        dev.avijit.orbittuner.metronome.MetronomeViewModel(
            engine, dev.avijit.orbittuner.settings.Prefs(app, "song-metronome-" + java.util.UUID.randomUUID()), { 0L }, persist = false,
        )
    }

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun metronomeOpensAtTheSongsTempoAndBeats() {
        api.songs["m"] = Song(id = "m", title = "Waltz", chordpro = "{tempo: 96}\n{time: 3/4}\n[G]One two three", updatedAt = 1)
        val engine = dev.avijit.orbittuner.metronome.FakeMetronome()
        launch("m", metronome = metronomeVm(engine))
        awaitSheet()
        rule.onNodeWithContentDescription("Metronome").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("bpm")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("bpm").assertTextEquals("96")
        assertEquals(3, rule.onAllNodes(hasTestTag("beat-dot")).fetchSemanticsNodes().size)
        // Closing the sheet stops the clicks.
        // Semantic click: the play button may sit below the test window's sheet bounds.
        rule.onNodeWithContentDescription("Start metronome").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("starts=${engine.starts} stops=${engine.stops}", 1, engine.starts)
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Close sheet")).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitUntil(5_000) { !engine.state.value.playing }
    }

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun metronomeDefaultsToEightyWithoutATempo() {
        api.songs["n"] = Song(id = "n", title = "Plain", chordpro = "[G]No tempo here", updatedAt = 1)
        launch("n", metronome = metronomeVm(dev.avijit.orbittuner.metronome.FakeMetronome()))
        awaitSheet()
        rule.onNodeWithContentDescription("Metronome").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("bpm")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("bpm").assertTextEquals("80")
        assertEquals(4, rule.onAllNodes(hasTestTag("beat-dot")).fetchSemanticsNodes().size)
    }

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun noMetronomeButtonWithoutAMetronome() {
        api.songs["p"] = Song(id = "p", title = "Plain", chordpro = "[G]No tempo here", updatedAt = 1)
        launch("p")
        awaitSheet()
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Metronome")).fetchSemanticsNodes().isEmpty())
    }

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun exportSharesAChordProFile() {
        api.songs["ag"] = amazingGrace()
        launch("ag")
        awaitSheet()
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Share as ChordPro").performClick()
        // The file is written off the main thread; the chooser starts when it is done.
        var started: Intent? = null
        rule.waitUntil(5_000) {
            scenario!!.onActivity { started = shadowOf(it).nextStartedActivity }
            started != null
        }
        val chooser = requireNotNull(started) { "no activity started" }
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION") val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals("amazing-grace.cho", send.getStringExtra(Intent.EXTRA_TITLE))
        assertTrue(send.getStringExtra(Intent.EXTRA_TEXT)!!.contains("{title: Amazing Grace}"))
        @Suppress("DEPRECATION") val stream = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertTrue(stream.toString(), stream.lastPathSegment!!.endsWith(".cho"))
    }

    /** Review Focus 4: rotation while scrolling keeps playing and keeps the place (within a line). */
    @Test @Config(qualifiers = "w411dp-h891dp")
    fun recreateWhileScrollingKeepsPlayingAndOffset() {
        val long = amazingGrace().let { s -> s.copy(chordpro = s.chordpro + ("\n" + s.chordpro.substringAfter("{time: 3/4}")).repeat(6)) }
        api.songs["ag"] = long
        val sc = launch("ag")
        awaitSheet()
        // Auto-scroll asks for every frame, so the clock is driven by hand (auto-advance would run to the end).
        rule.mainClock.autoAdvance = false
        rule.onNodeWithContentDescription("Play").performClick()
        rule.mainClock.advanceTimeBy(4_000)
        val before = scrollOffset()
        assertTrue("should have scrolled, offset=$before", before > 0f)
        rule.onNodeWithContentDescription("Pause").assertIsDisplayed()

        sc.recreate()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        awaitSheet()
        val after = scrollOffset()
        val line = with(rule.density) { (20 * 1.5f + 18 * 1.45f).sp.toPx() }
        assertTrue("offset $before -> $after (line $line)", abs(after - before) <= line)
        rule.onNodeWithContentDescription("Pause").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(1_000)
        assertTrue("keeps scrolling after recreate", scrollOffset() > after)
        rule.mainClock.autoAdvance = true
    }

    /** A song whose lines are all different, so "the same line" is checkable. */
    private fun numbered(lines: Int): Song = Song(
        id = "n", title = "Numbered", originalKey = "G", updatedAt = 1,
        chordpro = "{key: G}\n{tempo: 80}\n" + (1..lines).joinToString("\n") { i -> "[G]Line $i goes [C]on and [D]on" },
    )

    /** Text of the first lyric line whose top is inside the sheet viewport. */
    private fun topVisibleLine(): String {
        val sheet = rule.onNodeWithTag("sheet").fetchSemanticsNode().boundsInRoot
        return rule.onAllNodes(hasText("Line ", substring = true) and hasAnyAncestor(hasTestTag("sheet")))
            .fetchSemanticsNodes()
            .filter { it.boundsInRoot.top >= sheet.top }
            .minBy { it.boundsInRoot.top }
            .config[SemanticsProperties.Text].joinToString("")
    }

    private fun assertLineVisible(text: String) {
        val sheet = rule.onNodeWithTag("sheet").fetchSemanticsNode().boundsInRoot
        val node = rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag("sheet"))).fetchSemanticsNode().boundsInRoot
        assertTrue("'$text' at $node should be inside $sheet", node.top >= sheet.top - 1 && node.bottom <= sheet.bottom + 1)
    }

    /** Review Focus 1: rotating/folding while playing keeps playing and keeps the same line in view; never rewinds. */
    @Test @Config(qualifiers = "w600dp-h960dp")
    fun rotateWhilePlayingKeepsTheSameLine() {
        api.songs["n"] = numbered(80)
        val sc = launch("n")
        awaitSheet()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithContentDescription("Play").performClick()
        rule.mainClock.advanceTimeBy(20_000)
        val line = topVisibleLine()
        assertTrue("should have scrolled past the start, top=$line", line != "Line 1 goes on and on")

        RuntimeEnvironment.setQualifiers("+land")
        sc.recreate()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        awaitSheet()
        repeat(3) { rule.mainClock.advanceTimeByFrame() }
        rule.onNodeWithContentDescription("Pause").assertIsDisplayed()
        assertLineVisible(line)
        rule.mainClock.autoAdvance = true
    }

    // ---- design renders (not assertions): written to app/build/screenshots, copied to docs/design/app ----

    private fun render(name: String, dark: Boolean = true, openChord: String? = null) {
        val ag = amazingGrace()
        api.songs["ag"] = ag.copy(chordpro = ag.chordpro + "\n" + EXTRA_VERSES)
        runBlocking { songs.open("ag"); songs.setPrefs(SongPrefs("ag", transpose = 2, capo = 0, scrollSpeed = 0.0, textScale = 1f)) }
        launch("ag", dark)
        awaitSheet()
        // Chord shapes load on Dispatchers.IO; let them land before drawing.
        rule.waitUntil(5_000) { Thread.sleep(50); shapesLoaded() }
        rule.waitForIdle()
        if (openChord != null) {
            // Semantic click, not a touch: a touch ripple crashes Robolectric's native RuntimeShader.
            rule.onNode(hasText(openChord) and hasAnyAncestor(hasTestTag("chord-strip"))).performSemanticsAction(SemanticsActions.OnClick)
            rule.mainClock.advanceTimeBy(1_000)
            rule.waitForIdle()
        }
        // Software draw of the window (captureToImage waits for a hardware redraw Robolectric never signals here).
        var bmp: Bitmap? = null
        scenario!!.onActivity { a ->
            val v = a.window.decorView
            bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(android.graphics.Canvas(it)) }
        }
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "songview-$name.png").outputStream().use { bmp!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers = "w412dp-h915dp-port-xhdpi")
    fun renderPhone() = render("phone")

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers = "w412dp-h915dp-port-xhdpi")
    fun renderPhoneLight() = render("phone-light", dark = false)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers = "w412dp-h915dp-port-xhdpi")
    fun renderPhoneDiagram() = render("phone-diagram", openChord = "F#m")

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers = "w884dp-h1104dp-port-hdpi")
    fun renderTablet() = render("tablet")

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers = "w1104dp-h884dp-land-hdpi")
    fun renderLandscape() = render("landscape")

    private fun shapesLoaded(): Boolean = ChordShapes.isLoaded(DiagramInstrument.Guitar)

    private companion object {
        const val EXTRA_VERSES = """
{start_of_chorus}
[G]Through many [G7]dangers, [C]toils and [G]snares
I [G]have al[Em]ready [D]come
'Tis [G]grace hath [G7]brought me [C]safe thus [G]far
And [Em]grace will [D]lead me [G]home
{end_of_chorus}

{start_of_verse: Verse 4}
When [G]we've been [G7]there ten [C]thousand [G]years
Bright [G]shining [Em]as the [D]sun
We've [G]no less [G7]days to [C]sing God's [G]praise
Than [Em]when we'd [D]first be[G]gun
{end_of_verse}
"""
    }

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun flagSheetSendsTheChosenReason() {
        api.songs["ag"] = amazingGrace()
        launch("ag")
        awaitSheet()
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Flag this song").performClick()
        rule.onNodeWithTag("flag-sheet").assertIsDisplayed()
        // Semantics clicks: the sheet slides in on the looper clock, which this rule doesn't drive.
        rule.onNodeWithText("Send flag").assertIsNotEnabled()
        rule.onNodeWithText("Wrong lyrics").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("Wrong lyrics", substring = true).assertIsSelected()
        rule.onNodeWithTag("flag-note").performTextInput("verse 3 is missing")
        rule.onNodeWithText("Send flag").assertIsEnabled()
        rule.onNodeWithText("Send flag").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitUntil(5_000) { flagged.isNotEmpty() }
        assertEquals(listOf(Triple("ag", dev.avijit.orbittuner.net.FlagReason.WrongLyrics, "verse 3 is missing")), flagged)
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("flag-sheet")).fetchSemanticsNodes().isEmpty() }
    }
}
