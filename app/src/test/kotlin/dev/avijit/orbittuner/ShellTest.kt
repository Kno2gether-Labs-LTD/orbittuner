package dev.avijit.orbittuner

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.test.core.app.ApplicationProvider
import dev.avijit.orbittuner.net.InMemoryKv
import dev.avijit.orbittuner.ui.shell.AppShell
import android.os.Looper
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithTag
import dev.avijit.orbittuner.settings.ToolsPane
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class ShellTest {
    @get:Rule val rule = createComposeRule()

    private val labels = listOf("Tune", "Songs", "Library", "Tools", "Settings")

    private fun show(): AppGraph {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val graph = AppGraph(ctx) { InMemoryKv() }
        rule.setContent { AppShell(graph) }
        return graph
    }

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun phoneShowsBottomBarWithFiveDestinations() {
        show()
        labels.forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        val songs = rule.onNodeWithText("Songs").getUnclippedBoundsInRoot()
        assertTrue("label should sit in the bottom half", songs.top > root.bottom / 2)
    }

    @Test @Config(qualifiers = "w1000dp-h800dp")
    fun tabletShowsNavigationRailWithFiveDestinations() {
        show()
        labels.forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        val songs = rule.onNodeWithText("Songs").getUnclippedBoundsInRoot()
        assertTrue("label should sit in the left rail", songs.left < 150.dp)
    }

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun clickingSongsShowsSearch() {
        show()
        rule.onNodeWithText("Songs").performClick()
        rule.onNodeWithText("Songs, artists or lyrics").assertIsDisplayed()
        rule.onNodeWithText("Connecting to the song library").assertIsDisplayed()
        rule.onNodeWithText("Account settings").performClick()
        rule.onNodeWithText("Setting up your account").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun toolsShowsTheMetronome() {
        show()
        rule.onNodeWithText("Tools").performClick()
        rule.waitUntilExactlyOneExists(hasContentDescription("Start metronome"), 5_000)
        rule.onNodeWithText("Metronome").assertIsSelected()
        rule.onNodeWithText("Backing tracks").assertIsNotSelected()
        rule.onNodeWithContentDescription("Start metronome").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun toolsRemembersBackingTracks() {
        val graph = show()
        rule.onNodeWithText("Tools").performClick()
        rule.waitUntilExactlyOneExists(hasContentDescription("Start metronome"), 5_000)
        rule.onNodeWithText("Backing tracks").performClick()
        rule.onNodeWithTag("level-Easy").assertIsDisplayed()
        rule.onNodeWithText("Backing tracks").assertIsSelected()
        runBlocking {
            val until = System.currentTimeMillis() + 5_000
            while (graph.prefs.toolsPane.first() != ToolsPane.Backing && System.currentTimeMillis() < until) {
                shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
            }
            assertEquals(ToolsPane.Backing, graph.prefs.toolsPane.first())
        }
    }
}
