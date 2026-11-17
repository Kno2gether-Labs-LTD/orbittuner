package dev.avijit.orbittuner.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** The account endpoints [AccountManager] uses; [Api] in production. Errors are [ApiError]. */
interface AccountService {
    suspend fun createAnonymous(host: String, deviceName: String): AccountResponse
    suspend fun me(): Me
    suspend fun deleteThisDevice()
    /** POST /v1/accounts/google with this device's token. */
    suspend fun linkGoogle(idToken: String): GoogleAccountResponse
}

fun Api.asAccounts(): AccountService = object : AccountService {
    override suspend fun createAnonymous(host: String, deviceName: String) = this@asAccounts.createAnonymous(host, deviceName)
    override suspend fun me() = this@asAccounts.me()
    override suspend fun deleteThisDevice() = this@asAccounts.deleteThisDevice()
    override suspend fun linkGoogle(idToken: String) = this@asAccounts.linkGoogle(idToken)
}

/** A completed Google link: the account's Google [email], and whether this device now belongs to another account. */
data class GoogleLinkResult(val email: String?, val switchedAccount: Boolean)

sealed interface AccountState {
    /** No account yet (first launch offline, or the service can't create one right now); retried with backoff. */
    object None : AccountState
    object Creating : AccountState
    /** Signed in. [me] is the latest /v1/me (null until it loads, e.g. while offline). */
    data class Ready(val me: Me?) : AccountState
    /** An admin disabled the account: nothing syncs, and no new account is made automatically. */
    object Disabled : AccountState
}

/** Retry delay for account creation: 2 s doubling to 5 min; at least 1 min after 429 rate_limited. */
fun accountBackoffMs(attempt: Int, error: ApiError?): Long {
    val exp = 2_000L shl attempt.coerceIn(0, 10)
    val base = if (error is ApiError.Http && error.code == 429) maxOf(exp, 60_000L) else exp
    return base.coerceAtMost(5 * 60_000L)
}

/**
 * Every install has an account: an anonymous one is created automatically (`POST /v1/accounts/anonymous`) and its
 * token kept in [auth]. [ensureAccount] is idempotent and single-flight; [start] keeps retrying with backoff while
 * there's no account (offline, 429, or a service without accounts yet).
 *
 * A revoked token (AuthStore.markRevoked) is replaced by a fresh anonymous account: auth is cleared first, so the
 * library's identity rules wipe the old account's favourites and playlists. A disabled account is never replaced
 * automatically. Local data made while there was no account at all is adopted by the next account (see
 * LibraryRepository.reconcileAccount).
 */
class AccountManager(
    private val service: AccountService,
    private val auth: AuthStore,
    private val scope: CoroutineScope,
    private val serviceHost: suspend () -> String,
    private val deviceName: String,
    private val backoffMs: (attempt: Int, error: ApiError?) -> Long = ::accountBackoffMs,
    /** Called once each time a revoked account is dropped (its local favourites/playlists get wiped): show a notice. */
    private val onRevokedReset: suspend () -> Unit = {},
    /**
     * Best-effort push of dirty local edits (LibraryRepository.sync) before a lost re-bind is repaired: the token
     * already belongs to the merged account, so they land there before the local library is wiped.
     */
    private val beforeIdentityRepair: suspend () -> Unit = {},
) {
    private val mutex = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val creating = MutableStateFlow(false)
    private val disabled = MutableStateFlow(false)
    private val me = MutableStateFlow<Me?>(null)
    private var lastError: ApiError? = null
    private var started = false

    private val _state = MutableStateFlow(compute())
    val state: StateFlow<AccountState> = _state.asStateFlow()

    private fun compute(): AccountState = when {
        disabled.value -> AccountState.Disabled
        auth.snapshot() != null -> AccountState.Ready(me.value?.takeIf { it.id == auth.user.value?.id })
        creating.value -> AccountState.Creating
        else -> AccountState.None
    }

    private fun update() { _state.value = compute() }

    private fun needsAccount() = !disabled.value && auth.token() == null

    /**
     * Makes sure there is a usable account, creating one if needed. True when signed in. Never throws [ApiError]:
     * failures (offline, 429, 404 from a service without accounts) return false and the [start] loop retries.
     */
    suspend fun ensureAccount(): Boolean = mutex.withLock { ensureLocked() }

    private suspend fun ensureLocked(): Boolean {
        if (disabled.value) return false
        if (auth.token() != null) return true
        // Revoked: forget the old account first, so its private data is wiped before a new one exists.
        if (auth.needsNewAccount.value) {
            auth.clear(); me.value = null
            try { onRevokedReset() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        creating.value = true; update()
        return try {
            val host = serviceHost()
            val r = service.createAnonymous(host, deviceName)
            auth.save(host, r.deviceToken, r.user)
            lastError = null
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            lastError = e
            false
        } catch (e: Exception) {
            lastError = ApiError.Http(-1, "unexpected")
            false
        } finally {
            creating.value = false; update()
        }
    }

    /** Starts the background work once: account creation with backoff, revoke handling and /v1/me refreshes. */
    fun start() {
        if (started) return
        started = true
        // Mirror auth changes made elsewhere (revoked by an API call, saved, cleared) into [state].
        scope.launch { combine(auth.host, auth.user, auth.needsNewAccount) { _, _, _ -> }.collect { update() } }
        // Fresh /v1/me whenever the signed-in user changes (start, new account, sign-out).
        scope.launch {
            auth.user.map { it?.id }.distinctUntilChanged().collect { if (auth.snapshot() != null) refreshMe() }
        }
        scope.launch {
            var attempt = 0
            while (true) {
                if (!needsAccount()) {
                    attempt = 0
                    combine(auth.host, auth.needsNewAccount, disabled) { _, _, _ -> }.first { needsAccount() }
                    continue
                }
                if (ensureAccount()) { attempt = 0; continue }
                // Wait out the backoff, or less if the user signs out / changes host meanwhile.
                while (wake.tryReceive().isSuccess) { /* drop stale wakes */ }
                withTimeoutOrNull(backoffMs(attempt++, lastError)) { wake.receive() }
            }
        }
    }

    /** Re-reads /v1/me in the background (after AI actions, or when Settings opens). */
    fun refresh() { scope.launch { refreshMe() } }

    suspend fun refreshMe() {
        val token = auth.token() ?: return
        val userId = auth.user.value?.id
        try {
            var m = service.me()
            // The identity changed while /v1/me was in flight (e.g. linkGoogle re-bound it): this answer may be stale.
            if (auth.user.value?.id != userId) return update()
            if (auth.token() == token && userId != m.id) m = repairIdentity(token, m) ?: return update()
            if (auth.token() == token && auth.user.value?.id == m.id) { me.value = m; disabled.value = false }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError.AccountDisabled) {
            accountDisabled(token)
        } catch (_: ApiError) {
            // Offline, revoked (handled by AuthStore) or a service without /v1/me: keep what we have.
        }
        update()
    }

    /**
     * /v1/me says this token belongs to another user: the service re-bound the device (a Google merge) but the link
     * response was lost. Save that user, so the library identity changes and the app wipes and re-pulls, as after a
     * normal re-bind. Dirty edits are pushed first ([beforeIdentityRepair], at most 20 s); with the token now the merged
     * account's, they reach it. /v1/me is re-read under the lock and the repair happens only if it still disagrees and
     * nothing changed meanwhile. Returns the fresh /v1/me to show, or null to show nothing from this refresh.
     */
    private suspend fun repairIdentity(token: String, stale: Me): Me? = mutex.withLock {
        // [stale] was read outside the lock; a linkGoogle may have re-bound since. Ask again, under the lock.
        if (auth.token() != token || auth.user.value?.id == stale.id) return@withLock null
        val fresh = service.me()
        val before = auth.user.value?.id
        if (auth.token() != token || before == fresh.id) return@withLock fresh
        try {
            withTimeoutOrNull(REPAIR_PUSH_TIMEOUT_MS) { beforeIdentityRepair() }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        val host = auth.host.value ?: return@withLock null
        if (auth.token() != token || auth.user.value?.id != before) return@withLock null
        auth.save(host, token, UserDto(fresh.id, role = fresh.role, plan = fresh.plan))
        fresh
    }

    /** A call with [token] got 403 account_disabled. Ignored when the token is no longer the current one. */
    fun accountDisabled(token: String) {
        if (auth.token() != token) return
        disabled.value = true
        update()
    }

    /**
     * Sign out of this device: revokes the token on the service when reachable (best effort; offline still signs
     * out here), forgets the account (the library wipes its favourites and playlists) and starts a fresh one.
     */
    suspend fun signOut() {
        val ok = mutex.withLock {
            if (auth.token() != null) {
                try { service.deleteThisDevice() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
            forgetLocked()
            ensureLocked()
        }
        if (!ok) wake.trySend(Unit)
    }

    /**
     * Links the current account to Google ([idToken] from Credential Manager). When the service merged this account
     * into the one that already holds that Google identity, the device is re-bound: the new user is saved (same token
     * value), which changes the library identity, so the app wipes local private data and re-pulls the merged account.
     * Throws [ApiError] (nothing changes here); [ApiError.Unauthorized] without an account.
     */
    suspend fun linkGoogle(idToken: String): GoogleLinkResult {
        val result: GoogleLinkResult? = mutex.withLock {
            val before = auth.snapshot() ?: throw ApiError.Unauthorized
            val r = try {
                service.linkGoogle(idToken)
            } catch (e: ApiError.AccountDisabled) {
                // Usually the target account; but it may be this one: let /v1/me decide (after the lock).
                null
            } ?: return@withLock null
            // The token can only have changed meanwhile by being revoked (the call then failed): still ours.
            if (auth.snapshot() != before) throw ApiError.Unauthorized
            val switched = r.user.id != before.userId
            val user = r.user.toUserDto()
            if (switched || auth.user.value?.let { it.role != user.role || it.plan != user.plan } == true) {
                auth.save(before.host, r.deviceToken ?: before.token, user)
            }
            // Show the email at once (even if /v1/me can't be reached right now).
            me.value = me.value?.takeIf { it.id == r.user.id }?.copy(google = r.user.google)
            update()
            GoogleLinkResult(r.user.google?.email, switched)
        }
        refreshMe()
        return result ?: throw ApiError.AccountDisabled
    }

    private companion object { const val REPAIR_PUSH_TIMEOUT_MS = 20_000L }

    /** Forgets the account on this device only (e.g. the service host changed), then starts a fresh one. */
    suspend fun signOutLocally() {
        val ok = mutex.withLock { forgetLocked(); ensureLocked() }
        if (!ok) wake.trySend(Unit)
    }

    private fun forgetLocked() {
        auth.clear()
        me.value = null
        disabled.value = false
        update()
    }
}
