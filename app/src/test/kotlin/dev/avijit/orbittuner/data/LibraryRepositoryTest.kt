package dev.avijit.orbittuner.data

import dev.avijit.orbittuner.data.db.AppDb
import dev.avijit.orbittuner.data.db.Favourite
import dev.avijit.orbittuner.data.db.PlaylistItemEntity
import dev.avijit.orbittuner.data.db.SongPrefs
import dev.avijit.orbittuner.music.FractionalIndex
import dev.avijit.orbittuner.net.ApiError
import dev.avijit.orbittuner.net.AuthStore
import dev.avijit.orbittuner.net.InMemoryKv
import dev.avijit.orbittuner.net.ItemSettings
import dev.avijit.orbittuner.net.Playlist
import dev.avijit.orbittuner.net.PlaylistItem
import dev.avijit.orbittuner.net.Song
import dev.avijit.orbittuner.net.UserDto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryRepositoryTest {
    private lateinit var db: AppDb
    private val auth = AuthStore(InMemoryKv()).apply { save("tuner.example", "tok", UserDto("u1", "Sam", "member")) }
    private val api = FakeService(onRevoked = { auth.markRevoked() })
    private var now = 1_000L
    private var syncRequests = 0
    /** Account B's server; account A (any other token) talks to [api]. */
    private val apiB = FakeService()

    /** Like production: one consistent auth snapshot, bound to the account's server. */
    private fun session(): SyncSession? {
        val snap = auth.snapshot() ?: return null
        sessionTokens += snap.token
        return SyncSession("${snap.host}|${snap.userId}", if (snap.token == "tokB") apiB else api, snap.token)
    }
    /** Token of every session snapshot built (sync start and per-request re-checks). */
    private val sessionTokens = ArrayList<String>()

    private fun pairB() = auth.save("b.example", "tokB", UserDto("u2", "Bea", "member"))

    @Before fun setUp() {
        db = testDb()
        api.addSong("s1", "Amazing Grace")
        api.addSong("s2", "Be Thou My Vision")
        api.addSong("s3", "Come Thou Fount")
    }

    @After fun tearDown() = db.close()

    private fun TestScope.lib(): LibraryRepository {
        val songs = SongRepository(api, db, backgroundScope, clock = { now })
        return LibraryRepository(
            db, songs, clock = { now }, requestSync = { syncRequests++ },
            identity = { auth.syncIdentity() }, session = { session() },
        )
    }

    private suspend fun LibraryRepository.cache(vararg ids: String) = ids.forEach { songs.open(it) }

    @Test fun offlineEditsAreDirtyThenSyncPushesAndClears() = runTest {
        val lib = lib()
        lib.cache("s1", "s2")
        api.offline = true
        val id = lib.createPlaylist("Sunday")
        lib.addToPlaylist(id, "s1")
        lib.addToPlaylist(id, "s2")
        lib.setFavourite("s1", true)
        assertTrue(db.library().playlist(id)!!.dirty)
        assertTrue(db.library().favourite("s1")!!.dirty)
        assertTrue(syncRequests >= 4)
        assertEquals(listOf("s1", "s2"), lib.playlists().first().single().songs.map { it.songId })

        try { lib.sync(); fail("offline sync must throw") } catch (_: ApiError.Offline) {}
        assertTrue(db.library().playlist(id)!!.dirty)

        api.offline = false
        lib.sync()
        val pushed = api.putPlaylistCalls.single()
        assertEquals("Sunday", pushed.name)
        assertEquals(listOf("s1", "s2"), pushed.items.sortedBy { it.position }.map { it.songId })
        assertEquals(listOf("s1"), api.putFavouritesCalls.single().filter { it.favourite }.map { it.songId })
        assertFalse(db.library().playlist(id)!!.dirty)
        assertFalse(db.library().favourite("s1")!!.dirty)
        assertEquals(listOf("s1"), lib.favourites().first().map { it.id })

        // Nothing dirty: a second sync pushes nothing.
        lib.sync()
        assertEquals(1, api.putPlaylistCalls.size)
        assertEquals(1, api.putFavouritesCalls.size)
    }

    @Test fun staleAdoptsServerCopyAndClearsDirty() = runTest {
        val lib = lib()
        lib.cache("s1", "s2")
        val id = lib.createPlaylist("Mine")
        lib.addToPlaylist(id, "s1")
        api.staleOnPut = Playlist(id, "Theirs", updatedAt = 99_000, items = listOf(PlaylistItem("s2", "a0")))
        lib.sync()
        val local = db.library().playlist(id)!!
        assertEquals("Theirs", local.name)
        assertEquals(99_000L, local.updatedAt)
        assertFalse(local.dirty)
        assertEquals(listOf("s2"), lib.playlists().first().single().songs.map { it.songId })
    }

    @Test fun tokenRevokedKeepsLocalDataAndDirtyFlags() = runTest {
        val lib = lib()
        lib.cache("s1")
        val id = lib.createPlaylist("Keep me")
        lib.addToPlaylist(id, "s1")
        lib.setFavourite("s1", true)
        api.failWith = ApiError.TokenRevoked
        try { lib.sync(); fail("expected TokenRevoked") } catch (_: ApiError.TokenRevoked) {}
        assertTrue(auth.needsNewAccount.value)
        val p = db.library().playlist(id)!!
        assertEquals("Keep me", p.name)
        assertTrue(p.dirty)
        assertTrue(db.library().favourite("s1")!!.dirty)
        assertEquals(listOf("s1"), lib.playlists().first().single().songs.map { it.songId })
        assertEquals(listOf("s1"), lib.favourites().first().map { it.id })
        assertTrue(api.putPlaylistCalls.isEmpty())
    }

    @Test fun moveBetweenA0AndA1GivesA0V() = runTest {
        val lib = lib()
        lib.cache("s1", "s2", "s3")
        val id = lib.createPlaylist("Order")
        lib.addToPlaylist(id, "s1")
        lib.addToPlaylist(id, "s2")
        lib.addToPlaylist(id, "s3")
        assertEquals(listOf("a0", "a1", "a2"), db.library().items(id).map { it.position })
        lib.move(id, "s3", prevSongId = "s1", nextSongId = "s2")
        assertEquals("a0V", db.library().item(id, "s3")!!.position)
        assertEquals(listOf("s1", "s3", "s2"), lib.playlists().first().single().songs.map { it.songId })
    }

    @Test fun addToPlaylistCachesTheSongAndIgnoresDuplicates() = runTest {
        val lib = lib()
        val id = lib.createPlaylist("P")
        lib.addToPlaylist(id, "s3")
        lib.addToPlaylist(id, "s3")
        assertEquals("Come Thou Fount", db.songs().get("s3")!!.title)
        assertEquals(1, db.library().items(id).size)
        assertEquals("Come Thou Fount", lib.playlists().first().single().songs.single().song!!.title)
    }

    @Test fun deleteIsATombstoneUntilSynced() = runTest {
        val lib = lib()
        lib.cache("s1")
        val id = lib.createPlaylist("Gone")
        lib.addToPlaylist(id, "s1")
        lib.sync()
        now = 5_000
        lib.delete(id)
        assertTrue(lib.playlists().first().isEmpty())
        val tomb = db.library().playlist(id)!!
        assertTrue(tomb.deleted && tomb.dirty)
        lib.sync()
        assertTrue(api.playlists[id]!!.deleted)
        assertFalse(db.library().playlist(id)!!.dirty)
        assertTrue(lib.playlists().first().isEmpty())
    }

    @Test fun pullMergesServerNewerOnlyOverDirtyLocal() = runTest {
        val lib = lib()
        lib.cache("s1", "s2")
        val clean = lib.createPlaylist("Clean")
        val dirty = lib.createPlaylist("Dirty")
        lib.sync()
        now = 10_000
        lib.rename(dirty, "Local edit")
        // Another device: older edit of the dirty one (must not win), newer edit of the clean one, and a new playlist.
        api.serverWrite(Playlist(dirty, "Older remote", updatedAt = 9_000))
        api.serverWrite(Playlist(clean, "Remote rename", updatedAt = 9_500, items = listOf(PlaylistItem("s2", "a0", ItemSettings(transpose = 2)))))
        api.serverWrite(Playlist("01HZZZZZZZZZZZZZZZZZZZZZZZ", "From tablet", updatedAt = 9_600))
        // The server copy of the dirty one is older (client updatedAt), so the push wins.
        lib.sync()
        assertEquals("Local edit", db.library().playlist(dirty)!!.name)
        assertEquals("Local edit", api.playlists[dirty]!!.name)
        assertEquals("Remote rename", db.library().playlist(clean)!!.name)
        assertEquals(2, lib.playlists().first().single { it.playlist.id == clean }.songs.single().settings.transpose)
        assertEquals(setOf("Remote rename", "Local edit", "From tablet"), lib.playlists().first().map { it.playlist.name }.toSet())
    }

    @Test fun pulledPlaylistSongsAreCachedInOneChangesCall() = runTest {
        val lib = lib()
        api.serverWrite(
            Playlist("01HAAAAAAAAAAAAAAAAAAAAAAA", "Remote", updatedAt = 500, items = listOf(PlaylistItem("s1", "a0"), PlaylistItem("s2", "a1"))),
        )
        lib.sync()
        assertEquals(listOf("Amazing Grace", "Be Thou My Vision"), lib.playlists().first().single().songs.map { it.song?.title })
        assertEquals(0, api.songCalls)
    }

    @Test fun downloadForOfflinePinsEverySong() = runTest {
        val lib = lib()
        val id = lib.createPlaylist("Gig")
        lib.addToPlaylist(id, "s1")
        lib.addToPlaylist(id, "s2")
        lib.downloadForOffline(id)
        assertTrue(db.songs().get("s1")!!.pinnedOffline && db.songs().get("s2")!!.pinnedOffline)
    }

    @Test fun setItemSettingsMarksDirty() = runTest {
        val lib = lib()
        lib.cache("s1")
        val id = lib.createPlaylist("S")
        lib.addToPlaylist(id, "s1")
        lib.sync()
        now = 7_000
        lib.setItemSettings(id, "s1", ItemSettings(transpose = -3, capo = 2))
        assertTrue(db.library().playlist(id)!!.dirty)
        lib.sync()
        assertEquals(ItemSettings(transpose = -3, capo = 2), api.playlists[id]!!.items.single().settings)
    }

    // ---- review fixes ----

    @Test fun pullUsesServerCursorNotDeviceClock() = runTest {
        val lib = lib()
        lib.createPlaylist("Mine")
        lib.sync()
        val cursor = db.syncState().get("playlists_cursor")!!.toLong()
        assertEquals(api.playlists.values.maxOf { it.modifiedAt }, cursor)
        // Another device with a clock far in the past writes a playlist after we hold a cursor.
        api.serverWrite(Playlist("01HBBBBBBBBBBBBBBBBBBBBBBB", "Slow clock", updatedAt = 5))
        lib.sync()
        assertEquals(cursor, api.getPlaylistsSince.last()) // sent back verbatim
        assertEquals("Slow clock", db.library().playlist("01HBBBBBBBBBBBBBBBBBBBBBBB")!!.name)
    }

    @Test fun repairToDifferentAccountWipesLibraryButKeepsSongs() = runTest {
        val lib = lib()
        lib.cache("s1")
        val id = lib.createPlaylist("Old account")
        lib.addToPlaylist(id, "s1")
        lib.setFavourite("s1", true)
        lib.songs.setPrefs(SongPrefs("s1", 2, 1, 1.0, 1.2f))
        lib.sync()
        now = 2_000
        lib.rename(id, "Unsynced edit")
        lib.setFavourite("s1", false)
        val putsBefore = api.putPlaylistCalls.size
        val favPutsBefore = api.putFavouritesCalls.size

        auth.save("other.example", "tok2", UserDto("u2", "Bea", "member"))
        lib.sync()
        assertEquals(putsBefore, api.putPlaylistCalls.size) // old account's dirty data never reaches the new one
        assertEquals(favPutsBefore, api.putFavouritesCalls.size)
        // The (shared) fake server still holds the old account's playlist, so the pull brings it back clean;
        // what matters is that the old unsynced edit is gone, not pushed.
        assertTrue(db.library().dirtyPlaylists().isEmpty())
        assertTrue(db.library().dirtyFavourites().isEmpty())
        assertEquals("Old account", db.library().playlist(id)!!.name)
        assertEquals(0L, api.getPlaylistsSince.last()) // cursor was reset
        assertEquals("Amazing Grace", db.songs().get("s1")!!.title) // song cache kept
        assertEquals(2, lib.songs.prefs("s1").first()!!.transpose) // prefs kept
    }

    @Test fun repairToDifferentAccountWithEmptyServerLeavesNothing() = runTest {
        val lib = lib()
        lib.cache("s1")
        val id = lib.createPlaylist("Old")
        lib.addToPlaylist(id, "s1")
        lib.setFavourite("s1", true)
        lib.sync()
        api.playlists.clear(); api.favs.clear() // the new account's server is empty
        auth.save("other.example", "tok2", UserDto("u2", "Bea", "member"))
        lib.sync()
        assertTrue(lib.playlists().first().isEmpty())
        assertTrue(lib.favourites().first().isEmpty())
        assertTrue(api.playlists.isEmpty())
        assertEquals("Amazing Grace", db.songs().get("s1")!!.title)
    }

    @Test fun sameAccountRepairAfterRevokeKeepsAndPushesEdits() = runTest {
        val lib = lib()
        lib.cache("s1")
        val id = lib.createPlaylist("Keep")
        lib.sync()
        now = 3_000
        lib.rename(id, "Edited while revoked")
        api.failWith = ApiError.TokenRevoked
        try { lib.sync(); fail() } catch (_: ApiError.TokenRevoked) {}
        api.failWith = null
        auth.save("tuner.example", "tok3", UserDto("u1", "Sam", "member"))
        lib.sync()
        assertEquals("Edited while revoked", api.playlists[id]!!.name)
        assertFalse(db.library().playlist(id)!!.dirty)
    }

    @Test fun draftsMadeWhileNeverPairedGoToTheNextPairedAccount() = runTest {
        auth.clear()
        val lib = lib()
        val id = lib.createPlaylist("Draft")
        try { lib.sync(); fail() } catch (_: ApiError.Unauthorized) {}
        assertTrue(db.library().playlist(id)!!.dirty) // no account recorded yet: nothing to protect
        pairB()
        lib.accountChanged()
        lib.sync()
        assertEquals("Draft", apiB.playlists[id]!!.name)
    }

    @Test fun playlistIsCappedAt500Items() = runTest {
        val lib = lib()
        val id = lib.createPlaylist("Big")
        var pos: String? = null
        db.library().insertItems((1..500).map { i -> pos = FractionalIndex.between(pos, null); PlaylistItemEntity(id, "x$i", pos!!, "{}") })
        try { lib.addToPlaylist(id, "s1"); fail("expected PlaylistFullException") } catch (_: PlaylistFullException) {}
        assertEquals(500, db.library().items(id).size)
    }

    @Test fun itemSettingsAreClampedToServerLimits() = runTest {
        val lib = lib()
        val id = lib.createPlaylist("S")
        lib.addToPlaylist(id, "s1")
        lib.setItemSettings(id, "s1", ItemSettings(transpose = 20, capo = -3, scrollSpeed = 5000.0))
        assertEquals(ItemSettings(transpose = 11, capo = 0, scrollSpeed = 1000.0), lib.playlists().first().single().songs.single().settings)
        lib.setItemSettings(id, "s1", ItemSettings(transpose = -20, capo = 40))
        assertEquals(ItemSettings(transpose = -11, capo = 11), lib.playlists().first().single().songs.single().settings)
    }

    @Test fun overlongPositionsAreRebalanced() = runTest {
        val lib = lib()
        val id = lib.createPlaylist("Deep")
        val a = "a0" + "1".repeat(62)
        val b = "a0" + "1".repeat(61) + "2"
        db.library().insertItems(listOf(PlaylistItemEntity(id, "s1", a, "{}"), PlaylistItemEntity(id, "s2", b, "{}")))
        lib.addToPlaylist(id, "s3")
        lib.move(id, "s3", prevSongId = "s1", nextSongId = "s2")
        val items = db.library().items(id)
        assertEquals(listOf("s1", "s3", "s2"), items.map { it.songId })
        assertTrue(items.all { it.position.length <= 64 })
    }

    @Test fun rejectedPlaylistIsMarkedAndSkippedUntilEdited() = runTest {
        val lib = lib()
        val id = lib.createPlaylist("Bad")
        val good = lib.createPlaylist("Good")
        api.putPlaylistFails[id] = ApiError.Http(400, "invalid_input")
        lib.sync()
        val p = db.library().playlist(id)!!
        assertTrue(p.dirty)
        assertEquals("invalid_input", p.syncError)
        assertEquals("invalid_input", lib.playlists().first().single { it.playlist.id == id }.playlist.syncError)
        assertTrue(api.playlists.containsKey(good)) // the others still went up
        val calls = api.putPlaylistCalls.count { it.id == id }
        lib.sync()
        assertEquals(calls, api.putPlaylistCalls.count { it.id == id }) // skipped
        api.putPlaylistFails.clear()
        now = 9_000
        lib.rename(id, "Fixed")
        assertEquals(null, db.library().playlist(id)!!.syncError)
        lib.sync()
        assertEquals("Fixed", api.playlists[id]!!.name)
    }

    @Test fun favouritesPushedInChunksOf1000() = runTest {
        val lib = lib()
        (1..1500).forEach { db.library().upsertFavourite(Favourite("f$it", true, 100, dirty = true)) }
        lib.sync()
        assertEquals(listOf(1000, 500), api.putFavouritesCalls.map { it.size })
        assertTrue(db.library().dirtyFavourites().isEmpty())
    }

    @Test fun favourites4xxDoesNotBlockPlaylists() = runTest {
        val lib = lib()
        lib.setFavourite("s1", true)
        val id = lib.createPlaylist("Still goes")
        api.putFavouritesFail = ApiError.Http(400, "invalid_input")
        lib.sync()
        assertTrue(api.playlists.containsKey(id))
        assertTrue(db.library().favourite("s1")!!.dirty)
    }

    @Test fun setFavouriteCachesTheSong() = runTest {
        val lib = lib()
        lib.setFavourite("s2", true)
        assertEquals("Be Thou My Vision", db.songs().get("s2")!!.title)
        assertEquals(listOf("s2"), lib.favourites().first().map { it.id })
    }

    @Test fun editDuringPushStaysDirty() = runTest {
        val lib = lib()
        val id = lib.createPlaylist("Before")
        api.duringPut = { now = 4_000; lib.rename(id, "During"); api.offline = true }
        try { lib.sync(); fail("second pass is offline") } catch (_: ApiError.Offline) {}
        val p = db.library().playlist(id)!!
        assertEquals("During", p.name)
        assertTrue(p.dirty)
        assertEquals("Before", api.playlists[id]!!.name)
    }

    @Test fun editDuringPushIsPushedByAnotherPass() = runTest {
        val lib = lib()
        val id = lib.createPlaylist("Before")
        api.duringPut = { now = 4_000; lib.rename(id, "During") }
        lib.sync()
        assertEquals(2, api.putPlaylistCalls.size)
        assertEquals("During", api.playlists[id]!!.name)
        assertFalse(db.library().playlist(id)!!.dirty)
    }

    // ---- account switch, round 2 ----

    @Test fun switchMidSyncNeverPushesOldDataWithNewTokenAndAborts() = runTest {
        val lib = lib()
        lib.cache("s1")
        val p1 = lib.createPlaylist("A one")
        val p2 = lib.createPlaylist("A two")
        lib.setFavourite("s1", true)
        // While the first PUT is in flight, the user confirms pairing to B.
        api.duringPut = { pairB(); lib.accountChanged() }
        lib.sync()
        assertEquals(1, api.putPlaylistCalls.size) // no further PUTs to A after the switch
        assertTrue(apiB.putPlaylistCalls.isEmpty()) // and none of A's data went to B
        assertTrue(apiB.putFavouritesCalls.isEmpty())
        assertTrue(apiB.playlists.isEmpty() && apiB.favs.isEmpty())
        assertTrue(lib.playlists().first().isEmpty()) // A's library is gone locally
        assertTrue(lib.favourites().first().isEmpty())
        assertEquals(null, db.library().playlist(p1)); assertEquals(null, db.library().playlist(p2))
    }

    @Test fun switchWipesAtPairingTimeAndLaterEditsUnderBSurvive() = runTest {
        val lib = lib()
        lib.cache("s1")
        lib.createPlaylist("A list")
        lib.setFavourite("s1", true)
        lib.sync()
        pairB()
        val before = syncRequests
        lib.accountChanged()
        assertTrue(lib.playlists().first().isEmpty()) // wiped before any sync
        assertTrue(lib.favourites().first().isEmpty())
        assertTrue(syncRequests > before) // and a sync for B was requested
        val b = lib.createPlaylist("B list")
        lib.sync()
        assertEquals(listOf("B list"), lib.playlists().first().map { it.playlist.name })
        assertEquals("B list", apiB.playlists[b]!!.name)
        assertEquals(listOf(b), apiB.putPlaylistCalls.map { it.id })
    }

    @Test fun writeAfterSwitchWipesOldAccountFirst() = runTest {
        val lib = lib()
        lib.createPlaylist("A list")
        lib.sync()
        pairB() // no accountChanged() yet: the write itself must reconcile first
        val b = lib.createPlaylist("B list")
        assertEquals(listOf("B list"), lib.playlists().first().map { it.playlist.name })
        lib.sync()
        assertEquals(setOf(b), apiB.playlists.keys)
        assertEquals("B list", db.library().playlist(b)!!.name)
    }

    @Test fun clearingAuthAfterSyncWipesLibrary() = runTest {
        val lib = lib()
        lib.cache("s1")
        lib.createPlaylist("A list")
        lib.setFavourite("s1", true)
        lib.sync()
        auth.clear()
        lib.accountChanged()
        assertTrue(lib.playlists().first().isEmpty())
        assertTrue(lib.favourites().first().isEmpty())
        assertEquals("Amazing Grace", db.songs().get("s1")!!.title)
        try { lib.sync(); fail() } catch (_: ApiError.Unauthorized) {}
    }

    @Test fun rejectedFavouritesStillPullOtherDevicesFavourites() = runTest {
        val lib = lib()
        lib.setFavourite("s1", true)
        api.favs["s2"] = dev.avijit.orbittuner.net.FavouriteItem("s2", true, 50) // from another device
        api.putFavouritesFail = ApiError.Http(400, "invalid_input")
        lib.sync()
        assertEquals(true, db.library().favourite("s2")?.favourite)
        assertTrue(db.library().favourite("s1")!!.dirty)
    }

    // ---- round 3 ----

    @Test fun switchBetweenGuardAndItemsReadNeverPushesEmptiedPlaylist() = runTest {
        val lib = lib()
        lib.cache("s1", "s2")
        val id = lib.createPlaylist("A list")
        lib.addToPlaylist(id, "s1"); lib.addToPlaylist(id, "s2")
        lib.sync()
        now = 5_000
        lib.rename(id, "A edited")
        lib.beforePlaylistRead = { lib.beforePlaylistRead = null; pairB(); lib.accountChanged() }
        val putsBefore = api.putPlaylistCalls.size
        lib.sync()
        assertTrue(api.putPlaylistCalls.drop(putsBefore).isEmpty()) // nothing (let alone an empty list) went to A
        assertEquals(listOf("s1", "s2"), api.playlists[id]!!.items.map { it.songId })
        assertTrue(apiB.putPlaylistCalls.isEmpty())
    }

    @Test fun deleteBetweenGuardAndReadSkipsTheStaleRow() = runTest {
        val lib = lib()
        lib.cache("s1")
        val id = lib.createPlaylist("P")
        lib.addToPlaylist(id, "s1")
        lib.beforePlaylistRead = { lib.beforePlaylistRead = null; now = 7_000; lib.delete(id) }
        lib.sync()
        // The push uses one consistent snapshot: the tombstone (never the old name with emptied items).
        assertTrue(api.putPlaylistCalls.none { !it.deleted && it.items.isEmpty() })
        assertTrue(api.playlists[id]!!.deleted)
    }

    @Test fun sameAccountNewTokenMidSyncRebindsTheSession() = runTest {
        val lib = lib()
        lib.createPlaylist("One")
        lib.createPlaylist("Two")
        // Re-paired to the same account with a new token (no revoke) while the first PUT is in flight.
        api.duringPut = { auth.save("tuner.example", "tokNew", UserDto("u1", "Sam", "member")) }
        lib.sync()
        assertEquals(listOf("tok", "tokNew"), sessionTokens.distinct())
        assertEquals("tokNew", sessionTokens.last())
        assertTrue(db.library().dirtyPlaylists().isEmpty())
        assertEquals(2, api.playlists.size)
    }

    @Test fun repeatedSwitchesEndInRetryableError() = runTest {
        val lib = lib()
        lib.createPlaylist("A")
        // Every pull sees the user switch account again (A -> B, then B -> C).
        api.duringGet = { pairB(); lib.accountChanged() }
        apiB.duringGet = { auth.save("c.example", "tokC", UserDto("u3", "C", "member")); lib.accountChanged() }
        try { lib.sync(); fail() } catch (_: AccountChangedException) {}
        assertTrue(apiB.putPlaylistCalls.isEmpty())
        assertEquals(androidx.work.ListenableWorker.Result.retry(), SyncWorker.runSync(0) { throw AccountChangedException() })
    }

    @Test fun accountChangedWithoutSyncRequest() = runTest {
        val lib = lib()
        val before = syncRequests
        lib.accountChanged(requestSync = false)
        assertEquals(before, syncRequests)
    }

    /** Settings shows the last successful sync; a switch to another account forgets it. */
    @Test fun lastSyncIsRecordedOnSuccessAndForgottenOnAccountSwitch() = runTest {
        val lib = lib()
        assertEquals(null, lib.lastSync().first())
        api.offline = true
        try { lib.sync() } catch (_: dev.avijit.orbittuner.net.ApiError) {}
        assertEquals(null, lib.lastSync().first())
        api.offline = false
        now = 7_000L
        lib.sync()
        assertEquals(7_000L, lib.lastSync().first())
        pairB()
        lib.accountChanged()
        assertEquals(null, lib.lastSync().first())
    }

    @Test fun downloadForOfflineReportsProgressAndPins() = runTest {
        val lib = lib()
        val id = lib.createPlaylist("Sunday")
        lib.addToPlaylist(id, "s1"); lib.addToPlaylist(id, "s2")
        val seen = ArrayList<Pair<Int, Int>>()
        lib.downloadForOffline(id) { d, t -> seen += d to t }
        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), seen)
        assertEquals(listOf("s1", "s2"), lib.downloaded().first().map { it.id })
        // Caching for offline is not playing: Recent stays empty until a song is opened.
        assertEquals(emptyList<String>(), lib.recent().first().map { it.id })
    }

    /** Review: favouriting, adding to a playlist and downloading cache songs without making them "recent". */
    @Test fun cachingForTheLibraryDoesNotCountAsPlayed() = runTest {
        val lib = lib()
        lib.setFavourite("s1", true)
        val id = lib.createPlaylist("Sunday")
        lib.addToPlaylist(id, "s2")
        lib.downloadForOffline(id)
        assertNotNull(db.songs().get("s1")); assertNotNull(db.songs().get("s2"))
        assertEquals(emptyList<String>(), lib.recent().first().map { it.id })
        now = 9_000L
        lib.songs.open("s2")
        assertEquals(listOf("s2"), lib.recent().first().map { it.id })
    }

    /** A3 review: another account's review/hidden songs must not stay in "On this device" after a switch. */
    @Test fun accountSwitchEvictsUnpublishedCachedSongs() = runTest {
        val lib = lib()
        api.songs["r1"] = Song(id = "r1", title = "My draft", chordpro = "{title: My draft}\n[G]la", status = "review", updatedAt = 10)
        lib.cache("s1", "r1")
        lib.songs.setPrefs(dev.avijit.orbittuner.data.db.SongPrefs("r1", transpose = 2, capo = 0, scrollSpeed = 0.0, textScale = 1f))
        lib.accountChanged() // same account: nothing evicted
        assertEquals("review", db.songs().get("r1")?.status)
        pairB()
        lib.accountChanged()
        assertEquals(null, db.songs().get("r1"))
        assertEquals(null, lib.songs.prefs("r1").first())
        assertEquals("published", db.songs().get("s1")?.status)
        assertEquals(listOf("s1"), lib.songs.localSearch("").first().let { it.yours + it.cached }.map { it.id })
    }
}
