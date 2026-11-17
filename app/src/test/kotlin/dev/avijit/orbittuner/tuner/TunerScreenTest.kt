package dev.avijit.orbittuner.tuner

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import dev.avijit.orbittuner.MainActivity
import dev.avijit.orbittuner.settings.MeterStyle
import dev.avijit.orbittuner.settings.Prefs
import dev.avijit.orbittuner.ui.theme.OrbitTunerTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Robolectric does not route injected touches into dialog windows (ModalBottomSheet, DropdownMenu), so nodes
 * inside them are activated through their OnClick semantics action, which runs the same handler.
 */
fun SemanticsNodeInteraction.tap(): SemanticsNodeInteraction = performSemanticsAction(SemanticsActions.OnClick)

/** Emits whatever the test puts in [state]; records calls. Like the real engine, [select] clears the ticks. */
class FakeTracker : PitchTracker {
    override val state = MutableStateFlow<TunerState>(TunerState.Idle)
    override val tunedStrings = MutableStateFlow<Set<Int>>(emptySet())
    @Volatile var selected: Triple<Instrument, Tuning?, TunerMode>? = null
    @Volatile var lastMode: TunerMode? = null
    var selects = 0
    var starts = 0
    var stops = 0
    var held = false
    override fun select(instrument: Instrument, tuning: Tuning?, mode: TunerMode) {
        selected = Triple(instrument, tuning, mode); lastMode = mode; selects++
        tunedStrings.value = emptySet()
    }
    override fun setMode(mode: TunerMode) { lastMode = mode }
    override fun holdTuned(hold: Boolean) { held = hold }
    override fun start() { starts++ }
    override fun stop() { stops++ }
}

class FakeTone : TonePlayer {
    val played = mutableListOf<Double>()
    override fun play(hz: Double) { played += hz }
    override fun stop() {}
}

/** A steady sine at [hz], 48 kHz, in 480-sample chunks, advancing [setClock] as it goes. */
private class SineSource(val hz: Double, val setClock: (Long) -> Unit) : AudioSource {
    private val stop = java.util.concurrent.atomic.AtomicBoolean(false)
    override fun start(onFrames: (FloatArray, Int) -> Unit) {
        var n = 0L
        val buf = FloatArray(480)
        while (!stop.get() && n < 48_000L * 3) {
            for (i in buf.indices) buf[i] = (0.5 * sin(2 * PI * hz * (n + i) / 48_000)).toFloat()
            n += 480
            setClock(n * 1000 / 48_000)
            onFrames(buf, 480)
        }
        while (!stop.get()) Thread.sleep(5)
    }
    override fun stop() { stop.set(true) }
    override fun reset() { stop.set(false) }
}

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class TunerScreenTest {
    @get:Rule val rule = createComposeRule()

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val instruments = Tunings.parse(java.io.File("src/main/assets/tunings.json").readText())
    private val prefs = Prefs(ctx, "tuner-test-" + UUID.randomUUID())

    private fun vm(engine: PitchTracker = FakeTracker(), tone: TonePlayer = FakeTone()) =
        TunerViewModel(engine, prefs, instruments, tone)

    private var haptics = 0

    private fun show(vm: TunerViewModel, problem: MicProblem? = null) {
        rule.setContent { OrbitTunerTheme { TunerScreen(vm, problem = problem, onTunedHaptic = { haptics++ }) } }
        awaitLoaded(vm)
    }

    /**
     * Polls [cond], draining the main looper each time: under Robolectric, work that background threads post to
     * Main (DataStore runs edit transforms in the caller's context) only runs when the looper is idled.
     */
    private fun until(cond: () -> Boolean) = rule.waitUntil(5_000) {
        shadowOf(Looper.getMainLooper()).idle()
        cond()
    }

    private fun awaitLoaded(vm: TunerViewModel) = until { vm.ui.value != null }

    private fun pegLabels(): List<String> = rule.onAllNodesWithTag("peg").fetchSemanticsNodes().map {
        it.config[SemanticsProperties.Text].first().text
    }

    @Test fun readingShowsNoteAndFlatStateDescription() {
        val engine = FakeTracker()
        val vm = vm(engine)
        show(vm)
        engine.state.value = TunerState.Reading("E", 2, -12.0, 81.84, 0, inTune = false)
        until { vm.readout.value.hasPitch }
        rule.onNodeWithTag("note").assertTextEquals("E")
        // The Guide speaks its instruction, not the cents bucket.
        rule.onNodeWithTag("meter")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Tighten"))
    }

    @Test fun guideIsTheDefaultAndSaysWhichWayToTurn() {
        val engine = FakeTracker()
        val vm = vm(engine)
        show(vm)
        engine.state.value = TunerState.Reading("A", 2, -20.0, 108.74, 1, inTune = false)
        until { vm.readout.value.hasPitch }
        rule.onNodeWithText("Tighten").assertIsDisplayed()
        rule.onNodeWithText("Turn the peg to raise the pitch").assertIsDisplayed()
        rule.onNodeWithTag("meter").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Tighten"))
    }

    @Test fun guidePointsToTheNextStringOnceTicked() {
        val engine = FakeTracker()
        val vm = vm(engine)
        show(vm)
        engine.state.value = TunerState.Reading("A", 2, 1.0, 110.06, 1, inTune = true)
        engine.tunedStrings.value = setOf(0, 1)
        until { vm.tuned.value == setOf(0, 1) && vm.readout.value.inTune }
        rule.onNodeWithText("Perfect").assertIsDisplayed()
        rule.onNodeWithText("Next: D string").assertIsDisplayed()
    }

    @Test fun dialShowsNoGuideWords() {
        runBlocking { prefs.setMeterStyle(MeterStyle.Dial) }
        val engine = FakeTracker()
        val vm = vm(engine)
        show(vm)
        until { vm.ui.value?.meterStyle == MeterStyle.Dial }
        engine.state.value = TunerState.Reading("A", 2, -20.0, 108.74, 1, inTune = false)
        until { vm.readout.value.hasPitch }
        rule.waitForIdle()
        rule.onNodeWithText("Tighten").assertDoesNotExist()
        rule.onNodeWithText("Turn the peg to raise the pitch").assertDoesNotExist()
        rule.onNodeWithTag("meter")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Tuning dial")))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Flat"))
    }

    @Test fun chromaticGuideNeverSaysNext() {
        val engine = FakeTracker()
        val vm = vm(engine)
        show(vm)
        rule.onNodeWithText("Chromatic").performClick()
        until { engine.lastMode == TunerMode.Chromatic }
        engine.tunedStrings.value = setOf(0, 1)
        engine.state.value = TunerState.Reading("A", 2, 1.0, 110.06, null, inTune = true)
        until { vm.readout.value.inTune }
        rule.waitForIdle()
        rule.onNodeWithText("In tune").assertIsDisplayed()
        rule.onNode(hasText("Next", substring = true)).assertDoesNotExist()
    }

    /**
     * Fold cover screen: the string chips must sit fully above the bottom nav, with no scrolling. Native graphics,
     * so text is measured with the real fonts (the legacy mode measures the large note far too small).
     */
    @Test @Config(qualifiers = "w360dp-h600dp") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun stringChipsFitOnAShortScreenWithGuide() = chipsFit(MeterStyle.Guide)

    @Test @Config(qualifiers = "w360dp-h600dp") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun stringChipsFitOnAShortScreenWithDial() = chipsFit(MeterStyle.Dial)

    /** Fold cover screen (about 369 dp wide): six string chips fit the width, none cut off at the right. */
    @Test @Config(qualifiers = "w360dp-h800dp")
    fun sixStringChipsFitA360dpWidth() {
        val vm = vm()
        show(vm)
        rule.waitForIdle()
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        val pegs = rule.onAllNodesWithTag("peg")
        assertEquals(6, pegs.fetchSemanticsNodes().size)
        for (i in 0 until 6) {
            val b = pegs[i].assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("peg $i right ${b.right} > root ${root.right}", b.right <= root.right)
            assertTrue("peg $i narrower than a 48 dp target", b.right - b.left >= 48.dp)
        }
    }

    private fun chipsFit(style: MeterStyle) {
        runBlocking { prefs.setMeterStyle(style) }
        val engine = FakeTracker()
        val vm = vm(engine)
        show(vm)
        until { vm.ui.value?.meterStyle == style }
        engine.state.value = TunerState.Reading("A", 2, -20.0, 108.74, 1, inTune = false)
        until { vm.readout.value.hasPitch }
        rule.waitForIdle()
        val rootBottom = rule.onRoot().getUnclippedBoundsInRoot().bottom
        val pegs = rule.onAllNodesWithTag("peg")
        val n = pegs.fetchSemanticsNodes().size
        assertEquals(6, n)
        for (i in 0 until n) {
            pegs[i].assertIsDisplayed()
            // Unclipped: a chip cut off by the bottom edge still reports clipped bounds that end at the root.
            val bottom = pegs[i].getUnclippedBoundsInRoot().bottom
            assertTrue("peg $i bottom $bottom > root $rootBottom", bottom <= rootBottom)
        }
    }

    @Test fun meterStateIsSpokenInCoarseBuckets() {
        fun r(c: Int, inTune: Boolean = false) = Readout(note = NoteLabel("E", 2), cents = c, inTune = inTune).stateDescription
        assertEquals("In tune", r(2, inTune = true))
        assertEquals("Slightly sharp", r(9))
        assertEquals("Flat", r(-31))
        assertEquals("Sharp", r(40))
    }

    /** Review: the mic hears the reference tone, so it must not tick a string or fire the haptic. */
    @Test fun referenceToneNeverMarksAStringTuned() {
        val engine = FakeTracker()
        val tone = FakeTone()
        val vm = vm(engine, tone)
        show(vm)
        rule.onAllNodesWithTag("peg")[1].performClick()
        until { vm.toneString.value == 1 }
        assertEquals(1, tone.played.size)
        assertTrue("engine told to hold ticks", engine.held)
        engine.state.value = TunerState.Reading("A", 2, 0.5, 110.0, 1, inTune = true)
        engine.tunedStrings.value = setOf(1)
        until { vm.readout.value.inTune }
        rule.waitForIdle()
        assertEquals(emptySet<Int>(), vm.tuned.value)
        assertEquals(0, haptics)
    }

    /** Review: picking another string (or mode) keeps the strings already tuned and does not re-fire the haptic. */
    @Test fun tappingAnotherStringKeepsTicks() {
        val engine = FakeTracker()
        val vm = vm(engine)
        show(vm)
        rule.onNodeWithText("Manual").performClick()
        until { engine.lastMode is TunerMode.Manual }
        engine.state.value = TunerState.Reading("E", 2, 0.2, 82.41, 0, inTune = true)
        engine.tunedStrings.value = setOf(0)
        until { vm.tuned.value == setOf(0) }
        rule.waitForIdle()
        assertEquals(1, haptics)
        val selectsBefore = engine.selects
        rule.onAllNodesWithTag("peg")[1].performClick()
        until { engine.lastMode == TunerMode.Manual(1) }
        rule.waitForIdle()
        assertEquals("a string change must not re-select", selectsBefore, engine.selects)
        assertEquals(setOf(0), vm.tuned.value)
        assertEquals(1, haptics)
    }

    @Test fun searchMatchesSolfegeAndAccidentalGlyphs() {
        val bass = instruments.first { it.id == "bass-4" }
        val half = bass.tunings.first { it.id == "half-step-down" }  // Eb1 Ab1 Db2 Gb2
        assertTrue(TuningSearch.matches(bass, half, "E♭ A♭"))
        assertTrue(TuningSearch.matches(bass, half, "eb ab"))
        assertTrue(TuningSearch.matches(bass, half, "Mi♭"))
        assertTrue(TuningSearch.matches(bass, half, "half step"))
        assertTrue(TuningSearch.matches(bass, half, "Sol♭"))
        assertTrue(!TuningSearch.matches(bass, half, "drop"))
    }

    @Test fun permissionDeniedOffersOpenSettings() {
        val engine = FakeTracker()
        val vm = vm(engine)
        show(vm)
        engine.state.value = TunerState.Error(MicError.PermissionDenied)
        until { vm.readout.value.error != null }
        rule.onNodeWithText("Open settings").assertExists()
        rule.onNodeWithText("Retry").assertExists()
    }

    @Test fun micInUseExplainsAndOffersRetry() {
        val engine = FakeTracker()
        val vm = vm(engine)
        show(vm)
        engine.state.value = TunerState.Error(MicError.InUse)
        until { vm.readout.value.error != null }
        rule.onNode(hasText("in use by another app", substring = true)).assertExists()
        rule.onNodeWithText("Retry").assertExists()
    }

    @Test fun pickingDropDShowsItsStrings() {
        val vm = vm()
        show(vm)
        assertEquals(listOf("E", "A", "D", "G", "B", "E"), pegLabels())
        rule.onNodeWithTag("tuning-header").performClick()
        rule.waitUntilExactlyOneExists(hasText("Drop D"))
        rule.onNodeWithText("Drop D").tap()
        until { vm.ui.value?.baseTuning?.name == "Drop D" }
        rule.waitForIdle()
        assertEquals(listOf("D", "A", "D", "G", "B", "E"), pegLabels())
    }

    @Test fun customTuningIsSavedAndListedAfterViewModelRecreated() {
        var current by mutableStateOf(vm())
        rule.setContent { OrbitTunerTheme { TunerScreen(current, onTunedHaptic = {}) } }
        awaitLoaded(current)
        rule.onNodeWithTag("more").performClick()
        rule.onNodeWithText("Create custom tuning").tap()
        rule.waitUntilExactlyOneExists(hasTestTag("custom-down-0"))
        rule.onNodeWithTag("custom-down-0").tap()
        rule.onNodeWithTag("custom-down-0").tap()
        rule.onNodeWithTag("custom-name").performTextInput("Low D test")
        rule.onNodeWithText("Save tuning").tap()
        until { current.ui.value?.baseTuning?.name == "Low D test" }
        // Persisted?
        until { runBlocking { prefs.customTunings.first() }.any { it.tuning.name == "Low D test" } }
        val saved = runBlocking { prefs.customTunings.first() }.single()
        assertEquals("guitar", saved.instrumentId)
        assertEquals("D2", saved.tuning.notes.first())
        until { runBlocking { prefs.lastTuningId.first() } == saved.tuning.id }

        // A fresh ViewModel (as after process death) lists it in the picker.
        current.viewModelScope.cancel()
        current = vm()
        awaitLoaded(current)
        rule.waitForIdle()
        rule.onNodeWithTag("tuning-header").assert(hasText("Low D test", substring = true))
        rule.onNodeWithTag("tuning-header").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Your tunings"))
        rule.onNode(hasText("Low D test") and hasText("D A D G B E", substring = true)).assertExists()
    }

    @Test fun offsetMinusOneShowsFlatsAndTunesLowEFlat() {
        val now = java.util.concurrent.atomic.AtomicLong(0)
        val engine = TunerEngine(SineSource(77.78) { now.set(it) }, { 440.0 }, { now.get() })
        val vm = vm(engine)
        show(vm)
        vm.setOffset(-1)
        until { vm.ui.value?.offset == -1 }
        rule.waitForIdle()
        assertEquals(listOf("E♭", "A♭", "D♭", "G♭", "B♭", "E♭"), pegLabels())

        val got = mutableListOf<TunerState.Reading>()
        engine.readingListener = { synchronized(got) { got += it } }
        vm.start()
        try {
            val end = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < end && engine.tunedStrings.value.isEmpty()) Thread.sleep(10)
            assertEquals(setOf(0), engine.tunedStrings.value)
            val last = synchronized(got) { got.last() }
            assertEquals(0, last.stringIndex)
            assertTrue("cents ${last.cents}", last.inTune && abs(last.cents) <= 3.0)
        } finally {
            vm.stop()
        }
    }

    /** Deferred from Task 7: a cleared VM must release the hold at once (the engine is an app singleton). */
    @Test fun clearingTheViewModelReleasesTheTickHold() {
        val engine = FakeTracker()
        val store = androidx.lifecycle.ViewModelStore()
        val vm = androidx.lifecycle.ViewModelProvider(store, object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = vm(engine) as T
        })[TunerViewModel::class.java]
        show(vm)
        rule.onAllNodesWithTag("peg")[1].performClick()
        until { vm.toneString.value == 1 }
        assertTrue(engine.held)
        store.clear()
        assertEquals(false, engine.held)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class TunerRecreateTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    /** Review Focus 4: the selection survives an Activity recreation. */
    @OptIn(ExperimentalTestApi::class)
    @Test fun recreateKeepsInstrumentAndTuning() {
        rule.waitUntilExactlyOneExists(hasTestTag("tuning-header"), 5_000)
        rule.onNodeWithTag("tuning-header").performClick()
        rule.waitUntilExactlyOneExists(hasText("Drop D"))
        rule.onNodeWithText("Drop D").tap()
        rule.waitUntilAtLeastOneExists(hasText("Drop D", substring = true))
        rule.waitForIdle()
        rule.activityRule.scenario.recreate()
        rule.waitUntilExactlyOneExists(hasTestTag("tuning-header"), 5_000)
        rule.onNodeWithTag("tuning-header").assert(hasText("Drop D", substring = true))
        rule.onNodeWithTag("tuning-header").assert(hasText("Guitar", substring = true))
    }
}
