package dev.avijit.orbittuner.music

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A musical key. [tonic] is a pitch class, 0 = C. */
data class Key(val tonic: Int, val minor: Boolean) {
    fun name(acc: Accidental): String = Note(tonic).name(acc) + if (minor) "m" else ""

    /**
     * The name shown to players, spelled the way [Transposer] spells keys: flats for the flat keys (Bb, Eb, Ebm, Bbm…),
     * sharps otherwise (F#m, C#m). Use it for every key on screen so a key never reads two ways.
     */
    fun displayName(): String {
        val flat = name(Accidental.Flat)
        return if (Transposer.keyAccidental(flat) == Accidental.Flat) flat else name(Accidental.Sharp)
    }

    /** This key moved by [semitones]. */
    fun shifted(semitones: Int): Key = copy(tonic = Math.floorMod(tonic + semitones, 12))
}

/**
 * How far to move a sheet so it matches what was sung, in -5..6 semitones.
 * [alternative] is the runner-up shift when the call is close (best - second < [KeyFinder.MARGIN]), else null.
 */
data class ShiftGuess(val semitones: Int, val confident: Boolean, val alternative: Int?)

object KeyFinder {
    /** Best minus second correlation needed for a confident shift. */
    const val MARGIN = 0.08

    /** Voiced seconds needed for a confident shift. */
    const val MIN_VOICED_SECONDS = 4.0

    private val MAJOR_SCALE = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    private val MINOR_SCALE = intArrayOf(0, 2, 3, 5, 7, 8, 10)

    /**
     * The sheet's key: the declared `{key}` when it reads as a key ("Eb", "F#m", "E minor"), otherwise inferred from
     * the chords. Returns null when neither gives an answer.
     */
    fun sheetKey(declared: String?, chords: List<String>): Key? =
        declared?.let(::parseKey) ?: inferKey(chords)

    /**
     * 12-bin chord-tone profile: for every chord occurrence, its root adds 2 and its other triad tones add 1, so
     * frequent chords weigh more. Tokens that are not chords add nothing.
     */
    fun chordProfile(chords: List<String>): DoubleArray {
        val p = DoubleArray(12)
        for (token in chords) {
            val c = parseChord(token) ?: continue
            for ((i, pc) in triadTones(c).withIndex()) p[pc] += if (i == 0) 2.0 else 1.0
        }
        return p
    }

    /**
     * The shift that best lines [sheet] up with [sung]: Pearson correlation of [sung] against [sheet] rotated up by
     * 0..11 semitones; the best rotation wins and is normalised to -5..6. Returns null when either profile is flat
     * (all zero or all equal), since there is nothing to correlate.
     */
    fun bestShift(sung: DoubleArray, sheet: DoubleArray, voicedSeconds: Double): ShiftGuess? {
        require(sung.size == 12 && sheet.size == 12) { "profiles must have 12 bins" }
        if (isFlat(sung) || isFlat(sheet)) return null
        val scores = DoubleArray(12) { r -> pearson(sung, DoubleArray(12) { sheet[Math.floorMod(it - r, 12)] }) }
        val order = (0 until 12).sortedByDescending { scores[it] }
        val best = order[0]
        val second = order[1]
        val close = scores[best] - scores[second] < MARGIN
        return ShiftGuess(
            semitones = normalise(best),
            confident = !close && voicedSeconds >= MIN_VOICED_SECONDS,
            alternative = if (close) normalise(second) else null,
        )
    }

    // ---- key parsing / inference ----

    private fun parseKey(declared: String): Key? {
        val c = ChordParser.parse(declared.trim()) ?: return null
        if (c.bass != null) return null
        return when (c.quality.trim()) {
            "", "M", "maj", "major", "Major" -> Key(c.root, false)
            "m", "min", "minor", "Minor" -> Key(c.root, true)
            else -> null
        }
    }

    private enum class Kind { Major, Minor, Dim, Aug, Sus2, Sus4, Power }

    private data class Parsed(val root: Int, val kind: Kind)

    private fun parseChord(token: String): Parsed? {
        var t = token.trim()
        if (t.length > 2 && t.first() == '(' && t.last() == ')') t = t.substring(1, t.length - 1)
        val c = ChordParser.parse(t) ?: return null
        return Parsed(c.root, kindOf(c.quality))
    }

    private fun kindOf(q: String): Kind = when {
        q.startsWith("maj") || q.startsWith("Maj") || q.startsWith("MAJ") -> Kind.Major
        q.startsWith("m7b5") || q.startsWith("m7♭5") || q.startsWith("ø") -> Kind.Dim
        q.startsWith("dim") || q.startsWith("°") -> Kind.Dim
        q.startsWith("min") || q.startsWith("m") -> Kind.Minor
        q.startsWith("aug") || q.startsWith("+") -> Kind.Aug
        q.startsWith("sus2") -> Kind.Sus2
        q.startsWith("sus") -> Kind.Sus4
        q.startsWith("5") -> Kind.Power
        else -> Kind.Major // "", "M", "7", "6", "9", "add9", ...
    }

    /** Root first, then the rest of the triad (power chords have no third). */
    private fun triadTones(c: Parsed): IntArray {
        val iv = when (c.kind) {
            Kind.Major -> intArrayOf(0, 4, 7)
            Kind.Minor -> intArrayOf(0, 3, 7)
            Kind.Dim -> intArrayOf(0, 3, 6)
            Kind.Aug -> intArrayOf(0, 4, 8)
            Kind.Sus2 -> intArrayOf(0, 2, 7)
            Kind.Sus4 -> intArrayOf(0, 5, 7)
            Kind.Power -> intArrayOf(0, 7)
        }
        return IntArray(iv.size) { (c.root + iv[it]) % 12 }
    }

    /**
     * A chord fits a key when all its triad tones are in the key's scale. Minor keys also accept a major V
     * (harmonic minor), which is how most minor songs cadence.
     */
    private fun fits(c: Parsed, key: Key): Boolean {
        val scale = (if (key.minor) MINOR_SCALE else MAJOR_SCALE).map { (key.tonic + it) % 12 }.toSet()
        if (triadTones(c).all { it in scale }) return true
        return key.minor && c.root == (key.tonic + 7) % 12 && c.kind == Kind.Major
    }

    private fun isTonic(c: Parsed, key: Key): Boolean =
        c.root == key.tonic && c.kind == (if (key.minor) Kind.Minor else Kind.Major)

    /**
     * Diatonic coverage over 24 keys: the share of chord occurrences that fit the key. Ties (e.g. a major key and
     * its relative minor) go to the key whose tonic chord is the last chord (+1) and/or the first chord (+1); a
     * remaining tie prefers the key whose tonic chord appears most often, then major.
     */
    private fun inferKey(tokens: List<String>): Key? {
        val chords = tokens.mapNotNull(::parseChord)
        if (chords.isEmpty()) return null
        val first = chords.first()
        val last = chords.last()
        val keys = (0 until 12).flatMap { listOf(Key(it, false), Key(it, true)) }
        val ranked = keys.map { k ->
            val coverage = chords.count { fits(it, k) }
            val ends = (if (isTonic(last, k)) 1 else 0) + (if (isTonic(first, k)) 1 else 0)
            val tonicCount = chords.count { isTonic(it, k) }
            Triple(k, coverage, ends to tonicCount)
        }
        val best = ranked.sortedWith(
            compareByDescending<Triple<Key, Int, Pair<Int, Int>>> { it.second }
                .thenByDescending { it.third.first }
                .thenByDescending { it.third.second }
                .thenBy { if (it.first.minor) 1 else 0 }
        ).first()
        return if (best.second == 0) null else best.first
    }

    // ---- correlation ----

    private fun isFlat(v: DoubleArray): Boolean {
        val m = v.average()
        return v.all { abs(it - m) < 1e-12 } || v.any { !it.isFinite() }
    }

    private fun pearson(a: DoubleArray, b: DoubleArray): Double {
        val ma = a.average()
        val mb = b.average()
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in 0 until 12) {
            val x = a[i] - ma
            val y = b[i] - mb
            num += x * y; da += x * x; db += y * y
        }
        return num / sqrt(da * db)
    }

    private fun normalise(r: Int): Int {
        val d = Math.floorMod(r, 12)
        return if (d > 6) d - 12 else d
    }
}

/**
 * Pitch-class histogram of a sung clip. Feed it one pitch estimate per analysis hop, in order: [add] with the hop's
 * frequency (0, negative or NaN when the hop is unvoiced) and its duration. Octaves fold into the same bin.
 *
 * [isSinging] tells singing from speech: singing holds notes, speech glides. Vibrato makes a held note's raw pitch
 * swing ±20–50 cents about 5–6 times a second, so stability is judged on the note's *centre*: the raw pitch averaged
 * over the last [SMOOTH_SECONDS] (about one vibrato cycle). A stable note is a run of consecutive voiced hops whose
 * centre stays within a [CENTRE_SPAN_CENTS] band for at least [STABLE_SECONDS]. Measuring the band's span (max - min)
 * rather than distance from the first hop means a run is not tied to where it happened to start.
 *
 * Why the band is 30 cents, not ±50 on raw pitch: any ±50 rule on raw pitch either rejects ordinary ±40–50 cent
 * vibrato (anchored at the first hop) or lets a glide at a quarter of speech speed pass (span ≤ 100 or ±50 of the
 * run mean: 67–73% "stable"). Averaging out vibrato first and then requiring a 30-cent centre keeps ±40–50 cent
 * vibrato at 4.5–6.5 Hz at ~75–90% stable while that slow glide stays at ~36%. The raw pitch still lands within
 * ±50 cents of the centre for any vibrato up to ±50 cents.
 */
class SungProfile {
    private val bins = DoubleArray(12)

    var voicedSeconds: Double = 0.0
        private set

    private var stableSeconds = 0.0

    // Trailing window of (cents, seconds) used to average out vibrato.
    private val window = ArrayDeque<Pair<Double, Double>>()
    private var windowSeconds = 0.0

    // Current run, measured on the smoothed centre.
    private var runMin = Double.NaN
    private var runMax = Double.NaN
    private var runSeconds = 0.0

    fun add(hz: Double, seconds: Double) {
        if (!(seconds > 0.0) || !seconds.isFinite()) return
        if (!(hz > 0.0) || !hz.isFinite()) {
            closeRun()
            clearWindow()
            return
        }
        val midi = Pitch.hzToMidi(hz)
        bins[Math.floorMod(midi.roundToInt(), 12)] += seconds
        voicedSeconds += seconds

        val cents = midi * 100.0
        // A jump well past any vibrato is a new note: start averaging afresh so the old note does not smear in.
        if (window.isNotEmpty() && abs(cents - centre()) > NEW_NOTE_CENTS) clearWindow()
        window.addLast(cents to seconds)
        windowSeconds += seconds
        while (window.size > 1 && windowSeconds - window.first().second >= SMOOTH_SECONDS - EPS) {
            windowSeconds -= window.removeFirst().second
        }
        val c = centre()

        if (!runMin.isNaN() && maxOf(runMax, c) - minOf(runMin, c) <= CENTRE_SPAN_CENTS) {
            runMin = minOf(runMin, c)
            runMax = maxOf(runMax, c)
            runSeconds += seconds
        } else {
            closeRun()
            runMin = c
            runMax = c
            runSeconds = seconds
        }
    }

    /** At least [MIN_SECONDS] voiced, and at least half of it in stable notes. */
    val isSinging: Boolean
        get() {
            val stable = stableSeconds + if (runSeconds >= STABLE_SECONDS - EPS) runSeconds else 0.0
            return voicedSeconds >= MIN_SECONDS - EPS && stable >= STABLE_SHARE * voicedSeconds - EPS
        }

    fun histogram(): DoubleArray = bins.copyOf()

    /** Duration-weighted mean of the smoothing window. */
    private fun centre(): Double = window.sumOf { it.first * it.second } / windowSeconds

    private fun clearWindow() {
        window.clear()
        windowSeconds = 0.0
    }

    private fun closeRun() {
        if (runSeconds >= STABLE_SECONDS - EPS) stableSeconds += runSeconds
        runMin = Double.NaN
        runMax = Double.NaN
        runSeconds = 0.0
    }

    companion object {
        const val MIN_SECONDS = 4.0
        const val STABLE_SECONDS = 0.15
        const val STABLE_SHARE = 0.5
        /** Averaging window for the note centre: about one cycle of 5.5 Hz vibrato. */
        const val SMOOTH_SECONDS = 0.18
        /** Widest the note centre may wander (max - min) and still be one held note. */
        const val CENTRE_SPAN_CENTS = 30.0
        /** A raw jump this far from the centre starts a new note. */
        const val NEW_NOTE_CENTS = 150.0
        private const val EPS = 1e-9
    }
}
