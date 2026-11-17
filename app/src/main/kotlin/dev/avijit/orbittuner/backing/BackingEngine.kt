package dev.avijit.orbittuner.backing

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the player shows: the beat just heard. [bar] is the 0-based bar within the progression (-1 in the count-in),
 * [beat] 0-based (-1 before the first); [chord] sounds now and [nextChord] comes next, transposed for display.
 */
data class BackingState(
    val playing: Boolean,
    val lessonId: String?,
    val bar: Int,
    val beat: Int,
    val chord: String,
    val nextChord: String,
    val countIn: Boolean,
)

/**
 * The app's backing-track player ([BackingEngine]; tests substitute a fake). Call [play], [stop] and [update] from the
 * main thread only: each is safe on its own, but two threads starting playback at once could race.
 */
interface Backing {
    val state: StateFlow<BackingState>

    /** Starts [lesson] from its count-in (restarts if something is playing). */
    fun play(lesson: Lesson, keyOffset: Int, bpm: Int, chords: Boolean)

    fun stop()

    /** Key and tempo change at the next bar line; [chords] at once. Ignored while stopped. */
    fun update(keyOffset: Int, bpm: Int, chords: Boolean)

    companion object {
        const val MIN_BPM = 40
        const val MAX_BPM = 220
        const val MIN_KEY = -6
        const val MAX_KEY = 6
        val IDLE = BackingState(playing = false, lessonId = null, bar = -1, beat = -1, chord = "", nextChord = "", countIn = false)
    }
}

/**
 * Generated backing tracks on a streaming 48 kHz mono 16-bit [AudioTrack], written by one thread at urgent-audio
 * priority in 20 ms buffers: [Sequencer] places the notes sample-accurately, [Voices] plays them. Like the metronome,
 * the position on screen follows the track's playback head (a chord shows when it is heard, not when it is written
 * ahead), [stop] drops what is buffered and releases the track, and a stopped worker can no longer publish.
 *
 * [onStart] runs before playback starts (AppGraph uses it to stop the metronome). One instance app-wide.
 */
class BackingEngine(private val onStart: () -> Unit = {}) : Backing {
    private val _state = MutableStateFlow(Backing.IDLE)
    override val state: StateFlow<BackingState> = _state.asStateFlow()

    private class Settings(val keyOffset: Int, val bpm: Int, val chords: Boolean)

    private val lock = Any()
    private var worker: Worker? = null

    override fun play(lesson: Lesson, keyOffset: Int, bpm: Int, chords: Boolean) {
        onStart()
        synchronized(lock) {
            worker?.running = false
            val s = Settings(keyOffset.coerceIn(Backing.MIN_KEY, Backing.MAX_KEY), bpm.coerceIn(Backing.MIN_BPM, Backing.MAX_BPM), chords)
            val first = lesson.bars[0].trim().split(Regex("\\s+"))
            val next = first.getOrNull(1) ?: lesson.bars[1 % lesson.bars.size].trim().split(Regex("\\s+"))[0]
            _state.value = BackingState(
                playing = true, lessonId = lesson.id, bar = -1, beat = -1,
                chord = lesson.chordIn(first[0], s.keyOffset), nextChord = lesson.chordIn(next, s.keyOffset), countIn = true,
            )
            worker = Worker(lesson, s).also { it.start() }
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

    override fun update(keyOffset: Int, bpm: Int, chords: Boolean) {
        synchronized(lock) {
            // The writer thread picks the new values up before its next buffer.
            worker?.settings = Settings(keyOffset.coerceIn(Backing.MIN_KEY, Backing.MAX_KEY), bpm.coerceIn(Backing.MIN_BPM, Backing.MAX_BPM), chords)
        }
    }

    /** Publishes [p] only if [w] is still the current worker (a stopped one may still be winding down). */
    private fun heard(w: Worker, p: Event.Position) {
        synchronized(lock) {
            if (worker === w) {
                _state.update { it.copy(bar = p.bar, beat = p.beat, chord = p.chord, nextChord = p.nextChord, countIn = p.bar < 0) }
            }
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

    private inner class Worker(private val lesson: Lesson, initial: Settings) : Thread("OT-Backing") {
        @Volatile var running = true
        @Volatile var settings = initial

        override fun run() {
            var track: AudioTrack? = null
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                val minBuf = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder().setSampleRate(SR).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(maxOf(minBuf, CHUNK * 2 * 3))
                    .build()
                track.play()

                val sequencer = Sequencer(SR)
                val voices = Voices(SR).apply { setGroove(lesson.groove) }
                val mix = FloatArray(CHUNK)
                val pcm = ShortArray(CHUNK)
                // Positions written but not yet played, in order.
                val pendingAt = LongArray(PENDING)
                val pendingPos = arrayOfNulls<Event.Position>(PENDING)
                var head = 0
                var count = 0
                var written = 0L
                while (running) {
                    val s = settings
                    sequencer.configure(lesson, s.keyOffset, s.bpm)
                    voices.chords = s.chords
                    val events = sequencer.next(CHUNK)
                    voices.render(mix, CHUNK, events)
                    for (i in 0 until CHUNK) pcm[i] = (mix[i] * Short.MAX_VALUE).toInt().toShort()
                    for (e in events) {
                        if (e !is Event.Position) continue
                        if (count == PENDING) { head = (head + 1) % PENDING; count-- }
                        val i = (head + count) % PENDING
                        pendingAt[i] = written + e.offset
                        pendingPos[i] = e
                        count++
                    }
                    if (track.write(pcm, 0, CHUNK, AudioTrack.WRITE_BLOCKING) < 0) break
                    written += CHUNK
                    // playbackHeadPosition is an unsigned 32-bit frame count.
                    val played = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
                    var heard: Event.Position? = null
                    while (count > 0 && pendingAt[head] <= played) {
                        heard = pendingPos[head]
                        pendingPos[head] = null
                        head = (head + 1) % PENDING
                        count--
                    }
                    if (heard != null && running) heard(this, heard)
                }
            } catch (e: Exception) {
                Log.w("Backing", "audio stopped", e)
            } finally {
                track?.let { t ->
                    // Drop what is still buffered so the music stops now, then give the track back.
                    try { t.pause(); t.flush(); t.stop() } catch (_: IllegalStateException) {}
                    t.release()
                }
                ended(this)
            }
        }
    }

    companion object {
        const val SR = 48_000
        /** 20 ms per write. */
        private const val CHUNK = 960
        private const val PENDING = 32
    }
}
