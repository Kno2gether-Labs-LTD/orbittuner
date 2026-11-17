package dev.avijit.orbittuner.metronome

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** What the metronome screen shows; [beat] is the beat just heard (0-based), or -1 when none. */
data class MetronomeState(
    val playing: Boolean,
    val bpm: Int,
    val beatsPerBar: Int,
    val accent: Boolean,
    val beat: Int,
)

/** The app's metronome ([MetronomeEngine]; tests substitute a fake). */
interface Metronome {
    val state: StateFlow<MetronomeState>
    fun start()
    fun stop()
    fun set(bpm: Int, beatsPerBar: Int, accent: Boolean)
}

/**
 * Sample-accurate metronome: clicks are mixed into a streaming 48 kHz mono 16-bit [AudioTrack] by a writer thread
 * in ~10 ms buffers, placed by [ClickScheduler], so timing comes from the audio clock, not from timers. The beat
 * shown on screen follows the track's playback head, so the dot lights when its click is played, not when it is
 * written ahead. One instance app-wide (AppGraph): the Tools tab and the song sheet share it.
 *
 * [stop] ends the thread, which drops what is still buffered and releases the track. [start] while playing and
 * [stop] while stopped do nothing. [onStart] runs before a start (AppGraph uses it to stop a backing track).
 */
class MetronomeEngine(private val onStart: () -> Unit = {}) : Metronome {
    private val _state = MutableStateFlow(
        MetronomeState(playing = false, bpm = Tempo.DEFAULT_BPM, beatsPerBar = Tempo.DEFAULT_BEATS, accent = true, beat = -1),
    )
    override val state: StateFlow<MetronomeState> = _state.asStateFlow()

    private val lock = Any()
    private var worker: Worker? = null

    override fun start() {
        if (synchronized(lock) { worker != null }) return
        onStart() // outside the lock: it stops the other player, which takes its own lock
        synchronized(lock) {
            if (worker != null) return
            worker = Worker().also { it.start() }
            _state.update { it.copy(playing = true, beat = -1) }
        }
    }

    override fun stop() {
        synchronized(lock) {
            val w = worker ?: return
            w.running = false
            worker = null
            _state.update { it.copy(playing = false, beat = -1) }
        }
    }

    override fun set(bpm: Int, beatsPerBar: Int, accent: Boolean) {
        // The writer thread picks the new values up before its next buffer.
        _state.update {
            it.copy(bpm = Tempo.clamp(bpm), beatsPerBar = beatsPerBar.coerceIn(1, Tempo.MAX_BEATS), accent = accent)
        }
    }

    /** Publishes [beat] only if [w] is still the current worker (a stopped one may still be winding down). */
    private fun heard(w: Worker, beat: Int) {
        synchronized(lock) {
            if (worker === w) _state.update { it.copy(beat = beat) }
        }
    }

    /** The thread ended on its own (track failure): show the play button again. */
    private fun ended(w: Worker) {
        synchronized(lock) {
            if (worker === w) {
                worker = null
                _state.update { it.copy(playing = false, beat = -1) }
            }
        }
    }

    private inner class Worker : Thread("OT-Metronome") {
        @Volatile var running = true

        override fun run() {
            var track: AudioTrack? = null
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                val minBuf = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder().setSampleRate(SR).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .setBufferSizeInBytes(maxOf(minBuf, CHUNK * 2 * 4))
                    .build()
                track.play()

                val scheduler = ClickScheduler(SR)
                val synth = ClickSynth(SR)
                val buf = ShortArray(CHUNK)
                // Clicks written but not yet played: absolute frame and beat, in order.
                val pendingAt = LongArray(PENDING)
                val pendingBeat = IntArray(PENDING)
                var head = 0
                var count = 0
                var written = 0L
                while (running) {
                    val s = _state.value
                    scheduler.configure(s.bpm, s.beatsPerBar)
                    val clicks = scheduler.next(CHUNK)
                    synth.render(buf, CHUNK, clicks, s.accent)
                    for (c in clicks) {
                        if (count == PENDING) { head = (head + 1) % PENDING; count-- } // never happens at 240 BPM
                        val i = (head + count) % PENDING
                        pendingAt[i] = written + c.offset
                        pendingBeat[i] = c.beat
                        count++
                    }
                    if (track.write(buf, 0, CHUNK, AudioTrack.WRITE_BLOCKING) < 0) break
                    written += CHUNK
                    // playbackHeadPosition is an unsigned 32-bit frame count.
                    val played = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
                    var beat = -1
                    while (count > 0 && pendingAt[head] <= played) {
                        beat = pendingBeat[head]
                        head = (head + 1) % PENDING
                        count--
                    }
                    if (beat >= 0 && running) heard(this, beat)
                }
            } catch (e: Exception) {
                Log.w("Metronome", "audio stopped", e)
            } finally {
                track?.let { t ->
                    // Drop what is still buffered so the clicks stop now, then give the track back.
                    try { t.pause(); t.flush(); t.stop() } catch (_: IllegalStateException) {}
                    t.release()
                }
                ended(this)
            }
        }
    }

    companion object {
        const val SR = 48_000
        /** 10 ms per write. */
        private const val CHUNK = 480
        private const val PENDING = 16
    }
}

/**
 * The click sound: a ~25 ms decaying sine, 1568 Hz (G6) for an accented first beat and 1046 Hz (C6) otherwise,
 * the accent louder. A click that starts near the end of one buffer carries on into the next.
 */
internal class ClickSynth(sampleRate: Int) {
    private val accentWave = wave(sampleRate, 1568.0, 0.85)
    private val plainWave = wave(sampleRate, 1046.0, 0.5)
    private var voice: FloatArray? = null
    private var voicePos = 0

    /** Fills [out] with silence plus the tail of the sounding click and any of [clicks]. */
    fun render(out: ShortArray, frames: Int, clicks: List<Click>, accent: Boolean) {
        var c = 0
        for (i in 0 until frames) {
            while (c < clicks.size && clicks[c].offset == i) {
                voice = if (accent && clicks[c].beat == 0) accentWave else plainWave
                voicePos = 0
                c++
            }
            val v = voice
            var sample = 0f
            if (v != null) {
                sample = v[voicePos++]
                if (voicePos >= v.size) voice = null
            }
            out[i] = (sample * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private companion object {
        const val LENGTH_S = 0.025
        const val ATTACK_S = 0.001
        const val DECAY_S = 0.006
        const val RELEASE_S = 0.003

        fun wave(sr: Int, hz: Double, amp: Double): FloatArray {
            val n = (LENGTH_S * sr).toInt()
            val attack = ATTACK_S * sr
            val release = RELEASE_S * sr
            return FloatArray(n) { i ->
                val t = i.toDouble() / sr
                val env = minOf(1.0, i / attack) * exp(-t / DECAY_S) * minOf(1.0, (n - 1 - i) / release)
                (amp * env * sin(2 * PI * hz * t)).toFloat()
            }
        }
    }
}
