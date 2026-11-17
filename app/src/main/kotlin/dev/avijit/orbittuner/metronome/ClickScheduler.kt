package dev.avijit.orbittuner.metronome

import kotlin.math.roundToLong

/** A click starting [offset] frames into the buffer just asked for; [beat] is 0-based within the bar. */
data class Click(val offset: Int, val beat: Int)

/**
 * Places metronome clicks on an audio stream, sample-accurately: the audio thread asks for the clicks in each
 * buffer it is about to write. Positions are kept as fractional frames, so uneven tempos (70 BPM is 41 142.857
 * frames) don't drift. Not thread-safe: one caller (the writer thread) only.
 */
class ClickScheduler(private val sampleRate: Int) {
    private var bpm = 120
    private var beats = 4
    private var interval = framesPerBeat(bpm)

    /** Frames handed out so far (start of the next buffer). */
    private var pos = 0L
    /** Absolute frame of the next click. */
    private var nextAt = 0.0
    /** Absolute frame of the last click emitted; null before the first. */
    private var lastAt: Double? = null
    private var beat = 0

    /**
     * A new tempo takes effect from the next click, measured from the last emitted one. If that point has already
     * gone by (a big speed-up), the next click plays at once: one click, never a catch-up burst. A new bar length
     * starts a new bar. Same values again: no change.
     */
    fun configure(bpm: Int, beatsPerBar: Int) {
        require(bpm > 0 && beatsPerBar > 0)
        if (beatsPerBar != beats) {
            beats = beatsPerBar
            beat = 0
        }
        if (bpm != this.bpm) {
            this.bpm = bpm
            interval = framesPerBeat(bpm)
            lastAt?.let { nextAt = it + interval }
        }
    }

    /** Back to the start: the next buffer begins with beat 0 at offset 0. */
    fun reset() {
        pos = 0L
        nextAt = 0.0
        lastAt = null
        beat = 0
    }

    /** The clicks that start within the next [frames] frames. */
    fun next(frames: Int): List<Click> {
        val end = pos + frames
        var out: MutableList<Click>? = null
        while (true) {
            val at = maxOf(nextAt, pos.toDouble())
            val frame = at.roundToLong()
            if (frame >= end) break
            (out ?: ArrayList<Click>(2).also { out = it }) += Click((frame - pos).toInt(), beat)
            lastAt = at
            nextAt = at + interval
            beat = (beat + 1) % beats
        }
        pos = end
        return out ?: emptyList()
    }

    private fun framesPerBeat(bpm: Int) = sampleRate * 60.0 / bpm
}
