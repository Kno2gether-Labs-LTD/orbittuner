package dev.avijit.orbittuner.tuner

import android.util.Log
import dev.avijit.orbittuner.BuildConfig
import dev.avijit.orbittuner.music.Accidental
import dev.avijit.orbittuner.music.Note
import dev.avijit.orbittuner.music.Pitch
import dev.avijit.orbittuner.tuner.dsp.Mpm
import dev.avijit.orbittuner.tuner.dsp.PitchResult
import dev.avijit.orbittuner.tuner.dsp.Smoother
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs
import kotlin.math.roundToInt

/** Allocation-free suppliers (a plain `() -> Double` boxes on every call in the audio loop). */
fun interface DoubleSource { fun get(): Double }
fun interface MillisClock { fun nowMs(): Long }

/** What the tuner UI needs from a pitch tracker ([TunerEngine]; tests substitute a fake). */
interface PitchTracker {
    val state: StateFlow<TunerState>
    val tunedStrings: StateFlow<Set<Int>>
    fun select(instrument: Instrument, tuning: Tuning?, mode: TunerMode)
    /** Changes only the mode (or the Manual string): unlike [select], strings already tuned stay tuned. */
    fun setMode(mode: TunerMode)
    /** While true (a reference tone is playing), no string can become tuned: the mic would hear the speaker. */
    fun holdTuned(hold: Boolean) {}
    fun start()
    fun stop()
}

sealed interface TunerMode {
    data object Auto : TunerMode
    data object Chromatic : TunerMode
    data class Manual(val stringIndex: Int) : TunerMode
}

sealed interface TunerState {
    data object Idle : TunerState
    data object Listening : TunerState
    data class Reading(
        val noteName: String,
        val octave: Int,
        val cents: Double,
        val hz: Double,
        val stringIndex: Int?,
        val inTune: Boolean,
        /**
         * System.nanoTime() when the audio chunk that completed this reading's analysis window
         * arrived in the engine (OT-LAT measures emit minus this).
         */
        val windowEndNanos: Long = 0L,
        /** Analysis window (samples) that produced this reading: 2048 or 8192. */
        val windowSize: Int = 0,
    ) : TunerState
    data class Error(val reason: MicError) : TunerState
}

/**
 * Pitch-tracking engine. Each [start] creates a [Run] with its own capture/analysis thread
 * ("OT-Tuner") and its own analysis state; a new run's thread first joins the previous one, so two
 * capture threads never overlap. The [AudioSource] capture loop feeds a ring buffer; every hop (50%
 * of the current window) the newest window is analysed with [Mpm] and published through [state].
 *
 * [select] expects a [Tuning] already offset by the user's whole-tuning offset.
 *
 * Modelling notes: tuning notes are in ascending PITCH order, not physical string order (re-entrant
 * ukulele/banjo strings are reordered), and courses of equal pitch (12-string, balalaika) are
 * merged into one distinct pitch. [stringIndex] therefore indexes [Tuning.notes].
 *
 * [start] and [stop] return immediately; after [stop] returns no further readings are published.
 */
class TunerEngine(
    private val source: AudioSource,
    private val a4: DoubleSource,
    private val clock: MillisClock,
) : PitchTracker {
    /** Test hook: every published reading, in order (never conflated). */
    @Volatile internal var readingListener: ((TunerState.Reading) -> Unit)? = null

    private class Selection(val tuning: Tuning?, val mode: TunerMode, val version: Int)

    private val lock = Any()
    private val _state = MutableStateFlow<TunerState>(TunerState.Idle)
    override val state: StateFlow<TunerState> = _state.asStateFlow()

    private val _tuned = MutableStateFlow<Set<Int>>(emptySet())
    /** Strings that have been continuously in tune for [TUNED_AFTER_MS]. */
    override val tunedStrings: StateFlow<Set<Int>> = _tuned.asStateFlow()

    @Volatile private var selection = Selection(null, TunerMode.Chromatic, 0)
    private var current: Run? = null

    override fun select(instrument: Instrument, tuning: Tuning?, mode: TunerMode) {
        synchronized(lock) {
            val effective = if (instrument.chromatic) null else tuning
            selection = Selection(effective, if (effective == null) TunerMode.Chromatic else mode, selection.version + 1)
            _tuned.value = emptySet()
        }
    }

    override fun setMode(mode: TunerMode) {
        synchronized(lock) {
            val cur = selection
            selection = Selection(cur.tuning, if (cur.tuning == null) TunerMode.Chromatic else mode, cur.version + 1)
        }
    }

    @Volatile private var hold = false
    override fun holdTuned(hold: Boolean) { this.hold = hold }

    override fun start() {
        synchronized(lock) {
            val old = current
            if (old != null) retire(old)
            val run = Run(old?.thread)
            current = run
            _state.value = TunerState.Listening
            run.thread.start()
        }
    }

    override fun stop() {
        synchronized(lock) {
            current?.let { retire(it) }
            current = null
            _state.value = TunerState.Idle
        }
    }

    /** Marks [run] stopped and, if its capture loop is (about to be) running, asks the source to stop. */
    private fun retire(run: Run) {
        run.stopped = true
        if (run.inSource && !run.done) {
            try { source.stop() } catch (_: Exception) {}
        }
    }

    private inner class Run(private val previous: Thread?) {
        @Volatile var stopped = false
        var inSource = false   // guarded by lock
        var done = false       // guarded by lock
        val thread = Thread({ body() }, "OT-Tuner")

        private val ring = FloatArray(LONG_WINDOW)
        private var total = 0L
        private var lastAnalysisAt = 0L
        private val smoother = Smoother()
        private var seenVersion = -1
        private var lastKey = Long.MIN_VALUE
        private var inTuneSince: Long? = null
        private var prevHz: Double? = null
        private var lastReadingAt = 0L
        private var noPitchSince: Long? = null
        private var fallbackActive = false
        private var lastLongAt = 0L
        private var fallbackEligible = false
        private var mpmShort: Mpm? = null
        private var mpmLong: Mpm? = null
        private val workShort = FloatArray(SHORT_WINDOW)
        private val workLong = FloatArray(LONG_WINDOW)
        // Per-selection/A4 cache of target frequencies (no per-callback allocation).
        private var cacheVersion = -1
        private var cacheA4 = Double.NaN
        private var targetHz = DoubleArray(0)
        private var lowestHz: Double? = null

        private fun body() {
            try {
                try { previous?.join() } catch (_: InterruptedException) {}
                // Under the lock: clear any stale stop flag and mark the source entered, so a stop()
                // either sees stopped (we skip) or signals a source that is guaranteed fresh.
                val go = synchronized(lock) {
                    if (stopped) false else { source.reset(); inSource = true; true }
                }
                if (go) {
                    try {
                        source.start { buf, n -> onFrames(buf, n) }
                    } finally {
                        // stop() after this point must not signal the (finished) source.
                        synchronized(lock) { inSource = false }
                    }
                    if (!stopped) fail(MicError.Unavailable)
                }
            } catch (e: SecurityException) {
                fail(MicError.PermissionDenied)
            } catch (e: MicException) {
                fail(e.reason)
            } catch (e: Throwable) {
                fail(MicError.Unavailable)
            } finally {
                synchronized(lock) { done = true }
            }
        }

        private fun fail(reason: MicError) {
            synchronized(lock) { if (!stopped) _state.value = TunerState.Error(reason) }
        }

        private fun onFrames(buf: FloatArray, n: Int) {
            if (stopped) return
            val arrival = System.nanoTime()
            for (i in 0 until n) ring[((total + i) % LONG_WINDOW).toInt()] = buf[i]
            total += n
            val sel = selection
            val a = a4.get()
            if (sel.version != cacheVersion || a != cacheA4) refreshTargets(sel, a)
            val base = windowFor(lowestHz, prevHz)
            if (total < base || total - lastAnalysisAt < base / 2) return
            lastAnalysisAt = total
            var win = base
            var res = detect(base)
            // Low-note fallback: the short window is always tried first at its normal hop (so plucks
            // keep the low-latency path); if it finds nothing, interleave a long-window attempt at
            // the long window's own hop. Never sticks on the long window.
            if (res == null && fallbackActive && base == SHORT_WINDOW &&
                total >= LONG_WINDOW && total - lastLongAt >= LONG_WINDOW / 2) {
                lastLongAt = total
                win = LONG_WINDOW
                res = detect(LONG_WINDOW)
            }
            analyse(res, win, sel, a, arrival)
        }

        private fun refreshTargets(sel: Selection, a: Double) {
            cacheVersion = sel.version; cacheA4 = a
            val midi = sel.tuning?.midi ?: emptyList()
            targetHz = DoubleArray(midi.size) { Pitch.midiToHz(midi[it].toDouble(), a) }
            lowestHz = if (sel.mode == TunerMode.Chromatic || targetHz.isEmpty()) null else targetHz.min()
            // The short window cannot see periods beyond ~1024 samples (< ~47 Hz); only selections that
            // can involve very low notes pay for occasional long-window attempts.
            fallbackEligible = sel.mode == TunerMode.Chromatic || targetHz.isEmpty() ||
                (lowestHz ?: Double.MAX_VALUE) < FALLBACK_BELOW_HZ
            noPitchSince = null; fallbackActive = false
        }

        private fun detect(win: Int): PitchResult? {
            val long = win == LONG_WINDOW
            val mpm = if (long) (mpmLong ?: Mpm(SAMPLE_RATE, win).also { mpmLong = it })
                else (mpmShort ?: Mpm(SAMPLE_RATE, win).also { mpmShort = it })
            val w = if (long) workLong else workShort
            val start = total - win
            for (i in 0 until win) w[i] = ring[((start + i) % LONG_WINDOW).toInt()]
            return mpm.detect(w)
        }

        private fun analyse(res: PitchResult?, win: Int, sel: Selection, a: Double, arrival: Long) {
            val now = clock.nowMs()
            if (seenVersion != sel.version) {
                seenVersion = sel.version
                smoother.reset(); lastKey = Long.MIN_VALUE; inTuneSince = null
            }
            if (res == null) {
                inTuneSince = null
                val since = noPitchSince ?: now.also { noPitchSince = it }
                if (fallbackEligible && now - since >= FALLBACK_MS) fallbackActive = true
                if (now - lastReadingAt > HOLD_MS) {
                    synchronized(lock) {
                        if (!stopped && _state.value is TunerState.Reading) {
                            prevHz = null
                            _state.value = TunerState.Listening
                        }
                    }
                }
                return
            }
            noPitchSince = null
            fallbackActive = false
            val hz = res.hz
            val tuning = sel.tuning
            var idx: Int? = null
            val targetMidi: Int
            val mode = sel.mode
            when {
                tuning != null && mode is TunerMode.Manual && targetHz.isNotEmpty() -> {
                    idx = mode.stringIndex.coerceIn(0, targetHz.size - 1)
                    targetMidi = tuning.midi[idx]
                }
                tuning != null && targetHz.isNotEmpty() -> {
                    // Nearest by pitch distance, never by index.
                    var best = 0
                    var bestD = Double.MAX_VALUE
                    for (i in targetHz.indices) {
                        val d = abs(Pitch.centsBetween(hz, targetHz[i]))
                        if (d < bestD) { bestD = d; best = i }
                    }
                    idx = best
                    targetMidi = tuning.midi[best]
                }
                else -> targetMidi = Pitch.hzToMidi(hz, a).roundToInt()
            }
            val tHz = if (idx != null) targetHz[idx] else Pitch.midiToHz(targetMidi.toDouble(), a)
            val rawCents = Pitch.centsBetween(hz, tHz)
            val key = (idx ?: -1).toLong() * 100_000 + targetMidi
            if (key != lastKey) { smoother.reset(); lastKey = key; inTuneSince = null }
            val cents = smoother.push(rawCents)
            val inTune = abs(cents) <= IN_TUNE_CENTS
            var tunedNow = false
            if (idx != null && inTune && !hold) {
                val since = inTuneSince ?: now.also { inTuneSince = it }
                tunedNow = now - since >= TUNED_AFTER_MS
            } else inTuneSince = null

            prevHz = hz
            lastReadingAt = now
            val reading = TunerState.Reading(
                noteName = Note(targetMidi).name(Accidental.Sharp),
                octave = Math.floorDiv(targetMidi, 12) - 1,
                cents = cents, hz = hz, stringIndex = idx, inTune = inTune, windowEndNanos = arrival, windowSize = win,
            )
            synchronized(lock) {
                if (stopped) return
                // Drop tuned-string updates computed against a selection that has since changed.
                if (tunedNow && selection.version == sel.version) _tuned.update { it + idx!! }
                _state.value = reading
                readingListener?.invoke(reading)
            }
            if (BuildConfig.DEBUG) Log.d("OT-LAT", "window-end->emit ${(System.nanoTime() - arrival) / 1000} us (window $win)")
        }
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val SHORT_WINDOW = 2048
        const val LONG_WINDOW = 8192
        const val LOW_HZ = 80.0
        const val IN_TUNE_CENTS = 3.0
        const val TUNED_AFTER_MS = 400L
        private const val HOLD_MS = 500L
        private const val FALLBACK_MS = 250L
        private const val FALLBACK_BELOW_HZ = 100.0

        /** 8192 when the lowest target, or the previous reading, is under 80 Hz; else 2048. */
        internal fun windowFor(lowestHz: Double?, prevHz: Double?): Int =
            if ((lowestHz != null && lowestHz < LOW_HZ) || (prevHz != null && prevHz < LOW_HZ)) LONG_WINDOW
            else SHORT_WINDOW
    }
}
