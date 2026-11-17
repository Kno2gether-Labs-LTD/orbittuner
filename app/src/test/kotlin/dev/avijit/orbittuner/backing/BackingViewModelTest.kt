package dev.avijit.orbittuner.backing

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.avijit.orbittuner.settings.Prefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class BackingViewModelTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = Prefs(ctx, "backing-vm-" + UUID.randomUUID())
    private val engine = FakeBacking()

    private fun vm(p: Prefs = prefs, e: Backing = engine): BackingViewModel {
        val vm = BackingViewModel(e, p)
        val until = System.currentTimeMillis() + 5_000
        while (!vm.loaded && System.currentTimeMillis() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertTrue("saved values loaded", vm.loaded)
        return vm
    }

    private fun lesson(id: String) = Lessons.byId(id)!!

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun opensAtTheLessonDefaultsAndPreviewsTheFirstChange() {
        val vm = vm()
        assertNull(vm.selection.value.lesson)
        vm.open("four-chords")
        val s = vm.selection.value
        assertEquals("four-chords", s.lesson?.id)
        assertEquals(0, s.keyOffset)
        assertEquals(76, s.bpm)
        assertTrue(s.chords)
        val d = BackingViewModel.display(s, engine.state.value)
        assertEquals("G", d.chord)
        assertEquals("D", d.nextChord)
        assertFalse(d.playing)
    }

    @Test fun playPassesKeyTempoAndChordsAndToggleStops() {
        val vm = vm()
        vm.open("two-chord")
        vm.toggle()
        assertEquals(listOf(FakeBacking.Play("two-chord", 0, 70, true)), engine.plays)
        assertTrue(BackingViewModel.display(vm.selection.value, engine.state.value).playing)
        vm.toggle()
        assertEquals(1, engine.stops)
        assertFalse(BackingViewModel.display(vm.selection.value, engine.state.value).playing)
    }

    @Test fun keyUpMovesTheChordAsTheSequencerSpellsIt() {
        val vm = vm()
        val l = lesson("slow-minor")
        vm.open(l.id)
        vm.shiftKey(+1)
        val d = BackingViewModel.display(vm.selection.value, engine.state.value)
        assertEquals(sequencerFirstChord(l, 1), d.chord)
        assertEquals("Bbm", d.chord) // A minor up a semitone: B♭ minor takes flats
        assertEquals("Bbm", l.keyIn(vm.selection.value.keyOffset))
    }

    @Test fun keyAndTempoClampAndReachAPlayingEngine() {
        val vm = vm()
        vm.open("two-chord")
        vm.toggle()
        vm.shiftKey(+1)
        vm.nudgeTempo(+5)
        assertEquals(FakeBacking.Update(1, 75, true), engine.updates.last())
        repeat(20) { vm.shiftKey(+1) }
        assertEquals(Backing.MAX_KEY, vm.selection.value.keyOffset)
        repeat(60) { vm.nudgeTempo(+5) }
        assertEquals(Backing.MAX_BPM, vm.selection.value.bpm)
        repeat(60) { vm.nudgeTempo(-5) }
        assertEquals(Backing.MIN_BPM, vm.selection.value.bpm)
    }

    @Test fun chordsOffStillPlaysDrumsAndBass() {
        val vm = vm()
        vm.open("two-chord")
        vm.setChords(false)
        vm.toggle()
        assertEquals(false, engine.plays.single().chords)
        vm.setChords(true)
        assertEquals(FakeBacking.Update(0, 70, true), engine.updates.last())
    }

    @Test fun whilePlayingTheChordFollowsTheEngine() {
        val vm = vm()
        vm.open("four-chords")
        vm.toggle()
        engine.state.value = engine.state.value.copy(bar = 2, beat = 3, chord = "Em", nextChord = "C", countIn = false)
        val d = BackingViewModel.display(vm.selection.value, engine.state.value)
        assertEquals("Em", d.chord)
        assertEquals("C", d.nextChord)
        assertEquals(3, d.beat)
        assertEquals(2, d.bar)
    }

    @Test fun anotherPlayerStoppingTheEngineShowsStopped() {
        val vm = vm()
        vm.open("four-chords")
        vm.toggle()
        // The metronome started: the coordinator stopped the engine under us.
        engine.state.value = engine.state.value.copy(playing = false, beat = -1)
        val d = BackingViewModel.display(vm.selection.value, engine.state.value)
        assertFalse(d.playing)
        assertEquals("G", d.chord) // ready to start from the top again
    }

    @Test fun closeStopsAndGoesBackToTheList() {
        val vm = vm()
        vm.open("four-chords")
        vm.toggle()
        vm.close()
        assertEquals(1, engine.stops)
        assertNull(vm.selection.value.lesson)
    }

    @Test fun keyAndTempoAreRememberedPerLesson() {
        val vm = vm()
        vm.open("four-chords")
        vm.shiftKey(-2)
        vm.nudgeTempo(+5)
        vm.open("two-chord")
        assertEquals(0, vm.selection.value.keyOffset)
        assertEquals(70, vm.selection.value.bpm)
        // Writes are async: wait for them to land.
        runBlocking {
            val until = System.currentTimeMillis() + 5_000
            while (prefs.backingLessons.first()["four-chords"]?.bpm != 81 && System.currentTimeMillis() < until) { idle(); Thread.sleep(5) }
        }
        val again = vm(prefs, FakeBacking())
        again.open("four-chords")
        assertEquals(-2, again.selection.value.keyOffset)
        assertEquals(81, again.selection.value.bpm)
    }

    @Test fun tempoLabelsCountEighthsInCompoundAndOddMeters() {
        assertEquals("70 BPM", tempoLabel(lesson("two-chord"), 70))
        assertEquals("♪ = 90", tempoLabel(lesson("bengali-6-8"), 90))
        assertEquals("♪ = 120", Lessons.all.first { it.timeSig.beats == 7 }.let { tempoLabel(it, 120) })
        assertEquals("90 BPM", tempoLabel(lesson("blues-in-a"), 90)) // shuffle stays BPM
        assertEquals("90 eighth notes per minute", tempoDescription(lesson("bengali-6-8"), 90))
        assertEquals("70 beats per minute", tempoDescription(lesson("two-chord"), 70))
    }
}
