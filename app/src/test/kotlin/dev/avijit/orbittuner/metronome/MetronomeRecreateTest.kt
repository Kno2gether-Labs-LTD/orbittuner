package dev.avijit.orbittuner.metronome

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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

/** The real engine through MainActivity: a configuration change (rotation, Fold unfold) keeps it playing; leaving stops it. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class MetronomeRecreateTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun engine() = (rule.activity.application as OrbitTunerApp).graph.metronome

    @After fun tearDown() = engine().stop()

    private fun startOnTools() {
        rule.onNodeWithText("Tools").performClick()
        rule.waitUntilExactlyOneExists(hasContentDescription("Start metronome"), 5_000)
        rule.onNodeWithContentDescription("Start metronome").performClick()
        rule.waitForIdle()
        assertTrue(engine().state.value.playing)
    }

    @Test fun recreateKeepsPlaying() {
        startOnTools()
        rule.activityRule.scenario.recreate()
        rule.waitUntilExactlyOneExists(hasContentDescription("Stop metronome"), 5_000)
        assertTrue(engine().state.value.playing)
    }

    @Test fun backgroundingStops() {
        startOnTools()
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED) // ON_STOP, not a configuration change
        assertFalse(engine().state.value.playing)
    }
}
