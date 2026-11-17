package dev.avijit.orbittuner.songs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.avijit.orbittuner.data.FakeService
import dev.avijit.orbittuner.data.LibraryRepository
import dev.avijit.orbittuner.data.LocalResults
import dev.avijit.orbittuner.data.SongRepository
import dev.avijit.orbittuner.data.db.AppDb
import dev.avijit.orbittuner.data.db.SongPrefs
import dev.avijit.orbittuner.net.ApiError
import dev.avijit.orbittuner.net.CategoryFacet
import dev.avijit.orbittuner.songview.ChordShapes
import dev.avijit.orbittuner.songview.DiagramInstrument
import dev.avijit.orbittuner.songview.SongScreen
import dev.avijit.orbittuner.songview.SongViewModel
import dev.avijit.orbittuner.songview.amazingGrace
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class SearchScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val fake = FakeDiscovery().apply {
        val yours = listOf(cached("ag", "Amazing Grace", "A", "John Newton"))
        val device = listOf(
            cached("gg", "Grace Greater Than Our Sin", "F", "Julia Johnston"),
            cached("wg", "Wonderful Grace of Jesus", "G", "Haldor Lillenas"),
        )
        local = { q ->
            if (q.isBlank()) LocalResults(yours, device + cached("bt", "Be Thou My Vision", "D", "Irish traditional"))
            else LocalResults(yours, device)
        }
        remote["grace"] = listOf(
            summary("ag", "Amazing Grace", "A", artist = "John Newton"),
            summary("mg", "Marvelous Grace of Our Loving Lord", "D", artist = "Julia Johnston"),
            summary("sg", "Saving Grace", "E", artist = null, category = "Worship"),
            summary("bg", "Amazing Grace", "C", language = "bn", artist = "Bengali translation", category = "Hymn"),
        )
        browseRows = { _, _ ->
            listOf(
                summary("hh", "How Great Thou Art", "Bb", artist = "Carl Boberg"),
                summary("ii", "It Is Well With My Soul", "C", artist = "Horatio Spafford"),
                summary("gd", "Great Is Thy Faithfulness", "D", artist = "Thomas Chisholm"),
                summary("ys", "Yeshu Masih", "G", language = "hi", artist = null, category = "Worship"),
                summary("10", "10,000 Reasons", "G", artist = "Matt Redman", category = "Worship"),
            )
        }
        facets = facets.copy(categories = facets.categories + CategoryFacet("Folk", 9) + CategoryFacet("Christmas", 14))
    }
    private var openedSong: String? = null
    private var openedSettings = false

    // Only for the tablet render's song pane (real executors, like SongScreenTest).
    private var db: AppDb? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After fun tearDown() { scope.cancel(); db?.close() }

    private fun show(dark: Boolean = true, detail: @androidx.compose.runtime.Composable (dev.avijit.orbittuner.songview.SongDest, () -> Unit, () -> Unit) -> Unit = { id, _, _ -> Box(Modifier.fillMaxSize().testTag("detail-${id.songId}")) }): SearchViewModel {
        val vm = fake.vm()
        rule.setContent {
            OrbitTunerTheme(darkTheme = dark) {
                SearchScreen(vm, onOpenSong = { openedSong = it }, onOpenSettings = { openedSettings = true }, detail = detail)
            }
        }
        settle()
        return vm
    }

    /** Runs the main looper past the debounce and the fake's latency. */
    private fun settle() {
        repeat(8) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150)); rule.waitForIdle() }
    }

    private fun search(q: String) {
        rule.onNodeWithTag("search-field").performTextInput(q)
        settle()
    }

    private fun top(text: String): Float = rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun tiersInOrderLibrarySkipsShownSongsAndWebWaitsForFewResults() {
        show()
        search("grace")
        rule.onNodeWithText("Saving Grace").assertIsDisplayed()
        assertTrue(top("Your songs") < top("On this device"))
        assertTrue(top("On this device") < top("Library"))
        // "ag" is in Your songs, so the Library tier doesn't repeat it.
        assertEquals(1, rule.onAllNodes(hasTestTag("row-ag")).fetchSemanticsNodes().size)
        // Three library results: no "Find on the web" yet.
        assertTrue(rule.onAllNodes(hasTestTag("find-on-web")).fetchSemanticsNodes().isEmpty())
    }

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun phoneTapOpensTheSong() {
        show()
        search("grace")
        rule.onNodeWithTag("row-sg").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("sg", openedSong)
    }

    @Test @Config(qualifiers = "w884dp-h1104dp")
    fun tabletTapShowsTheSongBesideTheList() {
        show()
        rule.onNodeWithTag("empty-detail").assertIsDisplayed()
        search("grace")
        rule.onNodeWithTag("row-sg").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.onNodeWithTag("detail-sg").assertExists()
        assertEquals(null, openedSong)
    }

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun notPairedOffersSettings() {
        fake.paired.value = false
        show()
        rule.onNodeWithText("Connecting to the song library").assertIsDisplayed()
        rule.onNodeWithText("Account settings").performClick()
        assertTrue(openedSettings)
    }

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun offlineShowsDeviceSongs() {
        fake.fail = ApiError.Offline
        show()
        search("grace")
        rule.onNodeWithText("Grace Greater Than Our Sin").assertIsDisplayed()
        rule.onNodeWithText("You're offline — showing songs on this device").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun exploreShowsLanguageNamesAndBrowsesByCategory() {
        show()
        rule.onNodeWithText("Hindi").assertIsDisplayed()
        rule.onNodeWithTag("category-Worship").performSemanticsAction(SemanticsActions.OnClick)
        settle()
        assertEquals(Triple<String?, String?, Int>(null, "Worship", 0), fake.browseCalls.last())
    }

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun exploreShowsOnlyTheTopThreeCategoriesEvenWithThirtyFacets() {
        fake.facets = fake.facets.copy(categories = (1..30).map { CategoryFacet("Cat$it", it) })
        show()
        assertEquals(3, rule.onAllNodes(hasTestTag("category-Cat30") or hasTestTag("category-Cat29") or hasTestTag("category-Cat28")).fetchSemanticsNodes().size)
        assertTrue(rule.onAllNodes(hasTestTag("category-Cat27")).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("For you").assertIsDisplayed()
        // Nothing listed until the user types.
        assertTrue(rule.onAllNodes(hasTestTag("category-option-Cat1")).fetchSemanticsNodes().isEmpty())
    }

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun categorySearchListsMatchesAndChoosingOneBrowsesAndShowsARemovableChip() {
        fake.facets = fake.facets.copy(categories = fake.facets.categories + CategoryFacet("90s", 4))
        val vm = show()
        rule.onNodeWithTag("category-search").performTextInput("nine")
        settle()
        assertTrue(rule.onAllNodes(hasTestTag("category-option-90s")).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("category-search").performTextClearance()
        rule.onNodeWithTag("category-search").performTextInput("90")
        settle()
        rule.onNodeWithTag("category-option-90s").performSemanticsAction(SemanticsActions.OnClick)
        settle()
        assertEquals(Triple<String?, String?, Int>(null, "90s", 0), fake.browseCalls.last())
        assertEquals(listOf("90s"), fake.recorded)
        rule.onNodeWithTag("category-selected").assertIsDisplayed()
        rule.onNodeWithTag("category-selected").performSemanticsAction(SemanticsActions.OnClick)
        settle()
        assertEquals(null, vm.explore.value.cat)
        assertTrue(rule.onAllNodes(hasTestTag("category-selected")).fetchSemanticsNodes().isEmpty())
    }

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun searchModeShowsOnlyTopThreeCategoryChipsAndAPickerToggle() {
        fake.facets = fake.facets.copy(categories = (1..30).map { CategoryFacet("Cat$it", it) })
        show()
        search("grace")
        val chips = rule.onAllNodes(hasTestTag("category-chip-Cat30") or hasTestTag("category-chip-Cat29") or hasTestTag("category-chip-Cat28")).fetchSemanticsNodes().size
        assertEquals(3, chips)
        assertTrue(rule.onAllNodes(hasTestTag("category-chip-Cat27")).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("category-picker-toggle").performSemanticsAction(SemanticsActions.OnClick)
        settle()
        rule.onNodeWithTag("category-search").performTextInput("Cat7")
        settle()
        rule.onNodeWithTag("category-option-Cat7").performSemanticsAction(SemanticsActions.OnClick)
        settle()
        assertEquals("Cat7", fake.browseCalls.last().second)
        assertEquals(1, rule.onAllNodes(hasTestTag("category-selected")).fetchSemanticsNodes().size)
    }

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun narrowExploreShowsForYouAsFullWidthRowsOfAtLeast48dp() {
        show()
        val h = rule.onNodeWithTag("category-Hymns").fetchSemanticsNode().size
        val density = rule.activity.resources.displayMetrics.density
        assertTrue(h.height / density >= 48f)
        val listW = rule.onNodeWithTag("category-Worship").fetchSemanticsNode().size.width
        assertTrue(listW > rule.activity.resources.displayMetrics.widthPixels * 0.8)
    }

    /** Process death: the field text is restored into a fresh ViewModel, which must search for it again. */
    @Test @Config(qualifiers = "w411dp-h891dp")
    fun restoredQueryIsSearchedAgainAfterProcessDeath() {
        val tester = StateRestorationTester(rule)
        tester.setContent {
            OrbitTunerTheme {
                // A new ViewModel per (re)creation, like after process death; the saveable field text survives.
                val vm = remember { fake.vm() }
                SearchScreen(vm, onOpenSong = {}, onOpenSettings = {}, detail = { _, _, _ -> })
            }
        }
        settle()
        rule.onNodeWithTag("search-field").performTextInput("grace")
        settle()
        tester.emulateSavedInstanceStateRestore()
        settle()
        rule.onNodeWithText("Saving Grace").assertIsDisplayed()
        assertEquals(listOf("grace", "grace"), fake.remoteCalls)
    }

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun filterChipsAreCheckableAndReportSelection() {
        show()
        search("grace")
        val chip = rule.onNode(hasText("Worship") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
        chip.assertIsNotSelected()
        chip.performSemanticsAction(SemanticsActions.OnClick)
        settle()
        chip.assertIsSelected()
    }

    @Test @Config(qualifiers = "w884dp-h1104dp")
    fun tabletSelectedRowIsMarkedSelected() {
        show()
        search("grace")
        rule.onNodeWithTag("row-sg").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.onNodeWithTag("row-sg").assertIsSelected()
        rule.onNodeWithTag("row-mg").assertIsNotSelected()
    }

    // ---- design renders: always run (smoke), written to docs/design/app only when ORBIT_SCREENSHOTS=1 ----

    private fun shoot(name: String) {
        settle()
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

    private fun phoneSearch(name: String, dark: Boolean) {
        show(dark)
        search("grace")
        shoot(name)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers = "w412dp-h915dp-port-xhdpi")
    fun renderPhoneDark() = phoneSearch("search-phone-dark", true)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers = "w412dp-h915dp-port-xhdpi")
    fun renderPhoneLight() = phoneSearch("search-phone-light", false)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers = "w412dp-h915dp-port-xhdpi")
    fun renderPhoneExplore() { show(true); shoot("search-phone-explore-dark") }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers = "w412dp-h915dp-port-xhdpi")
    fun renderPhoneUnpaired() { fake.paired.value = false; show(true); shoot("search-phone-unpaired-dark") }

    private fun tablet(name: String, dark: Boolean) {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val d = Room.inMemoryDatabaseBuilder(ctx, AppDb::class.java).allowMainThreadQueries().build().also { db = it }
        val api = FakeService().apply { songs["ag"] = amazingGrace() }
        val songs = SongRepository(api, d, scope)
        val lib = LibraryRepository(d, songs, identity = { null }, session = { null })
        runBlocking { songs.open("ag"); songs.setPrefs(SongPrefs("ag", transpose = 2, capo = 0, scrollSpeed = 0.0, textScale = 1f)) }
        show(dark) { dest, close, _ ->
            val id = dest.songId
            val vm = viewModel(key = "song:$id") { SongViewModel(id, null, songs, lib) }
            SongScreen(vm, onBack = close)
        }
        search("grace")
        rule.onNodeWithTag("row-ag").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("sheet")).fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(5_000) { Thread.sleep(50); ChordShapes.isLoaded(DiagramInstrument.Guitar) }
        shoot(name)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers = "w884dp-h1104dp-port-hdpi")
    fun renderTabletDark() = tablet("search-tablet-dark", true)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers = "w884dp-h1104dp-port-hdpi")
    fun renderTabletLight() = tablet("search-tablet-light", false)

    // ---- find on the web ----

    private fun showWithAdd(dark: Boolean) {
        val vm = fake.vm()
        rule.setContent {
            OrbitTunerTheme(darkTheme = dark) {
                SearchScreen(vm, onOpenSong = { openedSong = it }, onOpenSettings = {}, detail = { id, _, _ -> Box(Modifier.fillMaxSize().testTag("detail-${id.songId}")) }, onAddSong = {})
            }
        }
        settle()
    }

    private fun webFake(): dev.avijit.orbittuner.add.FakeContributions {
        val web = dev.avijit.orbittuner.add.FakeContributions()
        web.polls += dev.avijit.orbittuner.add.FakeContributions.job(
            "running", dev.avijit.orbittuner.add.FakeContributions.item(1, "done", "w1"), dev.avijit.orbittuner.add.FakeContributions.item(2, "review", "w2"),
            dev.avijit.orbittuner.add.FakeContributions.item(3, "queued"),
        )
        fake.contributions = web
        fake.remote["zion"] = listOf(summary("z1", "Marching to Zion", "D", artist = "Isaac Watts"))
        fake.webSongs["w1"] = SongRow("w1", "Zion", "Hillsong United", "A", "en", listOf("Worship"))
        fake.webSongs["w2"] = SongRow("w2", "Come Ye That Love the Lord (Zion)", "Chris Tomlin", "G", "en", listOf("Worship"))
        val local = fake.local
        fake.local = { q -> if (q.contains("zion")) LocalResults(emptyList(), emptyList()) else local(q) }
        return web
    }

    // ---- Sing a line (A3) ----

    /** Hums until stopped. */
    private class Hum : dev.avijit.orbittuner.tuner.AudioSource {
        private val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        override fun reset() = stopped.set(false)
        override fun stop() = stopped.set(true)
        override fun start(onFrames: (FloatArray, Int) -> Unit) {
            val buf = FloatArray(1024) { (0.2 * kotlin.math.sin(it * 0.03)).toFloat() }
            while (!stopped.get()) { onFrames(buf, buf.size); Thread.sleep(2) }
        }
    }

    private fun awaitText(text: String) {
        val end = System.currentTimeMillis() + 10_000
        while (rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty()) {
            check(System.currentTimeMillis() < end) { "no \"$text\"" }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); Thread.sleep(10); rule.waitForIdle()
        }
    }

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun leavingTheAppMidRecordingReleasesTheMicAndSendsNothing() {
        val vm = fake.vm()
        var sent = 0
        val sing = dev.avijit.orbittuner.voice.SingLineViewModel(
            recorder = { dev.avijit.orbittuner.voice.VoiceRecorder(Hum(), Dispatchers.Default) },
            search = { sent++; dev.avijit.orbittuner.voice.VoiceOutcome.NoWords }, shiftFor = { null },
        )
        shadowOf(rule.activity.application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        rule.setContent {
            OrbitTunerTheme(darkTheme = true) { SearchScreen(vm, onOpenSong = {}, onOpenSettings = {}, detail = { _, _, _ -> }, singLine = sing) }
        }
        settle()
        rule.onNodeWithContentDescription("Sing a line").performClick()
        awaitText("Sing a line, or say the song name")
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED) // home button
        repeat(20) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); Thread.sleep(10) }
        assertEquals(dev.avijit.orbittuner.voice.SingState.Idle, sing.state.value)
        assertEquals(0, sent)
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        settle()
        assertTrue(rule.onAllNodes(hasText("Sing a line, or say the song name")).fetchSemanticsNodes().isEmpty())
    }

    private fun pumpUntil(cond: () -> Boolean) {
        repeat(100) {
            if (cond()) return
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); Thread.sleep(10); rule.waitForIdle()
        }
        assertTrue("condition not met", cond())
    }

    /** Final review: Undo/choose/dismiss uses the shift up; a fold or process death must not apply it again. */
    @Test @Config(qualifiers = "w884dp-h1104dp")
    fun aUsedUpSungShiftLeavesTheSavedRoute() {
        val vm = fake.vm()
        val hit = dev.avijit.orbittuner.voice.RankedHit(
            dev.avijit.orbittuner.voice.VoiceHit("ag", "Amazing Grace", "John Newton", "en", emptyList(), "A", "lyrics"), 0.0, null, library = false,
        )
        val reply = dev.avijit.orbittuner.voice.VoiceOutcome.Results("lyrics", "amazing grace", listOf(hit), null, emptyList(), singing = true)
        val sing = dev.avijit.orbittuner.voice.SingLineViewModel(
            recorder = { dev.avijit.orbittuner.voice.VoiceRecorder(Hum(), Dispatchers.Default) },
            search = { reply }, shiftFor = { dev.avijit.orbittuner.music.ShiftGuess(2, confident = true, alternative = null) },
        )
        shadowOf(rule.activity.application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val seen = ArrayList<dev.avijit.orbittuner.songview.SongDest>()
        var sungDone: (() -> Unit)? = null
        val tester = StateRestorationTester(rule)
        tester.setContent {
            OrbitTunerTheme(darkTheme = true) {
                SearchScreen(vm, onOpenSong = {}, onOpenSettings = {}, singLine = sing, detail = { d, _, done ->
                    seen += d; sungDone = done
                    Box(Modifier.fillMaxSize().testTag("detail-${d.songId}"))
                })
            }
        }
        settle()
        rule.onNodeWithContentDescription("Sing a line").performClick()
        awaitText("Sing a line, or say the song name")
        rule.runOnIdle { sing.stop() } // the route, not the sheet's buttons, is under test here
        pumpUntil { sing.state.value is dev.avijit.orbittuner.voice.SingState.Results }
        rule.runOnIdle { sing.open(hit) }
        pumpUntil { seen.isNotEmpty() }
        settle()
        assertEquals(dev.avijit.orbittuner.songview.SungShift(2), seen.last().shift)

        rule.runOnIdle { sungDone!!() } // e.g. Undo in the song view
        settle()
        assertEquals(dev.avijit.orbittuner.songview.SongDest("ag"), seen.last())

        seen.clear()
        tester.emulateSavedInstanceStateRestore() // rotation / process death
        settle()
        assertEquals(dev.avijit.orbittuner.songview.SongDest("ag"), seen.last())
        rule.onNodeWithTag("detail-ag").assertExists()
    }

    // ---- a new sung key for the song open beside the list (re-review) ----

    private var nextShift = dev.avijit.orbittuner.music.ShiftGuess(2, confident = true, alternative = null)
    private val agHit = dev.avijit.orbittuner.voice.RankedHit(
        dev.avijit.orbittuner.voice.VoiceHit("ag", "Amazing Grace", "John Newton", "en", emptyList(), "G", "lyrics"), 0.0, null, library = false,
    )
    private var songVm: SongViewModel? = null
    private val songVms = HashSet<SongViewModel>()
    private val dests = ArrayList<dev.avijit.orbittuner.songview.SongDest>()

    /** Two panes with a real song view model beside the list (saved +2 for "ag"), and Sing a line answering "ag". */
    private fun showWithSong(): Pair<SearchViewModel, dev.avijit.orbittuner.voice.SingLineViewModel> {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val d = Room.inMemoryDatabaseBuilder(ctx, AppDb::class.java).allowMainThreadQueries().build().also { db = it }
        val api = FakeService().apply { songs["ag"] = amazingGrace() }
        val songs = SongRepository(api, d, scope)
        val lib = LibraryRepository(d, songs, identity = { null }, session = { null })
        runBlocking { songs.open("ag"); songs.setPrefs(SongPrefs("ag", transpose = 2, capo = 0, scrollSpeed = 0.0, textScale = 1f)) }
        val vm = fake.vm()
        val reply = dev.avijit.orbittuner.voice.VoiceOutcome.Results("lyrics", "amazing grace", listOf(agHit), null, emptyList(), singing = true)
        val sing = dev.avijit.orbittuner.voice.SingLineViewModel(
            recorder = { dev.avijit.orbittuner.voice.VoiceRecorder(Hum(), Dispatchers.Default) },
            search = { reply }, shiftFor = { nextShift },
        )
        shadowOf(rule.activity.application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        rule.setContent {
            OrbitTunerTheme(darkTheme = true) {
                SearchScreen(vm, onOpenSong = {}, onOpenSettings = {}, singLine = sing, detail = { dest, _, done ->
                    dests += dest
                    dev.avijit.orbittuner.songview.SongRouteHost(dest, done, create = {
                        SongViewModel(dest.songId, dest.playlistId, songs, lib, sung = dest.shift)
                    }) { v -> songVm = v; songVms += v; Box(Modifier.fillMaxSize().testTag("detail-${dest.songId}")) }
                })
            }
        }
        settle()
        return vm to sing
    }

    private fun singAndOpen(sing: dev.avijit.orbittuner.voice.SingLineViewModel) {
        rule.onNodeWithContentDescription("Sing a line").performClick()
        awaitText("Sing a line, or say the song name")
        rule.runOnIdle { sing.stop() }
        pumpUntil { sing.state.value is dev.avijit.orbittuner.voice.SingState.Results }
        rule.runOnIdle { sing.open(agHit) }
        pumpUntil { dests.lastOrNull()?.shift != null }
        settle()
    }

    private fun songUi(): dev.avijit.orbittuner.songview.SongUi {
        pumpUntil { songVm?.state?.value is dev.avijit.orbittuner.songview.SongState.Ready }
        return (songVm!!.state.value as dev.avijit.orbittuner.songview.SongState.Ready).ui
    }

    /** Case A: the song is open beside the list; the player sings and picks that same song. */
    @Test @Config(qualifiers = "w884dp-h1104dp")
    fun aNewSungKeyReachesTheSongAlreadyOpenBesideTheList() {
        val (_, sing) = showWithSong()
        search("grace")
        rule.onNodeWithTag("row-ag").performSemanticsAction(SemanticsActions.OnClick)
        settle()
        assertEquals(2, songUi().transpose)
        val opened = songVm

        nextShift = dev.avijit.orbittuner.music.ShiftGuess(-3, confident = true, alternative = null)
        singAndOpen(sing)
        pumpUntil { songUi().transpose == -3 }
        assertTrue("same song view model", songVm === opened)
        assertEquals(dev.avijit.orbittuner.songview.SungBanner.Moved("E", capo = null), songUi().sungBanner)

        rule.runOnIdle { songVm!!.undoSungShift() }
        pumpUntil { dests.last().shift == null }
        assertEquals(2, songUi().transpose)
    }

    /** Case B: sing, open, Undo, then sing again in another key and pick the same song. */
    @Test @Config(qualifiers = "w884dp-h1104dp")
    fun singingAgainAfterUndoMovesTheOpenSongToTheNewKey() {
        val (_, sing) = showWithSong()
        singAndOpen(sing)
        pumpUntil { songUi().transpose == 2 && songUi().sungBanner == null } // sung +2 = saved +2: no move
        nextShift = dev.avijit.orbittuner.music.ShiftGuess(5, confident = true, alternative = null)
        singAndOpen(sing)
        pumpUntil { songUi().transpose == 5 }
        assertEquals(dev.avijit.orbittuner.songview.SungBanner.Moved("C", capo = null), songUi().sungBanner)
        rule.runOnIdle { songVm!!.undoSungShift() }
        pumpUntil { dests.last().shift == null }
        assertEquals(2, songUi().transpose)

        nextShift = dev.avijit.orbittuner.music.ShiftGuess(-3, confident = true, alternative = null)
        singAndOpen(sing)
        pumpUntil { songUi().transpose == -3 }
        assertEquals(dev.avijit.orbittuner.songview.SungBanner.Moved("E", capo = null), songUi().sungBanner)
        assertEquals("one song view model throughout", 1, songVms.size)
    }

    /** A recreated screen whose route lost its used-up shift opens the song as saved: nothing is applied again. */
    @Test @Config(qualifiers = "w884dp-h1104dp")
    fun aRecreatedScreenDoesNotReapplyAConsumedShift() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val d = Room.inMemoryDatabaseBuilder(ctx, AppDb::class.java).allowMainThreadQueries().build().also { db = it }
        val api = FakeService().apply { songs["ag"] = amazingGrace() }
        val songs = SongRepository(api, d, scope)
        val lib = LibraryRepository(d, songs, identity = { null }, session = { null })
        runBlocking { songs.open("ag"); songs.setPrefs(SongPrefs("ag", transpose = 2, capo = 0, scrollSpeed = 0.0, textScale = 1f)) }
        val vm = fake.vm()
        val reply = dev.avijit.orbittuner.voice.VoiceOutcome.Results("lyrics", "amazing grace", listOf(agHit), null, emptyList(), singing = true)
        nextShift = dev.avijit.orbittuner.music.ShiftGuess(-3, confident = true, alternative = null)
        val sing = dev.avijit.orbittuner.voice.SingLineViewModel(
            recorder = { dev.avijit.orbittuner.voice.VoiceRecorder(Hum(), Dispatchers.Default) },
            search = { reply }, shiftFor = { nextShift },
        )
        shadowOf(rule.activity.application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val tester = StateRestorationTester(rule)
        tester.setContent {
            OrbitTunerTheme(darkTheme = true) {
                SearchScreen(vm, onOpenSong = {}, onOpenSettings = {}, singLine = sing, detail = { dest, _, done ->
                    dests += dest
                    dev.avijit.orbittuner.songview.SongRouteHost(dest, done, create = {
                        SongViewModel(dest.songId, dest.playlistId, songs, lib, sung = dest.shift)
                    }) { v -> songVm = v; Box(Modifier.fillMaxSize().testTag("detail-${dest.songId}")) }
                })
            }
        }
        settle()
        singAndOpen(sing)
        pumpUntil { songUi().transpose == -3 }
        rule.runOnIdle { songVm!!.undoSungShift() }
        pumpUntil { dests.last().shift == null }
        val before = songVm

        tester.emulateSavedInstanceStateRestore()
        settle()
        assertEquals(null, dests.last().shift)
        pumpUntil { songVm !== before }
        val ui = songUi()
        assertEquals(2, ui.transpose)
        assertEquals(null, ui.sungBanner)
    }

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun micIsHiddenWithoutSingALine() {
        show()
        assertTrue(rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Sing a line"))).fetchSemanticsNodes().isEmpty())
    }

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun singALineWebRowOpensFindOnTheWebWithItsQuery() {
        val web = webFake()
        val vm = fake.vm()
        val reply = dev.avijit.orbittuner.voice.VoiceOutcome.Results(
            "lyrics", "hum saath chalenge har pal", emptyList(), "dil se gao", emptyList(), singing = true,
        )
        val sing = dev.avijit.orbittuner.voice.SingLineViewModel(
            recorder = { dev.avijit.orbittuner.voice.VoiceRecorder(Hum(), Dispatchers.Default) },
            search = { reply }, shiftFor = { null },
        )
        shadowOf(rule.activity.application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        rule.setContent {
            OrbitTunerTheme(darkTheme = true) {
                SearchScreen(vm, onOpenSong = {}, onOpenSettings = {}, detail = { _, _, _ -> }, singLine = sing)
            }
        }
        settle()
        rule.onNodeWithContentDescription("Sing a line").performClick()
        awaitText("Sing a line, or say the song name")
        rule.onNodeWithContentDescription("Stop").performClick()
        awaitText("Search the web for 'dil se gao'")
        settle()
        rule.onNodeWithText("Search the web for 'dil se gao'").performSemanticsAction(SemanticsActions.OnClick)
        settle()
        assertEquals(listOf("dil se gao" to null), web.discovers)
        assertEquals("dil se gao", vm.ui.value.query)
        rule.onNodeWithTag("search-field").assertTextContains("dil se gao")
        assertTrue(rule.onAllNodes(hasText("Is it one of these?")).fetchSemanticsNodes().isEmpty())
    }

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun findOnTheWebStreamsResultsIntoTheirOwnTier() {
        val web = webFake()
        show()
        search("zion")
        rule.onNodeWithTag("songs-list").performScrollToKey("web")
        rule.onNodeWithText("Find on the web").performClick()
        settle()
        assertEquals(listOf("zion" to null), web.discovers)
        rule.onNodeWithTag("songs-list").performScrollToKey("web-h")
        rule.onNodeWithText("From the web").assertIsDisplayed()
        rule.onNodeWithTag("songs-list").performScrollToKey("w-w2")
        rule.onNodeWithText("In review").assertIsDisplayed()
        rule.onNodeWithTag("row-w1").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("w1", openedSong)
    }

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun findOnTheWebLimitMessage() {
        val web = webFake()
        web.discover = { _, _ -> throw ApiError.Limit(429, "plan_limit_reached", 3, "free") }
        show()
        search("zion")
        rule.onNodeWithTag("songs-list").performScrollToKey("web")
        rule.onNodeWithText("Find on the web").performClick()
        settle()
        rule.onNodeWithTag("songs-list").performScrollToKey("web-fail")
        rule.onNodeWithText("You've used today's AI imports (Free plan: 3/day)").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun addSongEntry() {
        var added = 0
        val vm = fake.vm()
        rule.setContent {
            OrbitTunerTheme { SearchScreen(vm, onOpenSong = {}, onOpenSettings = {}, detail = { _, _, _ -> }, onAddSong = { added++ }) }
        }
        settle()
        rule.onNodeWithContentDescription("Add a song").performClick()
        assertEquals(1, added)
    }

    private fun webShot(name: String, dark: Boolean) {
        webFake()
        showWithAdd(dark)
        search("zion")
        rule.onNodeWithTag("songs-list").performScrollToKey("web")
        rule.onNodeWithText("Find on the web").performClick()
        settle()
        rule.onNodeWithTag("songs-list").performScrollToKey("l-h")
        shoot(name)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers = "w412dp-h915dp-port-xhdpi")
    fun renderWebPhoneDark() = webShot("search-web-phone-dark", true)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers = "w412dp-h915dp-port-xhdpi")
    fun renderWebPhoneLight() = webShot("search-web-phone-light", false)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers = "w884dp-h1104dp-port-hdpi")
    fun renderWebTabletDark() = webShot("search-web-tablet-dark", true)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers = "w884dp-h1104dp-port-hdpi")
    fun renderWebTabletLight() = webShot("search-web-tablet-light", false)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers = "w412dp-h915dp-port-xhdpi")
    fun renderWebOfferPhoneDark() {
        webFake()
        showWithAdd(true)
        search("zion")
        shoot("search-web-offer-phone-dark")
    }
}
