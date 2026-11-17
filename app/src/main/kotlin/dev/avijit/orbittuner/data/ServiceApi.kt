package dev.avijit.orbittuner.data

import dev.avijit.orbittuner.net.Api
import dev.avijit.orbittuner.net.FavouriteItem
import dev.avijit.orbittuner.net.Page
import dev.avijit.orbittuner.net.Playlist
import dev.avijit.orbittuner.net.PlaylistPage
import dev.avijit.orbittuner.net.Song
import dev.avijit.orbittuner.net.SongSummary
import dev.avijit.orbittuner.net.VoiceReply
import java.security.SecureRandom

/** The slice of the service the repositories use; [Api] in production, a fake in tests. Errors are [dev.avijit.orbittuner.net.ApiError]. */
interface ServiceApi {
    suspend fun song(id: String): Song
    suspend fun changes(since: Long, ids: List<String>): List<Song>
    suspend fun search(q: String, lang: String?, cat: String?, page: Int): Page<SongSummary>
    suspend fun getFavourites(): List<FavouriteItem>
    suspend fun putFavourites(items: List<FavouriteItem>): List<FavouriteItem>
    suspend fun getPlaylists(since: Long): PlaylistPage
    suspend fun putPlaylist(p: Playlist): Playlist
    /** POST /v1/voice (Sing a line). */
    suspend fun voice(wav: ByteArray, langs: List<String>, artists: List<String>): VoiceReply
}

fun Api.asService(): ServiceApi = object : ServiceApi {
    override suspend fun song(id: String) = this@asService.song(id)
    override suspend fun changes(since: Long, ids: List<String>) = this@asService.changes(since, ids)
    override suspend fun search(q: String, lang: String?, cat: String?, page: Int) = this@asService.search(q, lang, cat, page)
    override suspend fun getFavourites() = this@asService.getFavourites()
    override suspend fun putFavourites(items: List<FavouriteItem>) = this@asService.putFavourites(items)
    override suspend fun getPlaylists(since: Long) = this@asService.getPlaylists(since)
    override suspend fun putPlaylist(p: Playlist) = this@asService.putPlaylist(p)
    override suspend fun voice(wav: ByteArray, langs: List<String>, artists: List<String>) = this@asService.voice(wav, langs, artists)
}

/** ULID (Crockford base32, 48-bit ms time + 80 random bits): the service requires ULID playlist ids. */
object Ulid {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val random = SecureRandom()

    fun next(timeMs: Long): String {
        val out = CharArray(26)
        var t = timeMs.coerceIn(0, (1L shl 48) - 1)
        for (i in 9 downTo 0) { out[i] = ALPHABET[(t and 31).toInt()]; t = t shr 5 }
        val bytes = ByteArray(10).also(random::nextBytes)
        var acc = 0; var bits = 0; var j = 10
        for (b in bytes) {
            acc = (acc shl 8) or (b.toInt() and 0xff); bits += 8
            while (bits >= 5) { bits -= 5; out[j++] = ALPHABET[(acc shr bits) and 31] }
        }
        return String(out)
    }
}
