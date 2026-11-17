package dev.avijit.orbittuner.net

import dev.avijit.orbittuner.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

val WireJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

/** The account this device signs in as. Anonymous accounts have no [name]; [plan] is as of sign-up (see [Me]). */
@Serializable data class UserDto(val id: String, val name: String = "", val role: String, val plan: String? = null)
/** POST /v1/accounts/anonymous. */
@Serializable data class AccountResponse(val deviceToken: String, val user: UserDto) {
    override fun toString() = "AccountResponse(user=$user)" // never print the token
}
@Serializable data class GoogleLink(val email: String? = null)
/** The user in a POST /v1/accounts/google response. */
@Serializable data class GoogleUser(val id: String, val role: String, val plan: String? = null, val google: GoogleLink? = null) {
    fun toUserDto() = UserDto(id, role = role, plan = plan)
}
/**
 * POST /v1/accounts/google. [deviceToken] is present when this device was re-bound to another (Google-linked)
 * account; its value is this device's existing token, so an identity change shows as a different [GoogleUser.id].
 */
@Serializable data class GoogleAccountResponse(val deviceToken: String? = null, val user: GoogleUser) {
    override fun toString() = "GoogleAccountResponse(rebound=${deviceToken != null}, user=$user)" // never print the token
}
/** GET /v1/me: the plan in force and today's AI allowance. */
@Serializable data class Me(
    val id: String, val role: String, val plan: String = "free", val planExpiresAt: Long? = null,
    val google: GoogleLink? = null, val aiLeftToday: Int = 0, val aiDailyLimit: Int = 0,
)
@Serializable data class SongSummary(
    val id: String, val title: String, val artist: String? = null, val language: String = "en",
    val categories: List<String> = emptyList(), val originalKey: String? = null, val version: Int = 1,
)
@Serializable data class Song(
    val id: String, val title: String, val artist: String? = null, val language: String = "en",
    val categories: List<String> = emptyList(), val originalKey: String? = null, val version: Int = 1,
    val chordpro: String, val hasNative: Boolean = false, val tempoBpm: Int? = null, val timeSig: String? = null,
    val capo: Int = 0, val difficulty: String? = null, val source: String = "owner", val sourceUrl: String? = null,
    val status: String = "published", val updatedAt: Long = 0,
)
@Serializable data class Page<T>(val items: List<T>, val nextPage: Int? = null)
@Serializable data class LanguageFacet(val code: String, val count: Int)
@Serializable data class CategoryFacet(val name: String, val count: Int)
@Serializable data class Facets(val languages: List<LanguageFacet> = emptyList(), val categories: List<CategoryFacet> = emptyList())
@Serializable data class FavouriteItem(val songId: String, val favourite: Boolean, val updatedAt: Long)
@Serializable data class ItemSettings(val transpose: Int? = null, val capo: Int? = null, val scrollSpeed: Double? = null)
@Serializable data class PlaylistItem(val songId: String, val position: String, val settings: ItemSettings = ItemSettings())
/** [updatedAt] is the client's last-write-wins stamp; [modifiedAt] is server-stamped (read-only, drives the pull cursor). */
@Serializable data class Playlist(
    val id: String, val name: String, val deleted: Boolean = false, val updatedAt: Long, val items: List<PlaylistItem> = emptyList(),
    val modifiedAt: Long = 0,
)
/** GET /v1/me/playlists: changes after `since`, plus the server cursor to send back verbatim next time. */
@Serializable data class PlaylistPage(val items: List<Playlist>, val cursor: Long)
@Serializable private data class Items<T>(val items: List<T>)

/** A title/artist the service thinks the clip is (POST /v1/voice). */
@Serializable data class VoiceGuess(val title: String, val artist: String? = null)
/** A catalogue song matched from the clip: a [SongSummary] plus how it matched, "lyrics" or "title". */
@Serializable data class VoiceResult(
    val id: String, val title: String, val artist: String? = null, val language: String = "en",
    val categories: List<String> = emptyList(), val originalKey: String? = null, val version: Int = 1,
    val match: String = "lyrics",
)
/**
 * POST /v1/voice. [intent]: "lyrics" (sung or recited words), "request" (a spoken title or command) or "none" (no words
 * heard). [webQuery] is a short search for "Find on the web"; when the service's LLM is down it is the raw transcript.
 */
@Serializable data class VoiceReply(
    val intent: String, val transcript: String = "", val language: String? = null,
    val guesses: List<VoiceGuess> = emptyList(), val webQuery: String? = null, val results: List<VoiceResult> = emptyList(),
    /** The transcript in Latin script, as chord sites write it (Bengali/Devanagari lines); absent from older services. */
    val romanized: String? = null,
)

/** What POST /v1/songs is asked to add: hand-written ChordPro, pasted text (converted, with AI per [ai]) or a web page. */
sealed interface NewSong {
    data class ChordPro(val chordpro: String) : NewSong
    /** [ai]: "auto" (AI only when the plain conversion finds no chords), "on" or "off". */
    data class Text(val text: String, val title: String?, val artist: String?, val ai: String = "auto") : NewSong
    data class Link(val url: String) : NewSong
}
/** POST /v1/discover: a job importing up to 3 pages, or [jobId] null when the web search found nothing usable. */
@Serializable data class DiscoverStart(val jobId: String? = null, val candidates: Int = 0)
/** One page of an import job: queued, then done | review | duplicate (with [songId]) | failed | limit_reached. */
@Serializable data class JobItem(val url: String, val status: String, val songId: String? = null, val error: String? = null)
/** GET /v1/import/jobs/:id. [status]: queued | running | done. */
@Serializable data class ImportJob(
    val id: String, val status: String, val kind: String = "links", val query: String? = null,
    val total: Int = 0, val done: Int = 0, val items: List<JobItem> = emptyList(),
)
/** Why a song is flagged (POST /v1/songs/:id/report). */
enum class FlagReason(val wire: String, val label: String) {
    WrongChords("wrong_chords", "Wrong chords"),
    WrongLyrics("wrong_lyrics", "Wrong lyrics"),
    NotASong("not_a_song", "Not a song"),
    Other("other", "Something else"),
}

sealed class ApiError(message: String) : Exception(message) {
    object Unauthorized : ApiError("unauthorized")
    object TokenRevoked : ApiError("token_revoked")
    /** 403 account_disabled: an admin disabled the account (on any /v1 route). */
    object AccountDisabled : ApiError("account_disabled")
    object Offline : ApiError("offline")
    data class Http(val code: Int, val error: String?) : ApiError("http $code ${error ?: ""}")
    data class Stale(val current: Playlist) : ApiError("stale")
    /** POST /v1/songs 409: the song already exists. */
    data class Duplicate(val songId: String?) : ApiError("duplicate")
    /** 429 plan_limit_reached / search_limit_reached / voice_limit_reached: today's allowance on [plan] ([limit] a day) is used up. */
    data class Limit(val code: Int, val error: String, val limit: Int?, val plan: String?) : ApiError("limit $error")
}

/**
 * Service client. [baseUrl] yields `https://host` (or null without an account), [token] the device token.
 * [onTokenRevoked] receives the token that was rejected on a 401 token_revoked (wire to AuthStore.markRevoked(token));
 * [onAccountDisabled] the token that got 403 account_disabled (wire to AccountManager.accountDisabled(token)).
 * [urlForHost] builds the base URL for the unauthenticated account creation.
 */
class Api(
    private val baseUrl: () -> String?,
    private val token: () -> String?,
    private val client: OkHttpClient,
    private val onTokenRevoked: (String) -> Unit = {},
    private val urlForHost: (String) -> String? = { ServiceHost.baseUrl(it, BuildConfig.DEBUG) },
    private val onAccountDisabled: (String) -> Unit = {},
    /** Read timeout of POST /v1/voice (the call itself may take this plus 15 s); tests shorten it. */
    private val voiceReadTimeoutMs: Long = VOICE_READ_TIMEOUT_MS,
) {
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    /** Same pool and settings, but a 75 s read timeout: POST /v1/discover runs a web search before answering. */
    private val discoverClient: OkHttpClient by lazy {
        client.newBuilder().readTimeout(75, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).build()
    }

    private val wavType = "audio/wav".toMediaType()
    /**
     * Transcription plus one LLM call, and for Bengali/Hindi sometimes a second pass of both: longer than the default
     * 30 s read.
     */
    private val voiceClient: OkHttpClient by lazy {
        client.newBuilder().readTimeout(voiceReadTimeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(voiceReadTimeoutMs + 15_000, TimeUnit.MILLISECONDS).build()
    }

    /** A client pinned to one base URL and token, e.g. for a sync that must not switch accounts mid-way. */
    fun bound(base: String, token: String): Api =
        Api({ base }, { token }, client, onTokenRevoked, urlForHost, onAccountDisabled, voiceReadTimeoutMs)

    /** Creates an anonymous account on [host] (no token sent). 429 rate_limited arrives as [ApiError.Http]. */
    suspend fun createAnonymous(host: String, deviceName: String): AccountResponse {
        val base = urlForHost(host) ?: throw ApiError.Http(400, "invalid_host")
        val body = buildJsonObject { put("deviceName", deviceName) }
        val req = Request.Builder().url(urlOf(base, "/v1/accounts/anonymous", emptyList())).post(body.toString().toRequestBody(jsonType)).build()
        return decode(execute(req, authed = false), AccountResponse.serializer())
    }

    suspend fun me(): Me = get("/v1/me", emptyList(), Me.serializer())

    /**
     * Links this account to the Google identity in [idToken] (or merges into the account that already has it and
     * re-binds this device). 401 invalid_google_token and the 409/403/503 refusals arrive as [ApiError.Http];
     * 403 account_disabled here is about the *target* account, so it is not reported as this device's.
     */
    suspend fun linkGoogle(idToken: String): GoogleAccountResponse {
        val body = buildJsonObject { put("idToken", idToken) }
        val req = authedBuilder("/v1/accounts/google", emptyList()).post(body.toString().toRequestBody(jsonType)).build()
        return decode(execute(req, reportDisabled = false), GoogleAccountResponse.serializer())
    }

    /** Revokes this device's token on the service (sign out). */
    suspend fun deleteThisDevice() {
        execute(authedBuilder("/v1/devices/me", emptyList()).delete().build())
    }

    suspend fun search(q: String, lang: String?, cat: String?, page: Int): Page<SongSummary> =
        get("/v1/search", listOf("q" to q, "lang" to lang, "cat" to cat, "page" to page.toString()), Page.serializer(SongSummary.serializer()))

    suspend fun browse(lang: String?, cat: String?, page: Int): Page<SongSummary> =
        get("/v1/browse", listOf("lang" to lang, "cat" to cat, "page" to page.toString()), Page.serializer(SongSummary.serializer()))

    suspend fun facets(): Facets = get("/v1/facets", emptyList(), Facets.serializer())

    suspend fun song(id: String): Song = get("/v1/songs/" + seg(id), emptyList(), Song.serializer())

    /** Songs changed since [since] among [ids]; chunked to the service's 200-id limit. */
    suspend fun changes(since: Long, ids: List<String>): List<Song> =
        ids.distinct().chunked(MAX_CHANGE_IDS).flatMap { chunk ->
            get("/v1/songs/changes", listOf("since" to since.toString(), "ids" to chunk.joinToString(",")), Items.serializer(Song.serializer())).items
        }

    suspend fun addSong(chordpro: String?, text: String?, title: String?, artist: String?): Song {
        val body = buildJsonObject {
            if (chordpro != null) put("chordpro", chordpro) else {
                text?.let { put("text", it) }; title?.let { put("title", it) }; artist?.let { put("artist", it) }
            }
        }
        val req = authedBuilder("/v1/songs", emptyList()).post(body.toString().toRequestBody(jsonType)).build()
        return decode(execute(req), Song.serializer())
    }

    /** POST /v1/songs. 409 -> [ApiError.Duplicate]; 429 plan_limit_reached -> [ApiError.Limit]. Status published | review. */
    suspend fun createSong(input: NewSong): Song {
        val body = buildJsonObject {
            when (input) {
                is NewSong.ChordPro -> put("chordpro", input.chordpro)
                is NewSong.Text -> {
                    put("text", input.text)
                    input.title?.let { put("title", it) }; input.artist?.let { put("artist", it) }
                    put("ai", input.ai)
                }
                is NewSong.Link -> put("url", input.url)
            }
        }
        val req = authedBuilder("/v1/songs", emptyList()).post(body.toString().toRequestBody(jsonType)).build()
        return decode(execute(req), Song.serializer())
    }

    /**
     * Find on the web. The service searches before it answers (up to ~55 s), so this call alone gets a longer read
     * timeout. 429 plan_limit_reached / search_limit_reached -> [ApiError.Limit]; 503 search_unavailable -> Http.
     */
    suspend fun discover(q: String, lang: String?): DiscoverStart {
        val body = buildJsonObject { put("q", q); lang?.let { put("lang", it) } }
        val req = authedBuilder("/v1/discover", emptyList()).post(body.toString().toRequestBody(jsonType)).build()
        return decode(execute(req, client = discoverClient), DiscoverStart.serializer())
    }

    /**
     * Sing a line: sends a 16 kHz mono PCM16 WAV ([Wav.encode16k][dev.avijit.orbittuner.voice.Wav.encode16k]) with
     * hints from on-device history, cleaned to the service's limits ([voiceLangs], [voiceArtists]). The bytes are only
     * sent, never logged. 400 bad_audio/bad_hints and 503 voice_unavailable arrive as [ApiError.Http]; 429
     * voice_limit_reached as [ApiError.Limit]. A read or call timeout means the service is slow, not that the phone is
     * offline: it arrives as [ApiError.Http] 504 "timeout" (connection failures stay [ApiError.Offline]).
     */
    suspend fun voice(wav: ByteArray, langs: List<String>, artists: List<String>): VoiceReply {
        val query = listOf(
            "langs" to voiceLangs(langs).joinToString(",").ifEmpty { null },
            "artists" to voiceArtists(artists).joinToString(",").ifEmpty { null },
        )
        val req = authedBuilder("/v1/voice", query).post(wav.toRequestBody(wavType)).build()
        return decode(execute(req, client = voiceClient, timeoutIsOffline = false), VoiceReply.serializer())
    }

    suspend fun importJob(id: String): ImportJob = get("/v1/import/jobs/" + seg(id), emptyList(), ImportJob.serializer())

    /** Flags a song: 204. 400 own_song, 404 and 429 flag_rate_limited arrive as [ApiError.Http]. A blank [note] is left out. */
    suspend fun flag(id: String, reason: FlagReason, note: String?) {
        val body = buildJsonObject {
            put("reason", reason.wire)
            note?.trim()?.takeIf { it.isNotEmpty() }?.let { put("note", it.take(MAX_NOTE)) }
        }
        val req = authedBuilder("/v1/songs/${seg(id)}/report", emptyList()).post(body.toString().toRequestBody(jsonType)).build()
        execute(req)
    }

    suspend fun report(id: String, note: String) {
        val body = buildJsonObject { put("note", note) }
        val req = authedBuilder("/v1/songs/${seg(id)}/report", emptyList()).post(body.toString().toRequestBody(jsonType)).build()
        execute(req)
    }

    suspend fun getFavourites(): List<FavouriteItem> =
        get("/v1/me/favourites", emptyList(), Items.serializer(FavouriteItem.serializer())).items

    suspend fun putFavourites(items: List<FavouriteItem>): List<FavouriteItem> {
        val body = WireJson.encodeToString(Items.serializer(FavouriteItem.serializer()), Items(items))
        val req = authedBuilder("/v1/me/favourites", emptyList()).put(body.toRequestBody(jsonType)).build()
        return decode(execute(req), Items.serializer(FavouriteItem.serializer())).items
    }

    /** [since] must be a cursor previously returned by the service (0 for everything); never device time. */
    suspend fun getPlaylists(since: Long): PlaylistPage =
        get("/v1/me/playlists", listOf("since" to since.toString()), PlaylistPage.serializer())

    /** 409 stale -> [ApiError.Stale] carrying the server's current playlist. */
    suspend fun putPlaylist(p: Playlist): Playlist {
        val body = WireJson.encodeToString(Playlist.serializer(), p)
        val req = authedBuilder("/v1/me/playlists/" + seg(p.id), emptyList()).put(body.toRequestBody(jsonType)).build()
        return decode(execute(req), Playlist.serializer())
    }

    suspend fun exportZip(ids: List<String>): ByteArray {
        val req = authedBuilder("/v1/export", listOf("ids" to ids.joinToString(","))).get().build()
        return execute(req).second
    }

    // ---- plumbing ----

    private suspend fun <T> get(path: String, query: List<Pair<String, String?>>, ser: KSerializer<T>): T =
        decode(execute(authedBuilder(path, query).get().build()), ser)

    private fun seg(s: String): String {
        if (s.isEmpty() || s == "." || s == "..") throw ApiError.Http(400, "invalid_id")
        return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }

    private fun urlOf(base: String, path: String, query: List<Pair<String, String?>>): HttpUrl {
        val b = (base.trimEnd('/') + path).toHttpUrlOrNull()?.newBuilder() ?: throw ApiError.Http(400, "invalid_url")
        query.forEach { (k, v) -> if (v != null) b.addQueryParameter(k, v) }
        return b.build()
    }

    private fun authedBuilder(path: String, query: List<Pair<String, String?>>): Request.Builder {
        val base = baseUrl()
        val t = token()
        if (base == null || t == null) throw ApiError.Unauthorized
        return Request.Builder().url(urlOf(base, path, query)).header("Authorization", "Bearer $t")
    }

    private fun <T> decode(res: Pair<Int, ByteArray>, ser: KSerializer<T>): T = try {
        WireJson.decodeFromString(ser, res.second.decodeToString())
    } catch (_: SerializationException) {
        throw ApiError.Http(res.first, "bad_response")
    } catch (_: IllegalArgumentException) {
        throw ApiError.Http(res.first, "bad_response")
    }

    /**
     * Runs the call; returns (code, body) for 2xx, otherwise throws the mapped [ApiError]. With [timeoutIsOffline] false
     * a read/call timeout ([InterruptedIOException], incl. [java.net.SocketTimeoutException]) once connected is
     * [ApiError.Http] 504 "timeout" instead of [ApiError.Offline].
     */
    private suspend fun execute(
        req: Request, authed: Boolean = true, reportDisabled: Boolean = authed, client: OkHttpClient = this.client,
        timeoutIsOffline: Boolean = true,
    ): Pair<Int, ByteArray> {
        val (code, bytes) = try {
            await(client.newCall(req))
        } catch (e: InterruptedIOException) {
            // A connect timeout is still "can't reach the service"; a read/call timeout means it is slow.
            val connecting = e.message?.contains("connect", ignoreCase = true) == true
            if (timeoutIsOffline || connecting) throw ApiError.Offline else throw ApiError.Http(504, "timeout")
        } catch (_: IOException) {
            throw ApiError.Offline
        }
        if (code in 200..299) return code to bytes
        val obj = try { WireJson.parseToJsonElement(bytes.decodeToString()) as? JsonObject } catch (_: Exception) { null }
        val error = (obj?.get("error") as? JsonPrimitive)?.contentOrNull
        when {
            code == 401 && error == "token_revoked" -> {
                if (authed) req.header("Authorization")?.removePrefix("Bearer ")?.let(onTokenRevoked)
                throw ApiError.TokenRevoked
            }
            code == 401 && error == "invalid_google_token" -> throw ApiError.Http(code, error)
            code == 401 -> throw ApiError.Unauthorized
            code == 403 && error == "account_disabled" -> {
                if (reportDisabled) req.header("Authorization")?.removePrefix("Bearer ")?.let(onAccountDisabled)
                throw ApiError.AccountDisabled
            }
            code == 409 && error == "stale" -> {
                val cur = obj?.get("current")?.let { runCatching { WireJson.decodeFromJsonElement(Playlist.serializer(), it) }.getOrNull() }
                if (cur != null) throw ApiError.Stale(cur)
            }
            code == 409 && error == "duplicate" -> throw ApiError.Duplicate((obj?.get("songId") as? JsonPrimitive)?.contentOrNull)
            code == 429 && (error == "plan_limit_reached" || error == "search_limit_reached" || error == "voice_limit_reached") -> throw ApiError.Limit(
                code, error, (obj?.get("limit") as? JsonPrimitive)?.contentOrNull?.toIntOrNull(), (obj?.get("plan") as? JsonPrimitive)?.contentOrNull,
            )
        }
        throw ApiError.Http(code, error)
    }

    /** Cancelling the coroutine cancels the OkHttp call; error bodies are read up to [MAX_ERROR_BODY] bytes. */
    private suspend fun await(call: Call): Pair<Int, ByteArray> = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWith(Result.failure(e))
            }
            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use { r ->
                        val src = r.body?.source()
                        val bytes = when {
                            src == null -> ByteArray(0)
                            r.isSuccessful -> src.readByteArray()
                            else -> { src.request(MAX_ERROR_BODY); src.buffer.readByteArray(minOf(src.buffer.size, MAX_ERROR_BODY)) }
                        }
                        Result.success(r.code to bytes)
                    }
                } catch (e: IOException) { Result.failure(e) }
                if (!cont.isCancelled) cont.resumeWith(result)
            }
        })
    }

    companion object {
        private const val MAX_CHANGE_IDS = 200
        private const val MAX_ERROR_BODY = 64L * 1024
        private const val MAX_NOTE = 500
        private val SPACES = Regex("[\\s\\p{Cc}]+")

        /** Languages the voice endpoint accepts as hints. */
        val VOICE_LANGS = setOf("en", "hi", "bn", "ta", "te", "ml", "kn", "mr", "gu", "pa", "ur")
        const val MAX_VOICE_LANGS = 3
        const val MAX_VOICE_ARTISTS = 5
        const val MAX_VOICE_ARTIST_CHARS = 60
        /** Up to two transcriptions and two LLM calls on the service (its own budget is about 42 s). */
        const val VOICE_READ_TIMEOUT_MS = 60_000L

        /** Up to 3 distinct known language codes, lowercased, in order. */
        fun voiceLangs(langs: List<String>): List<String> =
            langs.map { it.trim().lowercase() }.filter { it in VOICE_LANGS }.distinct().take(MAX_VOICE_LANGS)

        /**
         * Up to 5 distinct artist names, each at most 60 chars. Commas (the list separator) and control characters
         * (which the service rejects with bad_hints) become spaces; runs of spaces collapse.
         */
        fun voiceArtists(artists: List<String>): List<String> =
            artists.map { it.replace(',', ' ').replace(SPACES, " ").trim().take(MAX_VOICE_ARTIST_CHARS).trim() }
                .filter { it.isNotEmpty() }.distinct().take(MAX_VOICE_ARTISTS)
    }
}
