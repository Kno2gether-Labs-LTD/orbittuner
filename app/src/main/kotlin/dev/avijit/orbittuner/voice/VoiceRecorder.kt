package dev.avijit.orbittuner.voice

import dev.avijit.orbittuner.music.SungProfile
import dev.avijit.orbittuner.tuner.AudioSource
import dev.avijit.orbittuner.tuner.dsp.Mpm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.math.sqrt

/**
 * One clip: the WAV to send (16 kHz PCM16), the sung pitch-class profile built on the device, and its length.
 * Holds audio in memory only; nothing here is ever logged or written to disk.
 */
class Recording(val wav: ByteArray, val sung: SungProfile, val seconds: Double) {
    override fun toString() = "Recording(seconds=$seconds, bytes=${wav.size}, voiced=${sung.voicedSeconds})"
}

/**
 * Records a [Recording] from an [AudioSource] (48 kHz mono float, e.g. AudioRecordSource) while the MPM pitch
 * detector builds a [SungProfile] from the same audio. The source holds the microphone only inside [record].
 *
 * Calling contract (A3):
 * ```
 * val session = recorder.begin()                       // synchronous, on the UI thread, BEFORE launching
 * job = scope.launch { val clip = recorder.record(session, onLevel = …) }
 * // Stop button:
 * session.stop()                                       // or recorder.stop(): the pending/current session only
 * ```
 * A stop targets one session. It wins even when it lands after the launch but before capture starts (then the mic is
 * never opened and the clip is empty, which [VoiceSearch] treats as too short). A stop for a session that has finished
 * (a double tap, a stop racing the 15 s end) does nothing, so it can never cut the next recording short. Each session is
 * recorded at most once. Cancelling the coroutine also stops the session and releases the mic.
 *
 * Mic permission is the caller's job (the same RECORD_AUDIO request path as the tuner); without it [record] throws the
 * source's SecurityException, and a busy or missing mic its MicException; no partial [Recording] is returned then.
 */
class VoiceRecorder(
    private val source: AudioSource,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Builds the WAV; runs on [io], never on the caller's (possibly main) dispatcher. Injectable for tests. */
    private val encode: (FloatArray, Int) -> ByteArray = Wav::encode16k,
) {
    private val lock = Any()
    /** The newest session from [begin]; [stop] targets it. Guarded by [lock]. */
    private var latest: Session? = null

    /** One armed recording. [stop] ends it (before or during capture); after it has finished, [stop] is a no-op. */
    inner class Session internal constructor() {
        // All guarded by [lock].
        internal var stopped = false
        internal var started = false
        internal var capturing = false
        internal var finished = false

        /** Ends this recording early (it returns what was captured so far); does nothing once it has finished. */
        fun stop() {
            synchronized(lock) {
                if (finished || stopped) return
                stopped = true
                // Only this session's capture loop is told; a finished session never reaches the source.
                if (capturing) source.stop()
            }
        }

        internal val isStopped: Boolean get() = synchronized(lock) { stopped }
    }

    /** Arms a new recording. Call it synchronously before launching [record], so a Stop tapped at once is not lost. */
    fun begin(): Session = synchronized(lock) { Session().also { latest = it } }

    /** Stops the newest session from [begin] if it is pending or recording; otherwise does nothing. */
    fun stop() {
        synchronized(lock) { latest }?.stop()
    }

    /**
     * Captures [session] until its stop, [maxSeconds], or cancellation. [onLevel] gets each chunk's level, 0..1, on the
     * capture thread. Capture and WAV encoding run on [io].
     */
    suspend fun record(session: Session, maxSeconds: Int = MAX_SECONDS, onLevel: (Float) -> Unit = {}): Recording {
        synchronized(lock) {
            check(!session.started) { "a session is recorded once" }
            session.started = true
        }
        return try {
            capture(session, maxSeconds, onLevel)
        } finally {
            synchronized(lock) {
                session.capturing = false
                session.finished = true
                if (latest === session) latest = null
            }
        }
    }

    private suspend fun capture(session: Session, maxSeconds: Int, onLevel: (Float) -> Unit): Recording = coroutineScope {
        val cap = SAMPLE_RATE * maxSeconds.coerceIn(1, MAX_SECONDS)
        val samples = FloatArray(cap)
        var count = 0
        val sung = SungProfile()
        val mpm = Mpm(SAMPLE_RATE, WINDOW)
        val window = FloatArray(WINDOW)
        var nextHop = WINDOW

        val capture = async(io) {
            // Under the lock: clear the source's stale flag and mark this session capturing, so a stop either sees
            // stopped (we skip the mic) or signals the source, whose start() then returns without capturing.
            val go = synchronized(lock) {
                if (session.stopped) false else { source.reset(); session.capturing = true; true }
            }
            if (go) source.start { buf, n ->
                if (session.isStopped) { source.stop(); return@start }
                val take = minOf(n, cap - count)
                if (take > 0) {
                    System.arraycopy(buf, 0, samples, count, take)
                    var e = 0.0
                    for (i in 0 until take) e += buf[i] * buf[i]
                    count += take
                    onLevel(sqrt(e / take).toFloat().let { (it * LEVEL_GAIN).coerceIn(0f, 1f) })
                    while (count >= nextHop) {
                        System.arraycopy(samples, nextHop - WINDOW, window, 0, WINDOW)
                        val hz = mpm.detect(window)?.hz?.takeIf { it in MIN_VOICE_HZ..MAX_VOICE_HZ } ?: 0.0
                        sung.add(hz, HOP.toDouble() / SAMPLE_RATE)
                        nextHop += HOP
                    }
                }
                if (count >= cap) source.stop()
            }
            synchronized(lock) { session.capturing = false }
            Recording(encode(samples, count), sung, count.toDouble() / SAMPLE_RATE)
        }
        try {
            capture.await()
        } catch (e: CancellationException) {
            session.stop() // the capture loop blocks its thread; ask it to end so the mic is released
            throw e
        }
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val MAX_SECONDS = 15
        /** Clips shorter than this are not sent (the service answers 400 bad_audio). */
        const val MIN_SECONDS = 1.0
        private const val WINDOW = 2048
        private const val HOP = 1024
        private const val MIN_VOICE_HZ = 65.0
        private const val MAX_VOICE_HZ = 1_100.0
        private const val LEVEL_GAIN = 4f
    }
}
