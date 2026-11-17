package dev.avijit.orbittuner.voice

import dev.avijit.orbittuner.chordpro.ChordPro
import dev.avijit.orbittuner.chordpro.Line
import dev.avijit.orbittuner.data.ServiceApi
import dev.avijit.orbittuner.music.KeyFinder
import dev.avijit.orbittuner.music.ShiftGuess
import dev.avijit.orbittuner.music.SungProfile
import dev.avijit.orbittuner.net.ApiError
import dev.avijit.orbittuner.net.VoiceGuess
import kotlinx.coroutines.CancellationException

/** What a Sing a line search came to. */
sealed interface VoiceOutcome {
    /**
     * Candidates, library matches first ([RankedHit.library]), then the catalogue ranked with history. [webQuery]:
     * "Search the web for …" (null when the service gave none). [singing]: the clip was sung, not spoken, so a chosen
     * song may be moved to the sung key ([VoiceSearch.shiftFor]).
     */
    data class Results(
        val intent: String,
        val transcript: String,
        val hits: List<RankedHit>,
        val webQuery: String?,
        val guesses: List<VoiceGuess>,
        val singing: Boolean,
    ) : VoiceOutcome
    /** "We didn't hear any words": the service heard no words (intent "none"). */
    data object NoWords : VoiceOutcome
    /** The clip was under a second (not sent), or the service rejected the audio (400 bad_audio). */
    data object TooShort : VoiceOutcome
    /** Today's voice searches are used up: [limit] a day on [plan]. */
    data class LimitReached(val limit: Int?, val plan: String? = null) : VoiceOutcome
    data object Offline : VoiceOutcome
    /** No account yet (or it was revoked/disabled): the service can't be asked. */
    data object NoAccount : VoiceOutcome
    /** The service couldn't transcribe (503 voice_unavailable) or failed otherwise. */
    data object Unavailable : VoiceOutcome
}

/** Semitones to apply on opening without asking: only for a confident guess. Close calls go to a chooser. */
val ShiftGuess.autoTranspose: Int? get() = if (confident) semitones else null

/** Letters, combining marks (Devanagari/Bengali vowel signs) and digits, as the local index tokenizes. */
private val TERM = Regex("[\\p{L}\\p{M}\\p{N}]+")

/** Words of a transcript sent to the on-device library search (which ANDs them). */
internal const val MAX_LOCAL_TERMS = 6

/**
 * Sing a line: sends a [Recording] to the service with history hints, searches the user's own library on the device
 * for the transcript, its romanized form (both capped at [MAX_LOCAL_TERMS] words) and the service's guesses, and ranks
 * everything with [HistoryRanker].
 *
 * [library] runs the local full-text search and returns only the user's favourites/playlists (as [VoiceHit.LIBRARY]
 * hits); [sheet] returns a song's ChordPro (cached, or fetched and cached).
 */
class VoiceSearch(
    private val api: ServiceApi,
    private val library: suspend (query: String) -> List<VoiceHit>,
    private val history: suspend () -> HistorySignals,
    private val sheet: suspend (songId: String) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The sung profile of the last [run], when it was singing; [shiftFor] uses it. */
    @Volatile private var lastSung: SungProfile? = null

    suspend fun run(recording: Recording): VoiceOutcome {
        lastSung = null
        if (recording.seconds < VoiceRecorder.MIN_SECONDS) return VoiceOutcome.TooShort
        val signals = try { history() } catch (e: CancellationException) { throw e } catch (_: Exception) { HistorySignals() }
        val reply = try {
            try {
                api.voice(recording.wav, signals.topLanguages, signals.topArtists)
            } catch (e: ApiError.Http) {
                // Hints are only a nudge: if the service refuses them, ask once more without any.
                if (e.code == 400 && e.error == "bad_hints") api.voice(recording.wav, emptyList(), emptyList()) else throw e
            }
        } catch (e: ApiError) {
            return when {
                e is ApiError.Offline -> VoiceOutcome.Offline
                e is ApiError.Limit -> VoiceOutcome.LimitReached(e.limit, e.plan)
                e is ApiError.Http && e.code == 400 && e.error == "bad_audio" -> VoiceOutcome.TooShort
                e is ApiError.Unauthorized || e is ApiError.TokenRevoked || e is ApiError.AccountDisabled -> VoiceOutcome.NoAccount
                else -> VoiceOutcome.Unavailable
            }
        }
        if (reply.intent == "none") return VoiceOutcome.NoWords

        val local = LinkedHashMap<String, VoiceHit>()
        // The local search ANDs every term, so a long line with one misheard word would match nothing: the transcript
        // and its romanized form (Latin script, what the library's Bengali/Hindi lyrics are written in) are capped.
        val queries = buildList {
            add(firstTerms(reply.transcript))
            reply.romanized?.let { add(firstTerms(it)) }
            reply.webQuery?.let(::add)
            reply.guesses.forEach { g -> add(listOfNotNull(g.title, g.artist).joinToString(" ")); add(g.title) }
        }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        for (q in queries) {
            val hits = try { library(q) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
            for (h in hits) local.putIfAbsent(h.id, h)
        }
        val server = reply.results.filter { it.id !in local }.map {
            VoiceHit(it.id, it.title, it.artist, it.language, it.categories, it.originalKey, it.match)
        }
        val singing = recording.sung.isSinging
        if (singing) lastSung = recording.sung
        return VoiceOutcome.Results(
            intent = reply.intent,
            transcript = reply.transcript,
            hits = HistoryRanker.rank(local.values.toList() + server, signals, clock()),
            webQuery = reply.webQuery?.trim()?.takeIf { it.isNotEmpty() },
            guesses = reply.guesses,
            singing = singing,
        )
    }

    /**
     * How far to move [songId] to the key sung in the last [run]. Null when that clip was not singing (speech, a spoken
     * request or title, or under 4 s of held notes), when the song can't be loaded, or when it has no chords to match.
     * Open transposed only by [autoTranspose] (a confident guess); a non-confident guess with an alternative is a close
     * call for the user to pick.
     */
    suspend fun shiftFor(songId: String): ShiftGuess? = shiftFor(songId, lastSung)

    suspend fun shiftFor(songId: String, sung: SungProfile?): ShiftGuess? {
        if (sung == null || !sung.isSinging) return null
        val chordpro = try { sheet(songId) } catch (e: CancellationException) { throw e } catch (_: Exception) { return null }
        val profile = KeyFinder.chordProfile(chordsOf(chordpro))
        return KeyFinder.bestShift(sung.histogram(), profile, sung.voicedSeconds)
    }

    /** The first [MAX_LOCAL_TERMS] words (letter/mark/digit runs) of [text], space-joined. */
    private fun firstTerms(text: String): String =
        TERM.findAll(text).take(MAX_LOCAL_TERMS).joinToString(" ") { it.value }

    /** Every chord occurrence in play order (repeats kept, so frequent chords weigh more). */
    private fun chordsOf(chordpro: String): List<String> =
        ChordPro.parse(chordpro).sections.flatMap { s -> s.lines.filterIsInstance<Line.Lyrics>().flatMap { l -> l.segments.mapNotNull { it.chord } } }
}
