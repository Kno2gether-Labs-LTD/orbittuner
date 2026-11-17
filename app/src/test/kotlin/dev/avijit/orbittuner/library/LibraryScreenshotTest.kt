package dev.avijit.orbittuner.library

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.avijit.orbittuner.data.FakeService
import dev.avijit.orbittuner.data.LibraryRepository
import dev.avijit.orbittuner.data.SongRepository
import dev.avijit.orbittuner.data.db.AppDb
import dev.avijit.orbittuner.net.AuthStore
import dev.avijit.orbittuner.net.InMemoryKv
import dev.avijit.orbittuner.net.ItemSettings
import dev.avijit.orbittuner.net.AccountManager
import dev.avijit.orbittuner.net.ApiError
import dev.avijit.orbittuner.net.FakeAccounts
import dev.avijit.orbittuner.net.Song
import dev.avijit.orbittuner.net.UserDto
import dev.avijit.orbittuner.settings.Prefs
import dev.avijit.orbittuner.settings.SettingsScreen
import dev.avijit.orbittuner.settings.SettingsViewModel
import dev.avijit.orbittuner.songview.SongViewModel
import dev.avijit.orbittuner.ui.theme.OrbitTunerTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

/** Design renders (not assertions): written to app/build/screenshots, copied to docs/design/app by hand. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
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

    private val set = listOf(
        listOf("ag", "Amazing Grace", "John Newton", "G"),
        listOf("btmv", "Be Thou My Vision", "Irish traditional", "D"),
        listOf("hgta", "How Great Thou Art", "Carl Boberg", "Bb"),
        listOf("ctf", "Come Thou Fount", "Robert Robinson", "D"),
        listOf("iwwms", "It Is Well with My Soul", "Horatio Spafford", "C"),
        listOf("dox", "Doxology", "Thomas Ken", "G"),
    )

    private fun seed(): String = runBlocking {
        (set + listOf(listOf("wmt", "Wild Mountain Thyme", "Traditional", "G"))).forEach { (id, t, a, k) ->
            api.songs[id] = Song(id = id, title = t, artist = a, originalKey = k, chordpro = "{title: $t}\n{key: $k}\n[$k]Words of the song", updatedAt = 1)
        }
        val sunday = lib.createPlaylist("Sunday set")
        set.forEach { lib.addToPlaylist(sunday, it[0]) }
        lib.setItemSettings(sunday, "ag", ItemSettings(transpose = 2))
        lib.setItemSettings(sunday, "btmv", ItemSettings(capo = 2))
        lib.setItemSettings(sunday, "hgta", ItemSettings(capo = 1))
        lib.downloadForOffline(sunday)
        lib.createPlaylist("Campfire").also { id -> listOf("wmt", "ctf", "dox", "ag").forEach { lib.addToPlaylist(id, it) } }
        lib.createPlaylist("Wedding, 12 April").also { id -> listOf("iwwms", "btmv", "hgta").forEach { lib.addToPlaylist(id, it) } }
        lib.createPlaylist("Fingerpicking practice").also { id -> listOf("wmt", "ag").forEach { lib.addToPlaylist(id, it) } }
        listOf("wmt", "btmv", "ag").forEach { songs.open(it) } // most recent last
        sunday
    }

    private fun settle() {
        repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) }
        rule.mainClock.advanceTimeBy(1_500)
        rule.waitForIdle()
    }

    private fun shot(name: String) {
        settle()
        var bmp: Bitmap? = null
        rule.activityRule.scenario.onActivity { a ->
            val v = a.window.decorView
            bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(android.graphics.Canvas(it)) }
        }
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun library(name: String, dark: Boolean, openPlaylist: Boolean = false) {
        val sunday = seed()
        val vm = LibraryViewModel(lib, auth)
        if (openPlaylist) vm.select(sunday)
        rule.setContent { OrbitTunerTheme(darkTheme = dark) { LibraryScreen(vm, onOpenSong = {}, onPlaySet = { _, _ -> }, onOpenAccount = {}) } }
        rule.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); rule.onAllNodes(hasText("Sunday set")).fetchSemanticsNodes().isNotEmpty() }
        shot(name)
    }

    private fun settings(name: String, dark: Boolean, state: String = "ready", debug: Boolean = false) {
        val accounts = FakeAccounts().also { s -> s.tokenSeen = { auth.token() } }
        if (state != "none") auth.save("tuner.orbitapps.space", "tok1", UserDto("u1", role = "user"))
        if (state == "plus") accounts.plan = "plus"
        if (state == "disabled") accounts.meFails = ApiError.AccountDisabled
        runBlocking { seed() }
        val m = AccountManager(accounts, auth, scope, serviceHost = { "tuner.orbitapps.space" }, deviceName = "Pixel")
        runBlocking { m.refreshMe() }
        if (state == "disabled") m.accountDisabled("tok1")
        val prefs = Prefs(ctx, "shot-" + UUID.randomUUID())
        val vm = SettingsViewModel(prefs, auth, m, lib, requestSync = {}, debug = debug)
        rule.setContent { OrbitTunerTheme(darkTheme = dark) { SettingsScreen(vm) } }
        rule.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); rule.onAllNodes(hasText("Settings")).fetchSemanticsNodes().isNotEmpty() }
        shot(name)
    }

    @Test @Config(qualifiers = "w412dp-h915dp-port-xhdpi") fun libraryPhoneDark() = library("library-phone-dark", true)
    @Test @Config(qualifiers = "w412dp-h915dp-port-xhdpi") fun libraryPhoneLight() = library("library-phone-light", false)
    @Test @Config(qualifiers = "w412dp-h915dp-port-xhdpi") fun playlistPhoneDark() = library("library-playlist-phone-dark", true, openPlaylist = true)
    @Test @Config(qualifiers = "w412dp-h915dp-port-xhdpi") fun playlistPhoneLight() = library("library-playlist-phone-light", false, openPlaylist = true)
    @Test @Config(qualifiers = "w884dp-h1104dp-port-hdpi") fun libraryTabletDark() = library("library-tablet-dark", true, openPlaylist = true)
    @Test @Config(qualifiers = "w884dp-h1104dp-port-hdpi") fun libraryTabletLight() = library("library-tablet-light", false, openPlaylist = true)

    @Test @Config(qualifiers = "w412dp-h915dp-port-xhdpi") fun settingsPhoneDark() = settings("settings-phone-dark", true)
    @Test @Config(qualifiers = "w412dp-h915dp-port-xhdpi") fun settingsPhoneLight() = settings("settings-phone-light", false)
    @Test @Config(qualifiers = "w412dp-h915dp-port-xhdpi") fun settingsPlusDebugPhoneDark() = settings("settings-account-plus-debug-phone-dark", true, state = "plus", debug = true)
    @Test @Config(qualifiers = "w412dp-h915dp-port-xhdpi") fun settingsSettingUpPhoneLight() = settings("settings-account-setting-up-phone-light", false, state = "none")
    @Test @Config(qualifiers = "w412dp-h915dp-port-xhdpi") fun settingsDisabledPhoneDark() = settings("settings-account-disabled-phone-dark", true, state = "disabled")
    @Test @Config(qualifiers = "w884dp-h1104dp-port-hdpi") fun settingsTabletDark() = settings("settings-tablet-dark", true)
    @Test @Config(qualifiers = "w884dp-h1104dp-port-hdpi") fun settingsTabletLight() = settings("settings-tablet-light", false)

    @Test @Config(qualifiers = "w412dp-h915dp-port-xhdpi")
    fun setlistPhoneDark() {
        val sunday = seed()
        val items = set.map { SetlistItem(it[0], it[1]) }
        rule.setContent {
            OrbitTunerTheme(darkTheme = true) {
                SetlistPager("Sunday set", items, startIndex = 1, page = { id -> viewModel(key = "s:$id") { SongViewModel(id, sunday, songs, lib) } }, onBack = {})
            }
        }
        rule.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); rule.onAllNodes(hasText("Next: How Great Thou Art")).fetchSemanticsNodes().isNotEmpty() }
        shot("library-setlist-phone-dark")
    }
}
