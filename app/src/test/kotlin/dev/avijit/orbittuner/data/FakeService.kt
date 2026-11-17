package dev.avijit.orbittuner.data

import dev.avijit.orbittuner.net.ApiError
import dev.avijit.orbittuner.net.FavouriteItem
import dev.avijit.orbittuner.net.Page
import dev.avijit.orbittuner.net.Playlist
import dev.avijit.orbittuner.net.PlaylistPage
import dev.avijit.orbittuner.net.Song
import dev.avijit.orbittuner.net.SongSummary
import dev.avijit.orbittuner.net.VoiceReply

/**
 * In-memory stand-in for the song service with the server's rules: last-write-wins on client updatedAt,
 * and a server-stamped, strictly increasing modifiedAt that drives the playlist pull cursor.
 */
class FakeService(private val onRevoked: () -> Unit = {}) : ServiceApi {
    var offline = false
    /** When set, every call throws this (TokenRevoked also fires [onRevoked], like the real Api). */
    var failWith: ApiError? = null
    /** When set, the next putPlaylist throws Stale(this) (after storing it as the server copy). */
    var staleOnPut: Playlist? = null
    /** Per-playlist-id rejection for putPlaylist (e.g. Http(400)). */
    val putPlaylistFails = HashMap<String, ApiError>()
    /** When set, putFavourites throws this. */
    var putFavouritesFail: ApiError? = null
    /** One-shot hook run inside putPlaylist before the server applies the write (simulates a concurrent local edit). */
    var duringPut: (suspend (Playlist) -> Unit)? = null
    /** Hook run inside every getPlaylists call. */
    var duringGet: (suspend () -> Unit)? = null

    val songs = LinkedHashMap<String, Song>()
    val favs = LinkedHashMap<String, FavouriteItem>()
    val playlists = LinkedHashMap<String, Playlist>()
    val putPlaylistCalls = ArrayList<Playlist>()
    val putFavouritesCalls = ArrayList<List<FavouriteItem>>()
    val getPlaylistsSince = ArrayList<Long>()
    val changesCalls = ArrayList<List<String>>()
    var songCalls = 0
    /** Server clock for modifiedAt; deliberately unrelated to any client's updatedAt. */
    private var serverClock = 5_000_000_000L

    private fun gate() {
        if (offline) throw ApiError.Offline
        failWith?.let { if (it === ApiError.TokenRevoked) onRevoked(); throw it }
    }

    /** A write that reaches the server from elsewhere (another device), stamped like the real service. */
    fun serverWrite(p: Playlist): Playlist {
        val saved = p.copy(modifiedAt = ++serverClock)
        playlists[p.id] = saved
        return saved
    }

    override suspend fun song(id: String): Song { gate(); songCalls++; return songs[id] ?: throw ApiError.Http(404, "not_found") }

    override suspend fun changes(since: Long, ids: List<String>): List<Song> {
        gate(); changesCalls += ids
        return ids.mapNotNull { songs[it] }.filter { it.updatedAt > since }
    }

    override suspend fun search(q: String, lang: String?, cat: String?, page: Int): Page<SongSummary> {
        gate()
        return Page(songs.values.filter { it.title.contains(q, ignoreCase = true) }.map { SongSummary(it.id, it.title, it.artist) })
    }

    override suspend fun getFavourites(): List<FavouriteItem> { gate(); return favs.values.toList() }

    override suspend fun putFavourites(items: List<FavouriteItem>): List<FavouriteItem> {
        gate(); putFavouritesCalls += items
        putFavouritesFail?.let { throw it }
        require(items.size <= 1000) { "server limit" }
        for (it in items) { val prev = favs[it.songId]; if (prev == null || it.updatedAt > prev.updatedAt) favs[it.songId] = it }
        return favs.values.toList()
    }

    override suspend fun getPlaylists(since: Long): PlaylistPage {
        gate(); getPlaylistsSince += since
        duringGet?.invoke()
        val items = playlists.values.filter { it.modifiedAt > since }.sortedBy { it.modifiedAt }
        return PlaylistPage(items, items.fold(since) { m, p -> maxOf(m, p.modifiedAt) })
    }

    override suspend fun putPlaylist(p: Playlist): Playlist {
        gate(); putPlaylistCalls += p
        duringPut?.let { duringPut = null; it(p) }
        putPlaylistFails[p.id]?.let { throw it }
        staleOnPut?.let { staleOnPut = null; throw ApiError.Stale(serverWrite(it)) }
        val prev = playlists[p.id]
        if (prev != null && prev.updatedAt >= p.updatedAt) throw ApiError.Stale(prev)
        return serverWrite(p)
    }

    /** One POST /v1/voice as received (the clip's size only: tests never keep audio). */
    data class VoiceCall(val bytes: Int, val langs: List<String>, val artists: List<String>)
    val voiceCalls = ArrayList<VoiceCall>()
    var voiceReply = VoiceReply(intent = "none")
    /** When set, voice() throws this. */
    var voiceError: ApiError? = null
    /** One-shot failures, taken in order before [voiceError] applies. */
    val voiceErrorsOnce = ArrayDeque<ApiError>()

    override suspend fun voice(wav: ByteArray, langs: List<String>, artists: List<String>): VoiceReply {
        gate(); voiceCalls += VoiceCall(wav.size, langs, artists)
        voiceErrorsOnce.removeFirstOrNull()?.let { throw it }
        voiceError?.let { throw it }
        return voiceReply
    }

    fun addSong(id: String, title: String, artist: String? = null, lyrics: String = "la la", updatedAt: Long = 10) {
        songs[id] = Song(id = id, title = title, artist = artist, chordpro = "{title: $title}\n[G]$lyrics", updatedAt = updatedAt)
    }
}
