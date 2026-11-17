package dev.avijit.orbittuner.songview

import dev.avijit.orbittuner.data.FakeService
import dev.avijit.orbittuner.data.LibraryRepository
import dev.avijit.orbittuner.data.SongRepository
import dev.avijit.orbittuner.data.db.AppDb
import dev.avijit.orbittuner.data.db.SongPrefs
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.Executors
import dev.avijit.orbittuner.net.ItemSettings
import dev.avijit.orbittuner.net.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Amazing Grace from the shared service fixtures (key G, 80 bpm, 3/4). */
fun amazingGrace(id: String = "ag"): Song = Song(
    id = id, title = "Amazing Grace", artist = "John Newton", originalKey = "G", tempoBpm = 80, timeSig = "3/4",
    chordpro = SongViewModelTest::class.java.getResource("/chordpro/amazing-grace.cho")!!.readText(), updatedAt = 10,
)

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SongViewModelTest {
    private lateinit var db: AppDb
    private val api = FakeService()

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        // One real executor for reads and writes: FIFO order, and flow re-queries never run inside a writer's transaction.
        val io = Executors.newSingleThreadExecutor()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDb::class.java)
            .setQueryExecutor(io).setTransactionExecutor(io).build()
        api.songs["ag"] = amazingGrace()
    }

    @After fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun TestScope.repos(): Pair<SongRepository, LibraryRepository> {
        testScope = this
        val songs = SongRepository(api, db, backgroundScope)
        return songs to LibraryRepository(db, songs, identity = { null }, session = { null })
    }

    /** Lets pending VM work (state recombination, saves) run, then returns the shown song. */
    private suspend fun SongViewModel.ready(): SongUi {
        testScope.testScheduler.advanceUntilIdle()
        return state.first { it is SongState.Ready }.let { (it as SongState.Ready).ui }
    }
    private lateinit var testScope: TestScope

    private fun SongUi.firstChord(): String = doc.chords.first()

    @Test fun transposeThenCapo() = runTest {
        val (songs, lib) = repos()
        val vm = SongViewModel("ag", null, songs, lib)
        val start = vm.ready()
        assertEquals("G", start.soundingKey)
        assertEquals("G", start.firstChord())
        assertEquals("John Newton", start.artist)

        vm.transposeBy(2)
        var ui = vm.ready()
        assertEquals(2, ui.transpose)
        assertEquals("A", ui.soundingKey)
        assertEquals("A", ui.firstChord())

        vm.setCapo(2)
        ui = vm.ready()
        assertEquals(2, ui.capo)
        assertEquals("G", ui.firstChord())
        assertEquals("A", ui.soundingKey)
    }

    @Test fun reopeningRestoresSongPrefs() = runTest {
        val (songs, lib) = repos()
        val first = SongViewModel("ag", null, songs, lib)
        first.ready()
        first.transposeBy(2)
        first.setCapo(2)
        first.ready()

        val again = SongViewModel("ag", null, songs, lib).ready()
        assertEquals(2, again.transpose)
        assertEquals(2, again.capo)
        assertEquals("A", again.soundingKey)
        assertEquals("G", again.firstChord())
    }

    @Test fun playlistItemSettingsWinAndEditsSaveToTheItem() = runTest {
        val (songs, lib) = repos()
        SongViewModel("ag", null, songs, lib).apply { ready(); transposeBy(2); setCapo(2); ready() }
        val pid = lib.createPlaylist("Sunday")
        lib.addToPlaylist(pid, "ag")
        lib.setItemSettings(pid, "ag", ItemSettings(transpose = -1))

        val vm = SongViewModel("ag", pid, songs, lib)
        val ui = vm.ready()
        assertEquals(-1, ui.transpose)
        assertEquals("Gb", ui.soundingKey)

        vm.transposeBy(1)
        vm.setCapo(3)
        assertEquals(0, vm.ready().transpose)
        val item = lib.playlists().first { p -> p.single().songs.single().settings.capo == 3 }.single().songs.single()
        assertEquals(ItemSettings(transpose = 0, capo = 3), item.settings)
        // The song's own prefs are untouched by playlist edits.
        val prefs = songs.prefs("ag").first()!!
        assertEquals(2, prefs.transpose)
        assertEquals(2, prefs.capo)
    }

    @Test fun playlistWritesBackOnlyItemOrEditedFields() = runTest {
        val (songs, lib) = repos()
        songs.open("ag")
        songs.setPrefs(SongPrefs("ag", transpose = 2, capo = 0, scrollSpeed = 40.0, textScale = 1f))
        val pid = lib.createPlaylist("Sunday")
        lib.addToPlaylist(pid, "ag")
        lib.setItemSettings(pid, "ag", ItemSettings(transpose = -1))

        val vm = SongViewModel("ag", pid, songs, lib)
        vm.ready()
        vm.setCapo(2)
        vm.ready()
        val item = lib.playlists().first { p -> p.single().songs.single().settings.capo == 2 }.single().songs.single()
        assertEquals(ItemSettings(transpose = -1, capo = 2), item.settings)

        // Text size is a song pref only: the item stays as it was, the song keeps its own transpose/speed.
        vm.setTextScale(1.5f)
        vm.ready()
        assertEquals(ItemSettings(transpose = -1, capo = 2), lib.playlists().first().single().songs.single().settings)
        assertEquals(SongPrefs("ag", transpose = 2, capo = 0, scrollSpeed = 40.0, textScale = 1.5f), songs.prefs("ag").first())
    }

    @Test fun diagramInstrumentIsAGlobalPreference() = runTest {
        val (songs, lib) = repos()
        val prefs = InMemoryDiagramPrefs()
        val vm = SongViewModel("ag", null, songs, lib, diagramPrefs = prefs)
        vm.ready()
        vm.setInstrument(DiagramInstrument.Piano)
        assertEquals(DiagramInstrument.Piano, vm.ready().instrument)
        assertEquals(DiagramInstrument.Piano, SongViewModel("ag", null, songs, lib, diagramPrefs = prefs).ready().instrument)
    }

    @Test fun unexpectedLoadErrorIsNotOffline() = runTest {
        val (_, lib) = repos()
        val broken = object : dev.avijit.orbittuner.data.ServiceApi by api {
            override suspend fun song(id: String): Song = throw IllegalStateException("corrupt response")
        }
        val vm = SongViewModel("missing", null, SongRepository(broken, db, backgroundScope), lib)
        testScope.testScheduler.advanceUntilIdle()
        assertEquals(SongState.Failed(offline = false), vm.state.first { it !is SongState.Loading })
    }

    @Test fun playStateAndFavourite() = runTest {
        val (songs, lib) = repos()
        val vm = SongViewModel("ag", null, songs, lib)
        assertFalse(vm.ready().scroll.playing)
        vm.setPlaying(true)
        assertTrue(vm.ready().scroll.playing)
        vm.toggleFavourite()
        vm.ready()
        assertTrue(vm.state.first { (it as? SongState.Ready)?.ui?.isFavourite == true } is SongState.Ready)
    }

    @Test fun exportCarriesSoundingKeyAndCapo() = runTest {
        val (songs, lib) = repos()
        val vm = SongViewModel("ag", null, songs, lib)
        vm.ready()
        vm.transposeBy(2)
        vm.setCapo(2)
        val text = vm.exportText()
        assertTrue(text, text.contains("{key: A}"))
        assertTrue(text, text.contains("{capo: 2}"))
        assertTrue(text, text.contains("[G]Amazing"))
        assertEquals("amazing-grace.cho", vm.exportFileName())
    }

    @Test fun flagSendsTheReasonAndThanks() = runTest {
        val (songs, lib) = repos()
        val sent = ArrayList<Triple<String, dev.avijit.orbittuner.net.FlagReason, String?>>()
        var fail: Exception? = null
        val vm = SongViewModel("ag", null, songs, lib, flagger = { id, reason, note -> fail?.let { throw it }; sent += Triple(id, reason, note) })
        vm.ready()
        vm.flag(dev.avijit.orbittuner.net.FlagReason.WrongChords, "  bridge chords  ")
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(Triple("ag", dev.avijit.orbittuner.net.FlagReason.WrongChords, "bridge chords")), sent)
        assertEquals("Thanks, we'll take a look", vm.messages.first())

        vm.flag(dev.avijit.orbittuner.net.FlagReason.Other, " ")
        testScheduler.advanceUntilIdle()
        assertEquals(null, sent.last().third)
        vm.messages.first()

        fail = dev.avijit.orbittuner.net.ApiError.Http(400, "own_song")
        vm.flag(dev.avijit.orbittuner.net.FlagReason.NotASong, null)
        testScheduler.advanceUntilIdle()
        assertEquals("You added this song, so it can't be flagged", vm.messages.first())

        fail = dev.avijit.orbittuner.net.ApiError.Http(429, "flag_rate_limited")
        vm.flag(dev.avijit.orbittuner.net.FlagReason.NotASong, null)
        testScheduler.advanceUntilIdle()
        assertEquals("You've sent a lot of flags today. Try again tomorrow.", vm.messages.first())

        fail = dev.avijit.orbittuner.net.ApiError.Offline
        vm.flag(dev.avijit.orbittuner.net.FlagReason.NotASong, null)
        testScheduler.advanceUntilIdle()
        assertEquals("Couldn't send the flag. Check your connection.", vm.messages.first())
    }

    // ---- Sing a line (A3) ----

    @Test fun songDestCarriesTheSungShift() {
        val d = SongDest("ag", shift = SungShift(2))
        assertEquals("song/ag?shift=2", d.route())
        assertEquals(d, SongDest.parse(d.route()))
        val close = SongDest("ag", shift = SungShift(2, alternative = -3))
        assertEquals(close, SongDest.parse(close.route()))
        val both = SongDest("ag", playlistId = "p1", shift = SungShift(-4))
        assertEquals(both, SongDest.parse(both.route()))
        assertEquals(SongDest("ag", "p1"), SongDest.parse("song/ag?playlist=p1"))
        // A mangled shift is ignored rather than failing the route.
        assertEquals(SongDest("ag"), SongDest.parse("song/ag?shift=x"))
    }

    @Test fun sungShiftOpensMovedAndUndoGoesBack() = runTest {
        val (songs, lib) = repos()
        val vm = SongViewModel("ag", null, songs, lib, sung = SungShift(2))
        var ui = vm.ready()
        assertEquals(2, ui.transpose)
        assertEquals("A", ui.soundingKey)
        assertEquals(SungBanner.Moved("A", capo = null), ui.sungBanner)

        vm.undoSungShift()
        ui = vm.ready()
        assertEquals(0, ui.transpose)
        assertEquals("G", ui.soundingKey)
        assertEquals(null, ui.sungBanner)
    }

    @Test fun aSungShiftIsNotSavedUntilThePlayerEditsSomething() = runTest {
        val (songs, lib) = repos()
        SongViewModel("ag", null, songs, lib, sung = SungShift(2)).ready()
        assertEquals(0, SongViewModel("ag", null, songs, lib).ready().transpose)
    }

    @Test fun movingTheKeyByHandHidesTheBanner() = runTest {
        val (songs, lib) = repos()
        val vm = SongViewModel("ag", null, songs, lib, sung = SungShift(2))
        vm.ready()
        vm.transposeBy(1)
        assertEquals(null, vm.ready().sungBanner)
    }

    @Test fun closeCallOffersBothKeysAndMovesOnlyWhenPicked() = runTest {
        val (songs, lib) = repos()
        val vm = SongViewModel("ag", null, songs, lib, sung = SungShift(2, alternative = -3))
        var ui = vm.ready()
        assertEquals(0, ui.transpose)
        assertEquals(SungBanner.CloseCall(SungBanner.Choice(2, "A"), SungBanner.Choice(-3, "E")), ui.sungBanner)

        vm.chooseSungShift(-3)
        ui = vm.ready()
        assertEquals(-3, ui.transpose)
        assertEquals(SungBanner.Moved("E", capo = null), ui.sungBanner)

        vm.undoSungShift()
        assertEquals(0, vm.ready().transpose)
    }

    @Test fun anAwkwardSungKeySuggestsACapo() = runTest {
        val (songs, lib) = repos()
        // G down 4 = Eb: capo 1 and play D shapes.
        val vm = SongViewModel("ag", null, songs, lib, sung = SungShift(-4))
        var ui = vm.ready()
        assertEquals(SungBanner.Moved("Eb", capo = dev.avijit.orbittuner.music.CapoHint(1, "D")), ui.sungBanner)

        vm.setCapo(1)
        ui = vm.ready()
        assertEquals("D", ui.firstChord())
        // Taken: the hint goes, the move stays.
        assertEquals(SungBanner.Moved("Eb", capo = null), ui.sungBanner)
    }

    @Test fun songsWithoutAKeyShowTheGuessedKey() = runTest {
        api.songs["nk"] = Song(id = "nk", title = "No key", chordpro = "[G]One [C]two [D]three [G]four", updatedAt = 1)
        val (songs, lib) = repos()
        val vm = SongViewModel("nk", null, songs, lib)
        var ui = vm.ready()
        assertEquals(null, ui.soundingKey)
        assertEquals("G", ui.inferredKey)
        vm.transposeBy(2)
        ui = vm.ready()
        assertEquals("A", ui.inferredKey)
        // Export never writes a guessed key.
        assertFalse(vm.exportText().contains("{key"))
    }

    @Test fun aSungShiftOnAKeylessSongNamesTheGuessedKey() = runTest {
        api.songs["nk"] = Song(id = "nk", title = "No key", chordpro = "[G]One [C]two [D]three [G]four", updatedAt = 1)
        val (songs, lib) = repos()
        val ui = SongViewModel("nk", null, songs, lib, sung = SungShift(2)).ready()
        assertEquals(SungBanner.Moved("A", capo = null), ui.sungBanner)
        assertEquals("A", ui.firstChord())
    }

    // ---- the saved key stays honest (fix rounds 1-2) ----

    @Test fun aCapoTakenForTheSungKeyKeepsThatKeyAndUndoRestoresBoth() = runTest {
        val (songs, lib) = repos()
        val vm = SongViewModel("ag", null, songs, lib, sung = SungShift(-4))
        vm.ready()
        vm.setCapo(1) // the banner's "Use capo 1": Eb with D shapes, accepted together
        vm.ready()
        var again = SongViewModel("ag", null, songs, lib).ready()
        assertEquals(-4, again.transpose)
        assertEquals(1, again.capo)
        assertEquals("Eb", again.soundingKey)

        vm.undoSungShift()
        val ui = vm.ready()
        assertEquals(0, ui.transpose)
        assertEquals(0, ui.capo)
        again = SongViewModel("ag", null, songs, lib).ready()
        assertEquals(0, again.transpose)
        assertEquals(0, again.capo)
        assertEquals("G", again.soundingKey)
    }

    @Test fun undoRestoresTheSavedKeyAndCapo() = runTest {
        val (songs, lib) = repos()
        SongViewModel("ag", null, songs, lib).apply { ready(); transposeBy(3); setCapo(2); ready() }
        val vm = SongViewModel("ag", null, songs, lib, sung = SungShift(-4))
        assertEquals(-4, vm.ready().transpose)
        vm.setTextScale(1.2f) // not a key decision: the saved key stays +3
        vm.ready()
        assertEquals(3, SongViewModel("ag", null, songs, lib).ready().transpose)
        vm.setCapo(1) // accepts Eb
        vm.ready()
        vm.undoSungShift()
        var ui = vm.ready()
        assertEquals(3, ui.transpose)
        assertEquals(2, ui.capo)
        ui = SongViewModel("ag", null, songs, lib).ready()
        assertEquals(3, ui.transpose)
        assertEquals(2, ui.capo)
    }

    @Test fun theSungKeyIsNotSavedByOtherEdits() = runTest {
        val (songs, lib) = repos()
        val vm = SongViewModel("ag", null, songs, lib, sung = SungShift(-4))
        vm.ready()
        vm.setTextScale(1.2f)
        vm.setSpeed(30f)
        vm.ready()
        assertEquals(0, SongViewModel("ag", null, songs, lib).ready().transpose)

        // A close call picked, then a text-size save: still not saved.
        val close = SongViewModel("ag", null, songs, lib, sung = SungShift(2, alternative = -3))
        close.ready()
        close.chooseSungShift(2)
        close.setTextScale(1.1f)
        close.ready()
        assertEquals(0, SongViewModel("ag", null, songs, lib).ready().transpose)

        // Moving the key by hand makes it the player's own: saved as usual.
        val own = SongViewModel("ag", null, songs, lib, sung = SungShift(2))
        own.ready()
        own.transposeBy(1)
        own.ready()
        assertEquals(3, SongViewModel("ag", null, songs, lib).ready().transpose)
    }

    // ---- a sung 0 over a saved transpose (final review) ----

    @Test fun aSungZeroOverASavedTransposeMovesToTheOwnKeyWithUndo() = runTest {
        val (songs, lib) = repos()
        SongViewModel("ag", null, songs, lib).apply { ready(); transposeBy(2); ready() }
        val vm = SongViewModel("ag", null, songs, lib, sung = SungShift(0))
        var ui = vm.ready()
        assertEquals(0, ui.transpose)
        assertEquals("G", ui.soundingKey)
        assertEquals(SungBanner.Moved("G", capo = null), ui.sungBanner)
        assertEquals(2, SongViewModel("ag", null, songs, lib).ready().transpose) // the move is not saved

        vm.undoSungShift()
        ui = vm.ready()
        assertEquals(2, ui.transpose)
        assertEquals(null, ui.sungBanner)
        assertEquals(2, SongViewModel("ag", null, songs, lib).ready().transpose)
    }

    @Test fun aSungShiftEqualToTheSavedKeyIsNoMove() = runTest {
        val (songs, lib) = repos()
        SongViewModel("ag", null, songs, lib).apply { ready(); transposeBy(2); ready() }
        val vm = SongViewModel("ag", null, songs, lib, sung = SungShift(2))
        val ui = vm.ready()
        assertEquals(2, ui.transpose)
        assertEquals(null, ui.sungBanner)
        assertTrue(vm.sungConsumed.value)
        // And a plain sung 0 on a song never transposed: nothing to say either.
        api.songs["nk"] = Song(id = "nk", title = "No key", chordpro = "[G]One [C]two [D]three [G]four", updatedAt = 1)
        val plain = SongViewModel("nk", null, songs, lib, sung = SungShift(0))
        plain.ready()
        assertTrue(plain.sungConsumed.value)
    }

    @Test fun pickingTheSongsOwnKeyInACloseCallMovesThereWithUndo() = runTest {
        val (songs, lib) = repos()
        SongViewModel("ag", null, songs, lib).apply { ready(); transposeBy(3); ready() }
        val vm = SongViewModel("ag", null, songs, lib, sung = SungShift(2, alternative = 0))
        assertEquals(3, vm.ready().transpose)
        vm.chooseSungShift(0) // "G": the song's own key, same path as any other pick
        var ui = vm.ready()
        assertEquals(0, ui.transpose)
        assertEquals(SungBanner.Moved("G", capo = null), ui.sungBanner)
        assertEquals(3, SongViewModel("ag", null, songs, lib).ready().transpose) // not saved by the pick

        vm.undoSungShift()
        ui = vm.ready()
        assertEquals(3, ui.transpose)
        assertEquals(3, SongViewModel("ag", null, songs, lib).ready().transpose)
    }

    @Test fun aCloseCallZeroAcceptedWithACapoSavesBothAndUndoRestoresBoth() = runTest {
        val (songs, lib) = repos()
        SongViewModel("ag", null, songs, lib).apply { ready(); transposeBy(3); setCapo(2); ready() }
        val vm = SongViewModel("ag", null, songs, lib, sung = SungShift(2, alternative = 0))
        vm.ready()
        vm.chooseSungShift(0)
        vm.setCapo(4) // a capo while the sung key shows accepts it
        vm.ready()
        var again = SongViewModel("ag", null, songs, lib).ready()
        assertEquals(0, again.transpose)
        assertEquals(4, again.capo)
        vm.undoSungShift()
        vm.ready()
        again = SongViewModel("ag", null, songs, lib).ready()
        assertEquals(3, again.transpose)
        assertEquals(2, again.capo)
    }

    @Test fun pickingTheKeyTheSongAlreadySitsInJustClosesTheChooser() = runTest {
        val (songs, lib) = repos()
        val vm = SongViewModel("ag", null, songs, lib, sung = SungShift(2, alternative = 0))
        vm.ready()
        vm.chooseSungShift(0) // saved 0 = own key: nothing to move
        val ui = vm.ready()
        assertEquals(0, ui.transpose)
        assertEquals(null, ui.sungBanner)
        assertTrue(vm.sungConsumed.value)
    }

    // ---- a used-up shift (final review) ----

    @Test fun theShiftIsConsumedOnceUndoneChosenOrDismissed() = runTest {
        val (songs, lib) = repos()
        val moved = SongViewModel("ag", null, songs, lib, sung = SungShift(2))
        moved.ready()
        assertFalse("pending while the banner shows", moved.sungConsumed.value)
        moved.undoSungShift()
        assertTrue(moved.sungConsumed.value)

        val chosen = SongViewModel("ag", null, songs, lib, sung = SungShift(2, alternative = -3))
        chosen.ready()
        assertFalse(chosen.sungConsumed.value)
        chosen.chooseSungShift(-3)
        assertTrue(chosen.sungConsumed.value)

        val dismissed = SongViewModel("ag", null, songs, lib, sung = SungShift(2, alternative = -3))
        dismissed.ready()
        dismissed.dismissSungShift()
        assertTrue(dismissed.sungConsumed.value)

        val byHand = SongViewModel("ag", null, songs, lib, sung = SungShift(2))
        byHand.ready()
        byHand.transposeBy(1)
        assertTrue(byHand.sungConsumed.value)
    }

    // ---- a new sung key for the open song (re-review) ----

    @Test fun aNewSungShiftReachesASongOpenedPlain() = runTest {
        val (songs, lib) = repos()
        SongViewModel("ag", null, songs, lib).apply { ready(); transposeBy(2); ready() }
        val vm = SongViewModel("ag", null, songs, lib)
        assertEquals(2, vm.ready().transpose)
        vm.applySung(SungShift(-3))
        var ui = vm.ready()
        assertEquals(-3, ui.transpose)
        assertEquals(SungBanner.Moved("E", capo = null), ui.sungBanner)
        assertFalse(vm.sungConsumed.value)
        assertEquals(2, SongViewModel("ag", null, songs, lib).ready().transpose) // not saved
        vm.undoSungShift()
        ui = vm.ready()
        assertEquals(2, ui.transpose)
        assertTrue(vm.sungConsumed.value)
    }

    @Test fun singingAgainAfterUndoMovesToTheNewKey() = runTest {
        val (songs, lib) = repos()
        val vm = SongViewModel("ag", null, songs, lib, sung = SungShift(2))
        assertEquals(2, vm.ready().transpose)
        vm.undoSungShift()
        assertEquals(0, vm.ready().transpose)
        vm.applySung(SungShift(-3))
        val ui = vm.ready()
        assertEquals(-3, ui.transpose)
        assertEquals(SungBanner.Moved("E", capo = null), ui.sungBanner)
        vm.undoSungShift()
        assertEquals(0, vm.ready().transpose)
    }

    @Test fun aNewShiftWhileASungKeyIsPendingIsPlacedAgainstTheSavedKey() = runTest {
        val (songs, lib) = repos()
        val vm = SongViewModel("ag", null, songs, lib, sung = SungShift(2))
        vm.ready()
        vm.applySung(SungShift(2)) // the one it opened with: no-op
        assertEquals(SungBanner.Moved("A", capo = null), vm.ready().sungBanner)
        vm.applySung(SungShift(-3))
        assertEquals(-3, vm.ready().transpose)
        vm.undoSungShift()
        assertEquals(0, vm.ready().transpose) // the saved key, not the earlier sung +2
        vm.applySung(SungShift(2, alternative = -3)) // a close call moves nothing
        val ui = vm.ready()
        assertEquals(0, ui.transpose)
        assertEquals(SungBanner.CloseCall(SungBanner.Choice(2, "A"), SungBanner.Choice(-3, "E")), ui.sungBanner)
    }

    @Test fun withoutShiftDropsOnlyTheShift() {
        val d = SongDest("ag", playlistId = "p1", shift = SungShift(2, alternative = -3))
        assertEquals("song/ag?playlist=p1", d.withoutShift().route())
        assertEquals(SongDest("ag"), SongDest("ag").withoutShift())
    }
}
