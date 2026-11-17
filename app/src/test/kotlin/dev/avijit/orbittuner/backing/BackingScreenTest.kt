package dev.avijit.orbittuner.backing

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import dev.avijit.orbittuner.settings.Prefs
import dev.avijit.orbittuner.ui.theme.OrbitTunerTheme
import org.junit.Assert.assertEquals
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
class BackingScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val engine = FakeBacking()
    private val vm = BackingViewModel(engine, Prefs(ctx, "backing-screen-" + UUID.randomUUID()))
    private var shown by mutableStateOf(true)

    private fun show() {
        rule.setContent { OrbitTunerTheme { if (shown) BackingHost(vm) } }
        rule.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); vm.loaded }
        rule.waitForIdle()
    }

    private fun openLesson(id: String) {
        rule.onNodeWithTag("lessons").performScrollToNode(hasTestTag("lesson-$id"))
        rule.onNodeWithTag("lesson-$id").performClick()
        rule.waitForIdle()
    }

    @Test fun listGroupsSixLessonsUnderEachLevelInOrder() {
        show()
        Level.entries.forEach { level ->
            rule.onNodeWithTag("lessons").performScrollToNode(hasTestTag("level-${level.name}"))
            rule.onNodeWithTag("level-${level.name}").assertTextEquals(level.name)
            val lessons = Lessons.all.filter { it.level == level }
            assertEquals(6, lessons.size)
            lessons.forEach { l ->
                rule.onNodeWithTag("lessons").performScrollToNode(hasTestTag("lesson-${l.id}"))
                rule.onNodeWithText(l.title).assertIsDisplayed()
                rule.onNodeWithText("${l.groove.label} · ${tempoLabel(l, l.bpm)}").assertIsDisplayed()
            }
        }
        // Each header sits between the last lesson of the level before and the first of its own.
        listOf("slow-minor" to Level.Medium, "" to Level.Advanced).forEach { (before, level) ->
            rule.onNodeWithTag("lessons").performScrollToNode(hasTestTag("level-${level.name}"))
            val first = Lessons.all.first { it.level == level }.id
            val prev = if (level == Level.Medium) before else Lessons.all.last { it.level == Level.Medium }.id
            val a = rule.onNodeWithTag("lesson-$prev").getUnclippedBoundsInRoot().top
            val h = rule.onNodeWithTag("level-${level.name}").getUnclippedBoundsInRoot().top
            val c = rule.onNodeWithTag("lesson-$first").getUnclippedBoundsInRoot().top
            assertTrue("$prev < ${level.name} < $first", a < h && h < c)
        }
        rule.onNodeWithTag("lessons").performScrollToNode(hasTestTag("level-Easy"))
        val easy = rule.onNodeWithTag("level-Easy").getUnclippedBoundsInRoot().top
        val two = rule.onNodeWithTag("lesson-two-chord").getUnclippedBoundsInRoot().top
        assertTrue(easy < two)
    }

    @Test fun openingALessonShowsTheChordAndTheNextOne() {
        show()
        openLesson("four-chords")
        rule.onNodeWithText("The four chords").assertIsDisplayed()
        rule.onNodeWithTag("chord").assertTextEquals("G")
        rule.onNodeWithTag("next-chord").assertTextEquals("Next: D")
        rule.onNodeWithText("G major pentatonic").assertExists()
        rule.onNodeWithText("The progression behind a thousand pop songs.\nKeep the strum light and even.").assertExists()
    }

    @Test fun playTurnsIntoStopAndBack() {
        show()
        openLesson("four-chords")
        rule.onNodeWithContentDescription("Play backing track").performClick()
        rule.onNodeWithContentDescription("Stop backing track").assertIsDisplayed()
        assertEquals(1, engine.plays.size)
        rule.onNodeWithContentDescription("Stop backing track").performClick()
        rule.onNodeWithContentDescription("Play backing track").assertIsDisplayed()
        assertEquals(1, engine.stops)
    }

    @Test fun countInShowsTheCount() {
        show()
        openLesson("four-chords")
        rule.onNodeWithContentDescription("Play backing track").performClick()
        engine.state.value = engine.state.value.copy(beat = 1)
        rule.waitForIdle()
        rule.onNodeWithTag("count-in").assertTextEquals("1", "2", "3", "4")
        engine.state.value = engine.state.value.copy(bar = 0, beat = 0, countIn = false)
        rule.waitForIdle()
        rule.onNodeWithTag("count-in").assertDoesNotExist()
        assertEquals(4, rule.onAllNodesWithTag("beat-dot").fetchSemanticsNodes().size)
    }

    @Test fun sixEightCountsSix() {
        show()
        openLesson("bengali-6-8")
        rule.onNodeWithContentDescription("Play backing track").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("count-in").assertTextEquals("1", "2", "3", "4", "5", "6")
        rule.onNodeWithTag("tempo").assertTextEquals("♪ = 180")
    }

    @Test fun keyUpMovesTheShownChord() {
        show()
        openLesson("slow-minor")
        rule.onNodeWithTag("chord").assertTextEquals("Am")
        rule.onNodeWithContentDescription("Key up").performClick()
        rule.onNodeWithTag("chord").assertTextEquals(sequencerFirstChord(Lessons.byId("slow-minor")!!, 1))
        rule.onNodeWithTag("key").assertTextEquals("Bbm")
    }

    @Test fun tempoPlusFiveUpdatesTheLabel() {
        show()
        openLesson("four-chords")
        rule.onNodeWithTag("tempo").assertTextEquals("76 BPM")
        rule.onNodeWithContentDescription("Faster by 5").performClick()
        rule.onNodeWithTag("tempo").assertTextEquals("81 BPM")
        rule.onNodeWithContentDescription("Slower by 5").performClick()
        rule.onNodeWithTag("tempo").assertTextEquals("76 BPM")
    }

    @Test fun chordsOffStillPlaysDrumsAndBass() {
        show()
        openLesson("four-chords")
        rule.onNodeWithContentDescription("Chords").performClick()
        rule.onNodeWithContentDescription("Play backing track").performClick()
        assertEquals(false, engine.plays.single().chords)
    }

    @Test fun theDroneHasNoChordsToggle() {
        show()
        openLesson("drone")
        rule.onNodeWithContentDescription("Play backing track").assertExists()
        rule.onNodeWithContentDescription("Chords").assertDoesNotExist()
    }

    @Test fun controlsAreFullTouchTargets() {
        show()
        openLesson("four-chords")
        listOf("Key down", "Key up", "Slower by 5", "Faster by 5", "Play backing track", "Back to lessons").forEach { d ->
            val b = rule.onNodeWithContentDescription(d).getUnclippedBoundsInRoot()
            assertTrue("$d is ${b.width} x ${b.height}", b.width >= 48.dp && b.height >= 48.dp)
        }
    }

    @Test fun anotherPlayerStartingShowsPlayAgain() {
        show()
        openLesson("four-chords")
        rule.onNodeWithContentDescription("Play backing track").performClick()
        engine.state.value = engine.state.value.copy(playing = false, beat = -1) // the metronome took over
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Play backing track").assertIsDisplayed()
    }

    @Test fun backGoesToTheListAndStops() {
        show()
        openLesson("four-chords")
        rule.onNodeWithContentDescription("Play backing track").performClick()
        rule.onNodeWithContentDescription("Back to lessons").performClick()
        rule.waitForIdle()
        assertEquals(1, engine.stops)
        rule.onNodeWithTag("lessons").assertExists()
    }

    @Test fun leavingTheScreenStops() {
        show()
        openLesson("four-chords")
        rule.onNodeWithContentDescription("Play backing track").performClick()
        shown = false
        rule.waitForIdle()
        assertEquals(1, engine.stops)
    }

    @Test fun backgroundingStops() {
        show()
        openLesson("four-chords")
        rule.onNodeWithContentDescription("Play backing track").performClick()
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertEquals(1, engine.stops)
    }

    @Test fun rotationKeepsPlaying() {
        show()
        openLesson("four-chords")
        rule.onNodeWithContentDescription("Play backing track").performClick()
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        assertEquals(0, engine.stops)
        assertTrue(engine.state.value.playing)
    }

    @Test fun scaleHintFollowsTheKeyOnScreen() {
        show()
        openLesson("two-chord")
        rule.onNodeWithTag("scale-hint", useUnmergedTree = true).assertTextEquals("E minor pentatonic")
        rule.onAllNodesWithTag("practice-key-note").assertCountEquals(0)
        vm.shiftKey(2)
        rule.waitForIdle()
        rule.onNodeWithTag("scale-hint", useUnmergedTree = true).assertTextEquals("F♯ minor pentatonic")
        rule.onNodeWithTag("practice-key-note", useUnmergedTree = true).assertExists()
    }

    @Test fun lessonRowsReadTempoInWords() {
        show()
        rule.onNodeWithTag("lessons").performScrollToNode(hasTestTag("lesson-bengali-6-8"))
        rule.onNodeWithContentDescription("6/8, 180 eighth notes per minute", substring = true).assertExists()
    }
}
