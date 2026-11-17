package dev.avijit.orbittuner.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory account endpoints: each created account gets user u<n> and token tok<n>. */
class FakeAccounts : AccountService {
    var failWith: ApiError? = null
    var meFails: ApiError? = null
    /** When set, createAnonymous waits for it (to hold a creation in flight). */
    var gate: CompletableDeferred<Unit>? = null
    val created = ArrayList<Pair<String, String>>()
    val deleted = ArrayList<String?>()
    var tokenSeen: () -> String? = { null }
    var plan = "free"
    var aiLeft = 2
    /** Google email /v1/me reports (set by a successful [linkGoogle]). */
    var googleEmail: String? = null
    /** /v1/me user id override (after a merge the same token belongs to another user). */
    var meUserId: String? = null
    val googleCalls = ArrayList<String>()
    /** One-shot: the next me() builds its answer, then waits for this before returning it (a slow, stale response). */
    var meGate: CompletableDeferred<Unit>? = null
    var meCalls = 0
    /** Per-call /v1/me user id (wins over [meUserId]). */
    var meUserIdFor: (() -> String)? = null
    /** POST /v1/accounts/google: by default links the current user (no deviceToken). */
    var google: suspend (idToken: String) -> GoogleAccountResponse = { _ ->
        val n = tokenSeen()?.removePrefix("tok") ?: throw ApiError.Unauthorized
        googleEmail = "sam@example.com"
        GoogleAccountResponse(null, GoogleUser("u$n", "user", "free", GoogleLink("sam@example.com")))
    }

    override suspend fun createAnonymous(host: String, deviceName: String): AccountResponse {
        gate?.await()
        failWith?.let { throw it }
        created += host to deviceName
        val n = created.size
        return AccountResponse("tok$n", UserDto("u$n", role = "user", plan = "free"))
    }

    override suspend fun me(): Me {
        meCalls++
        meFails?.let { throw it }
        val n = tokenSeen()?.removePrefix("tok") ?: throw ApiError.Unauthorized
        val m = Me(meUserIdFor?.invoke() ?: meUserId ?: "u$n", "user", plan, google = googleEmail?.let { GoogleLink(it) }, aiLeftToday = aiLeft, aiDailyLimit = 3)
        meGate?.let { meGate = null; it.await() }
        return m
    }

    override suspend fun linkGoogle(idToken: String): GoogleAccountResponse {
        googleCalls += idToken
        return google(idToken)
    }

    override suspend fun deleteThisDevice() {
        failWith?.let { throw it }
        deleted += tokenSeen()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AccountManagerTest {
    private val auth = AuthStore(InMemoryKv())
    private val service = FakeAccounts().also { s -> s.tokenSeen = { auth.token() } }
    private var host = "tuner.orbitapps.space"

    private var resets = 0
    private fun TestScope.manager() =
        AccountManager(service, auth, backgroundScope, serviceHost = { host }, deviceName = "Pixel 8", onRevokedReset = { resets++ })

    @Test fun firstLaunchCreatesAnAccountAndSavesTheToken() = runTest {
        val m = manager()
        m.start()
        runCurrent()
        assertEquals(listOf("tuner.orbitapps.space" to "Pixel 8"), service.created)
        assertEquals("tok1", auth.token())
        assertEquals("tuner.orbitapps.space", auth.host.value)
        assertEquals("u1", auth.user.value?.id)
        val s = m.state.value
        assertTrue("$s", s is AccountState.Ready)
        assertEquals(2, (s as AccountState.Ready).me?.aiLeftToday)
    }

    @Test fun anExistingAccountIsKeptAndRefreshedOnStart() = runTest {
        auth.save("tuner.orbitapps.space", "tok7", UserDto("u7", "Owner", "owner"))
        val m = manager()
        m.start()
        runCurrent()
        assertTrue(service.created.isEmpty())
        assertEquals("u7", (m.state.value as AccountState.Ready).me?.id)
    }

    @Test fun ensureAccountIsSingleFlight() = runTest {
        val m = manager()
        service.gate = CompletableDeferred()
        val a = async { m.ensureAccount() }
        val b = async { m.ensureAccount() }
        runCurrent()
        assertEquals(AccountState.Creating, m.state.value)
        service.gate!!.complete(Unit)
        assertTrue(a.await()); assertTrue(b.await())
        assertEquals(1, service.created.size)
    }

    @Test fun offlineFirstLaunchRetriesWithBackoffAndCreatesTheAccountLater() = runTest {
        service.failWith = ApiError.Offline
        val m = manager()
        m.start()
        runCurrent()
        assertNull(auth.token())
        assertEquals(AccountState.None, m.state.value)
        advanceTimeBy(1_900); runCurrent()
        assertNull(auth.token())
        service.failWith = null
        advanceTimeBy(30_000); runCurrent()
        assertEquals("tok1", auth.token())
        assertTrue(m.state.value is AccountState.Ready)
    }

    @Test fun aServiceWithoutAccountsKeepsRetryingWithoutCrashing() = runTest {
        service.failWith = ApiError.Http(404, "not_found")
        val m = manager()
        m.start()
        advanceTimeBy(30 * 60_000L); runCurrent()
        assertNull(auth.token())
        assertEquals(AccountState.None, m.state.value)
    }

    @Test fun ensureAccountWakesTheRetryLoopAtOnce() = runTest {
        service.failWith = ApiError.Offline
        val m = manager()
        m.start()
        runCurrent()
        service.failWith = null
        assertTrue(m.ensureAccount())
        runCurrent()
        assertEquals(1, service.created.size)
    }

    @Test fun backoffGrowsIsCappedAndWaitsLongerWhenRateLimited() {
        assertEquals(2_000L, accountBackoffMs(0, ApiError.Offline))
        assertEquals(4_000L, accountBackoffMs(1, ApiError.Offline))
        assertEquals(5 * 60_000L, accountBackoffMs(20, ApiError.Offline))
        assertTrue(accountBackoffMs(0, ApiError.Http(429, "rate_limited")) >= 60_000L)
    }

    @Test fun revokedTokenCreatesAFreshAccount() = runTest {
        auth.save("tuner.orbitapps.space", "tok0", UserDto("u0", role = "user"))
        val m = manager()
        m.start()
        runCurrent()
        auth.markRevoked("tok0")
        runCurrent()
        assertEquals("u1", auth.user.value?.id)
        assertEquals("tok1", auth.token())
        assertFalse(auth.needsNewAccount.value)
    }

    @Test fun disabledAccountShowsDisabledAndIsNotRecreated() = runTest {
        auth.save("tuner.orbitapps.space", "tok0", UserDto("u0", role = "user"))
        service.meFails = ApiError.AccountDisabled
        val m = manager()
        m.start()
        runCurrent()
        assertEquals(AccountState.Disabled, m.state.value)
        advanceTimeBy(10 * 60_000L); runCurrent()
        assertTrue(service.created.isEmpty())
        assertEquals("tok0", auth.token())
    }

    @Test fun disabledReportedByAnyCallForTheCurrentTokenOnly() = runTest {
        auth.save("tuner.orbitapps.space", "tok0", UserDto("u0", role = "user"))
        val m = manager()
        m.accountDisabled("old-token")
        assertFalse(m.state.value == AccountState.Disabled)
        m.accountDisabled("tok0")
        assertEquals(AccountState.Disabled, m.state.value)
    }

    @Test fun signOutDeletesTheDeviceAndCreatesANewAnonymousAccount() = runTest {
        val m = manager()
        m.start()
        runCurrent()
        assertEquals("u1", auth.user.value?.id)
        m.signOut()
        runCurrent()
        assertEquals(listOf<String?>("tok1"), service.deleted)
        assertEquals("u2", auth.user.value?.id)
        assertEquals("tok2", auth.token())
    }

    @Test fun signOutWhileOfflineStillSignsOutLocally() = runTest {
        auth.save("tuner.orbitapps.space", "tok0", UserDto("u0", role = "user"))
        val m = manager()
        service.failWith = ApiError.Offline
        m.signOut()
        assertNull(auth.user.value)
        assertNull(auth.token())
    }

    @Test fun changingTheHostSignsOutLocallyAndCreatesAnAccountThere() = runTest {
        val m = manager()
        m.start()
        runCurrent()
        host = "10.0.2.2:8787"
        m.signOutLocally()
        runCurrent()
        assertTrue(service.deleted.isEmpty())
        assertEquals("10.0.2.2:8787", auth.host.value)
        assertEquals("u2", auth.user.value?.id)
    }

    @Test fun refreshUpdatesTheAiAllowance() = runTest {
        val m = manager()
        m.start()
        runCurrent()
        service.aiLeft = 1
        m.refresh()
        runCurrent()
        assertEquals(1, (m.state.value as AccountState.Ready).me?.aiLeftToday)
    }

    @Test fun revokedResetIsReportedOnceAndSignOutIsNot() = runTest {
        auth.save("tuner.orbitapps.space", "tok0", UserDto("u0", role = "user"))
        val m = manager()
        m.start()
        runCurrent()
        auth.markRevoked("tok0")
        runCurrent()
        assertEquals(1, resets)
        m.signOut()
        m.signOutLocally()
        runCurrent()
        assertEquals(1, resets)
    }
}
