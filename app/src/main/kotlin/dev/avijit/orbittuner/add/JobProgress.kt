package dev.avijit.orbittuner.add

import dev.avijit.orbittuner.net.ApiError
import dev.avijit.orbittuner.net.ImportJob
import dev.avijit.orbittuner.songs.SongRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Languages POST /v1/discover accepts for `lang` (service LANGS); any other chip is left out of the request. */
private val DISCOVER_LANGS = setOf("en", "hi", "bn", "ta", "te", "ml", "kn", "mr", "gu", "pa", "ur")

/** The language to send with a web search: [lang] when the service accepts it, else null (search all languages). */
fun discoverLang(lang: String?): String? = lang?.lowercase()?.takeIf { it in DISCOVER_LANGS }

/** Whether a query can go to POST /v1/discover (service rules: 2–120 characters, a song name rather than a link). */
enum class DiscoverQuery { Ok, TooShort, TooLong, Link }

fun discoverQuery(q: String): DiscoverQuery {
    val t = q.trim()
    return when {
        Regex("://|^www\\.", RegexOption.IGNORE_CASE).containsMatchIn(t) -> DiscoverQuery.Link
        t.length < 2 -> DiscoverQuery.TooShort
        t.length > 120 -> DiscoverQuery.TooLong
        else -> DiscoverQuery.Ok
    }
}

/** A song imported from the web; [inReview] songs are visible only to their creator until a moderator publishes them. */
data class WebRow(val row: SongRow, val inReview: Boolean)

/** The "From the web" tier. */
sealed interface WebTier {
    data object Idle : WebTier
    /** The service is searching the web (can take most of a minute). */
    data object Searching : WebTier
    /** Pages are being imported; finished songs stream into [rows]. */
    data class Importing(val rows: List<WebRow>, val pending: Int) : WebTier
    /** [note] explains an empty or partial result. */
    data class Done(val rows: List<WebRow>, val note: String? = null) : WebTier
    /** [limit]: a plan or search allowance ran out (offer Google sign-in); [canRetry]: worth trying again now. */
    data class Failed(val message: String, val limit: Boolean = false, val canRetry: Boolean = false) : WebTier
}

/** The search a [WebTier] belongs to. */
data class WebState(val query: String, val lang: String?, val tier: WebTier)

/**
 * Find on the web: `POST /v1/discover`, then polls the import job every [pollMs] until it is done, fetching each
 * finished song ([fetchSong]) so results stream in. Poll errors back off (doubling, up to [maxBackoffMs]); after
 * [maxErrors] in a row it gives up with a retry. [pause] stops polling (screen left) and [resume] picks it up again;
 * [clear] forgets the search. The discover call itself is never cancelled by [pause]: the service creates the job
 * either way, so the result is kept for [resume].
 */
class WebFinder(
    private val service: ContributionService,
    private val fetchSong: suspend (String) -> SongRow,
    private val scope: CoroutineScope,
    private val onAiUsed: () -> Unit = {},
    private val pollMs: Long = 2_000,
    private val maxBackoffMs: Long = 30_000,
    private val maxErrors: Int = 6,
) {
    private val _state = MutableStateFlow(WebState("", null, WebTier.Idle))
    val state: StateFlow<WebState> = _state.asStateFlow()

    private var search: Job? = null
    private var poller: Job? = null
    private var jobId: String? = null
    private var paused = false
    /** Songs fetched for the current job, by id, in arrival order. */
    private val rows = LinkedHashMap<String, WebRow>()

    fun find(q: String, lang: String?) {
        val query = q.trim()
        if (query.isEmpty()) return
        clear()
        _state.value = WebState(query, lang, WebTier.Searching)
        search = scope.launch {
            val start = try {
                service.discover(query, lang)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A 400 refuses the request itself: trying the same search again can't help.
                val retryable = e !is ApiError.Limit && !(e is ApiError.Http && e.code == 400)
                set(WebTier.Failed(contributionErrorMessage(e), limit = e is ApiError.Limit, canRetry = retryable))
                if (e is ApiError.Limit) onAiUsed()
                return@launch
            }
            val id = start.jobId
            if (id == null) {
                set(WebTier.Done(emptyList(), "Nothing found on the web for “$query”"))
                return@launch
            }
            jobId = id
            onAiUsed()
            set(WebTier.Importing(emptyList(), start.candidates))
            if (!paused) poller = scope.launch { poll(id) }
        }
    }

    /** Try the same search again (after a failure). */
    fun retry() {
        val s = _state.value
        if (jobId != null && s.tier is WebTier.Failed) { set(WebTier.Importing(rows.values.toList(), 0)); resume() }
        else if (s.query.isNotEmpty()) find(s.query, s.lang)
    }

    fun pause() {
        paused = true
        poller?.cancel(); poller = null
    }

    fun resume() {
        paused = false
        val id = jobId ?: return
        if (poller?.isActive == true) return
        val t = _state.value.tier
        if (t is WebTier.Importing) poller = scope.launch { poll(id) }
    }

    fun clear() {
        search?.cancel(); search = null
        poller?.cancel(); poller = null
        jobId = null
        rows.clear()
        _state.value = WebState("", null, WebTier.Idle)
    }

    private fun set(tier: WebTier) { _state.value = _state.value.copy(tier = tier) }

    private suspend fun poll(id: String) {
        var errors = 0
        while (true) {
            val job = try {
                service.importJob(id).also { errors = 0 }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors++
                if (errors >= maxErrors || e is ApiError.Http && e.code == 404) {
                    set(WebTier.Failed(contributionErrorMessage(e), canRetry = true))
                    return
                }
                delay(minOf(pollMs shl errors, maxBackoffMs))
                continue
            }
            if (jobId != id) return
            collect(job)
            val finished = job.status == "done" || job.items.none { it.status == "queued" }
            if (finished) {
                set(done(job))
                onAiUsed()
                return
            }
            set(WebTier.Importing(rows.values.toList(), job.items.count { it.status == "queued" }))
            delay(pollMs)
        }
    }

    /** Fetches songs of newly finished items; a failed fetch is retried on the next poll. */
    private suspend fun collect(job: ImportJob) {
        for (item in job.items) {
            val songId = item.songId ?: continue
            if (item.status !in SONG_STATUSES || songId in rows) continue
            val row = try {
                fetchSong(songId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue
            }
            rows[songId] = WebRow(row, inReview = item.status == "review")
        }
    }

    private fun done(job: ImportJob): WebTier.Done {
        val list = rows.values.toList()
        val limited = job.items.count { it.status == "limit_reached" }
        val note = when {
            list.isNotEmpty() && limited > 0 -> if (limited == 1) "1 more page needs tomorrow's AI imports" else "$limited more pages need tomorrow's AI imports"
            list.isNotEmpty() -> null
            limited > 0 -> "You're out of AI imports for today, so the pages found weren't imported"
            job.items.isEmpty() -> "Nothing found on the web for “${_state.value.query}”"
            else -> "Found ${job.items.size} ${if (job.items.size == 1) "page" else "pages"}, but none had chords we could read"
        }
        return WebTier.Done(list, note)
    }

    private companion object { val SONG_STATUSES = setOf("done", "review", "duplicate") }
}
