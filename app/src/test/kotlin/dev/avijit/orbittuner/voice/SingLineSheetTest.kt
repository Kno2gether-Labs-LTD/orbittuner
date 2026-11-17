package dev.avijit.orbittuner.voice

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.avijit.orbittuner.music.ShiftGuess
import dev.avijit.orbittuner.songview.SongDest
import dev.avijit.orbittuner.songview.SungShift
import dev.avijit.orbittuner.tuner.AudioSource
import dev.avijit.orbittuner.tuner.MicError
import dev.avijit.orbittuner.tuner.MicException
import dev.avijit.orbittuner.ui.theme.OrbitTunerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SingLineSheetTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    /** A tone that runs until stopped (or throws [fail] once started). */
    private class Tone(private val fail: Exception? = null) : AudioSource {
        val stopped = AtomicBoolean(false)
        val stops = AtomicInteger(0)
        override fun reset() = stopped.set(false)
        override fun stop() { stops.incrementAndGet(); stopped.set(true) }
        override fun start(onFrames: (FloatArray, Int) -> Unit) {
            fail?.let { throw it }
            val buf = FloatArray(1024)
            var t = 0L
            while (!stopped.get()) {
                for (i in buf.indices) buf[i] = (0.3 * sin(2 * PI * 220.0 * (t + i) / 48_000)).toFloat()
                onFrames(buf, buf.size)
                t += buf.size
                Thread.sleep(2)
            }
        }
    }

    private fun hit(id: String, title: String, artist: String?, key: String?, reason: String? = null, match: String = "lyrics") =
        RankedHit(VoiceHit(id, title, artist, "hi", emptyList(), key, match), 0.0, reason, library = false)

    private val hits = listOf(
        hit("th", "Dil Se Gao", "Meera Sen", "D#m", reason = "In your favourites"),
        hit("ch", "Chandni Raat", "Meera Sen", "C", reason = "You often play Meera Sen"),
        hit("ag", "Pal Do Pal", "Ravi Das", null, match = "title"),
    )
    private val results = VoiceOutcome.Results("lyrics", "hum saath chalenge har pal", hits, "dil se gao", emptyList(), singing = true)

    private fun settle() = repeat(6) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)); rule.waitForIdle() }

    private fun vm(
        source: Tone = Tone(),
        outcome: suspend (Recording) -> VoiceOutcome = { results },
        shift: suspend (String) -> ShiftGuess? = { null },
    ) = SingLineViewModel(recorder = { VoiceRecorder(source, Dispatchers.Default) }, search = outcome, shiftFor = shift)

    private fun show(state: SingState, level: Float = 0.35f, onRetry: () -> Unit = {}, onWeb: (String) -> Unit = {}, onSignIn: () -> Unit = {}, onStop: () -> Unit = {}) {
        val lv = mutableFloatStateOf(level)
        rule.setContent {
            OrbitTunerTheme(darkTheme = true) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.BottomCenter) {
                    // Drawn like the sheet: surface, rounded top, handle room.
                    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).padding(top = 22.dp)) {
                        SingLineContent(
                            state, level = { lv.floatValue }, onStop = onStop, onRetry = onRetry, onOpen = {}, onSearchWeb = onWeb,
                            onSignIn = onSignIn, onAllowMic = {}, onClose = {}, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        settle()
    }

    // ---- states on screen ----

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun listeningAsksForALineWithABetaChipCountdownAndPrivacyLine() {
        var stops = 0
        show(SingState.Listening(secondsLeft = 12), onStop = { stops++ })
        rule.onNodeWithText("Sing a line, or say the song name").assertIsDisplayed()
        rule.onNodeWithText("Beta").assertIsDisplayed()
        rule.onNodeWithText("Your clip is sent to be turned into words, then deleted. We never keep it.").assertIsDisplayed()
        rule.onNodeWithContentDescription("12 seconds left", substring = true).assertExists()
        rule.onNodeWithContentDescription("Stop").assertIsEnabled().performClick()
        assertEquals(1, stops)
        shoot("sing-listening-phone-dark")
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun stopIsDisabledOnceTapped() {
        show(SingState.Listening(secondsLeft = 9, stopping = true))
        rule.onNodeWithContentDescription("Stop").assertIsNotEnabled()
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun noWordsSaysSoAndOffersTryAgain() {
        var retried = 0
        show(SingState.Problem(SingProblem.NoWords), onRetry = { retried++ })
        rule.onNodeWithText("We didn't hear any words").assertIsDisplayed()
        rule.onNodeWithText("Try again").performClick()
        assertEquals(1, retried)
        shoot("sing-no-words-phone-dark")
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun tooShortSaysItWasABitShort() {
        show(SingState.Problem(SingProblem.TooShort))
        rule.onNodeWithText("That was a bit short").assertIsDisplayed()
        rule.onNodeWithText("Try again").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun limitSaysHowManyTriesAndOffersSignInWhenAnonymous() {
        var signIn = 0
        show(SingState.Problem(SingProblem.Limit(3, "anonymous")), onSignIn = { signIn++ })
        rule.onNodeWithText("3 tries a day", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Sign in").performClick()
        assertEquals(1, signIn)
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun limitOnAPlanHasNoSignIn() {
        show(SingState.Problem(SingProblem.Limit(10, "free")))
        rule.onNodeWithText("10 tries a day", substring = true).assertIsDisplayed()
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasText("Sign in")).fetchSemanticsNodes().isEmpty())
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun offlineAndUnavailableAreKind() {
        show(SingState.Problem(SingProblem.Offline))
        rule.onNodeWithText("You're offline").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun resultsAskAndShowWhy() {
        var web: String? = null
        show(SingState.Results(results), onWeb = { web = it })
        rule.onNodeWithText("Is it one of these?").assertIsDisplayed()
        rule.onNodeWithText("Dil Se Gao").assertIsDisplayed()
        rule.onNodeWithText("In your favourites").assertIsDisplayed()
        // Keys are spelled the app's way (the server sent "D#m").
        rule.onNodeWithText("Ebm").assertIsDisplayed()
        shoot("sing-results-phone-dark")
        rule.onNodeWithText("Search the web for 'dil se gao'").performClick()
        assertEquals("dil se gao", web)
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun findingRenders() {
        show(SingState.Finding)
        rule.onNodeWithText("Finding your song…").assertIsDisplayed()
        shoot("sing-finding-phone-dark")
    }

    // ---- view model ----

    @Test fun recordsThenFindsThenShowsResults() {
        val src = Tone()
        val vm = vm(src)
        vm.start()
        assertEquals(SingState.Listening(VoiceRecorder.MAX_SECONDS), vm.state.value)
        Thread.sleep(150)
        vm.stop()
        vm.stop() // a second tap does nothing
        assertEquals(SingState.Listening(VoiceRecorder.MAX_SECONDS, stopping = true), vm.state.value)
        awaitState(vm) { it is SingState.Results }
        assertEquals(SingState.Results(results), vm.state.value)
        // The stop reached the source (the capture loop may echo it once more as it exits).
        assertTrue(src.stops.get() >= 1)
    }

    @Test fun levelsFromTheCaptureThreadReachTheUi() {
        val vm = vm()
        vm.start()
        awaitLooper { vm.level.value > 0.05f }
        vm.stop()
        awaitState(vm) { it is SingState.Results }
    }

    @Test fun outcomesBecomeProblems() {
        val cases = mapOf<VoiceOutcome, SingProblem>(
            VoiceOutcome.NoWords to SingProblem.NoWords,
            VoiceOutcome.TooShort to SingProblem.TooShort,
            VoiceOutcome.LimitReached(3, "anonymous") to SingProblem.Limit(3, "anonymous"),
            VoiceOutcome.Offline to SingProblem.Offline,
            VoiceOutcome.Unavailable to SingProblem.Unavailable,
            VoiceOutcome.NoAccount to SingProblem.NoAccount,
        )
        for ((outcome, problem) in cases) {
            val vm = vm(outcome = { outcome })
            vm.start(); vm.stop()
            awaitState(vm) { it is SingState.Problem }
            assertEquals(SingState.Problem(problem), vm.state.value)
        }
    }

    @Test fun aBusyMicIsAProblemNotACrash() {
        val vm = vm(source = Tone(fail = MicException(MicError.InUse)))
        vm.start()
        awaitState(vm) { it is SingState.Problem }
        assertEquals(SingState.Problem(SingProblem.MicBusy), vm.state.value)
    }

    @Test fun openingASungResultCarriesAConfidentShift() {
        val asked = ArrayList<String>()
        val vm = vm(shift = { asked += it; ShiftGuess(2, confident = true, alternative = null) })
        vm.start(); vm.stop()
        awaitState(vm) { it is SingState.Results }
        vm.open(hits[0])
        assertEquals(SongDest("th", shift = SungShift(2)), awaitOpened(vm))
        assertEquals(listOf("th"), asked)
    }

    @Test fun aCloseCallCarriesBothChoices() {
        val vm = vm(shift = { ShiftGuess(2, confident = false, alternative = -3) })
        vm.start(); vm.stop()
        awaitState(vm) { it is SingState.Results }
        vm.open(hits[0])
        assertEquals(SongDest("th", shift = SungShift(2, alternative = -3)), awaitOpened(vm))
    }

    @Test fun spokenRequestsOpenInTheSongsOwnKey() {
        var asked = 0
        val spoken = results.copy(intent = "request", singing = false)
        val vm = vm(outcome = { spoken }, shift = { asked++; ShiftGuess(2, true, null) })
        vm.start(); vm.stop()
        awaitState(vm) { it is SingState.Results }
        vm.open(hits[1])
        assertEquals(SongDest("ch"), awaitOpened(vm))
        assertEquals(0, asked)
    }

    @Test fun noShiftOrAnUnsureOneOpensPlain() {
        for (guess in listOf<ShiftGuess?>(null, ShiftGuess(3, false, null))) {
            val vm = vm(shift = { guess })
            vm.start(); vm.stop()
            awaitState(vm) { it is SingState.Results }
            vm.open(hits[0])
            assertEquals(guess.toString(), SongDest("th"), awaitOpened(vm))
        }
    }

    /** A confident 0 is kept: over a saved transpose it moves the song back to its own key (the song view decides). */
    @Test fun aConfidentZeroShiftIsKept() {
        val vm = vm(shift = { ShiftGuess(0, true, null) })
        vm.start(); vm.stop()
        awaitState(vm) { it is SingState.Results }
        vm.open(hits[0])
        assertEquals(SongDest("th", shift = SungShift(0)), awaitOpened(vm))
    }

    // ---- fix round 1 ----

    @Test fun aCancelledRecordingIsNeverSent() {
        var sent = 0
        val vm = vm(outcome = { sent++; results })
        vm.start()
        Thread.sleep(150)
        vm.cancel()
        // Give a racing capture every chance to finish and be (wrongly) sent.
        repeat(20) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); Thread.sleep(10) }
        assertEquals(0, sent)
        assertEquals(SingState.Idle, vm.state.value)
    }

    @Test fun closingWhileAResultOpensNeverJumpsLater() {
        val gate = kotlinx.coroutines.CompletableDeferred<ShiftGuess?>()
        val vm = vm(shift = { gate.await() })
        vm.start(); vm.stop()
        awaitState(vm) { it is SingState.Results }
        vm.open(hits[0])
        vm.cancel()
        gate.complete(ShiftGuess(2, true, null))
        var got: SongDest? = null
        val job = CoroutineScope(Dispatchers.Main).launch { got = vm.opened.first() }
        vm.start() // the next mic tap
        repeat(20) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); Thread.sleep(10) }
        job.cancel()
        vm.cancel()
        assertEquals(null, got)
    }

    private fun awaitLooper(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            Thread.sleep(10)
        }
    }

    private fun awaitState(vm: SingLineViewModel, cond: (SingState) -> Boolean) = awaitLooper { cond(vm.state.value) }

    private fun awaitOpened(vm: SingLineViewModel): SongDest {
        var got: SongDest? = null
        val job = CoroutineScope(Dispatchers.Main).launch { got = vm.opened.first() }
        awaitLooper { got != null }
        job.cancel()
        return got!!
    }

    // ---- renders ----

    private fun shoot(name: String) {
        if (System.getenv("ORBIT_SCREENSHOTS") != "1") return
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        val root = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { root.draw(Canvas(bmp)) }
        val dir = File("../docs/design/app").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
