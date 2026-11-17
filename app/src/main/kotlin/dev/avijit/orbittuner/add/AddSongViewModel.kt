package dev.avijit.orbittuner.add

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.avijit.orbittuner.music.ChordParser
import dev.avijit.orbittuner.music.Note
import dev.avijit.orbittuner.music.Transposer
import dev.avijit.orbittuner.net.Api
import dev.avijit.orbittuner.net.ApiError
import dev.avijit.orbittuner.net.DiscoverStart
import dev.avijit.orbittuner.net.ImportJob
import dev.avijit.orbittuner.net.Me
import dev.avijit.orbittuner.net.NewSong
import dev.avijit.orbittuner.net.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

/** The contribution endpoints (add a song, find on the web); [Api] in production, a fake in tests. Errors are [ApiError]. */
interface ContributionService {
    suspend fun createSong(input: NewSong): Song
    suspend fun discover(q: String, lang: String?): DiscoverStart
    suspend fun importJob(id: String): ImportJob
}

fun Api.asContributions(): ContributionService = object : ContributionService {
    override suspend fun createSong(input: NewSong) = this@asContributions.createSong(input)
    override suspend fun discover(q: String, lang: String?) = this@asContributions.discover(q, lang)
    override suspend fun importJob(id: String) = this@asContributions.importJob(id)
}

enum class AddTab(val label: String) { Paste("Paste text"), Link("Link"), Write("Write") }

/** Outcome of the last submit. */
sealed interface AddResult {
    data object Idle : AddResult
    data object Sending : AddResult
    data class Published(val song: Song) : AddResult
    /** Saved, but only the creator (and moderators) see it until a moderator publishes it. */
    data class InReview(val song: Song) : AddResult
    data class Duplicate(val songId: String?) : AddResult
    /** [signInHint]: a plan limit on an account not linked to Google (signing in raises it). */
    data class Failed(val message: String, val signInHint: Boolean = false) : AddResult
}

/** Today's AI allowance from /v1/me. [google]: the account is linked to Google. */
data class AiAllowance(val left: Int, val limit: Int, val plan: String, val google: Boolean)

/**
 * Add a song: pasted text (converted by the service, with AI when it finds no chords), a web link (always AI) or a
 * hand-written ChordPro sheet. The form fields live in the screen; this holds the submit and its result.
 */
class AddSongViewModel(
    private val service: ContributionService,
    me: Flow<Me?>,
    /** After an AI action: refresh /v1/me so the allowance shown is current. */
    private val onAiUsed: () -> Unit,
) : ViewModel() {
    private val _result = MutableStateFlow<AddResult>(AddResult.Idle)
    val result: StateFlow<AddResult> = _result.asStateFlow()

    val allowance: StateFlow<AiAllowance?> = me.map { m ->
        m?.let { AiAllowance(it.aiLeftToday, it.aiDailyLimit, it.plan, it.google != null) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var job: Job? = null

    fun submitPaste(text: String, title: String?, artist: String?) {
        if (text.isBlank()) return fail("Paste the chords and lyrics first")
        send(NewSong.Text(text, title.clean(), artist.clean(), ai = "auto"), ai = true)
    }

    fun submitLink(url: String) {
        val u = normaliseUrl(url) ?: return fail("That doesn't look like a web link")
        send(NewSong.Link(u), ai = true)
    }

    fun submitWrite(title: String, artist: String?, key: String?, body: String) {
        if (title.isBlank()) return fail("Give the song a title")
        if (body.isBlank()) return fail("Write the lyrics with [chords] first")
        send(NewSong.ChordPro(writeChordPro(title, artist, key, body)), ai = false)
    }

    /** Back to the form (after a result, or when the user edits again). */
    fun reset() { if (_result.value !is AddResult.Sending) _result.value = AddResult.Idle }

    private fun fail(message: String) { _result.value = AddResult.Failed(message) }

    private fun send(input: NewSong, ai: Boolean) {
        if (job?.isActive == true) return
        _result.value = AddResult.Sending
        job = viewModelScope.launch {
            _result.value = try {
                val song = service.createSong(input)
                if (song.status == "published") AddResult.Published(song) else AddResult.InReview(song)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError.Duplicate) {
                AddResult.Duplicate(e.songId)
            } catch (e: Exception) {
                AddResult.Failed(contributionErrorMessage(e), signInHint = e is ApiError.Limit && allowance.value?.google != true)
            } finally {
                if (ai) onAiUsed()
            }
        }
    }
}

private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/** "chords.example/x" -> "https://chords.example/x"; null unless it is an http(s) URL with a dotted host. */
internal fun normaliseUrl(raw: String): String? {
    val t = raw.trim()
    if (t.isEmpty() || t.any { it.isWhitespace() }) return null
    val withScheme = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(t)) t else "https://$t"
    val uri = try { java.net.URI(withScheme) } catch (_: Exception) { return null }
    if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) return null
    val host = uri.host ?: return null
    return if (host.contains('.')) withScheme else null
}

private fun planName(plan: String?) = (plan ?: "free").replaceFirstChar { it.titlecase(Locale.ROOT) }

/** User-facing copy for a failed contribution call (add song, find on the web). */
fun contributionErrorMessage(e: Exception): String = when (e) {
    is ApiError.Limit -> {
        val what = if (e.error == "search_limit_reached") "web searches" else "AI imports"
        if (e.limit != null) "You've used today's $what (${planName(e.plan)} plan: ${e.limit}/day)" else "You've used today's $what"
    }
    ApiError.Offline -> "You're offline. Connect and try again."
    ApiError.AccountDisabled -> "This account is disabled, so it can't add songs."
    ApiError.Unauthorized, ApiError.TokenRevoked -> "Your account is still being set up. Try again in a moment."
    is ApiError.Http -> when (e.error) {
        "rate_limited" -> "That's a lot in one minute. Wait a moment, then try again."
        "ai_limit_reached" -> "AI imports are paused for today. You can still write the song yourself."
        "no_chords" -> "We couldn't find any chords in that text. Put chords above the lyrics, or use Write."
        "ai_unparseable", "ai_too_long" -> "The AI couldn't turn that into a chord sheet. Try just the chords and lyrics."
        "missing_title" -> "Add a title so the song can be found"
        "empty" -> "There's nothing to add yet"
        "too_large" -> "That's too long for one song"
        "bad_url" -> "That link can't be imported. Use a public web page address."
        "fetch_failed" -> "We couldn't open that page. Check the link, or paste the text instead."
        "unsupported_type" -> "That link isn't a page we can read. Paste the text instead."
        "ai_unavailable", "timeout" -> "The AI didn't answer in time. Try again in a minute."
        "search_unavailable" -> "Web search isn't available right now"
        "search_failed" -> "The web search didn't answer. Try again."
        "queue_unavailable" -> "Imports are busy right now. Try again in a minute."
        "invalid_input" -> "That search can't be sent. Try just the song name."
        else -> "Something went wrong (${e.code}). Try again."
    }
    else -> "Something went wrong. Try again."
}

private fun metaValue(s: String?): String? = s?.replace(Regex("[\\r\\n{}]+"), " ")?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }

/** The Write tab's sheet: title/artist/key directives, a blank line, then the body as typed (newline-terminated). */
fun writeChordPro(title: String, artist: String?, key: String?, body: String): String = buildString {
    metaValue(title)?.let { append("{title: ").append(it).append("}\n") }
    metaValue(artist)?.let { append("{artist: ").append(it).append("}\n") }
    metaValue(key)?.let { append("{key: ").append(it).append("}\n") }
    append('\n')
    val b = body.replace("\r\n", "\n").replace('\r', '\n').trim('\n')
    append(b).append('\n')
}

/**
 * Common chords in [key]: major keys I ii iii IV V vi V7 (G -> G Am Bm C D Em D7); minor keys i III iv v V7 VI VII
 * (Am -> Am C Dm Em E7 F G). Spelled with the key's accidentals. An unknown key gives C's.
 */
fun chordPalette(key: String): List<String> {
    val parsed = ChordParser.parse(key.trim())?.takeIf { it.quality == "" || it.quality == "m" } ?: return chordPalette("C")
    val minor = parsed.quality == "m"
    val acc = Transposer.keyAccidental(key.trim())
    fun n(semis: Int) = Note(parsed.root + semis).name(acc)
    return if (!minor) listOf(n(0), n(2) + "m", n(4) + "m", n(5), n(7), n(9) + "m", n(7) + "7")
    else listOf(n(0) + "m", n(3), n(5) + "m", n(7) + "m", n(7) + "7", n(8), n(10))
}

/** Inserts `[chord]` at the cursor (replacing any selection) and puts the cursor after it. */
fun insertChord(value: TextFieldValue, chord: String): TextFieldValue {
    val start = minOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
    val end = maxOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
    val tag = "[$chord]"
    return TextFieldValue(value.text.substring(0, start) + tag + value.text.substring(end), TextRange(start + tag.length))
}
