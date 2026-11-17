package dev.avijit.orbittuner.metronome

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.avijit.orbittuner.settings.Prefs
import dev.avijit.orbittuner.ui.theme.OrbitTunerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class MetronomeScreenTest {
    @get:Rule val rule = createComposeRule()

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val engine = FakeMetronome()
    private val vm = MetronomeViewModel(engine, Prefs(ctx, "metronome-screen-" + UUID.randomUUID()), { 0L })

    private fun show(compact: Boolean = false) {
        rule.setContent { OrbitTunerTheme { MetronomeScreen(vm, compact = compact) } }
        rule.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); vm.loaded }
        rule.waitForIdle()
    }

    @Test fun plusFiveUpdatesTheNumber() {
        show()
        rule.onNodeWithTag("bpm").assertTextEquals("80")
        rule.onNodeWithText("Andante").assertIsDisplayed()
        rule.onNodeWithText("+5").performClick()
        rule.onNodeWithTag("bpm").assertTextEquals("85")
        rule.onNodeWithText("−1").performClick()
        rule.onNodeWithTag("bpm").assertTextEquals("84")
    }

    @Test fun playButtonToggles() {
        show()
        rule.onNodeWithText("Start slow. Speed up once it feels easy.").assertIsDisplayed()
        rule.onNodeWithContentDescription("Start metronome").performClick()
        assertEquals(1, engine.starts)
        rule.onNodeWithContentDescription("Stop metronome").performClick()
        assertEquals(1, engine.stops)
        rule.onNodeWithContentDescription("Start metronome").assertIsDisplayed()
    }

    @Test fun threeBeatsShowThreeDots() {
        show()
        assertEquals(4, rule.onAllNodesWithTag("beat-dot").fetchSemanticsNodes().size)
        rule.onNodeWithTag("beats-3").performClick()
        rule.waitForIdle()
        assertEquals(3, rule.onAllNodesWithTag("beat-dot").fetchSemanticsNodes().size)
        assertEquals(3, engine.state.value.beatsPerBar)
    }

    @Test fun beatChipsAreFullTouchTargets() {
        show()
        Tempo.BEAT_CHOICES.forEach { n ->
            val b = rule.onNodeWithTag("beats-$n").getUnclippedBoundsInRoot()
            assertTrue("chip $n is ${b.width} x ${b.height}", b.width >= 48.dp && b.height >= 48.dp)
        }
    }

    @Test @Config(qualifiers = "w360dp-h640dp")
    fun beatChipsFitASmallPhone() {
        show()
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        val six = rule.onNodeWithTag("beats-6").getUnclippedBoundsInRoot()
        assertTrue("chip 6 ends at ${six.right}", six.right <= root.right - 16.dp)
        rule.onNodeWithText("Beats per bar").assertIsDisplayed()
    }

    @Test fun accentToggle() {
        show()
        rule.onNodeWithContentDescription("Accent the first beat").performClick()
        assertEquals(false, engine.state.value.accent)
    }

    @Test fun tapTempoIsThere() {
        show(compact = true)
        rule.onNodeWithText("Tap tempo").performClick()
        rule.onNodeWithContentDescription("Start metronome").assertIsDisplayed()
    }
}
