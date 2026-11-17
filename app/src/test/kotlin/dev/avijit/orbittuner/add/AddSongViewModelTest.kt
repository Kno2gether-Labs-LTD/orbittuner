package dev.avijit.orbittuner.add

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.avijit.orbittuner.chordpro.ChordPro
import dev.avijit.orbittuner.net.ApiError
import dev.avijit.orbittuner.net.GoogleLink
import dev.avijit.orbittuner.net.Me
import dev.avijit.orbittuner.net.NewSong
import dev.avijit.orbittuner.net.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddSongViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val fake = FakeContributions()
    private val me = MutableStateFlow<Me?>(Me("u1", "user", plan = "free", aiLeftToday = 2, aiDailyLimit = 3))
    private var aiRefreshes = 0

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = AddSongViewModel(fake, me, onAiUsed = { aiRefreshes++ })

    @Test fun pasteSubmitsTextWithAutoAiAndShowsPublished() = runTest(dispatcher) {
        val vm = vm()
        vm.submitPaste("G     C\nAmazing grace", title = " Amazing Grace ", artist = "  ")
        advanceUntilIdle()
        assertEquals(listOf<NewSong>(NewSong.Text("G     C\nAmazing grace", "Amazing Grace", null, ai = "auto")), fake.created)
        val r = vm.result.value as AddResult.Published
        assertEquals("n1", r.song.id)
        assertEquals(1, aiRefreshes)
    }

    @Test fun linkSubmitsUrlAndShowsInReview() = runTest(dispatcher) {
        fake.create = { Song(id = "r1", title = "Saving Grace", chordpro = "", status = "review") }
        val vm = vm()
        vm.submitLink("  chords.example/saving-grace ")
        advanceUntilIdle()
        assertEquals(listOf<NewSong>(NewSong.Link("https://chords.example/saving-grace")), fake.created)
        assertEquals("r1", (vm.result.value as AddResult.InReview).song.id)
        assertEquals(1, aiRefreshes)
    }

    @Test fun writeSubmitsChordProWithoutAi() = runTest(dispatcher) {
        val vm = vm()
        vm.submitWrite("Amazing Grace", "John Newton", "G", "[G]Amazing [C]grace\n")
        advanceUntilIdle()
        val sent = fake.created.single() as NewSong.ChordPro
        assertEquals("{title: Amazing Grace}\n{artist: John Newton}\n{key: G}\n\n[G]Amazing [C]grace\n", sent.chordpro)
        assertTrue(vm.result.value is AddResult.Published)
        assertEquals(0, aiRefreshes)
    }

    @Test fun duplicateOffersTheExistingSong() = runTest(dispatcher) {
        fake.create = { throw ApiError.Duplicate("s9") }
        val vm = vm()
        vm.submitWrite("Amazing Grace", null, null, "[G]Amazing grace")
        advanceUntilIdle()
        assertEquals(AddResult.Duplicate("s9"), vm.result.value)
    }

    @Test fun planLimitShowsThePlanMessageAndSignInHint() = runTest(dispatcher) {
        fake.create = { throw ApiError.Limit(429, "plan_limit_reached", 3, "free") }
        val vm = vm()
        vm.submitLink("https://chords.example/x")
        advanceUntilIdle()
        val f = vm.result.value as AddResult.Failed
        assertEquals("You've used today's AI imports (Free plan: 3/day)", f.message)
        assertTrue(f.signInHint)

        // A Google-linked account gets no sign-in hint.
        me.value = me.value!!.copy(google = GoogleLink("a@example.com"))
        vm.submitLink("https://chords.example/x")
        advanceUntilIdle()
        assertFalse((vm.result.value as AddResult.Failed).signInHint)
    }

    @Test fun validationRefusesBeforeCallingTheService() = runTest(dispatcher) {
        val vm = vm()
        vm.submitWrite("  ", null, null, "[G]la")
        advanceUntilIdle()
        assertEquals("Give the song a title", (vm.result.value as AddResult.Failed).message)
        vm.submitPaste("   ", null, null)
        advanceUntilIdle()
        assertEquals("Paste the chords and lyrics first", (vm.result.value as AddResult.Failed).message)
        vm.submitLink("not a link")
        advanceUntilIdle()
        assertEquals("That doesn't look like a web link", (vm.result.value as AddResult.Failed).message)
        assertTrue(fake.created.isEmpty())
    }

    @Test fun allowanceComesFromMe() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        assertEquals(AiAllowance(left = 2, limit = 3, plan = "free", google = false), vm.allowance.value)
        me.value = null
        advanceUntilIdle()
        assertNull(vm.allowance.value)
    }

    @Test fun errorCopyCoversServiceErrors() {
        assertEquals("You've used today's web searches (Plus plan: 20/day)", contributionErrorMessage(ApiError.Limit(429, "search_limit_reached", 20, "plus")))
        assertEquals("You're offline. Connect and try again.", contributionErrorMessage(ApiError.Offline))
        assertTrue(contributionErrorMessage(ApiError.Http(422, "no_chords")).contains("chords"))
        assertTrue(contributionErrorMessage(ApiError.Http(502, "fetch_failed")).contains("open that page"))
        assertTrue(contributionErrorMessage(ApiError.Http(429, "rate_limited")).contains("minute"))
        assertTrue(contributionErrorMessage(ApiError.Http(503, "search_unavailable")).contains("isn't available"))
    }

    @Test fun writeChordProEscapesMetaAndSkipsBlanks() {
        assertEquals("{title: A B}\n\nla\n", writeChordPro("A{\nB}", " ", "", "la"))
        val doc = ChordPro.parse(writeChordPro("T", "Me", "D", "[D]one [G]two"))
        assertEquals("T", doc.meta["title"]); assertEquals("D", doc.meta["key"]); assertEquals(listOf("D", "G"), doc.chords)
    }

    @Test fun paletteFollowsTheKey() {
        assertEquals(listOf("G", "Am", "Bm", "C", "D", "Em", "D7"), chordPalette("G"))
        assertEquals(listOf("F", "Gm", "Am", "Bb", "C", "Dm", "C7"), chordPalette("F"))
        assertEquals(listOf("Am", "C", "Dm", "Em", "E7", "F", "G"), chordPalette("Am"))
        assertEquals(chordPalette("C"), chordPalette("nonsense"))
    }

    @Test fun insertChordAtCursorReplacesSelection() {
        val v = insertChord(TextFieldValue("Amazing grace", TextRange(8)), "C")
        assertEquals("Amazing [C]grace", v.text); assertEquals(TextRange(11), v.selection)
        val w = insertChord(TextFieldValue("ab", TextRange(0, 2)), "G")
        assertEquals("[G]", w.text); assertEquals(TextRange(3), w.selection)
    }
}
