package dev.avijit.orbittuner.backing

import androidx.test.core.app.ApplicationProvider
import dev.avijit.orbittuner.OrbitTunerApp
import dev.avijit.orbittuner.PlaybackCoordinator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Only one of the metronome and a backing track plays: starting either stops the other (real engines, AppGraph). */
@RunWith(RobolectricTestRunner::class)
class PlaybackExclusionTest {
    private val graph = ApplicationProvider.getApplicationContext<OrbitTunerApp>().graph
    private val lesson = Lessons.all.first()

    @After fun tearDown() {
        graph.metronome.stop()
        graph.backing.stop()
    }

    @Test fun backingStopsTheMetronome() {
        graph.metronome.start()
        assertTrue(graph.metronome.state.value.playing)
        graph.backing.play(lesson, 0, lesson.bpm, chords = true)
        assertTrue(graph.backing.state.value.playing)
        assertFalse(graph.metronome.state.value.playing)
    }

    @Test fun metronomeStopsTheBacking() {
        graph.backing.play(lesson, 0, lesson.bpm, chords = true)
        assertEquals(lesson.id, graph.backing.state.value.lessonId)
        assertTrue(graph.backing.state.value.countIn)
        graph.metronome.start()
        assertTrue(graph.metronome.state.value.playing)
        assertFalse(graph.backing.state.value.playing)
    }

    @Test fun coordinatorStopsEveryoneElse() {
        val c = PlaybackCoordinator()
        val stopped = mutableListOf<String>()
        c.register("a") { stopped += "a" }
        c.register("b") { stopped += "b" }
        c.register("c") { stopped += "c" }
        c.starting("b")
        assertEquals(listOf("a", "c"), stopped)
    }
}
