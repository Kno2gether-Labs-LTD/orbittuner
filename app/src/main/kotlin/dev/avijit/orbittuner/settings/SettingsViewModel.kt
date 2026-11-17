package dev.avijit.orbittuner.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.avijit.orbittuner.BuildConfig
import dev.avijit.orbittuner.data.LibraryRepository
import dev.avijit.orbittuner.net.AccountManager
import dev.avijit.orbittuner.net.AccountState
import dev.avijit.orbittuner.net.AuthStore
import dev.avijit.orbittuner.net.GoogleSignIn
import dev.avijit.orbittuner.net.GoogleSignInOutcome
import dev.avijit.orbittuner.net.ServiceHost
import dev.avijit.orbittuner.songview.DiagramInstrument
import dev.avijit.orbittuner.songview.DiagramPrefs
import dev.avijit.orbittuner.songview.InMemoryDiagramPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Settings: the account (plan, AI allowance, sign out, sync status), the app preferences and, in debug builds, the
 * service host override. [requestSync] asks for a background sync (SyncScheduler in production).
 */
class SettingsViewModel(
    private val prefs: Prefs,
    auth: AuthStore,
    private val accounts: AccountManager,
    library: LibraryRepository,
    private val requestSync: () -> Unit,
    private val diagramPrefs: DiagramPrefs = InMemoryDiagramPrefs(),
    val version: String = BuildConfig.VERSION_NAME,
    /** Shows Settings > Developer (the service host override). Release builds never do. */
    val debug: Boolean = BuildConfig.DEBUG,
    val defaultHost: String = BuildConfig.SERVICE_HOST,
    /** Runs sign-out and host changes (the app scope in production) so leaving Settings can't cancel them midway. */
    private val actionScope: CoroutineScope? = null,
    /** Null when the build has no Google web client ID: the button is hidden. */
    private val googleSignIn: GoogleSignIn? = null,
) : ViewModel() {

    private fun <T> Flow<T>.state(initial: T): StateFlow<T> = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val a4: StateFlow<Double> = prefs.a4.state(Prefs.DEFAULT_A4)
    val noteNaming: StateFlow<NoteNaming> = prefs.noteNaming.state(NoteNaming.Letters)
    val accidental: StateFlow<AccidentalPref> = prefs.accidental.state(AccidentalPref.Sharps)
    val meterStyle: StateFlow<MeterStyle> = prefs.meterStyle.state(MeterStyle.Guide)
    /** Same app-wide choice the diagram panel makes. */
    val instrument: StateFlow<DiagramInstrument> = diagramPrefs.instrument.map { it ?: DiagramInstrument.Guitar }.state(DiagramInstrument.Guitar)
    val theme: StateFlow<ThemeMode> = prefs.theme.state(ThemeMode.System)
    val keepScreenOn: StateFlow<Boolean> = prefs.keepScreenOn.state(false)

    val account: StateFlow<AccountState> = accounts.state

    /** The service the account lives on. */
    val accountHost: StateFlow<String?> = auth.host

    /** A revoked account was replaced and this device's favourites/playlists were cleared (until dismissed). */
    val resetNotice: StateFlow<Boolean> = prefs.accountResetNotice.state(false)
    fun dismissResetNotice() = launch { prefs.setAccountResetNotice(false) }

    /** Debug override (null: [defaultHost]). */
    val hostOverride: StateFlow<String?> = prefs.serviceHostOverride.state(null)

    /** Device time of the last successful sync (null: not yet with this account). */
    val lastSync: StateFlow<Long?> = library.lastSync().state(null)

    /** Playlists the service rejected on their last push. */
    val syncErrors: StateFlow<Int> = library.playlists().map { all -> all.count { it.playlist.syncError != null } }.state(0)

    init { accounts.refresh() }

    /** False when this build has no Google client ID ("Google sign-in isn't set up yet"). */
    val googleAvailable: Boolean = googleSignIn != null
    private val _googleBusy = MutableStateFlow(false)
    val googleBusy: StateFlow<Boolean> = _googleBusy.asStateFlow()
    /** Why the last Google sign-in failed (null: nothing to show; a cancelled picker shows nothing). */
    private val _googleError = MutableStateFlow<String?>(null)
    val googleError: StateFlow<String?> = _googleError.asStateFlow()
    /** Rejected local changes would be lost by a merge: waiting for the person to continue or cancel. */
    private val _googleConfirm = MutableStateFlow<GoogleSignInOutcome.NeedsConfirmation?>(null)
    val googleConfirm: StateFlow<GoogleSignInOutcome.NeedsConfirmation?> = _googleConfirm.asStateFlow()

    /**
     * Shows the Google account picker over [activity] and links (or merges) the account. Runs in the app scope so
     * leaving Settings can't stop it between the server-side merge and saving the new identity.
     */
    fun signInWithGoogle(activity: Context) {
        val g = googleSignIn ?: return
        runGoogle { g.signIn(activity) }
    }

    /** The person accepted losing the rejected changes: link now. */
    fun confirmGoogleSignIn() {
        val g = googleSignIn ?: return
        val pending = _googleConfirm.value ?: return
        _googleConfirm.value = null
        runGoogle { g.confirm(pending) }
    }

    fun cancelGoogleSignIn() { _googleConfirm.value = null }

    private fun runGoogle(step: suspend () -> GoogleSignInOutcome) {
        if (_googleBusy.value) return
        _googleBusy.value = true
        _googleError.value = null
        (actionScope ?: viewModelScope).launch {
            try {
                when (val out = step()) {
                    is GoogleSignInOutcome.Failed -> _googleError.value = out.message
                    is GoogleSignInOutcome.NeedsConfirmation -> _googleConfirm.value = out
                    GoogleSignInOutcome.Cancelled, is GoogleSignInOutcome.SignedIn -> Unit
                }
            } finally {
                _googleBusy.value = false
            }
        }
    }

    fun setA4(hz: Double) = launch { prefs.setA4(hz) }
    fun setNoteNaming(v: NoteNaming) = launch { prefs.setNoteNaming(v) }
    fun setAccidental(v: AccidentalPref) = launch { prefs.setAccidental(v) }
    fun setMeterStyle(v: MeterStyle) = launch { prefs.setMeterStyle(v) }
    fun setInstrument(v: DiagramInstrument) = launch { diagramPrefs.setInstrument(v) }
    fun setTheme(v: ThemeMode) = launch { prefs.setTheme(v) }
    fun setKeepScreenOn(on: Boolean) = launch { prefs.setKeepScreenOn(on) }

    /** Revokes this device, wipes this account's favourites and playlists here, and starts a fresh anonymous account. */
    fun signOut() { (actionScope ?: viewModelScope).launch { accounts.signOut() } }

    fun syncNow() = requestSync()

    /**
     * Debug builds: points new accounts at [host] (blank: back to [defaultHost]). Changing it signs out locally, so the
     * next account is made there. False (and nothing changes) for an invalid host or in a release build.
     */
    fun setServiceHost(host: String): Boolean {
        if (!debug) return false
        val h = host.trim().lowercase()
        if (h.isNotEmpty() && !ServiceHost.isValid(h)) return false
        val next = h.takeIf { it.isNotEmpty() && it != defaultHost }
        (actionScope ?: viewModelScope).launch {
            if (next == prefs.serviceHostOverride.first()) return@launch
            prefs.setServiceHostOverride(next)
            accounts.signOutLocally()
        }
        return true
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
