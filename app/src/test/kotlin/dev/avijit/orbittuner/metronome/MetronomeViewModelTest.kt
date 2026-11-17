package dev.avijit.orbittuner.metronome

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.avijit.orbittuner.settings.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/** Records what the view model asks of the engine; [set] updates [state] like the real one. */
class FakeMetronome(bpm: Int = Tempo.DEFAULT_BPM, beats: Int = Tempo.DEFAULT_BEATS) : Metronome {
    override val state = MutableStateFlow(MetronomeState(playing = false, bpm = bpm, beatsPerBar = beats, accent = true, beat = -1))
    val sets = mutableListOf<Triple<Int, Int, Boolean>>()
    var starts = 0
    var stops = 0
    override fun start() { starts++; state.update { it.copy(playing = true) } }
    override fun stop() { stops++; state.update { it.copy(playing = false, beat = -1) } }
    override fun set(bpm: Int, beatsPerBar: Int, accent: Boolean) {
        sets += Triple(bpm, beatsPerBar, accent)
        state.update { it.copy(bpm = bpm, beatsPerBar = beatsPerBar, accent = accent) }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MetronomeViewModelTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = Prefs(ctx, "metronome-test-" + UUID.randomUUID())
    private var now = 0L

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(engine: Metronome = FakeMetronome()) = MetronomeViewModel(engine, prefs, { now })

    /** The view model reads Prefs off the main thread; wait (real time) for it to land. */
    private fun until(cond: () -> Boolean) = runBlocking {
        withTimeout(5_000) { while (!cond()) kotlinx.coroutines.delay(5) }
    }

    @Test fun nudgeClampsAtTheTop() {
        val e = FakeMetronome(bpm = 238)
        val vm = vm(e)
        until { vm.loaded }
        e.set(238, 4, true)
        vm.nudge(5)
        assertEquals(240, e.state.value.bpm)
        vm.nudge(1)
        assertEquals(240, e.state.value.bpm)
    }

    @Test fun nudgeClampsAtTheBottom() {
        val e = FakeMetronome()
        val vm = vm(e)
        until { vm.loaded }
        e.set(32, 4, true)
        vm.nudge(-5)
        assertEquals(30, e.state.value.bpm)
    }

    @Test fun setBeatsPushesToTheEngine() {
        val e = FakeMetronome()
        val vm = vm(e)
        until { vm.loaded }
        vm.setAccent(false)
        vm.setBeats(3)
        assertEquals(Triple(80, 3, false), e.sets.last())
        assertEquals(3, vm.state.value.beatsPerBar)
    }

    @Test fun valuesPersistAndReload() {
        val vm = vm()
        until { vm.loaded }
        vm.nudge(5); vm.nudge(1); vm.setBeats(6)
        runBlocking {
            withTimeout(5_000) {
                prefs.metronomeBpm.first { it == 86 }
                prefs.metronomeBeats.first { it == 6 }
            }
        }
        val e2 = FakeMetronome()
        val again = vm(e2)
        until { again.loaded }
        assertEquals(86, e2.state.value.bpm)
        assertEquals(6, e2.state.value.beatsPerBar)
    }

    @Test fun loadDefaultsAndSongValues() {
        runBlocking { prefs.setMetronomeBpm(150); prefs.setMetronomeBeats(2) }
        val e = FakeMetronome(bpm = 120, beats = 6)
        val vm = vm(e)
        vm.load(null, null)
        until { vm.loaded }
        // The saved Tools values must not override a song's load, even when they arrive later.
        assertEquals(80 to 4, e.state.value.bpm to e.state.value.beatsPerBar)
        vm.load(96, 3)
        assertEquals(96 to 3, e.state.value.bpm to e.state.value.beatsPerBar)
        vm.load(400, 99)
        assertEquals(240 to 12, e.state.value.bpm to e.state.value.beatsPerBar)
    }

    @Test fun toolsValuesComeBackAfterTheSongSheetLoadedItsOwn() {
        runBlocking { prefs.setMetronomeBpm(80); prefs.setMetronomeBeats(4) }
        val e = FakeMetronome(bpm = 50, beats = 5)
        val tools = vm(e)
        until { tools.loaded }
        assertEquals(80 to 4, e.state.value.bpm to e.state.value.beatsPerBar)
        MetronomeViewModel(e, prefs, { now }, persist = false).load(96, 3)
        assertEquals(96 to 3, e.state.value.bpm to e.state.value.beatsPerBar)
        tools.restore()
        assertEquals(80 to 4, e.state.value.bpm to e.state.value.beatsPerBar)
        // The next nudge saves the Tools value, not the song's.
        tools.nudge(1)
        runBlocking { withTimeout(5_000) { prefs.metronomeBpm.first { it == 81 } } }
    }

    @Test fun restoreKeepsAccentAndLeavesAPlayingEngineAlone() {
        val e = FakeMetronome()
        val tools = vm(e)
        until { tools.loaded }
        tools.setAccent(false)
        MetronomeViewModel(e, prefs, { now }, persist = false).load(96, 3)
        e.start()
        tools.restore()
        assertEquals(96 to 3, e.state.value.bpm to e.state.value.beatsPerBar)
        e.stop()
        tools.restore()
        assertEquals(Triple(80, 4, false), Triple(e.state.value.bpm, e.state.value.beatsPerBar, e.state.value.accent))
    }

    @Test fun toggleStartsAndStops() {
        val e = FakeMetronome()
        val vm = vm(e)
        vm.toggle()
        assertTrue(vm.state.value.playing)
        vm.toggle()
        assertFalse(vm.state.value.playing)
        assertEquals(1 to 1, e.starts to e.stops)
    }

    @Test fun tapSetsTheTempo() {
        val e = FakeMetronome()
        val vm = vm(e)
        until { vm.loaded }
        vm.tap(); now += 500; vm.tap()
        assertEquals(120, e.state.value.bpm)
    }

    @Test fun aNonPersistingViewModelNeverTouchesPrefs() {
        runBlocking { prefs.setMetronomeBpm(150); prefs.setMetronomeBeats(2) }
        val e = FakeMetronome()
        val vm = MetronomeViewModel(e, prefs, { now }, persist = false)
        vm.load(96, 3)
        assertTrue(vm.loaded)
        vm.nudge(5); vm.nudge(-1); vm.setBeats(6); vm.setBpm(100)
        assertEquals(100 to 6, e.state.value.bpm to e.state.value.beatsPerBar)
        runBlocking {
            kotlinx.coroutines.delay(300)
            assertEquals(150, prefs.metronomeBpm.first())
            assertEquals(2, prefs.metronomeBeats.first())
        }
    }

    @Test fun beatsForATimeSignature() {
        assertEquals(listOf(3, 2, 6, 4, 4, 4, 4, 4), listOf("3/4", "2/4", "6/8", "4/4", "5/4", null, "", "x/4").map(Tempo::beatsFor))
        assertEquals(2, Tempo.beatsFor(" 2 /2"))
    }
}
