package dev.avijit.orbittuner.library

import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.isActive
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.avijit.orbittuner.data.FakeService
import dev.avijit.orbittuner.data.LibraryRepository
import dev.avijit.orbittuner.data.SongRepository
import dev.avijit.orbittuner.data.db.AppDb
import dev.avijit.orbittuner.net.ItemSettings
import dev.avijit.orbittuner.net.Song
import dev.avijit.orbittuner.songview.SongViewModel
import dev.avijit.orbittuner.ui.theme.OrbitTunerTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class SetlistPagerTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db: AppDb = Room.inMemoryDatabaseBuilder(ctx, AppDb::class.java).allowMainThreadQueries().build()
    private val api = FakeService()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val songs = SongRepository(api, db, scope)
    private val lib = LibraryRepository(db, songs, identity = { null }, session = { null })

    @After fun tearDown() {
        // Let in-flight writes (view-model and repository coroutines on Main) finish before the database closes, so
        // nothing fails after the test and gets reported against a later one.
        rule.waitForIdle()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        scope.cancel()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        db.close()
    }

    private fun until(cond: () -> Boolean) = rule.waitUntil(5_000) {
        shadowOf(Looper.getMainLooper()).idle()
        cond()
    }

    private fun shown(text: String) = rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    private fun described(desc: String) = rule.onAllNodes(hasContentDescription(desc)).fetchSemanticsNodes().isNotEmpty()

    @Test fun opensAtItem0SwipesToItem1AndKeepsEachItemsSettings() {
        api.songs["a"] = Song(id = "a", title = "Alpha", originalKey = "G", chordpro = "{key: G}\n[G]First [C]song", updatedAt = 1)
        api.songs["b"] = Song(id = "b", title = "Beta", originalKey = "D", chordpro = "{key: D}\n[D]Second [G]song", updatedAt = 1)
        val pid = runBlocking {
            lib.createPlaylist("Sunday").also { id ->
                lib.addToPlaylist(id, "a"); lib.addToPlaylist(id, "b")
                lib.setItemSettings(id, "b", ItemSettings(capo = 3))
            }
        }
        val items = listOf(SetlistItem("a", "Alpha"), SetlistItem("b", "Beta"))
        rule.setContent {
            OrbitTunerTheme {
                SetlistPager(
                    name = "Sunday", items = items, startIndex = 0,
                    page = { songId -> viewModel(key = "setlist:$songId") { SongViewModel(songId, pid, songs, lib) } },
                    onBack = {},
                )
            }
        }
        until { shown("Alpha") && described("Capo 0") }
        rule.onNodeWithTag("setlist-pager").assertIsDisplayed()
        // A short song is all on screen, so its end is showing: the next song is announced.
        until { shown("Next: Beta") }

        rule.onNodeWithTag("setlist-pager").performTouchInput { swipeLeft() }
        until { shown("Beta") && described("Capo 3") }
        rule.onNode(hasContentDescription("Capo 3")).assertIsDisplayed()

        rule.onNodeWithTag("setlist-pager").performTouchInput { swipeRight() }
        until { described("Capo 0") }
        assertTrue("capo 3 belongs to Beta only", rule.onAllNodes(hasContentDescription("Capo 3")).fetchSemanticsNodes().isEmpty())
    }

    /** Review: pages more than one away are cleared, so off-screen songs stop loading and collecting. */
    @Test fun pagesFarFromTheCurrentOneAreCleared() {
        listOf("a" to "Alpha", "b" to "Beta", "c" to "Gamma").forEach { (id, t) ->
            api.songs[id] = Song(id = id, title = t, originalKey = "G", chordpro = "{key: G}\n[G]$t", updatedAt = 1)
        }
        val pid = runBlocking { lib.createPlaylist("Sunday").also { id -> listOf("a", "b", "c").forEach { lib.addToPlaylist(id, it) } } }
        val vms = HashMap<String, SongViewModel>()
        rule.setContent {
            OrbitTunerTheme {
                SetlistPager(
                    name = "Sunday", items = listOf(SetlistItem("a", "Alpha"), SetlistItem("b", "Beta"), SetlistItem("c", "Gamma")), startIndex = 0,
                    page = { songId -> viewModel(key = "setlist:$songId") { SongViewModel(songId, pid, songs, lib) }.also { vms[songId] = it } },
                    onBack = {},
                )
            }
        }
        until { shown("Alpha") }
        rule.onNodeWithTag("setlist-pager").performTouchInput { swipeLeft() }
        until { shown("Beta") }
        rule.onNodeWithTag("setlist-pager").performTouchInput { swipeLeft() }
        until { shown("Gamma") }
        until { vms["a"]?.viewModelScope?.isActive == false }
        assertTrue("the neighbour stays alive", vms.getValue("b").viewModelScope.isActive)
    }
}
