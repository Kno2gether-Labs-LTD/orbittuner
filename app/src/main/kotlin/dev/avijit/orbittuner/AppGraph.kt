package dev.avijit.orbittuner

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import dev.avijit.orbittuner.data.LibraryRepository
import dev.avijit.orbittuner.data.SongRepository
import dev.avijit.orbittuner.data.SyncScheduler
import dev.avijit.orbittuner.data.SyncSession
import dev.avijit.orbittuner.data.asService
import dev.avijit.orbittuner.data.categories
import dev.avijit.orbittuner.data.ftsMatch
import dev.avijit.orbittuner.data.syncIdentity
import dev.avijit.orbittuner.data.db.AppDb
import dev.avijit.orbittuner.backing.BackingEngine
import dev.avijit.orbittuner.metronome.MetronomeEngine
import dev.avijit.orbittuner.net.Api
import dev.avijit.orbittuner.net.AuthStore
import dev.avijit.orbittuner.net.EncryptedKv
import dev.avijit.orbittuner.net.AccountManager
import dev.avijit.orbittuner.net.CredentialManagerIdTokenProvider
import dev.avijit.orbittuner.net.GoogleSignIn
import dev.avijit.orbittuner.net.ServiceHost
import dev.avijit.orbittuner.net.asAccounts
import dev.avijit.orbittuner.net.SecureKv
import dev.avijit.orbittuner.settings.Prefs
import dev.avijit.orbittuner.tuner.AudioRecordSource
import dev.avijit.orbittuner.tuner.Instrument
import dev.avijit.orbittuner.tuner.TunerEngine
import dev.avijit.orbittuner.tuner.Tunings
import dev.avijit.orbittuner.voice.HistorySignals
import dev.avijit.orbittuner.voice.VoiceHit
import dev.avijit.orbittuner.voice.VoiceRecorder
import dev.avijit.orbittuner.voice.VoiceSearch
import dev.avijit.orbittuner.voice.loadHistorySignals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual DI holder; later tasks add repositories and services here. Members are lazy so tests stay cheap. */
class AppGraph(val context: Context, private val authKv: () -> SecureKv = { EncryptedKv(context.applicationContext) }) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** [authKv] is swappable because Robolectric has no AndroidKeyStore (shell tests pass an in-memory store). */
    val auth: AuthStore by lazy { AuthStore(authKv()) }
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
            .build()
    }
    val api: Api by lazy {
        Api(
            baseUrl = { auth.host.value?.let { ServiceHost.baseUrl(it, BuildConfig.DEBUG) } },
            token = { auth.token() },
            client = httpClient,
            onTokenRevoked = { token -> auth.markRevoked(token) },
            onAccountDisabled = { token -> accounts.accountDisabled(token) },
        )
    }

    val prefs: Prefs by lazy { Prefs(context) }

    /** The service new accounts are made on: BuildConfig.SERVICE_HOST, or (debug builds only) the Settings override. */
    suspend fun serviceHost(): String =
        (if (BuildConfig.DEBUG) prefs.serviceHostOverride.first()?.takeIf(ServiceHost::isValid) else null) ?: BuildConfig.SERVICE_HOST

    val accounts: AccountManager by lazy {
        AccountManager(
            api.asAccounts(), auth, appScope, serviceHost = ::serviceHost,
            deviceName = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android device",
            onRevokedReset = { prefs.setAccountResetNotice(true) },
            // A lost Google re-bind is repaired from /v1/me: push dirty edits to the merged account first.
            beforeIdentityRepair = { library.sync() },
        )
    }

    /** "Sign in with Google"; null when this build has no GOOGLE_WEB_CLIENT_ID (the button is hidden). */
    val googleSignIn: GoogleSignIn? by lazy {
        BuildConfig.GOOGLE_WEB_CLIENT_ID.takeIf { it.isNotBlank() }?.let { clientId ->
            // Local edits are pushed first, so a server-side merge carries them into the Google account.
            GoogleSignIn(
                CredentialManagerIdTokenProvider(clientId), accounts,
                pushLocal = { library.sync() }, unsynced = { library.unsyncedChanges() },
            )
        }
    }

    /** App start (MainActivity): create the account if needed (retrying while offline) and refresh /v1/me. Idempotent. */
    fun start() {
        try {
            accounts.start()
            library // starts the identity watcher, so an account switch wipes the old library at once
        } catch (e: Exception) {
            // The key store can be unavailable (e.g. Robolectric); the tuner and local features must still work.
            Log.w("AppGraph", "accounts not started", e)
        }
    }
    val instruments: List<Instrument> by lazy { Tunings.load(context) }
    val tuner: TunerEngine by lazy {
        TunerEngine(AudioRecordSource(context), { prefs.a4State.value }, { SystemClock.elapsedRealtime() })
    }

    /** Starting the metronome stops a backing track and vice versa. */
    private val playback = PlaybackCoordinator()

    /** The one metronome: the Tools tab and the song sheet share it, so they can never click over each other. */
    val metronome: MetronomeEngine by lazy {
        lateinit var engine: MetronomeEngine
        engine = MetronomeEngine(onStart = { playback.starting(engine) })
        playback.register(engine) { engine.stop() }
        engine
    }

    /** The one backing-track player (Tools tab). */
    val backing: BackingEngine by lazy {
        lateinit var engine: BackingEngine
        engine = BackingEngine(onStart = { playback.starting(engine) })
        playback.register(engine) { engine.stop() }
        engine
    }

    val db: AppDb by lazy { AppDb.open(context) }
    val songs: SongRepository by lazy { SongRepository(api.asService(), db, appScope) }
    val library: LibraryRepository by lazy {
        val repo = LibraryRepository(
            db, songs,
            requestSync = { SyncScheduler.requestSafely(context) },
            identity = { auth.syncIdentity() },
            session = {
                auth.snapshot()?.let { a ->
                    ServiceHost.baseUrl(a.host, BuildConfig.DEBUG)?.let { base ->
                        SyncSession("${a.host}|${a.userId}", api.bound(base, a.token).asService(), a.token)
                    }
                }
            },
        )
        // Keyed on the identity value (host|userId) plus revoked state: an A -> B switch (new account, sign-out, revoke)
        // wipes A's library at once (before UI writes land), and a new account requests a sync.
        appScope.launch {
            combine(auth.host, auth.user, auth.needsNewAccount) { _, _, revoked -> auth.syncIdentity() to revoked }
                .distinctUntilChanged()
                .collect { (_, revoked) -> repo.accountChanged(requestSync = !revoked) }
        }
        repo
    }

    // ---- Sing a line, history ----

    /** On-device taste for ranking voice results and Explore "For you" (E1). Local only. */
    suspend fun historySignals(): HistorySignals = loadHistorySignals(db, prefs.browsedCategories.first())

    /** Explore: count one browse of category [name] towards [HistorySignals.categoryCounts]. Fire-and-forget. */
    fun recordBrowsedCategory(name: String) {
        appScope.launch { prefs.recordBrowsedCategory(name) }
    }

    /** A recorder on its own AudioRecordSource; it holds the mic only while recording. Needs RECORD_AUDIO granted. */
    fun voiceRecorder(): VoiceRecorder = VoiceRecorder(AudioRecordSource(context))

    val voiceSearch: VoiceSearch by lazy {
        VoiceSearch(
            api = api.asService(),
            library = { q ->
                val match = ftsMatch(q)
                if (match == null) emptyList() else db.songs().search(match).first().filter { it.yours }.map { h ->
                    val s = h.song
                    VoiceHit(s.id, s.title, s.artist, s.language, s.categories(), s.originalKey, VoiceHit.LIBRARY)
                }
            },
            history = ::historySignals,
            sheet = { id -> songs.cache(id).chordpro },
        )
    }
}
