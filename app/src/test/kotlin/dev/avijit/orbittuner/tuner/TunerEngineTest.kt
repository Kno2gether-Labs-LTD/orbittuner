package dev.avijit.orbittuner.tuner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

private const val SR = 48_000

/**
 * Feeds a sine for [seconds] in 480-sample chunks (advancing the clock in step), then idles until
 * stopped, like a real capture loop. [startDelayMs] simulates slow AudioRecord construction; a stop
 * requested before/while "constructing" is honoured and consumed (the AudioSource contract).
 */
private class ToneSource(
    val hz: Double, val seconds: Double, val setClock: (Long) -> Unit, val startDelayMs: Long = 0,
    val leadSilenceMs: Int = 0,
) : AudioSource {
    private val stopRequested = java.util.concurrent.atomic.AtomicBoolean(false)
    val concurrent = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile var maxConcurrent = 0
    val starts = java.util.concurrent.atomic.AtomicInteger(0)
    override fun start(onFrames: (FloatArray, Int) -> Unit) {
        maxConcurrent = maxOf(maxConcurrent, concurrent.incrementAndGet())
        starts.incrementAndGet()
        try {
            if (startDelayMs > 0) Thread.sleep(startDelayMs)
            val total = (seconds * SR).toInt() + leadSilenceMs * SR / 1000
            val silent = leadSilenceMs * SR / 1000
            var n = 0
            while (!stopRequested.get()) {
                if (n < total) {
                    val buf = FloatArray(480) { if (n + it < silent) 0f else (0.5 * sin(2 * PI * hz * (n + it) / SR)).toFloat() }
                    n += 480
                    setClock(n * 1000L / SR)
                    onFrames(buf, 480)
                } else Thread.sleep(5)
            }
        } finally {
            concurrent.decrementAndGet()
        }
    }
    override fun stop() { stopRequested.set(true) }
    override fun reset() { stopRequested.set(false) }
}

@RunWith(RobolectricTestRunner::class)
class TunerEngineTest {
    private val instruments = Tunings.parse(java.io.File("src/main/assets/tunings.json").readText())
    private val guitar = instruments.first { it.id == "guitar" }
    private val standard = guitar.tunings.first { it.id == "standard" }

    @Volatile private var now = 0L

    private fun engine(src: AudioSource, a4: Double = 440.0) = TunerEngine(src, { a4 }, { now })

    private fun <T> await(timeoutMs: Long = 10_000, f: () -> T?): T {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { f()?.let { return it }; Thread.sleep(10) }
        throw AssertionError("timed out")
    }

    private fun lastReading(e: TunerEngine) = await { e.state.value as? TunerState.Reading }

    @Test fun autoModeLocksOntoLowE() {
        val e = engine(ToneSource(82.41, 1.0, { now = it }))
        e.select(guitar, standard, TunerMode.Auto); e.start()
        await { (e.tunedStrings.value.contains(0)).takeIf { it } }
        val r = e.state.value as TunerState.Reading
        assertEquals("E", r.noteName); assertEquals(2, r.octave); assertEquals(0, r.stringIndex)
        assertTrue("cents ${r.cents}", abs(r.cents) <= 1.0); assertTrue(r.inTune)
        e.stop()
    }

    @Test fun sharpByThirtyThreeCents() {
        val e = engine(ToneSource(84.0, 0.5, { now = it }))
        e.select(guitar, standard, TunerMode.Auto); e.start()
        val r = await { (e.state.value as? TunerState.Reading)?.takeIf { abs(it.cents - 33) < 3 } }
        assertFalse(r.inTune); assertEquals(0, r.stringIndex)
        assertFalse(e.tunedStrings.value.contains(0))
        e.stop()
    }

    @Test fun a432Chromatic() {
        val e = engine(ToneSource(432.0, 0.5, { now = it }), a4 = 432.0)
        e.select(instruments.first { it.id == "chromatic" }, null, TunerMode.Chromatic); e.start()
        val r = await { (e.state.value as? TunerState.Reading)?.takeIf { abs(it.cents) <= 1.0 } }
        assertEquals("A", r.noteName); assertEquals(4, r.octave)
        e.stop()
    }

    @Test fun manualModeUsesChosenString() {
        val e = engine(ToneSource(110.0, 0.5, { now = it }))
        e.select(guitar, standard, TunerMode.Manual(1)); e.start()
        val r = await { (e.state.value as? TunerState.Reading)?.takeIf { it.stringIndex == 1 } }
        assertEquals("A", r.noteName); assertTrue(abs(r.cents) <= 1.0)
        e.stop()
    }

    @Test fun securityExceptionMapsToPermissionDenied() {
        val e = engine(object : AudioSource {
            override fun start(onFrames: (FloatArray, Int) -> Unit) { throw SecurityException("no") }
            override fun stop() {}
        })
        e.start()
        assertEquals(TunerState.Error(MicError.PermissionDenied), await { e.state.value.takeIf { it is TunerState.Error } })
    }

    @Test fun initFailureMapsToInUse() {
        val e = engine(object : AudioSource {
            override fun start(onFrames: (FloatArray, Int) -> Unit) { throw MicException(MicError.InUse) }
            override fun stop() {}
        })
        e.start()
        assertEquals(TunerState.Error(MicError.InUse), await { e.state.value.takeIf { it is TunerState.Error } })
    }

    @Test fun otherFailureMapsToUnavailable() {
        val e = engine(object : AudioSource {
            override fun start(onFrames: (FloatArray, Int) -> Unit) { throw IllegalStateException("x") }
            override fun stop() {}
        })
        e.start()
        assertEquals(TunerState.Error(MicError.Unavailable), await { e.state.value.takeIf { it is TunerState.Error } })
    }

    @Test fun lowStringUsesLongWindow() {
        assertEquals(2048, TunerEngine.windowFor(lowestHz = 82.41, prevHz = null))
        assertEquals(8192, TunerEngine.windowFor(lowestHz = 41.2, prevHz = null))
        assertEquals(8192, TunerEngine.windowFor(lowestHz = null, prevHz = 70.0))
    }

    @Test fun stopBeforeSlowStartEmitsNothingAndNeverOverlaps() {
        val src = ToneSource(110.0, 5.0, { now = it }, startDelayMs = 300)
        val e = engine(src)
        e.select(guitar, standard, TunerMode.Auto)
        e.start(); e.stop()
        e.start(); e.stop()
        assertEquals(TunerState.Idle, e.state.value)
        val end = System.currentTimeMillis() + 900
        while (System.currentTimeMillis() < end) {
            assertEquals(TunerState.Idle, e.state.value); Thread.sleep(20)
        }
        // A fresh start after the aborted ones works, and capture threads never overlapped.
        e.start()
        await { e.state.value as? TunerState.Reading }
        e.stop()
        assertEquals(TunerState.Idle, e.state.value)
        assertEquals(1, src.maxConcurrent)
    }

    @Test fun stopReturnsPromptlyEvenWhenSourceIsSlow() {
        val e = engine(ToneSource(110.0, 5.0, { now = it }, startDelayMs = 1000))
        e.start()
        val t0 = System.nanoTime(); e.stop()
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 200)
    }

    @Test fun lowBassNoteReadableInChromaticViaLongWindowFallback() {
        val e = engine(ToneSource(41.2, 2.0, { now = it }))
        e.select(instruments.first { it.id == "chromatic" }, null, TunerMode.Chromatic); e.start()
        val r = await { (e.state.value as? TunerState.Reading)?.takeIf { abs(it.cents) <= 2.0 } }
        assertEquals("E", r.noteName); assertEquals(1, r.octave)
        e.stop()
    }

    @Test fun captureLoopEndingWithoutStopIsUnavailable() {
        val e = engine(object : AudioSource {
            override fun start(onFrames: (FloatArray, Int) -> Unit) {}
            override fun stop() {}
        })
        e.start()
        assertEquals(TunerState.Error(MicError.Unavailable), await { e.state.value.takeIf { it is TunerState.Error } })
    }

    @Test fun windowEndStampIsRecent() {
        val e = engine(ToneSource(82.41, 1.0, { now = it }))
        e.select(guitar, standard, TunerMode.Auto); e.start()
        val r = lastReading(e)
        val ageMs = (System.nanoTime() - r.windowEndNanos) / 1_000_000
        assertTrue(r.windowEndNanos > 0 && ageMs < 5_000)
        e.stop()
    }

    @Test fun pluckAfterSilenceOnGuitarAnalysedWithShortWindow() {
        // 300 ms of silence activates the (alternating) low-note fallback; the pluck must still be
        // analysed on the 2048 window first, and 8192 must never stick.
        val e = engine(ToneSource(110.0, 1.0, { now = it }, leadSilenceMs = 300))
        val readings = java.util.Collections.synchronizedList(mutableListOf<TunerState.Reading>())
        e.readingListener = { readings.add(it) }
        e.select(guitar, standard, TunerMode.Auto); e.start()
        await { readings.size.takeIf { it >= 10 } }
        e.stop()
        assertEquals(2048, readings.first().windowSize)
        assertTrue(readings.drop(1).all { it.windowSize == 2048 })
    }

    @Test fun errorExitRacingStopDoesNotPoisonNextStart() {
        val firstEntered = java.util.concurrent.CountDownLatch(1)
        val stopSeen = java.util.concurrent.CountDownLatch(1)
        val flag = java.util.concurrent.atomic.AtomicBoolean(false)
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val tone = ToneSource(110.0, 2.0, { now = it })
        val src = object : AudioSource {
            override fun start(onFrames: (FloatArray, Int) -> Unit) {
                if (calls.incrementAndGet() == 1) {
                    firstEntered.countDown(); stopSeen.await()
                    throw IllegalStateException("boom") // error exit, stop flag left set (stale)
                }
                if (flag.get()) return // a stale flag would abort the second start immediately
                tone.start(onFrames)
            }
            override fun stop() { flag.set(true); tone.stop(); stopSeen.countDown() }
            override fun reset() { flag.set(false); tone.reset() }
        }
        val e = engine(src)
        e.select(guitar, standard, TunerMode.Auto)
        e.start(); firstEntered.await(); e.stop()
        e.start()
        await { e.state.value as? TunerState.Reading }
        e.stop()
    }
}
