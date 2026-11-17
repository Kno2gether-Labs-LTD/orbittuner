package dev.avijit.orbittuner.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.avijit.orbittuner.data.db.AppDb
import dev.avijit.orbittuner.net.AccountManager
import dev.avijit.orbittuner.net.AccountState
import dev.avijit.orbittuner.net.ApiError
import dev.avijit.orbittuner.net.AuthStore
import dev.avijit.orbittuner.net.FakeAccounts
import dev.avijit.orbittuner.net.GoogleAccountResponse
import dev.avijit.orbittuner.net.GoogleIdTokenProvider
import dev.avijit.orbittuner.net.GoogleLink
import dev.avijit.orbittuner.net.GoogleSignIn
import dev.avijit.orbittuner.net.GoogleSignInOutcome
import dev.avijit.orbittuner.net.GoogleUser
import dev.avijit.orbittuner.net.IdTokenResult
import dev.avijit.orbittuner.net.InMemoryKv
import dev.avijit.orbittuner.net.Playlist
import dev.avijit.orbittuner.net.googleErrorMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Google sign-in with a fake Credential Manager: link, merge (re-bound device), cancel and error copy. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GoogleSignInTest {
    private lateinit var db: AppDb
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val auth = AuthStore(InMemoryKv())
    private val accounts = FakeAccounts().also { s -> s.tokenSeen = { auth.token() } }
    private val servers = HashMap<String, FakeService>()
    private fun server(token: String) = servers.getOrPut(token) { FakeService().also(::seedSongs) }
    private val shared = FakeService().also(::seedSongs)
    private val events = ArrayList<String>()

    private fun seedSongs(s: FakeService) {
        s.addSong("s1", "Amazing Grace"); s.addSong("s2", "Be Thou My Vision")
    }

    @Before fun setUp() { db = testDb() }
    @After fun tearDown() = db.close()

    private fun TestScope.lib(): LibraryRepository = LibraryRepository(
        db, SongRepository(shared, db, backgroundScope),
        identity = { auth.syncIdentity() },
        session = { auth.snapshot()?.let { SyncSession("${it.host}|${it.userId}", server(it.token), it.token) } },
    )

    private fun TestScope.manager(beforeIdentityRepair: suspend () -> Unit = {}) =
        AccountManager(
            accounts, auth, backgroundScope, serviceHost = { "tuner.orbitapps.space" }, deviceName = "Fold",
            beforeIdentityRepair = beforeIdentityRepair,
        )

    private fun signIn(m: AccountManager, lib: LibraryRepository, provider: GoogleIdTokenProvider = FakeProvider(), push: suspend () -> Unit = { lib.sync() }) =
        GoogleSignIn(provider, m, pushLocal = push, unsynced = { lib.unsyncedChanges() })

    private class FakeProvider(var result: IdTokenResult = IdTokenResult.Token("id-token-1")) : GoogleIdTokenProvider {
        var calls = 0
        override suspend fun requestIdToken(context: Context): IdTokenResult { calls++; return result }
    }

    @Test fun successLinksTheAccountKeepsTheTokenAndShowsTheEmail() = runTest {
        val lib = lib()
        val m = manager()
        assertTrue(m.ensureAccount())
        val provider = FakeProvider()
        val g = signIn(m, lib, provider)

        val out = g.signIn(ctx)

        assertEquals(GoogleSignInOutcome.SignedIn("sam@example.com", switchedAccount = false), out)
        assertEquals(listOf("id-token-1"), accounts.googleCalls)
        assertEquals("tok1", auth.token())
        assertEquals("u1", auth.user.value?.id)
        val s = m.state.value as AccountState.Ready
        assertEquals("sam@example.com", s.me?.google?.email)
    }

    @Test fun reboundDevicePushesLocalEditsFirstThenWipesAndRepullsTheMergedAccount() = runTest {
        val lib = lib()
        val m = manager()
        assertTrue(m.ensureAccount())
        lib.songs.open("s1")
        val id = lib.createPlaylist("Made before sign-in")
        lib.addToPlaylist(id, "s1")
        // The Google account's other device already has a playlist on the account this device merges into.
        server("tok1").serverWrite(Playlist("pX", "From my phone", updatedAt = 10))
        accounts.google = { _ ->
            events += "link"
            accounts.meUserId = "x9"; accounts.googleEmail = "sam@example.com"
            GoogleAccountResponse("tok1", GoogleUser("x9", "user", "plus", GoogleLink("sam@example.com")))
        }
        val g = signIn(m, lib, push = { events += "push"; lib.sync() })

        val out = g.signIn(ctx)

        assertEquals(GoogleSignInOutcome.SignedIn("sam@example.com", switchedAccount = true), out)
        assertEquals(listOf("push", "link"), events)
        // The dirty local playlist reached the anonymous account before the server merged it.
        assertEquals(listOf("Made before sign-in"), server("tok1").putPlaylistCalls.map { it.name })
        // Same token value, new identity.
        assertEquals("tok1", auth.token())
        assertEquals("x9", auth.user.value?.id)
        assertEquals("plus", auth.user.value?.plan)

        // The identity watcher (AppGraph) then wipes the old account's local data and re-pulls the merged state.
        lib.accountChanged()
        assertTrue(lib.playlists().first().isEmpty())
        lib.sync()
        assertEquals(listOf("From my phone", "Made before sign-in"), lib.playlists().first().map { it.playlist.name }.sorted())
        assertEquals(0L, server("tok1").getPlaylistsSince.last())
        assertEquals("sam@example.com", (m.state.value as AccountState.Ready).me?.google?.email)
    }

    @Test fun changesThatDidNotReachTheAccountAbortTheSignIn() = runTest {
        val lib = lib()
        val m = manager()
        assertTrue(m.ensureAccount())
        lib.createPlaylist("Not pushed")
        lib.setFavourite("s1", true)
        val g = signIn(m, lib, push = { throw ApiError.Offline })

        val out = g.signIn(ctx)

        assertEquals(GoogleSignInOutcome.Failed("Some changes haven't reached your account yet. Connect to the internet and try again."), out)
        assertTrue(accounts.googleCalls.isEmpty())
        assertEquals(listOf("Not pushed"), lib.playlists().first().map { it.playlist.name })
        assertTrue(lib.hasUnsyncedChanges())
    }

    @Test fun pushTimingOutAbortsTheSignIn() = runTest {
        val lib = lib()
        val m = manager()
        assertTrue(m.ensureAccount())
        lib.createPlaylist("Slow")
        val g = GoogleSignIn(FakeProvider(), m, pushLocal = { kotlinx.coroutines.awaitCancellation() }, unsynced = { lib.unsyncedChanges() }, pushTimeoutMs = 1_000)
        assertTrue(g.signIn(ctx) is GoogleSignInOutcome.Failed)
        assertTrue(accounts.googleCalls.isEmpty())
    }

    @Test fun rejectedChangesNeedConfirmationBeforeLinking() = runTest {
        val lib = lib()
        val m = manager()
        assertTrue(m.ensureAccount())
        val id = lib.createPlaylist("Rejected")
        server("tok1").putPlaylistFails[id] = ApiError.Http(400, "invalid_input")
        val g = signIn(m, lib)

        val out = g.signIn(ctx)

        assertTrue("$out", out is GoogleSignInOutcome.NeedsConfirmation)
        assertEquals(1, (out as GoogleSignInOutcome.NeedsConfirmation).changes)
        assertTrue(accounts.googleCalls.isEmpty())
        assertEquals(UnsyncedChanges(pending = 0, rejected = 1), lib.unsyncedChanges())
        // Declining is simply not confirming: nothing changed. Confirming links with the same ID token.
        assertEquals(GoogleSignInOutcome.SignedIn("sam@example.com", switchedAccount = false), g.confirm(out))
        assertEquals(listOf("id-token-1"), accounts.googleCalls)
    }

    @Test fun lostLinkResponseAfterAServerMergeIsRepairedFromMe() = runTest {
        val lib = lib()
        val m = manager(beforeIdentityRepair = { events += "push"; lib.sync() })
        assertTrue(m.ensureAccount())
        lib.songs.open("s1")
        lib.createPlaylist("Dirty before repair")
        server("tok1").serverWrite(Playlist("pX", "From my phone", updatedAt = 10))
        // The service merged this device into x9, but the link response never arrived.
        accounts.meUserId = "x9"; accounts.googleEmail = "sam@example.com"

        m.refreshMe()

        assertEquals(listOf("push"), events)
        assertEquals(listOf("Dirty before repair"), server("tok1").putPlaylistCalls.map { it.name })
        assertEquals("x9", auth.user.value?.id)
        assertEquals("tok1", auth.token())
        assertEquals("sam@example.com", (m.state.value as AccountState.Ready).me?.google?.email)
        lib.accountChanged()
        lib.sync()
        assertEquals(listOf("Dirty before repair", "From my phone"), lib.playlists().first().map { it.playlist.name }.sorted())
    }

    @Test fun staleMeFromBeforeARebindNeverOverwritesTheNewIdentity() = runTest {
        val m = manager()
        assertTrue(m.ensureAccount())
        accounts.google = { _ ->
            accounts.meUserId = "x9"; accounts.googleEmail = "sam@example.com"
            GoogleAccountResponse("tok1", GoogleUser("x9", "user", "free", GoogleLink("sam@example.com")))
        }
        // A /v1/me answered (as u1) before the re-bind, but delivered after it.
        val gate = CompletableDeferred<Unit>()
        accounts.meGate = gate
        val slow = async { m.refreshMe() }
        runCurrent()
        m.linkGoogle("id-token-1")
        assertEquals("x9", auth.user.value?.id)
        gate.complete(Unit)
        slow.await()
        assertEquals("x9", auth.user.value?.id)
        assertEquals("tok1", auth.token())
    }

    @Test fun repairRefetchesMeUnderTheLockAndSkipsWhenItAgrees() = runTest {
        val m = manager(beforeIdentityRepair = { events += "push" })
        assertTrue(m.ensureAccount())
        // First answer says another user, the re-check under the lock says u1 again: nothing to repair.
        var answers = listOf("x9", "u1")
        accounts.meUserIdFor = { answers.first().also { answers = answers.drop(1).ifEmpty { listOf("u1") } } }
        m.refreshMe()
        assertEquals("u1", auth.user.value?.id)
        assertTrue(events.isEmpty())
    }

    @Test fun confirmFailingWithAnInvalidGoogleTokenSaysTryAgain() = runTest {
        val lib = lib()
        val m = manager()
        assertTrue(m.ensureAccount())
        val id = lib.createPlaylist("Rejected")
        server("tok1").putPlaylistFails[id] = ApiError.Http(400, "invalid_input")
        val g = signIn(m, lib)
        val pending = g.signIn(ctx) as GoogleSignInOutcome.NeedsConfirmation
        accounts.google = { throw ApiError.Http(401, "invalid_google_token") }
        val out = g.confirm(pending) as GoogleSignInOutcome.Failed
        assertTrue(out.message, out.message.contains("try again"))
        assertEquals("u1", auth.user.value?.id)
        assertEquals(listOf("Rejected"), lib.playlists().first().map { it.playlist.name })
    }

    @Test fun disabledReplyFromTheGoogleRouteRechecksThisDevice() = runTest {
        val lib = lib()
        val m = manager()
        assertTrue(m.ensureAccount())
        accounts.google = { throw ApiError.AccountDisabled }
        accounts.meFails = ApiError.AccountDisabled
        val out = signIn(m, lib).signIn(ctx)
        assertTrue(out is GoogleSignInOutcome.Failed)
        assertEquals(AccountState.Disabled, m.state.value)
    }

    @Test fun cancelLeavesEverythingUnchanged() = runTest {
        val m = manager()
        assertTrue(m.ensureAccount())
        var pushed = false
        val g = GoogleSignIn(FakeProvider(IdTokenResult.Cancelled), m, pushLocal = { pushed = true }, unsynced = { UnsyncedChanges(0, 0) })

        assertEquals(GoogleSignInOutcome.Cancelled, g.signIn(ctx))
        assertTrue(accounts.googleCalls.isEmpty())
        assertTrue(!pushed)
        assertEquals("tok1", auth.token())
        assertEquals("u1", auth.user.value?.id)
    }

    @Test fun providerFailureIsShownAndNothingChanges() = runTest {
        val m = manager()
        assertTrue(m.ensureAccount())
        val g = GoogleSignIn(FakeProvider(IdTokenResult.Failed("No Google account on this device.")), m, pushLocal = {}, unsynced = { UnsyncedChanges(0, 0) })
        assertEquals(GoogleSignInOutcome.Failed("No Google account on this device."), g.signIn(ctx))
        assertTrue(accounts.googleCalls.isEmpty())
    }

    @Test fun alreadyLinkedElsewhereExplainsSignOutFirst() = runTest {
        val m = manager()
        assertTrue(m.ensureAccount())
        accounts.google = { throw ApiError.Http(409, "google_already_linked") }
        val out = GoogleSignIn(FakeProvider(), m, pushLocal = {}, unsynced = { UnsyncedChanges(0, 0) }).signIn(ctx) as GoogleSignInOutcome.Failed
        assertTrue(out.message, out.message.contains("Sign out of this device first"))
        assertEquals("u1", auth.user.value?.id)
        assertEquals("tok1", auth.token())
    }

    @Test fun errorCopyCoversTheServiceErrors() {
        fun msg(code: Int, e: String) = googleErrorMessage(ApiError.Http(code, e))
        assertTrue(msg(401, "invalid_google_token").contains("Google couldn't confirm"))
        assertTrue(msg(409, "google_linked_elsewhere").contains("already linked to another OrbitTuner account"))
        assertTrue(msg(403, "admin_use_portal").contains("portal"))
        assertTrue(msg(503, "google_not_configured").contains("isn't available"))
        assertTrue(msg(503, "google_unavailable").contains("Couldn't reach Google"))
        assertTrue(msg(429, "rate_limited").contains("Too many"))
        assertTrue(googleErrorMessage(ApiError.Offline).contains("offline"))
        assertTrue(googleErrorMessage(ApiError.AccountDisabled).contains("disabled"))
    }
}
