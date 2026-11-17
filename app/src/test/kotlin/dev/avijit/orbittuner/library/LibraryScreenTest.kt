package dev.avijit.orbittuner.library

import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.avijit.orbittuner.data.FakeService
import dev.avijit.orbittuner.data.LibraryRepository
import dev.avijit.orbittuner.data.SongRepository
import dev.avijit.orbittuner.data.UnsyncedChanges
import dev.avijit.orbittuner.data.db.AppDb
import dev.avijit.orbittuner.net.AuthStore
import dev.avijit.orbittuner.net.InMemoryKv
import dev.avijit.orbittuner.net.AccountManager
import dev.avijit.orbittuner.net.ApiError
import dev.avijit.orbittuner.net.FakeAccounts
import dev.avijit.orbittuner.net.GoogleIdTokenProvider
import dev.avijit.orbittuner.net.GoogleSignIn
import dev.avijit.orbittuner.net.IdTokenResult
import dev.avijit.orbittuner.net.Song
import dev.avijit.orbittuner.net.UserDto
import dev.avijit.orbittuner.settings.Prefs
import dev.avijit.orbittuner.settings.SettingsScreen
import dev.avijit.orbittuner.settings.SettingsViewModel
import dev.avijit.orbittuner.ui.theme.OrbitTunerTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class LibraryScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    // Real query executors (see SongScreenTest): flow re-queries must not run inside the writer's transaction.
    private val db: AppDb = Room.inMemoryDatabaseBuilder(ctx, AppDb::class.java).allowMainThreadQueries().build()
    private val api = FakeService()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val songs = SongRepository(api, db, scope)
    private val lib = LibraryRepository(db, songs, identity = { null }, session = { null })
    private val auth = AuthStore(InMemoryKv())

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

    private fun exists(text: String) = rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun show(vm: LibraryViewModel) = rule.setContent {
        OrbitTunerTheme { LibraryScreen(vm, onOpenSong = {}, onPlaySet = { _, _ -> }, onOpenAccount = {}) }
    }

    @Test fun creatingAPlaylistShowsIt() {
        show(LibraryViewModel(lib, auth))
        rule.onNodeWithText("New playlist").performClick()
        rule.onNodeWithTag("new-playlist-name").performTextInput("Sunday")
        rule.onNodeWithText("Create").performClick()
        until { exists("Sunday") }
        rule.onNodeWithText("Sunday").assertIsDisplayed()
        assertEquals(listOf("Sunday"), runBlocking { lib.playlists().first().map { it.playlist.name } })
    }

    @Test fun draggingItem3AboveItem1MovesWithTheRightNeighbours() {
        listOf("a" to "Alpha", "b" to "Beta", "c" to "Gamma").forEach { (id, t) ->
            api.songs[id] = Song(id = id, title = t, chordpro = "{title: $t}\n[G]Words", updatedAt = 1)
        }
        val pid = runBlocking {
            lib.createPlaylist("Sunday").also { id -> listOf("a", "b", "c").forEach { lib.addToPlaylist(id, it) } }
        }
        val calls = mutableListOf<List<String?>>()
        val vm = LibraryViewModel(lib, auth, move = { p, song, prev, next ->
            calls += listOf(song, prev, next)
            lib.move(p, song, prev, next)
        })
        vm.select(pid)
        show(vm)
        until { rule.onAllNodesWithTag("grip").fetchSemanticsNodes().size == 3 }

        val rowHeight = rule.onAllNodesWithTag("setlist-row")[0].fetchSemanticsNode().size.height.toFloat()
        rule.onAllNodesWithTag("grip")[2].performTouchInput {
            down(center)
            val step = rowHeight / 10f
            repeat(25) { moveBy(Offset(0f, -step)) }
            up()
        }
        until { calls.isNotEmpty() }
        assertEquals(listOf(listOf("c", null, "a")), calls)
        until { runBlocking { lib.playlists().first().single().songs.map { it.songId } } == listOf("c", "a", "b") }
    }

    @Test fun disabledAccountShowsANonBlockingBannerInTheLibrary() {
        show(LibraryViewModel(lib, auth, accountDisabled = MutableStateFlow(true)))
        rule.onNodeWithTag("account-banner").assertIsDisplayed()
        // Non-blocking: the rest of the library is still there.
        rule.onNodeWithText("New playlist").assertIsDisplayed()
    }

    private val accounts = FakeAccounts().also { s -> s.tokenSeen = { auth.token() } }
    private fun manager() = AccountManager(accounts, auth, scope, serviceHost = { "tuner.orbitapps.space" }, deviceName = "Test")
    private fun settingsVm(debug: Boolean = false, m: AccountManager = manager(), google: GoogleSignIn? = null) =
        SettingsViewModel(Prefs(ctx, "settings-test-" + UUID.randomUUID()), auth, m, lib, requestSync = {}, debug = debug, googleSignIn = google)

    private fun googleSignIn(m: AccountManager, result: IdTokenResult, unsynced: UnsyncedChanges = UnsyncedChanges(0, 0)) =
        GoogleSignIn(object : GoogleIdTokenProvider {
            override suspend fun requestIdToken(context: Context) = result
        }, m, pushLocal = {}, unsynced = { unsynced })

    @Test fun accountSectionShowsPlanAllowanceGoogleAndSignOut() {
        auth.save("tuner.orbitapps.space", "tok1", UserDto("u1", role = "user"))
        val m = manager()
        runBlocking { m.refreshMe() }
        rule.setContent { OrbitTunerTheme { SettingsScreen(settingsVm(m = m)) } }
        until { exists("AI imports left today") }
        rule.onNode(hasText("Free") and hasTestTag("plan-chip")).assertIsDisplayed()
        rule.onNodeWithText("2 of 3").assertIsDisplayed()
        rule.onNodeWithText("Sign in with Google").assertIsDisplayed()
        rule.onNodeWithText("Google sign-in isn't set up yet").assertIsDisplayed()
        rule.onNodeWithText("Sign out of this device").performClick()
        rule.onNodeWithTag("sign-out-confirm").performClick()
        until { accounts.deleted.isNotEmpty() }
        assertEquals(listOf<String?>("tok1"), accounts.deleted)
    }

    @Test fun missingGoogleClientIdHidesTheButton() {
        auth.save("tuner.orbitapps.space", "tok1", UserDto("u1", role = "user"))
        val m = manager()
        rule.setContent { OrbitTunerTheme { SettingsScreen(settingsVm(m = m, google = null)) } }
        until { exists("Google sign-in isn't set up yet") }
        assertTrue(rule.onAllNodesWithTag("google-sign-in").fetchSemanticsNodes().isEmpty())
    }

    @Test fun signingInWithGoogleShowsSignedInAsTheEmailAndKeepsSignOut() {
        auth.save("tuner.orbitapps.space", "tok1", UserDto("u1", role = "user"))
        val m = manager()
        runBlocking { m.refreshMe() }
        rule.setContent { OrbitTunerTheme { SettingsScreen(settingsVm(m = m, google = googleSignIn(m, IdTokenResult.Token("id1")))) } }
        until { exists("AI imports left today") }
        rule.onNodeWithTag("google-sign-in").performScrollTo().performClick()
        until { exists("Signed in as sam@example.com") }
        assertEquals(listOf("id1"), accounts.googleCalls)
        rule.onNode(hasText("Free") and hasTestTag("plan-chip")).assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTag("google-sign-in").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("Sign out of this device").assertExists()
    }

    @Test fun cancellingGoogleSignInChangesNothing() {
        auth.save("tuner.orbitapps.space", "tok1", UserDto("u1", role = "user"))
        val m = manager()
        runBlocking { m.refreshMe() }
        rule.setContent { OrbitTunerTheme { SettingsScreen(settingsVm(m = m, google = googleSignIn(m, IdTokenResult.Cancelled))) } }
        until { exists("AI imports left today") }
        rule.onNodeWithTag("google-sign-in").performScrollTo().performClick()
        rule.waitForIdle(); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(accounts.googleCalls.isEmpty())
        assertTrue(rule.onAllNodesWithTag("google-error").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("google-sign-in").assertExists()
        assertEquals("tok1", auth.token())
    }

    @Test fun googleSignInErrorIsExplained() {
        auth.save("tuner.orbitapps.space", "tok1", UserDto("u1", role = "user"))
        accounts.google = { throw ApiError.Http(409, "google_already_linked") }
        val m = manager()
        runBlocking { m.refreshMe() }
        rule.setContent { OrbitTunerTheme { SettingsScreen(settingsVm(m = m, google = googleSignIn(m, IdTokenResult.Token("id1")))) } }
        until { exists("AI imports left today") }
        rule.onNodeWithTag("google-sign-in").performScrollTo().performClick()
        until { rule.onAllNodesWithTag("google-error").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Sign out of this device first", substring = true).assertExists()
    }

    @Test fun rejectedChangesAskBeforeSigningInWithGoogle() {
        auth.save("tuner.orbitapps.space", "tok1", UserDto("u1", role = "user"))
        val m = manager()
        runBlocking { m.refreshMe() }
        val g = googleSignIn(m, IdTokenResult.Token("id1"), UnsyncedChanges(pending = 0, rejected = 2))
        rule.setContent { OrbitTunerTheme { SettingsScreen(settingsVm(m = m, google = g)) } }
        until { exists("AI imports left today") }
        rule.onNodeWithTag("google-sign-in").performScrollTo().performClick()
        until { exists(CONFIRM_TEXT) }
        assertTrue(accounts.googleCalls.isEmpty())
        // Cancel: nothing happens.
        rule.onNodeWithTag("google-confirm-cancel").performClick()
        until { !exists(CONFIRM_TEXT) }
        assertTrue(accounts.googleCalls.isEmpty())
        // Again, and continue.
        rule.onNodeWithTag("google-sign-in").performScrollTo().performClick()
        until { exists(CONFIRM_TEXT) }
        rule.onNodeWithTag("google-confirm").performClick()
        until { exists("Signed in as sam@example.com") }
        assertEquals(1, accounts.googleCalls.size)
    }

    @Test fun noAccountYetSaysItIsSettingUp() {
        rule.setContent { OrbitTunerTheme { SettingsScreen(settingsVm()) } }
        until { exists("Setting up your account") }
    }

    @Test fun disabledAccountShowsTheBannerInSettings() {
        auth.save("tuner.orbitapps.space", "tok1", UserDto("u1", role = "user"))
        accounts.meFails = ApiError.AccountDisabled
        val m = manager()
        m.accountDisabled("tok1")
        rule.setContent { OrbitTunerTheme { SettingsScreen(settingsVm(m = m)) } }
        until { exists("This account is disabled") }
    }

    @Test fun developerHostOverrideOnlyInDebugBuilds() {
        val release = settingsVm(debug = false)
        assertFalse(release.setServiceHost("10.0.2.2:8787"))
        val debug = settingsVm(debug = true)
        assertFalse(debug.setServiceHost("evil.com/x"))
        rule.setContent { OrbitTunerTheme { SettingsScreen(debug) } }
        until { exists("Developer") }
        rule.onNodeWithText("Service host").performScrollTo().assertIsDisplayed()
    }

    @Test fun developerSectionHiddenInReleaseBuilds() {
        rule.setContent { OrbitTunerTheme { SettingsScreen(settingsVm(debug = false)) } }
        until { exists("Appearance") }
        assertFalse(exists("Developer"))
    }

    @Test fun revokedResetNoticeShowsInTheLibraryAndDismisses() {
        val notice = MutableStateFlow(true)
        show(LibraryViewModel(lib, auth, resetNotice = notice, dismissResetNotice = { notice.value = false }))
        rule.onNodeWithTag("account-reset-banner").assertIsDisplayed()
        rule.onNodeWithText(RESET_TEXT, substring = true).assertIsDisplayed()
        rule.onNodeWithText("Dismiss").performClick()
        until { !exists(RESET_TEXT) }
    }

    @Test fun revokedResetNoticeShowsInSettingsAndDismissIsRemembered() {
        val prefs = Prefs(ctx, "settings-test-" + UUID.randomUUID())
        runBlocking { prefs.setAccountResetNotice(true) }
        val vm = SettingsViewModel(prefs, auth, manager(), lib, requestSync = {})
        rule.setContent { OrbitTunerTheme { SettingsScreen(vm) } }
        until { exists(RESET_TEXT) }
        rule.onNodeWithTag("account-reset-banner").assertIsDisplayed()
        rule.onNodeWithText("Dismiss").performClick()
        until { !exists(RESET_TEXT) }
        until { runBlocking { !prefs.accountResetNotice.first() } }
    }

    private companion object {
        const val CONFIRM_TEXT =
            "2 changes couldn't be saved and will be removed if this Google account already has OrbitTuner data. Continue?"
        const val RESET_TEXT = "This device was signed out, so a new account was started. Favourites and playlists on this device were cleared."
    }
}
