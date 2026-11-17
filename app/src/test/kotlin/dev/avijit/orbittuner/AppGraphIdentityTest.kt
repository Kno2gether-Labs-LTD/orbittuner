package dev.avijit.orbittuner

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.avijit.orbittuner.net.InMemoryKv
import dev.avijit.orbittuner.net.UserDto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The AppGraph identity watcher: a Google re-bind (same token, new user id) wipes the old account's library. */
@RunWith(RobolectricTestRunner::class)
class AppGraphIdentityTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val graph = AppGraph(ctx) { InMemoryKv() }

    @After fun tearDown() {
        shadowOf(Looper.getMainLooper()).idle()
        graph.db.close()
        ctx.deleteDatabase("orbittuner.db")
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** The watcher hops between the main looper and Room's executors: poll both. */
    private fun until(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (true) {
            idle()
            if (cond()) return
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(10)
        }
    }

    private fun names() = runBlocking { graph.library.playlists().first() }.map { it.playlist.name }

    @Test fun reboundIdentityWithTheSameTokenWipesLocalPrivateData() {
        graph.auth.save("tuner.orbitapps.space", "tok1", UserDto("u1", role = "user"))
        val lib = graph.library
        idle()
        runBlocking { lib.createPlaylist("Anonymous account's") }
        until { names() == listOf("Anonymous account's") }

        // What AccountManager.linkGoogle saves after a server-side merge.
        graph.auth.save("tuner.orbitapps.space", "tok1", UserDto("x9", role = "user", plan = "plus"))
        until { names().isEmpty() }
    }

    @Test fun sameIdentityResaveKeepsLocalData() {
        graph.auth.save("tuner.orbitapps.space", "tok1", UserDto("u1", role = "user"))
        val lib = graph.library
        idle()
        runBlocking { lib.createPlaylist("Mine") }
        graph.auth.save("tuner.orbitapps.space", "tok1", UserDto("u1", role = "user", plan = "plus"))
        repeat(20) { idle(); Thread.sleep(10) }
        assertEquals(listOf("Mine"), names())
    }
}
