package dev.avijit.orbittuner.data

import dev.avijit.orbittuner.data.db.AppDb
import dev.avijit.orbittuner.net.AccountManager
import dev.avijit.orbittuner.net.ApiError
import dev.avijit.orbittuner.net.AuthStore
import dev.avijit.orbittuner.net.FakeAccounts
import dev.avijit.orbittuner.net.InMemoryKv
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Automatic accounts and the local library: adopt pre-account data, wipe on sign-out / revoke. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AccountLibraryTest {
    private lateinit var db: AppDb
    private val auth = AuthStore(InMemoryKv())
    private val accounts = FakeAccounts().also { s -> s.tokenSeen = { auth.token() } }
    /** One song server per account token. */
    private val servers = HashMap<String, FakeService>()
    private fun server(token: String) = servers.getOrPut(token) { FakeService(onRevoked = { auth.markRevoked(token) }).also(::seedSongs) }
    private val shared = FakeService().also(::seedSongs)

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

    private fun TestScope.manager() =
        AccountManager(accounts, auth, backgroundScope, serviceHost = { "tuner.orbitapps.space" }, deviceName = "Fold")

    @Test fun offlineFirstLaunchKeepsLocalDataAndItSyncsToTheNewAccount() = runTest {
        accounts.failWith = ApiError.Offline
        val lib = lib()
        val m = manager()
        m.start()
        runCurrent()
        assertNull(auth.token())
        // Offline, no account: the library still works locally.
        lib.songs.open("s1")
        val id = lib.createPlaylist("Before account")
        lib.addToPlaylist(id, "s1")
        lib.setFavourite("s1", true)
        try { lib.sync(); fail("no account yet") } catch (_: ApiError.Unauthorized) {}

        accounts.failWith = null
        assertTrue(m.ensureAccount())
        lib.accountChanged()
        lib.sync()

        val pushed = server("tok1").putPlaylistCalls.single()
        assertEquals("Before account", pushed.name)
        assertEquals(listOf("s1"), pushed.items.map { it.songId })
        assertEquals(listOf("s1"), server("tok1").favs.values.filter { it.favourite }.map { it.songId })
        assertEquals(listOf("Before account"), lib.playlists().first().map { it.playlist.name })
    }

    @Test fun signOutWipesPrivateDataAndStartsAFreshAccount() = runTest {
        val lib = lib()
        val m = manager()
        assertTrue(m.ensureAccount())
        lib.songs.open("s1")
        val id = lib.createPlaylist("Mine")
        lib.addToPlaylist(id, "s1")
        lib.setFavourite("s1", true)
        lib.sync()

        m.signOut()
        runCurrent()
        lib.accountChanged()
        assertEquals("u2", auth.user.value?.id)
        assertTrue(lib.playlists().first().isEmpty())
        assertTrue(lib.favourites().first().isEmpty())
        // Shared content stays: the song is still cached.
        assertNotNull(db.songs().get("s1"))
        lib.sync()
        assertTrue(server("tok2").putPlaylistCalls.isEmpty())
    }

    @Test fun revokedTokenGetsANewAccountWithoutTheOldPrivateData() = runTest {
        val lib = lib()
        val m = manager()
        m.start()
        runCurrent()
        lib.songs.open("s1")
        lib.createPlaylist("Old account")
        server("tok1").failWith = ApiError.TokenRevoked
        try { lib.sync(); fail() } catch (_: ApiError.TokenRevoked) {}
        runCurrent()
        assertEquals("u2", auth.user.value?.id)
        lib.accountChanged()
        assertTrue(lib.playlists().first().isEmpty())
        lib.sync()
        assertTrue(server("tok2").putPlaylistCalls.isEmpty())
    }
}
