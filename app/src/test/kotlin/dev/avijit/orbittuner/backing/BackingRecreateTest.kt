package dev.avijit.orbittuner.backing

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import dev.avijit.orbittuner.MainActivity
import dev.avijit.orbittuner.OrbitTunerApp
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The real engines through MainActivity: rotation keeps a backing track playing, leaving the app or the pane stops it,
 * and starting the metronome stops it.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class BackingRecreateTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun graph() = (rule.activity.application as OrbitTunerApp).graph

    @After fun tearDown() {
        graph().backing.stop()
        graph().metronome.stop()
    }

    private fun startOnTools() {
        rule.onNodeWithText("Tools").performClick()
        rule.waitUntilExactlyOneExists(hasTestTag("tools-backing"), 5_000)
        rule.onNodeWithTag("tools-backing").performClick()
        rule.waitUntilExactlyOneExists(hasTestTag("lesson-two-chord"), 5_000)
        rule.onNodeWithTag("lesson-two-chord").performClick()
        rule.onNodeWithContentDescription("Play backing track").performClick()
        rule.waitForIdle()
        assertTrue(graph().backing.state.value.playing)
    }

    @Test fun recreateKeepsPlaying() {
        startOnTools()
        rule.activityRule.scenario.recreate()
        rule.waitUntilExactlyOneExists(hasContentDescription("Stop backing track"), 5_000)
        assertTrue(graph().backing.state.value.playing)
    }

    @Test fun backgroundingStops() {
        startOnTools()
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertFalse(graph().backing.state.value.playing)
    }

    @Test fun switchingToTheMetronomeStops() {
        startOnTools()
        rule.onNodeWithTag("tools-metronome").performClick()
        rule.waitUntilExactlyOneExists(hasContentDescription("Start metronome"), 5_000)
        assertFalse(graph().backing.state.value.playing)
    }

    @Test fun startingTheMetronomeStopsTheTrack() {
        startOnTools()
        graph().metronome.start() // e.g. from a song's metronome sheet
        rule.waitUntilExactlyOneExists(hasContentDescription("Play backing track"), 5_000)
        assertFalse(graph().backing.state.value.playing)
    }
}
