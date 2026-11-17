package dev.avijit.orbittuner.voice

import dev.avijit.orbittuner.tuner.AudioSource
import dev.avijit.orbittuner.tuner.MicError
import dev.avijit.orbittuner.tuner.MicException
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin

class VoiceRecorderTest {
    /** A 220 Hz (A3) tone in 1024-sample chunks; stops after [seconds], or never ([seconds] null) until stop(). */
    private class ToneSource(private val seconds: Double?, private val hz: Double = 220.0) : AudioSource {
        val stopped = AtomicBoolean(false)
        val running = CountDownLatch(1)
        @Volatile var active = false
        override fun reset() = stopped.set(false)
        override fun stop() = stopped.set(true)
        override fun start(onFrames: (FloatArray, Int) -> Unit) {
            active = true
            try {
                val buf = FloatArray(1024)
                var t = 0L
                running.countDown()
                while (!stopped.get() && (seconds == null || t < seconds * 48_000)) {
                    for (i in buf.indices) buf[i] = (0.4 * sin(2 * PI * hz * (t + i) / 48_000)).toFloat()
                    onFrames(buf, buf.size)
                    t += buf.size
                    if (seconds == null) Thread.sleep(1)
                }
            } finally { active = false }
        }
    }

    @Test fun capsAtMaxSecondsAndProfilesThePitch() = runBlocking {
        val src = ToneSource(seconds = null)
        val levels = ArrayList<Float>()
        val r = withTimeout(10_000) { VoiceRecorder(src, Dispatchers.Default).let { it.record(it.begin(), maxSeconds = 2) { synchronized(levels) { levels += it } } } }
        assertEquals(2.0, r.seconds, 1e-9)
        assertEquals(44 + 2 * 32_000, r.wav.size)
        assertFalse(src.active)
        // A held A3 is singing-like but under 4 s; every voiced hop lands in the A bin.
        assertEquals(r.sung.voicedSeconds, r.sung.histogram()[9], 1e-9)
        assertTrue(r.sung.voicedSeconds > 1.8)
        assertTrue(levels.isNotEmpty() && levels.all { it in 0f..1f } && levels.max() > 0.5f)
    }

    @Test fun stopEndsEarlyWithWhatWasCaptured() = runBlocking {
        val src = ToneSource(seconds = null)
        val rec = VoiceRecorder(src, Dispatchers.Default)
        val session = rec.begin()
        val job = async(Dispatchers.Default) { rec.record(session) }
        assertTrue(src.running.await(5, TimeUnit.SECONDS))
        Thread.sleep(100)
        session.stop()
        val r = withTimeout(5_000) { job.await() }
        assertTrue(r.seconds > 0 && r.seconds < 15)
        assertEquals(44 + 2 * ((r.seconds * 48_000).toInt() / 3), r.wav.size)
    }

    @Test fun cancellingReleasesTheSource() = runBlocking {
        val src = ToneSource(seconds = null)
        val job = async(Dispatchers.Default) { VoiceRecorder(src, Dispatchers.Default).let { it.record(it.begin()) } }
        assertTrue(src.running.await(5, TimeUnit.SECONDS))
        job.cancel()
        withTimeout(5_000) { job.join() }
        assertFalse(src.active)
    }

    @Test fun sourceEndingEarlyReturnsTheClip() = runBlocking {
        val r = VoiceRecorder(ToneSource(seconds = 0.5), Dispatchers.Default).let { it.record(it.begin()) }
        assertTrue(r.seconds < VoiceRecorder.MIN_SECONDS)
    }

    @Test fun encodingRunsOnTheIoDispatcherNotTheCaller() = runBlocking {
        var ioThread: Thread? = null
        val exec = Executors.newSingleThreadExecutor { Thread(it, "rec-io").also { t -> ioThread = t } }
        try {
            var encodedOn: Thread? = null
            val rec = VoiceRecorder(ToneSource(seconds = 0.5), exec.asCoroutineDispatcher()) { s, n ->
                encodedOn = Thread.currentThread()
                Wav.encode16k(s, n)
            }
            val caller = Thread.currentThread() // runBlocking's thread stands in for Main
            val r = rec.record(rec.begin())
            assertSame(ioThread, encodedOn)
            assertNotEquals(caller, encodedOn)
            assertEquals(44 + 2 * ((r.seconds * 48_000).toInt() / 3), r.wav.size)
        } finally { exec.shutdown() }
    }

    @Test fun stopRightAfterLaunchEndsPromptly() = runBlocking {
        repeat(20) { i ->
            val src = ToneSource(seconds = null)
            val rec = VoiceRecorder(src, Dispatchers.Default)
            val session = rec.begin()
            val job = async(Dispatchers.Default) { rec.record(session) }
            if (i % 2 == 0) session.stop() else rec.stop()
            val r = withTimeout(2_000) { job.await() }
            assertTrue("ran ${r.seconds} s", r.seconds < 1.0)
            assertFalse(src.active)
        }
    }

    @Test fun stopBeforeCaptureBeginsSkipsTheMicForThatSession() = runBlocking {
        val src = ToneSource(seconds = null)
        val rec = VoiceRecorder(src, Dispatchers.Default)
        val session = rec.begin()
        session.stop() // tapped before the recording coroutine reached the source
        val r = withTimeout(2_000) { rec.record(session) }
        assertEquals(0.0, r.seconds, 0.0)
        assertEquals(1L, src.running.count) // never started
        // Only that session was stopped: the next one runs to its limit.
        val again = withTimeout(10_000) { rec.record(rec.begin(), maxSeconds = 1) }
        assertEquals(1.0, again.seconds, 1e-9)
    }

    @Test fun staleStopWithNothingPendingDoesNotCutTheNextRecording() = runBlocking {
        val rec = VoiceRecorder(ToneSource(seconds = null), Dispatchers.Default)
        rec.stop() // nothing armed yet
        assertEquals(1.0, withTimeout(10_000) { rec.record(rec.begin(), maxSeconds = 1) }.seconds, 1e-9)
        // A double-tapped Stop landing after the clip ended, on the recorder and on the finished session.
        val first = rec.begin()
        withTimeout(10_000) { rec.record(first, maxSeconds = 1) }
        first.stop(); rec.stop()
        assertEquals(1.0, withTimeout(10_000) { rec.record(rec.begin(), maxSeconds = 1) }.seconds, 1e-9)
    }

    @Test fun anOldSessionsStopDoesNotEndTheNewOne() = runBlocking {
        val src = ToneSource(seconds = null)
        val rec = VoiceRecorder(src, Dispatchers.Default)
        val old = rec.begin()
        withTimeout(10_000) { rec.record(old, maxSeconds = 1) }
        val current = rec.begin()
        val job = async(Dispatchers.Default) { rec.record(current, maxSeconds = 1) }
        assertTrue(src.running.await(5, TimeUnit.SECONDS))
        old.stop()
        assertEquals(1.0, withTimeout(10_000) { job.await() }.seconds, 1e-9)
    }

    @Test fun aSessionIsRecordedOnce() = runBlocking {
        val rec = VoiceRecorder(ToneSource(seconds = 0.1), Dispatchers.Default)
        val s = rec.begin()
        rec.record(s)
        try { rec.record(s); fail() } catch (_: IllegalStateException) {}
    }

    private class FailingSource(private val e: Exception) : AudioSource {
        override fun start(onFrames: (FloatArray, Int) -> Unit) { onFrames(FloatArray(1024), 1024); throw e }
        override fun stop() {}
    }

    @Test fun permissionDeniedPropagates() = runBlocking {
        try { VoiceRecorder(FailingSource(SecurityException("RECORD_AUDIO not granted")), Dispatchers.Default).let { it.record(it.begin()) }; fail() }
        catch (_: SecurityException) {}
    }

    @Test fun micInUsePropagates() = runBlocking {
        try { VoiceRecorder(FailingSource(MicException(MicError.InUse)), Dispatchers.Default).let { it.record(it.begin()) }; fail() }
        catch (e: MicException) { assertEquals(MicError.InUse, e.reason) }
    }
}
