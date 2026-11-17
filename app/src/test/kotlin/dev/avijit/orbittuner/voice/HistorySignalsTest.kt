package dev.avijit.orbittuner.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.avijit.orbittuner.data.db.AppDb
import dev.avijit.orbittuner.data.db.CachedSong
import dev.avijit.orbittuner.data.db.Favourite
import dev.avijit.orbittuner.data.db.PlaylistEntity
import dev.avijit.orbittuner.data.db.PlaylistItemEntity
import dev.avijit.orbittuner.data.testDb
import dev.avijit.orbittuner.net.WireJson
import dev.avijit.orbittuner.settings.Prefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class HistorySignalsTest {
    private lateinit var db: AppDb

    @Before fun setUp() { db = testDb() }
    @After fun tearDown() = db.close()

    private suspend fun song(id: String, artist: String?, lang: String, cats: List<String>, openedAt: Long = 0) =
        db.songs().store(
            CachedSong(
                id = id, title = id, artist = artist, chordpro = "", language = lang,
                categoriesJson = WireJson.encodeToString(ListSerializer(String.serializer()), cats),
                originalKey = null, version = 1, updatedAt = 1, lastOpenedAt = openedAt, pinnedOffline = false,
            ),
            "",
        )

    @Test fun signalsFromOpenedFavouritedAndPlaylistSongs() = runTest {
        song("o1", "Meera Sen", "hi", listOf("bollywood"), openedAt = 100)
        song("o2", "Meera Sen", "hi", listOf("bollywood", "romantic"), openedAt = 200)
        song("f1", "Rabindranath Tagore", "bn", listOf("rabindra-sangeet"))
        song("p1", "Rabindranath Tagore", "bn", listOf("rabindra-sangeet"))
        song("p2", "Lata", "hi", listOf("classic"))
        song("unrelated", "Nobody", "ta", listOf("film")) // cached only: not history
        val lib = db.library()
        lib.upsertFavourite(Favourite("f1", favourite = true, updatedAt = 1, dirty = false))
        lib.upsertFavourite(Favourite("gone", favourite = false, updatedAt = 1, dirty = false))
        lib.upsertPlaylist(PlaylistEntity("pl", "Set", deleted = false, updatedAt = 1, dirty = false))
        lib.upsertItem(PlaylistItemEntity("pl", "p1", "a", "{}"))
        lib.upsertItem(PlaylistItemEntity("pl", "p2", "b", "{}"))
        lib.upsertItem(PlaylistItemEntity("pl", "o1", "c", "{}"))
        lib.upsertPlaylist(PlaylistEntity("dead", "Old", deleted = true, updatedAt = 1, dirty = false))
        lib.upsertItem(PlaylistItemEntity("dead", "unrelated", "a", "{}"))

        val s = loadHistorySignals(db, browsed = mapOf("rabindra-sangeet" to 2, "folk" to 1))
        assertEquals(setOf("f1"), s.favourites)
        assertEquals(setOf("p1", "p2", "o1"), s.playlists)
        assertEquals(mapOf("o1" to 100L, "o2" to 200L), s.openedAt)
        assertEquals(listOf("Meera Sen", "Rabindranath Tagore", "Lata"), s.topArtists)
        assertEquals(listOf("hi", "bn"), s.topLanguages)
        assertEquals(
            mapOf("bollywood" to 2, "romantic" to 1, "rabindra-sangeet" to 4, "classic" to 1, "folk" to 1),
            s.categoryCounts,
        )
    }

    @Test fun emptyHistory() = runTest {
        val s = loadHistorySignals(db, browsed = emptyMap())
        assertTrue(s.favourites.isEmpty() && s.playlists.isEmpty() && s.openedAt.isEmpty())
        assertTrue(s.topArtists.isEmpty() && s.topLanguages.isEmpty() && s.categoryCounts.isEmpty())
    }

    @Test fun topListsAreCapped() = runTest {
        for (i in 0 until 8) song("s$i", "Artist $i", listOf("en", "hi", "bn", "ta", "te")[i % 5], emptyList(), openedAt = 10L + i)
        val s = loadHistorySignals(db, browsed = emptyMap())
        assertEquals(5, s.topArtists.size)
        assertEquals(3, s.topLanguages.size)
    }

    @Test fun browsedCategoriesAreCountedAndKept() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = Prefs(ctx, "browsed-test-" + UUID.randomUUID())
        runBlocking {
            prefs.recordBrowsedCategory("90s")
            prefs.recordBrowsedCategory(" 90s ")
            prefs.recordBrowsedCategory("folk")
            prefs.recordBrowsedCategory("  ")
            assertEquals(mapOf("90s" to 2, "folk" to 1), prefs.browsedCategories.first())
        }
    }
}
