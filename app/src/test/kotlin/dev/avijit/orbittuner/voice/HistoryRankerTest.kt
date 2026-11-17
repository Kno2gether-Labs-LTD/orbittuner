package dev.avijit.orbittuner.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryRankerTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000L * day

    private fun hit(id: String, artist: String? = null, lang: String = "xx", match: String = "lyrics") =
        VoiceHit(id = id, title = id.uppercase(), artist = artist, language = lang, categories = emptyList(), originalKey = null, match = match)

    private fun score(h: VoiceHit, s: HistorySignals) = HistoryRanker.rank(listOf(h), s, now).single()

    @Test fun noHistoryScoresZeroAndHasNoReason() {
        val r = score(hit("a"), HistorySignals())
        assertEquals(0.0, r.score, 1e-9)
        assertNull(r.reason)
        assertEquals(false, r.library)
    }

    @Test fun eachBoostIsExact() {
        assertEquals(3.0, score(hit("a"), HistorySignals(favourites = setOf("a"))).score, 1e-9)
        assertEquals(2.0, score(hit("a"), HistorySignals(playlists = setOf("a"))).score, 1e-9)
        // Opened 10 days ago: +1.5; 2 days ago: +1.5 +0.5; 40 days ago: nothing.
        assertEquals(1.5, score(hit("a"), HistorySignals(openedAt = mapOf("a" to now - 10 * day))).score, 1e-9)
        assertEquals(2.0, score(hit("a"), HistorySignals(openedAt = mapOf("a" to now - 2 * day))).score, 1e-9)
        assertEquals(0.0, score(hit("a"), HistorySignals(openedAt = mapOf("a" to now - 40 * day))).score, 1e-9)
        assertEquals(1.0, score(hit("a", artist = "Meera Sen"), HistorySignals(topArtists = listOf("meera sen "))).score, 1e-9)
        assertEquals(0.5, score(hit("a", lang = "hi"), HistorySignals(topLanguages = listOf("hi"))).score, 1e-9)
    }

    @Test fun boostsAdd() {
        val s = HistorySignals(
            favourites = setOf("a"), playlists = setOf("a"), openedAt = mapOf("a" to now - day),
            topArtists = listOf("X"), topLanguages = listOf("hi"),
        )
        assertEquals(3.0 + 2.0 + 1.5 + 0.5 + 1.0 + 0.5, score(hit("a", "X", "hi"), s).score, 1e-9)
    }

    @Test fun reasons() {
        assertEquals("In your favourites", score(hit("a"), HistorySignals(favourites = setOf("a"), playlists = setOf("a"))).reason)
        assertEquals("In your playlists", score(hit("a"), HistorySignals(playlists = setOf("a"))).reason)
        assertEquals("You opened this recently", score(hit("a"), HistorySignals(openedAt = mapOf("a" to now - day))).reason)
        assertEquals("You often play X", score(hit("a", "X"), HistorySignals(topArtists = listOf("X"))).reason)
    }

    @Test fun libraryMatchesAlwaysListFirst() {
        // b is recent + top artist + top language (3.5) but not in the library; a is only in a playlist (2.0).
        val s = HistorySignals(
            playlists = setOf("a"), openedAt = mapOf("b" to now - day), topArtists = listOf("X"), topLanguages = listOf("hi"),
        )
        val out = HistoryRanker.rank(listOf(hit("b", "X", "hi"), hit("a")), s, now)
        assertEquals(listOf("a", "b"), out.map { it.hit.id })
        assertTrue(out[0].library)
        assertEquals(false, out[1].library)
    }

    @Test fun localLibraryHitsAreLibraryEvenWithoutSignals() {
        val out = HistoryRanker.rank(listOf(hit("b"), hit("a", match = VoiceHit.LIBRARY)), HistorySignals(), now)
        assertEquals(listOf("a", "b"), out.map { it.hit.id })
    }

    @Test fun higherScoreWinsWithinEachGroup() {
        val s = HistorySignals(openedAt = mapOf("c" to now - day))
        assertEquals(listOf("c", "a", "b"), HistoryRanker.rank(listOf(hit("a"), hit("b"), hit("c")), s, now).map { it.hit.id })
    }

    @Test fun tiesKeepServerOrder() {
        val ids = listOf("e", "b", "d", "a", "c")
        val s = HistorySignals(topLanguages = listOf("hi"))
        val out = HistoryRanker.rank(ids.map { hit(it, lang = "hi") }, s, now)
        assertEquals(ids, out.map { it.hit.id })
    }
}
