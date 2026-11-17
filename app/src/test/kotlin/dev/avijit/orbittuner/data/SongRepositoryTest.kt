package dev.avijit.orbittuner.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.avijit.orbittuner.data.db.AppDb
import dev.avijit.orbittuner.net.ApiError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** In-memory Room running queries inline, so the test scheduler sees every step (deterministic). */
fun testDb(): AppDb =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDb::class.java)
        .allowMainThreadQueries()
        .setQueryExecutor { it.run() }
        .setTransactionExecutor { it.run() }
        .build()

@RunWith(RobolectricTestRunner::class)
class SongRepositoryTest {
    private lateinit var db: AppDb
    private val api = FakeService()
    private var now = 1_000L

    @Before fun setUp() {
        db = testDb()
    }

    @After fun tearDown() = db.close()

    private fun TestScope.songs() = SongRepository(api, db, backgroundScope, clock = { now })

    @Test fun openOnlineCachesThenOpensOffline() = runTest {
        api.addSong("s1", "Amazing Grace", "Newton", lyrics = "how sweet the sound")
        val repo = songs()
        val first = repo.open("s1")
        assertEquals("Amazing Grace", first.title)
        assertEquals(1_000L, first.lastOpenedAt)
        assertEquals(1, api.songCalls)

        api.offline = true
        now = 2_000L
        val again = repo.open("s1")
        runCurrent() // the background refresh fails quietly while offline
        assertEquals("Amazing Grace", again.title)
        assertEquals(2_000L, again.lastOpenedAt)
        assertEquals(2_000L, db.songs().get("s1")!!.lastOpenedAt)
    }

    @Test fun openNeverCachedWhileOfflineThrowsOffline() = runTest {
        api.offline = true
        try {
            songs().open("nope")
            fail("expected Offline")
        } catch (e: ApiError.Offline) {
            // expected
        }
        assertNull(db.songs().get("nope"))
    }

    @Test fun openCachedRefreshesInBackgroundWhenOnline() = runTest {
        api.addSong("s1", "Old title", updatedAt = 10)
        val repo = songs()
        repo.open("s1")
        api.addSong("s1", "New title", updatedAt = 20)
        assertEquals("Old title", repo.open("s1").title) // cache-first, not blocked on the network
        runCurrent()
        assertEquals("New title", db.songs().get("s1")!!.title)
    }

    @Test fun localSearchSplitsYoursFromCached() = runTest {
        api.addSong("fav", "Amazing Grace", lyrics = "how sweet the sound")
        api.addSong("other", "Grace Alone", lyrics = "every promise")
        api.addSong("nomatch", "Holy Holy", lyrics = "lord god almighty")
        val repo = songs()
        listOf("fav", "other", "nomatch").forEach { repo.open(it) }
        LibraryRepository(db, repo, clock = { now }, requestSync = {}, identity = { "h|u" }, session = { SyncSession("h|u", api) }).setFavourite("fav", true)

        val res = repo.localSearch("grace").first()
        assertEquals(listOf("fav"), res.yours.map { it.id })
        assertEquals(listOf("other"), res.cached.map { it.id })

        // Lyrics are searchable, prefix terms work, and FTS syntax in user text is inert.
        assertEquals(listOf("fav"), repo.localSearch("swe").first().yours.map { it.id })
        assertEquals(listOf("nomatch"), repo.localSearch("(almi\" -*").first().cached.map { it.id })
        assertTrue(repo.localSearch("\"*()").first().let { it.yours.size + it.cached.size } == 3)
        assertTrue(repo.localSearch("grace OR holy").first().let { it.yours.isEmpty() && it.cached.isEmpty() })
    }

    @Test fun ftsMatchBuildsQuotedPrefixTermsOnly() {
        assertEquals("\"amazing*\" \"grace*\"", ftsMatch("Amazing  grace!"))
        assertEquals("\"don*\" \"t*\"", ftsMatch("don't"))
        assertEquals("\"a*\" \"or*\" \"b*\"", ftsMatch("a OR b"))
        assertNull(ftsMatch("  \"*() - "))
        // Combining marks stay inside the term (Devanagari vowel signs / virama, decomposed accents).
        assertEquals("\"नमस्ते*\"", ftsMatch("नमस्ते"))
        assertEquals("\"cafe\u0301*\"", ftsMatch("Cafe\u0301!"))
        assertEquals(12, ftsMatch((1..20).joinToString(" ") { "w$it" })!!.split(' ').size)
    }

    @Test fun refreshCachedUsesChangesInChunksOf200() = runTest {
        val repo = songs()
        for (i in 0 until 450) { api.addSong("s$i", "Song $i", updatedAt = 10); repo.open("s$i") }
        api.addSong("s7", "Song 7 v2", updatedAt = 50)
        repo.refreshCached()
        assertEquals(listOf(200, 200, 50), api.changesCalls.map { it.size })
        assertEquals("Song 7 v2", db.songs().get("s7")!!.title)
        assertEquals("Song 8", db.songs().get("s8")!!.title)
    }

    @Test fun localSearchMatchesWordsWithCombiningMarks() = runTest {
        api.addSong("hi", "नमस्ते दुनिया", lyrics = "प्रेम")
        api.addSong("fr", "Cafe\u0301 du Monde")
        val repo = songs()
        repo.open("hi"); repo.open("fr")
        assertEquals(listOf("hi"), repo.localSearch("नमस्ते").first().cached.map { it.id })
        assertEquals(listOf("hi"), repo.localSearch("प्रेम").first().cached.map { it.id })
        assertEquals(listOf("fr"), repo.localSearch("cafe\u0301").first().cached.map { it.id })
    }

    @Test fun cacheStoresWithoutStampingLastOpened() = runTest {
        api.addSong("s1", "Amazing Grace")
        val repo = songs()
        repo.cache("s1")
        assertEquals(0L, db.songs().get("s1")!!.lastOpenedAt)
        now = 5_000L
        repo.cache("s1") // already cached: still not stamped
        assertEquals(0L, db.songs().get("s1")!!.lastOpenedAt)
        repo.open("s1")
        assertEquals(5_000L, db.songs().get("s1")!!.lastOpenedAt)
    }
}
