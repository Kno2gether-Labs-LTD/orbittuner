package dev.avijit.orbittuner.data

import androidx.room.withTransaction
import dev.avijit.orbittuner.chordpro.ChordPro
import dev.avijit.orbittuner.chordpro.Line
import dev.avijit.orbittuner.data.db.AppDb
import dev.avijit.orbittuner.data.db.CachedSong
import dev.avijit.orbittuner.data.db.SongPrefs
import dev.avijit.orbittuner.net.ApiError
import dev.avijit.orbittuner.net.Page
import dev.avijit.orbittuner.net.Song
import dev.avijit.orbittuner.net.SongSummary
import dev.avijit.orbittuner.net.WireJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.util.Locale

/** Local search split: songs in the user's playlists or favourites, then everything else cached. */
data class LocalResults(val yours: List<CachedSong>, val cached: List<CachedSong>)

/** Letters, combining marks (e.g. Devanagari vowel signs, decomposed accents) and digits. */
private val TERM = Regex("[\\p{L}\\p{M}\\p{N}]+")
private const val MAX_TERMS = 12
private const val CHANGES_CHUNK = 200

/**
 * FTS4 MATCH expression of quoted prefix terms (`"term*"`, implicitly ANDed) built from letter/mark/digit runs, mirroring
 * the service's ftsQuery (which uses FTS5's `"term"*` form). Null when nothing is searchable. Terms are lowercased and
 * quoted, so operators (OR, NOT, NEAR), quotes and parentheses in user text are inert.
 */
fun ftsMatch(q: String): String? {
    val terms = TERM.findAll(q).map { it.value.lowercase(Locale.ROOT) }.take(MAX_TERMS).toList()
    return if (terms.isEmpty()) null else terms.joinToString(" ") { "\"$it*\"" }
}

/** Plain lyric text of a ChordPro document, for the local full-text index. */
internal fun lyricsOf(chordpro: String): String =
    ChordPro.parse(chordpro).sections.asSequence().flatMap { it.lines.asSequence() }
        .mapNotNull { line -> (line as? Line.Lyrics)?.segments?.joinToString("") { it.text } }
        .filter { it.isNotBlank() }
        .joinToString("\n")

fun CachedSong.categories(): List<String> =
    try { WireJson.decodeFromString(ListSerializer(String.serializer()), categoriesJson) } catch (_: Exception) { emptyList() }

/**
 * Songs: cache-first for reading (anything opened once stays readable offline), service for discovery.
 * [scope] runs background refreshes; [clock] is injectable for tests.
 */
class SongRepository(
    private val api: ServiceApi,
    private val db: AppDb,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.songs()

    /**
     * The player opened the song: cached copy immediately (refreshed in the background when reachable), else fetched
     * and cached; stamps lastOpenedAt (Library > Recent). Only the song view should call this.
     * Throws [ApiError.Offline] only when the song was never cached and the service is unreachable.
     */
    suspend fun open(id: String): CachedSong = fetch(id, played = true)

    /** Like [open], for keeping a song on the device (favourite, playlist, download): never marks it as played. */
    suspend fun cache(id: String): CachedSong = fetch(id, played = false)

    private suspend fun fetch(id: String, played: Boolean): CachedSong {
        val now = clock()
        val cached = dao.get(id)
        if (cached != null) {
            if (played) dao.touch(id, now)
            scope.launch {
                try { refresh(listOf(cached.id), cached.updatedAt) } catch (_: ApiError) { /* offline/revoked: keep the cache */ }
            }
            return if (played) cached.copy(lastOpenedAt = now) else cached
        }
        return store(api.song(id), lastOpenedAt = if (played) now else null)
    }

    fun localSearch(q: String): Flow<LocalResults> {
        val match = ftsMatch(q)
        val hits = if (match == null) dao.recent() else dao.search(match)
        return hits.map { list ->
            val (yours, rest) = list.partition { it.yours }
            LocalResults(yours.map { it.song }, rest.map { it.song })
        }.distinctUntilChanged()
    }

    suspend fun remoteSearch(q: String, lang: String?, cat: String?, page: Int): Page<SongSummary> =
        api.search(q, lang, cat, page)

    /** Pulls newer versions of every cached song via /v1/songs/changes, ≤ 200 ids per call. */
    suspend fun refreshCached() {
        // Sorted by updatedAt so each chunk's `since` (its oldest copy) is tight.
        for (chunk in dao.stamps().chunked(CHANGES_CHUNK)) {
            refresh(chunk.map { it.id }, chunk.minOf { it.updatedAt })
        }
    }

    /** Caches songs the user has but the device lacks (e.g. pulled from another device); one call per 200. */
    internal suspend fun cacheMissing(ids: List<String>, via: ServiceApi = api) {
        for (chunk in ids.distinct().chunked(CHANGES_CHUNK)) {
            for (s in via.changes(0, chunk)) if (dao.get(s.id) == null) store(s, lastOpenedAt = null)
        }
    }

    internal suspend fun setPinned(id: String, pinned: Boolean) = dao.setPinned(id, pinned)

    fun prefs(id: String): Flow<SongPrefs?> = dao.prefs(id)
    suspend fun setPrefs(prefs: SongPrefs) = dao.putPrefs(prefs)

    private suspend fun refresh(ids: List<String>, since: Long) {
        for (s in api.changes(since, ids)) {
            val local = dao.get(s.id) ?: continue
            if (s.updatedAt > local.updatedAt || s.version > local.version) store(s, lastOpenedAt = null)
        }
    }

    /** Upserts [s]; keeps the local lastOpenedAt (unless given) and pinnedOffline. */
    private suspend fun store(s: Song, lastOpenedAt: Long?): CachedSong = db.withTransaction {
        val existing = dao.get(s.id)
        val row = CachedSong(
            id = s.id, title = s.title, artist = s.artist, chordpro = s.chordpro, language = s.language,
            categoriesJson = WireJson.encodeToString(ListSerializer(String.serializer()), s.categories),
            originalKey = s.originalKey, version = s.version, updatedAt = s.updatedAt,
            lastOpenedAt = lastOpenedAt ?: existing?.lastOpenedAt ?: 0L,
            pinnedOffline = existing?.pinnedOffline ?: false,
            status = s.status,
        )
        dao.store(row, lyricsOf(s.chordpro))
        row
    }
}
