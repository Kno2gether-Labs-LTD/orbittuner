package dev.avijit.orbittuner.backing

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * The backing band, synthesised: drums from tables made once at start, a warm sine-plus-harmonics bass, and
 * guitar strings by Karplus-Strong (noise burst into a tuned delay loop with a damping filter), strummed with the
 * notes staggered ~10-12 ms. The buses are summed at gentle levels, DC-blocked and passed through a soft knee
 * limiter (linear below [KNEE], tanh above), so the output never leaves [-1, 1].
 *
 * Everything is allocated in the constructor: [render] allocates nothing. Not thread-safe: the writer thread only.
 */
class Voices(private val sampleRate: Int) {
    /** Strummed chords on/off; off mutes ringing strings (like a hand on them) and skips new strums. */
    var chords = true

    private var style = Groove.Pop.style
    private var drone = false

    /** Frames rendered so far. */
    private var now = 0L
    private val rng = Rng(0x2545F491)

    // ---- Strings ----
    private val cap = sampleRate / MIN_STRING_HZ + 4
    private val strings = Array(STRINGS) { KsString(cap) }
    private val excite = FloatArray(cap)
    private val exciteComb = FloatArray(cap)
    private val pAt = LongArray(PENDING)
    private val pMidi = IntArray(PENDING)
    private val pVel = FloatArray(PENDING)
    private var pCount = 0
    private var pNext = Long.MAX_VALUE
    private var chordGain = 1f
    private val chordStep = (1 - exp(-1.0 / (0.02 * sampleRate))).toFloat()
    private val body = Biquad.peaking(sampleRate, 210.0, 1.1, 2.5)
    private val air = Biquad.lowpass(sampleRate, 5_200.0, 0.7)
    private var hpX = 0f
    private var hpY = 0f
    private val hpA = exp(-2 * PI * 70.0 / sampleRate).toFloat()

    // ---- Bass ----
    private val bass = arrayOf(BassVoice(), BassVoice())
    private var bassTurn = 0
    private val bassTone = Biquad.lowpass(sampleRate, 1_100.0, 0.6)

    // ---- Drums ----
    private val kits = DrumKind.entries.map { DrumTables.make(it, sampleRate) }.toTypedArray()
    private val dTable = arrayOfNulls<FloatArray>(DRUM_PLAYERS)
    private val dPos = IntArray(DRUM_PLAYERS)
    private val dGain = FloatArray(DRUM_PLAYERS)

    // ---- Master ----
    private var dcX = 0f
    private var dcY = 0f

    fun setGroove(g: Groove) {
        style = g.style
        drone = g == Groove.Drone
    }

    /** Silence and forget everything sounding. */
    fun reset() {
        strings.forEach { it.stop() }
        bass.forEach { it.stop() }
        dTable.fill(null)
        pCount = 0
        pNext = Long.MAX_VALUE
        dcX = 0f; dcY = 0f; hpX = 0f; hpY = 0f
        body.clear(); air.clear(); bassTone.clear()
    }

    /** Fills [out] with [frames] samples; [events] are in offset order, offsets in 0 until [frames]. */
    fun render(out: FloatArray, frames: Int, events: List<Event>) {
        var e = 0
        val chordTarget = if (chords || drone) 1f else 0f
        if (chordTarget == 0f && chordGain > 0f) strings.forEach { if (it.active) it.mute() }
        for (i in 0 until frames) {
            while (e < events.size && events[e].offset <= i) handle(events[e++])
            if (now >= pNext) firePending()

            var str = 0f
            for (s in strings) if (s.active) str += s.tick()
            chordGain += (chordTarget - chordGain) * chordStep
            // Guitar body: 70 Hz high-pass, a little low-mid body, rolled-off top.
            val hp = hpA * (hpY + str - hpX)
            hpX = str; hpY = hp
            val guitar = air.run(body.run(hp.toDouble())).toFloat() * chordGain

            var b = 0f
            for (v in bass) if (v.state != 0) b += v.tick()
            val bassOut = bassTone.run(b.toDouble()).toFloat()

            var d = 0f
            for (p in 0 until DRUM_PLAYERS) {
                val t = dTable[p] ?: continue
                d += t[dPos[p]] * dGain[p]
                if (++dPos[p] >= t.size) dTable[p] = null
            }

            val mix = guitar * (if (drone) DRONE_LEVEL else STRING_LEVEL) + bassOut * BASS_LEVEL + d * DRUM_LEVEL
            val y = mix - dcX + 0.9995f * dcY
            dcX = mix; dcY = y
            out[i] = limit(y)
            now++
        }
        // Anything left (bad offsets) still plays, at the end of this buffer.
        while (e < events.size) handle(events[e++])
    }

    private fun handle(ev: Event) {
        when (ev) {
            is Event.Drum -> {
                var slot = dTable.indexOfFirst { it == null }
                if (slot < 0) slot = (0 until DRUM_PLAYERS).maxBy { dPos[it] }
                dTable[slot] = kits[ev.kind.ordinal]
                dPos[slot] = 0
                dGain[slot] = ev.vel * (0.95f + 0.1f * rng.unit())
            }
            is Event.Bass -> {
                bass[bassTurn].release()
                bassTurn = 1 - bassTurn
                bass[bassTurn].on(ev.midi, ev.durFrames, ev.vel)
            }
            is Event.Strum -> strum(ev)
            is Event.Position -> Unit
        }
    }

    private fun strum(ev: Event.Strum) {
        if (!chords && !drone) return
        if (ev.down && !drone) {
            // A new chord: the hand lets go of strings that aren't in it.
            for (s in strings) if (s.active && s.midi !in ev.midis) s.mute()
        }
        val stagger = (if (ev.down) DOWN_STAGGER_S else UP_STAGGER_S) * sampleRate
        for (i in ev.midis.indices) {
            if (pCount == PENDING) break
            val m = ev.midis[i]
            val at = now + (i * stagger).roundToInt()
            pAt[pCount] = at
            pMidi[pCount] = m
            // Each string a touch different; strings later in an up-stroke a little softer.
            pVel[pCount] = ev.vel * (0.9f + 0.12f * rng.unit()) * (if (ev.down) 1f else 1f - 0.06f * i)
            pCount++
            if (at < pNext) pNext = at
        }
        if (now >= pNext) firePending()
    }

    private fun firePending() {
        var k = 0
        var next = Long.MAX_VALUE
        for (i in 0 until pCount) {
            if (pAt[i] <= now) {
                pluck(pMidi[i], pVel[i])
            } else {
                pAt[k] = pAt[i]; pMidi[k] = pMidi[i]; pVel[k] = pVel[i]
                if (pAt[k] < next) next = pAt[k]
                k++
            }
        }
        pCount = k
        pNext = next
    }

    private fun pluck(midi: Int, vel: Float) {
        var slot: KsString? = strings.firstOrNull { it.active && it.midi == midi }
        if (slot == null) slot = strings.firstOrNull { !it.active }
        // All busy: steal a muted string first, else the one plucked longest ago.
        if (slot == null) slot = strings.minBy { it.startedAt - (if (it.muted) 1L shl 40 else 0L) }
        slot.pluck(midi, vel)
    }

    private fun limit(x: Float): Float {
        val a = abs(x)
        if (a <= KNEE) return x
        val y = KNEE + (1 - KNEE) * tanh((a - KNEE) / (1 - KNEE))
        return if (x < 0) -y else y
    }

    /** One plucked string: a tuned delay loop with a two-point averaging (damping) filter and an all-pass for the fraction. */
    private inner class KsString(cap: Int) {
        private val buf = FloatArray(cap)
        private var len = 0
        private var idx = 0
        private var c = 0f
        private var g = 0f
        private var gMute = 0f
        private var prev = 0f
        private var apIn = 0f
        private var apOut = 0f
        private var cyclePeak = 0f
        var midi = -1
        var active = false
        var muted = false
        var startedAt = 0L

        fun pluck(note: Int, vel: Float) {
            val hz = 440.0 * 2.0.pow((note - 69) / 12.0)
            val d = sampleRate / hz
            val n = floor(d - 0.5 - 0.1).toInt().coerceIn(2, buf.size)
            val frac = d - 0.5 - n
            val keep = if (active && note == midi && !muted) 0.5f else if (active) 0.1f else 0f
            c = ((1 - frac) / (1 + frac)).toFloat()
            // Loop gain per period for the groove's ring time; a muted string dies in ~80 ms.
            g = 10.0.pow(-3.0 / (hz * style.sustainS)).toFloat()
            gMute = 10.0.pow(-3.0 / (hz * MUTE_S)).toFloat()

            // Excitation: noise, darker for soft strokes, through a pick-position comb, without DC.
            val a = (0.08 + 0.72 * style.brightness * (0.45 + 0.55 * vel)).toFloat()
            var lp = 0f
            for (i in 0 until n) { lp += a * (rng.unit() * 2 - 1 - lp); excite[i] = lp }
            val pick = (n * PICK_POSITION).roundToInt().coerceAtLeast(1)
            var mean = 0f
            for (i in 0 until n) { exciteComb[i] = excite[i] - excite[(i - pick + n) % n]; mean += exciteComb[i] }
            mean /= n
            var energy = 0f
            for (i in 0 until n) { exciteComb[i] -= mean; energy += exciteComb[i] * exciteComb[i] }
            val scale = if (energy > 0f) PLUCK_RMS * vel / sqrt(energy / n) else 0f
            for (i in 0 until n) {
                val old = if (i < len) buf[(idx + i) % len] else 0f
                buf[i] = old * keep + exciteComb[i] * scale
            }
            len = n
            idx = 0
            midi = note
            active = true
            muted = false
            cyclePeak = 1f
            startedAt = now
        }

        fun mute() { muted = true }

        fun stop() { active = false; midi = -1; len = 0; prev = 0f; apIn = 0f; apOut = 0f }

        fun tick(): Float {
            val x = buf[idx]
            val avg = (x + prev) * 0.5f * (if (muted) gMute else g)
            prev = x
            val y = c * avg + apIn - c * apOut
            apIn = avg
            apOut = y
            buf[idx] = y
            val ax = abs(x)
            if (ax > cyclePeak) cyclePeak = ax
            if (++idx >= len) {
                idx = 0
                if (cyclePeak < SILENT) stop()
                cyclePeak = 0f
            }
            return x
        }
    }

    /** Bass: sine with a 2nd and 3rd harmonic (so phone speakers can hear the low notes), soft attack, held then released. */
    private inner class BassVoice {
        var state = 0 // 0 idle, 1 held, 2 released
        private var phase = 0.0
        private var inc = 0.0
        private var env = 0f
        private var vel = 0f
        private var attacking = false
        private var hold = 0
        private var rel = 0f
        private val atk = (1.0 / (0.006 * sampleRate)).toFloat()
        private val decay = exp(-1.0 / (0.4 * sampleRate)).toFloat()
        private val slowRel = exp(-1.0 / (0.035 * sampleRate)).toFloat()
        private val fastRel = exp(-1.0 / (0.008 * sampleRate)).toFloat()

        fun on(midi: Int, dur: Int, v: Float) {
            inc = 2 * PI * 440.0 * 2.0.pow((midi - 69) / 12.0) / sampleRate
            phase = 0.0
            env = 0f
            vel = v
            attacking = true
            hold = dur
            rel = slowRel
            state = 1
        }

        /** A new note is taking over: get out of the way quickly. */
        fun release() { if (state != 0) { state = 2; rel = fastRel } }

        fun stop() { state = 0; env = 0f }

        fun tick(): Float {
            if (state == 1) {
                if (attacking) { env += atk; if (env >= 1f) { env = 1f; attacking = false } }
                else env = SUSTAIN + (env - SUSTAIN) * decay
                if (--hold <= 0) state = 2
            } else {
                env *= rel
                if (env < 1e-4f) { state = 0; return 0f }
            }
            val s = sin(phase)
            val co = cos(phase)
            val w = s + 0.32 * (2 * s * co) + 0.12 * s * (3 - 4 * s * s)
            phase += inc
            if (phase > 2 * PI) phase -= 2 * PI
            return (w * env * vel).toFloat()
        }
    }

    companion object {
        /** The limiter is linear up to here. */
        const val KNEE = 0.7f
        private const val STRING_LEVEL = 0.3f
        /** The drone is the only voice in its lesson. */
        private const val DRONE_LEVEL = 0.9f
        private const val BASS_LEVEL = 0.13f
        private const val DRUM_LEVEL = 0.36f
        private const val PLUCK_RMS = 0.32f
        private const val PICK_POSITION = 0.17
        private const val MUTE_S = 0.09
        private const val SUSTAIN = 0.62f
        private const val DOWN_STAGGER_S = 0.012
        private const val UP_STAGGER_S = 0.010
        private const val SILENT = 2e-5f
        private const val STRINGS = 8
        private const val PENDING = 48
        private const val DRUM_PLAYERS = 16
        private const val MIN_STRING_HZ = 50
    }
}

/** xorshift32: cheap deterministic noise. */
internal class Rng(seed: Int) {
    private var s = if (seed == 0) 1 else seed

    /** Uniform in [0, 1). */
    fun unit(): Float {
        s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5)
        return (s ushr 8) / 16_777_216f
    }
}

/** RBJ biquad, direct form I. */
internal class Biquad(private val b0: Double, private val b1: Double, private val b2: Double, private val a1: Double, private val a2: Double) {
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

    fun run(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = if (abs(y) < 1e-20) 0.0 else y
        return y
    }

    fun clear() { x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0 }

    companion object {
        fun lowpass(sr: Int, hz: Double, q: Double): Biquad {
            val w = 2 * PI * hz / sr; val al = sin(w) / (2 * q); val cw = cos(w); val a0 = 1 + al
            return Biquad((1 - cw) / 2 / a0, (1 - cw) / a0, (1 - cw) / 2 / a0, -2 * cw / a0, (1 - al) / a0)
        }

        fun highpass(sr: Int, hz: Double, q: Double): Biquad {
            val w = 2 * PI * hz / sr; val al = sin(w) / (2 * q); val cw = cos(w); val a0 = 1 + al
            return Biquad((1 + cw) / 2 / a0, -(1 + cw) / a0, (1 + cw) / 2 / a0, -2 * cw / a0, (1 - al) / a0)
        }

        fun bandpass(sr: Int, hz: Double, q: Double): Biquad {
            val w = 2 * PI * hz / sr; val al = sin(w) / (2 * q); val cw = cos(w); val a0 = 1 + al
            return Biquad(al / a0, 0.0, -al / a0, -2 * cw / a0, (1 - al) / a0)
        }

        fun peaking(sr: Int, hz: Double, q: Double, db: Double): Biquad {
            val a = 10.0.pow(db / 40); val w = 2 * PI * hz / sr; val al = sin(w) / (2 * q); val cw = cos(w); val a0 = 1 + al / a
            return Biquad((1 + al * a) / a0, -2 * cw / a0, (1 - al * a) / a0, -2 * cw / a0, (1 - al / a) / a0)
        }
    }
}

/** One-shot drum sounds, rendered once. Short and soft-edged: a 1 ms fade-in and a fade-out at the end of each table. */
internal object DrumTables {
    fun make(kind: DrumKind, sr: Int): FloatArray {
        val rng = Rng(0x9E3779B1.toInt() + kind.ordinal * 7919)
        fun noise() = rng.unit() * 2 - 1
        fun t(i: Int) = i.toDouble() / sr
        val out = when (kind) {
            DrumKind.Kick -> {
                var ph = 0.0
                val lp = Biquad.lowpass(sr, 2_500.0, 0.7)
                FloatArray((0.32 * sr).toInt()) { i ->
                    val tt = t(i)
                    val hz = 47 + 75 * exp(-tt / 0.028)
                    ph += 2 * PI * hz / sr
                    val body = sin(ph) * exp(-tt / 0.13)
                    val click = lp.run(noise().toDouble()) * exp(-tt / 0.0025) * 0.35
                    (0.95 * body + click).toFloat()
                }
            }
            DrumKind.Snare -> {
                val hp = Biquad.highpass(sr, 1_400.0, 0.7)
                val lp = Biquad.lowpass(sr, 8_000.0, 0.7)
                FloatArray((0.24 * sr).toInt()) { i ->
                    val tt = t(i)
                    val tone = 0.45 * sin(2 * PI * 185 * tt) * exp(-tt / 0.045) + 0.22 * sin(2 * PI * 330 * tt) * exp(-tt / 0.028)
                    val rattle = lp.run(hp.run(noise().toDouble())) * exp(-tt / 0.07) * 0.75
                    (tone + rattle).toFloat()
                }
            }
            DrumKind.Rim -> {
                val bp = Biquad.bandpass(sr, 2_200.0, 3.0)
                FloatArray((0.07 * sr).toInt()) { i ->
                    val tt = t(i)
                    val tone = 0.5 * sin(2 * PI * 1_650 * tt) * exp(-tt / 0.010) + 0.35 * sin(2 * PI * 820 * tt) * exp(-tt / 0.018)
                    (tone + bp.run(noise().toDouble()) * exp(-tt / 0.006) * 0.8).toFloat() * 0.8f
                }
            }
            DrumKind.Hat -> {
                val hp1 = Biquad.highpass(sr, 7_000.0, 0.7)
                val hp2 = Biquad.highpass(sr, 7_000.0, 0.7)
                FloatArray((0.08 * sr).toInt()) { i ->
                    (hp2.run(hp1.run(noise().toDouble())) * exp(-t(i) / 0.018) * 0.55).toFloat()
                }
            }
            DrumKind.Brush -> {
                val bp = Biquad.bandpass(sr, 3_800.0, 0.8)
                FloatArray((0.22 * sr).toInt()) { i ->
                    val tt = t(i)
                    val env = minOf(1.0, tt / 0.006) * exp(-tt / 0.065)
                    (bp.run(noise().toDouble()) * env * 0.6).toFloat()
                }
            }
            DrumKind.Shaker -> {
                val hp = Biquad.highpass(sr, 4_500.0, 0.7)
                FloatArray((0.11 * sr).toInt()) { i ->
                    val tt = t(i)
                    val env = minOf(1.0, tt / 0.012) * exp(-tt / 0.03)
                    (hp.run(noise().toDouble()) * env * 0.45).toFloat()
                }
            }
            DrumKind.Sticks -> FloatArray((0.05 * sr).toInt()) { i ->
                val tt = t(i)
                (0.5 * sin(2 * PI * 2_450 * tt) * exp(-tt / 0.009) + 0.35 * sin(2 * PI * 1_180 * tt) * exp(-tt / 0.014)).toFloat()
            }
        }
        val fadeIn = (0.001 * sr).toInt()
        val fadeOut = (0.01 * sr).toInt()
        for (i in 0 until minOf(fadeIn, out.size)) out[i] *= i.toFloat() / fadeIn
        for (i in 0 until minOf(fadeOut, out.size)) out[out.size - 1 - i] *= i.toFloat() / fadeOut
        return out
    }
}
