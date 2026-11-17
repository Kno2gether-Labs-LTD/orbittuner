package dev.avijit.orbittuner.voice

import dev.avijit.orbittuner.data.FakeService
import dev.avijit.orbittuner.music.Pitch
import dev.avijit.orbittuner.music.SungProfile
import dev.avijit.orbittuner.net.ApiError
import dev.avijit.orbittuner.net.VoiceGuess
import dev.avijit.orbittuner.net.VoiceReply
import dev.avijit.orbittuner.net.VoiceResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class VoiceSearchTest {
    private val api = FakeService()
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000L * day
    private var signals = HistorySignals()
    private val libraryQueries = ArrayList<String>()
    private var libraryHits: (String) -> List<VoiceHit> = { emptyList() }
    private val sheets = HashMap<String, String>()

    private val search = VoiceSearch(
        api = api,
        library = { q -> libraryQueries += q; libraryHits(q) },
        history = { signals },
        sheet = { id -> sheets[id] ?: throw ApiError.Offline },
        clock = { now },
    )

    // ---- clips ----

    private val hop = 0.02
    private fun hz(midi: Double) = Pitch.midiToHz(midi)

    /** "Twinkle Twinkle" sung in D, each note 0.45 s with light vibrato; [notes] limits its length. */
    private fun singing(notes: Int = 42): SungProfile {
        val d = 62; val e = 64; val fs = 66; val g = 67; val a = 69; val b = 71
        val line1 = listOf(d, d, a, a, b, b, a, g, g, fs, fs, e, e, d)
        val line2 = listOf(a, a, g, g, fs, fs, e, a, a, g, g, fs, fs, e)
        val p = SungProfile()
        var t = 0.0
        for (n in (line1 + line2 + line1).take(notes)) {
            var held = 0.0
            while (held < 0.45) { p.add(hz(n + 0.3 * sin(2 * Math.PI * 5.5 * (t + held))), hop); held += hop }
            p.add(0.0, hop)
            t += held + hop
        }
        return p
    }

    /** A gliding, speech-like contour: voiced but never held. */
    private fun speech(seconds: Double = 6.0): SungProfile {
        val p = SungProfile()
        var t = 0.0
        while (t < seconds) {
            p.add(hz(50.0 + 4.0 * sin(2 * Math.PI * 3.0 * t) + 2.0 * sin(2 * Math.PI * 6.9 * t)), hop)
            t += hop
        }
        return p
    }

    private fun rec(sung: SungProfile = speech(), seconds: Double = 5.0) = Recording(ByteArray(64), sung, seconds)

    private val twinkleInC = "{title: Twinkle}\n[C]Twinkle twinkle [F]little [C]star\n[G]how I [C]wonder [G]what you [C]are\n" +
        "[C]up a[G]bove the [C]world so [G]high\n[C]like a [G]diamond [C]in the [G]sky\n[C]Twinkle [F]twinkle [C]little [G]star [C]"

    private fun reply(intent: String = "lyrics", vararg ids: String, webQuery: String? = "twinkle twinkle little star") = VoiceReply(
        intent = intent, transcript = "twinkle twinkle little star", language = "en",
        guesses = listOf(VoiceGuess("Twinkle Twinkle", "Trad")), webQuery = webQuery,
        results = ids.map { VoiceResult(id = it, title = it.uppercase(), artist = "Trad", match = "lyrics") },
    )

    // ---- errors ----

    @Test fun clipUnderOneSecondIsNotSent() = runTest {
        assertEquals(VoiceOutcome.TooShort, search.run(rec(seconds = 0.9)))
        assertTrue(api.voiceCalls.isEmpty())
    }

    @Test fun badAudioFromServerIsTooShort() = runTest {
        api.voiceError = ApiError.Http(400, "bad_audio")
        assertEquals(VoiceOutcome.TooShort, search.run(rec()))
    }

    @Test fun intentNoneIsNoWords() = runTest {
        api.voiceReply = VoiceReply(intent = "none", transcript = "")
        assertEquals(VoiceOutcome.NoWords, search.run(rec()))
    }

    @Test fun limitReached() = runTest {
        api.voiceError = ApiError.Limit(429, "voice_limit_reached", 3, "anonymous")
        assertEquals(VoiceOutcome.LimitReached(3, "anonymous"), search.run(rec()))
    }

    @Test fun offline() = runTest {
        api.voiceError = ApiError.Offline
        assertEquals(VoiceOutcome.Offline, search.run(rec()))
    }

    @Test fun serviceDownIsUnavailable() = runTest {
        api.voiceError = ApiError.Http(503, "voice_unavailable")
        assertEquals(VoiceOutcome.Unavailable, search.run(rec()))
    }

    @Test fun aSlowServiceIsUnavailableNotOffline() = runTest {
        api.voiceError = ApiError.Http(504, "timeout") // Api.voice's read/call timeout
        assertEquals(VoiceOutcome.Unavailable, search.run(rec()))
    }

    @Test fun noAccount() = runTest {
        api.voiceError = ApiError.Unauthorized
        assertEquals(VoiceOutcome.NoAccount, search.run(rec()))
    }

    // ---- results ----

    @Test fun sendsHistoryHintsWithTheClip() = runTest {
        signals = HistorySignals(topLanguages = listOf("hi", "bn"), topArtists = listOf("Meera Sen"))
        api.voiceReply = reply("lyrics", "s1")
        search.run(rec())
        val call = api.voiceCalls.single()
        assertEquals(listOf("hi", "bn"), call.langs)
        assertEquals(listOf("Meera Sen"), call.artists)
    }

    @Test fun badHintsRetriesOnceWithoutHints() = runTest {
        signals = HistorySignals(topLanguages = listOf("hi"), topArtists = listOf("Meera Sen"))
        api.voiceErrorsOnce += ApiError.Http(400, "bad_hints")
        api.voiceReply = reply("lyrics", "s1")
        val out = search.run(rec()) as VoiceOutcome.Results
        assertEquals(listOf("s1"), out.hits.map { it.hit.id })
        assertEquals(2, api.voiceCalls.size)
        assertEquals(listOf("hi"), api.voiceCalls[0].langs)
        assertTrue(api.voiceCalls[1].langs.isEmpty() && api.voiceCalls[1].artists.isEmpty())
    }

    @Test fun badHintsTwiceIsUnavailableAfterOneRetry() = runTest {
        signals = HistorySignals(topLanguages = listOf("hi"))
        api.voiceError = ApiError.Http(400, "bad_hints")
        assertEquals(VoiceOutcome.Unavailable, search.run(rec()))
        assertEquals(2, api.voiceCalls.size)
    }

    @Test fun otherErrorsAreNotRetried() = runTest {
        api.voiceError = ApiError.Http(503, "voice_unavailable")
        search.run(rec())
        assertEquals(1, api.voiceCalls.size)
    }

    @Test fun libraryFirstThenServerRankedAndDeduped() = runTest {
        signals = HistorySignals(favourites = setOf("mine"), openedAt = mapOf("s2" to now - day))
        libraryHits = { listOf(VoiceHit("mine", "Mine", "Trad", "en", emptyList(), null, VoiceHit.LIBRARY)) }
        api.voiceReply = reply("lyrics", "s1", "s2", "mine")
        val out = search.run(rec()) as VoiceOutcome.Results
        assertEquals(listOf("mine", "s2", "s1"), out.hits.map { it.hit.id })
        assertEquals("In your favourites", out.hits[0].reason)
        assertEquals("You opened this recently", out.hits[1].reason)
        assertEquals("twinkle twinkle little star", out.webQuery)
        assertEquals("twinkle twinkle little star", out.transcript)
        assertTrue("transcript searched locally", "twinkle twinkle little star" in libraryQueries)
        assertTrue("guess searched locally", libraryQueries.any { it.contains("Twinkle Twinkle") })
    }

    @Test fun theRomanizedLineFindsLatinScriptLyricsInTheLibrary() = runTest {
        // A Bengali-script transcript can't match the library's Latin-script lyrics; the service's romanized form can.
        libraryHits = { q -> if (q == "ami tomake bhalobashi") listOf(VoiceHit("bn1", "Ami Tomake", "X", "bn", emptyList(), null, VoiceHit.LIBRARY)) else emptyList() }
        api.voiceReply = VoiceReply(intent = "lyrics", transcript = "আমি তোমাকে ভালোবাসি", romanized = "ami tomake bhalobashi", language = "bn")
        val out = search.run(rec()) as VoiceOutcome.Results
        assertEquals(listOf("bn1"), out.hits.map { it.hit.id })
        assertTrue("আমি তোমাকে ভালোবাসি" in libraryQueries)
    }

    @Test fun longLinesAreCappedSoOneMisheardWordDoesNotBlockTheLocalMatch() = runTest {
        val line = "hum saath chalenge har pal dil ki baat kahe sada"
        api.voiceReply = VoiceReply(intent = "lyrics", transcript = line, romanized = "$line tum", webQuery = null)
        search.run(rec())
        assertEquals(listOf("hum saath chalenge har pal dil"), libraryQueries.distinct())
        assertTrue(libraryQueries.all { it.split(' ').size <= MAX_LOCAL_TERMS })
    }

    @Test fun emptyResultsStillOfferTheWebQuery() = runTest {
        // LLM down: the server's webQuery is the raw transcript.
        api.voiceReply = reply("lyrics", webQuery = "twinkle twinkle little star").copy(guesses = emptyList())
        val out = search.run(rec()) as VoiceOutcome.Results
        assertTrue(out.hits.isEmpty())
        assertEquals("twinkle twinkle little star", out.webQuery)
    }

    @Test fun blankWebQueryIsNull() = runTest {
        api.voiceReply = reply("lyrics", "s1", webQuery = "  ")
        assertNull((search.run(rec()) as VoiceOutcome.Results).webQuery)
    }

    @Test fun localSearchFailureStillShowsServerResults() = runTest {
        libraryHits = { throw IllegalStateException("db") }
        api.voiceReply = reply("lyrics", "s1")
        assertEquals(listOf("s1"), (search.run(rec()) as VoiceOutcome.Results).hits.map { it.hit.id })
    }

    // ---- shift ----

    @Test fun spokenRequestNeverShifts() = runTest {
        sheets["s1"] = twinkleInC
        api.voiceReply = reply("request", "s1")
        val out = search.run(rec(speech())) as VoiceOutcome.Results
        assertFalse(out.singing)
        assertNull(search.shiftFor("s1"))
    }

    @Test fun threeSecondsOfSingingDoesNotAutoTranspose() = runTest {
        sheets["s1"] = twinkleInC
        api.voiceReply = reply("lyrics", "s1")
        val sung = singing(notes = 6) // 6 x 0.45 s = 2.7 s voiced
        assertTrue(sung.voicedSeconds < 4.0)
        search.run(rec(sung))
        val shift = search.shiftFor("s1")
        assertNull(shift?.autoTranspose)
        assertNull(shift)
    }

    @Test fun singingAboveTheSheetShiftsConfidently() = runTest {
        sheets["s1"] = twinkleInC
        api.voiceReply = reply("lyrics", "s1")
        val out = search.run(rec(singing())) as VoiceOutcome.Results
        assertTrue(out.singing)
        val shift = search.shiftFor("s1")!!
        assertEquals(2, shift.semitones)
        assertTrue(shift.confident)
        assertEquals(2, shift.autoTranspose)
    }

    @Test fun singingShiftsWhateverTheIntent() = runTest {
        sheets["s1"] = twinkleInC
        api.voiceReply = reply("request", "s1")
        search.run(rec(singing()))
        assertEquals(2, search.shiftFor("s1")?.autoTranspose)
    }

    @Test fun songWithoutChordsGivesNoShift() = runTest {
        sheets["s1"] = "{title: Words only}\njust some words here"
        api.voiceReply = reply("lyrics", "s1")
        search.run(rec(singing()))
        assertNull(search.shiftFor("s1"))
    }

    @Test fun unreachableSongGivesNoShift() = runTest {
        api.voiceReply = reply("lyrics", "s1")
        search.run(rec(singing()))
        assertNull(search.shiftFor("missing"))
    }

    @Test fun shiftForBeforeAnyRunIsNull() = runTest {
        sheets["s1"] = twinkleInC
        assertNull(search.shiftFor("s1"))
    }
}
