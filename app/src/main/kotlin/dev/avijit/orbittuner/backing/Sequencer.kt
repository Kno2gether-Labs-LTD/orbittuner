package dev.avijit.orbittuner.backing

import dev.avijit.orbittuner.music.ChordParser
import dev.avijit.orbittuner.songview.ChordTheory
import kotlin.math.floor
import kotlin.math.roundToLong

/** Something that starts [offset] frames into the buffer just asked for. */
sealed interface Event {
    val offset: Int

    data class Drum(override val offset: Int, val kind: DrumKind, val vel: Float) : Event

    /** A bass note held for [durFrames]. */
    data class Bass(override val offset: Int, val midi: Int, val durFrames: Int, val vel: Float = 1f) : Event

    /** A strum: [midis] in the order the pick hits them (low to high going down, high to low going up). */
    data class Strum(override val offset: Int, val midis: List<Int>, val down: Boolean, val vel: Float) : Event

    /**
     * A beat: [bar] is the 0-based bar within the progression (-1 during the count-in), [beat] the 0-based beat.
     * [chord] is what sounds now and [nextChord] the change after it, both transposed and spelled for display.
     * During the count-in [chord] is the first chord, so the player can get ready.
     */
    data class Position(override val offset: Int, val bar: Int, val beat: Int, val chord: String, val nextChord: String) : Event
}

/**
 * Turns a lesson into note events, sample-accurately, for the audio thread: one count-in bar of sticks, then the
 * progression in a loop. Bar starts are computed from the start of the current tempo segment (frame = segment start
 * + bars x bar length), never by adding up rounded lengths, so ten minutes of buffers drift by zero frames.
 *
 * [configure] before the first [next] applies at once; while playing it waits for the next bar line (key, tempo and
 * lesson all change together there), so a change never cuts a bar short or doubles a beat.
 * Not thread-safe: one caller (the writer thread) only.
 */
class Sequencer(private val sampleRate: Int) {
    private class Config(val lesson: Lesson, val keyOffset: Int, val bpm: Int)

    private class Timed(val at: Double, val event: Event)

    private var config: Config? = null
    private var pending: Config? = null
    private var started = false

    /** Frames handed out so far (start of the next buffer). */
    private var pos = 0L
    /** Bar counter since start: 0 is the count-in, n >= 1 is progression bar (n - 1) mod size. */
    private var bar = 0L
    private var segStart = 0.0
    private var segBar = 0L
    private var barLen = 0.0
    private var barStart = 0.0
    private var events: List<Timed> = emptyList()
    private var idx = 0

    fun configure(lesson: Lesson, keyOffset: Int, bpm: Int) {
        val key = keyOffset.coerceIn(Backing.MIN_KEY, Backing.MAX_KEY)
        val tempo = bpm.coerceIn(Backing.MIN_BPM, Backing.MAX_BPM)
        val cur = pending ?: config
        // Called before every buffer: the same values again must not allocate.
        if (cur != null && cur.lesson == lesson && cur.keyOffset == key && cur.bpm == tempo) return
        val c = Config(lesson, key, tempo)
        if (started) pending = c else config = c
    }

    /** Back to the count-in at frame 0 (keeps the configuration). */
    fun reset() {
        pending?.let { config = it }
        pending = null
        started = false
        pos = 0L
        bar = 0L
    }

    /** The events that start within the next [frames] frames, in time order. */
    fun next(frames: Int): List<Event> {
        checkNotNull(config) { "configure first" }
        if (!started) {
            started = true
            segStart = 0.0
            segBar = 0L
            barLen = barLength(config!!)
            startBar()
        }
        val end = pos + frames
        var out: MutableList<Event>? = null
        while (true) {
            if (idx >= events.size) {
                if ((barStart + barLen).roundToLong() >= end) break
                bar++
                pending?.let { p ->
                    val len = barLength(p)
                    if (len != barLen) {
                        segStart = barStart + barLen
                        segBar = bar
                        barLen = len
                    }
                    config = p
                    pending = null
                }
                startBar()
                continue
            }
            val t = events[idx]
            val frame = (barStart + t.at).roundToLong()
            if (frame >= end) break
            val o = (maxOf(frame, pos) - pos).toInt()
            (out ?: ArrayList<Event>(8).also { out = it }) += withOffset(t.event, o)
            idx++
        }
        pos = end
        return out ?: emptyList()
    }

    private fun barLength(c: Config) = c.lesson.timeSig.beats * framesPerBeat(c.bpm)

    private fun framesPerBeat(bpm: Int) = sampleRate * 60.0 / bpm

    private fun startBar() {
        barStart = segStart + (bar - segBar) * barLen
        events = build(config!!, bar)
        idx = 0
    }

    private fun withOffset(e: Event, o: Int): Event = when (e) {
        is Event.Drum -> e.copy(offset = o)
        is Event.Bass -> e.copy(offset = o)
        is Event.Strum -> e.copy(offset = o)
        is Event.Position -> e.copy(offset = o)
    }

    /** The bar's events with their time from the bar line, sorted (once per bar, not per buffer). */
    private fun build(c: Config, n: Long): List<Timed> {
        val l = c.lesson
        val g = l.groove
        val beats = l.timeSig.beats
        val beatLen = framesPerBeat(c.bpm)
        val out = ArrayList<Timed>(48)
        if (n == 0L) {
            val first = tokens(l.bars[0])
            val next = if (first.size > 1) first[1] else tokens(l.bars[1 % l.bars.size])[0]
            for (b in 0 until beats) {
                out += Timed(b * beatLen, Event.Position(0, -1, b, l.chordIn(first[0], c.keyOffset), l.chordIn(next, c.keyOffset)))
                out += Timed(b * beatLen, Event.Drum(0, DrumKind.Sticks, if (b == 0) 0.9f else 0.6f))
            }
            return out
        }
        val barIdx = ((n - 1) % l.bars.size).toInt()
        val chords = tokens(l.bars[barIdx])
        val following = tokens(l.bars[(barIdx + 1) % l.bars.size])[0]
        fun chordAt(beat: Int): Int {
            var k = 0
            while (k + 1 < chords.size && (k + 1) * beats / chords.size <= beat) k++
            return k
        }
        val shown = chords.map { l.chordIn(it, c.keyOffset) }
        val shownNext = l.chordIn(following, c.keyOffset)
        for (b in 0 until beats) {
            val k = chordAt(b)
            out += Timed(b * beatLen, Event.Position(0, barIdx, b, shown[k], if (k + 1 < shown.size) shown[k + 1] else shownNext))
        }

        val spb = g.stepsPerBeat
        val steps = beats * spb
        fun stepTime(s: Int): Double {
            val beat = s / spb
            val sub = s % spb
            if (spb == 1) return beat * beatLen
            val pairLen = beatLen * 2 / spb
            return beat * beatLen + (sub / 2) * pairLen + (if (sub % 2 == 1) g.swing * pairLen else 0.0)
        }

        val fill = l.bars.size >= 4 && barIdx == l.bars.size - 1
        for ((kind, line) in g.drums) {
            val use = (if (fill) g.fill[kind] else null) ?: line
            for (s in 0 until steps) drumVel(use[s % use.length])?.let { out += Timed(stepTime(s), Event.Drum(0, kind, it)) }
        }

        val voicings = chords.map { Voicing.strum(l.chordIn(it, c.keyOffset)) }
        val roots = chords.map { Voicing.bassRoot(l.chordIn(it, c.keyOffset)) }

        // Bass: each note lasts until the next one or the bar line.
        var s = 0
        while (s < steps) {
            val ch = g.bass[s % g.bass.length]
            if (ch == '.') { s++; continue }
            var e = s + 1
            while (e < steps && g.bass[e % g.bass.length] == '.') e++
            val t = stepTime(s)
            val until = if (e < steps) stepTime(e) else beats * beatLen
            val root = roots[chordAt(s / spb)]
            val midi = when (ch) {
                'F', 'f' -> root + 7
                'O' -> root + 12
                'L' -> root - 5
                else -> root
            }
            val vel = if (ch == 'r' || ch == 'f') 0.7f else 1f
            out += Timed(t, Event.Bass(0, midi, ((until - t) * g.bassLength).toInt().coerceAtLeast(1), vel))
            s = e
        }

        for (st in 0 until steps) {
            val ch = g.strum[st % g.strum.length]
            if (ch == '.') continue
            val k = chordAt(st / spb)
            val vel = when (ch) { 'D' -> 0.9f; 'd' -> 0.62f; 'U' -> 0.72f; else -> 0.48f }
            val midis = if (g == Groove.Drone) {
                listOf(Voicing.tanpura(roots[k], st % 4))
            } else {
                val v = voicings[k]
                val down = ch == 'D' || ch == 'd'
                // Up-strokes catch the top strings only, as a real pick does.
                if (down) v else v.takeLast(minOf(4, v.size)).reversed()
            }
            out += Timed(stepTime(st), Event.Strum(0, midis, ch == 'D' || ch == 'd', vel))
        }
        out.sortBy { it.at } // stable: same-time events keep Position first
        return out
    }

    private fun tokens(bar: String) = bar.trim().split(WS)

    private fun drumVel(ch: Char): Float? = when (ch) { 'X' -> 1f; 'x' -> 0.72f; 'o' -> 0.42f; else -> null }

    private companion object {
        val WS = Regex("\\s+")
    }
}

/**
 * Chord voicings from the shared parser ([ChordParser]) and chord-tone table ([ChordTheory]): a guitar-like spread
 * with the root on the lowest string (E2..D#3), then fifth, octave, third and fifth again, kept within E2..E5; 7th
 * chords put the 7th low, as an open A7 or E7 does. The bass plays roots around E1..E3.
 */
internal object Voicing {
    private const val LOW = 40 // E2
    private const val HIGH = 76 // E5

    fun strum(chord: String): List<Int> {
        val c = ChordParser.parse(chord) ?: return listOf(LOW, LOW + 7, LOW + 12)
        val root = LOW + ((c.root - LOW % 12) % 12 + 12) % 12
        val tones = (ChordTheory.pitchClasses(chord) ?: listOf(c.root, (c.root + 7) % 12)).map { ((it - c.root) % 12 + 12) % 12 }.toSet()
        val third = listOf(4, 3, 5, 2).firstOrNull { it in tones }
        val fifth = listOf(7, 6, 8).firstOrNull { it in tones } ?: 7
        // A diminished 7th's 7th is a major 6th above the root; otherwise a 6th is an added note.
        val seventh = listOf(10, 11).firstOrNull { it in tones } ?: if (9 in tones && 6 in tones) 9 else null
        val sixth = if (seventh == null && 9 in tones) 9 else null
        val ninth = if (2 in tones && third != 2) 2 else null
        val v = sortedSetOf(root, root + fifth)
        if (seventh != null) {
            v += root + seventh
            third?.let { v += root + 12 + it }
            v += root + 12 + fifth
        } else {
            v += root + 12
            third?.let { v += root + 12 + it }
            sixth?.let { v += root + 12 + it }
            v += root + 12 + fifth
            if (sixth == null) v += root + 24
        }
        ninth?.let { v += root + 14 }
        if (third == null && seventh == null) v.remove(root + 24) // power chord: keep it tight
        var out = v.filter { it <= HIGH }
        if (out.size > 6) out = out.take(6)
        // A slash bass goes underneath.
        val bass = c.bass
        if (bass != null && bass != c.root) {
            val lo = LOW + ((bass - LOW % 12) % 12 + 12) % 12
            out = listOf(lo) + out.filter { it > lo }
        }
        return out
    }

    /** The bass note for a chord (its slash bass, else its root), in A1..G#2, i.e. around E1..E3 with its fifth/octave. */
    fun bassRoot(chord: String): Int {
        val c = ChordParser.parse(chord) ?: return 33
        val pc = c.bass ?: c.root
        return 33 + ((pc - 33 % 12) % 12 + 12) % 12
    }

    /** Tanpura cycle on [root]'s pitch class: Pa (fifth below), Sa, Sa, low Sa. */
    fun tanpura(bassRoot: Int, step: Int): Int {
        val sa = 48 + bassRoot % 12 // C3..B3
        return when (step) { 0 -> sa - 5; 3 -> sa - 12; else -> sa }
    }
}
