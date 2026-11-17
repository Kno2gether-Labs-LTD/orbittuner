package dev.avijit.orbittuner.backing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

class VoicesTest {
    private val sr = 48_000

    /** Renders [seconds] of audio, feeding [events] (offsets relative to the start) in 480-frame buffers. */
    private fun render(voices: Voices, seconds: Double, events: List<Event> = emptyList(), size: Int = 480): FloatArray {
        val total = (seconds * sr).toInt()
        val out = FloatArray(total)
        val buf = FloatArray(size)
        var pos = 0
        while (pos < total) {
            val n = minOf(size, total - pos)
            val chunk = events.filter { it.offset in pos until pos + n }.map { it.withOffset(it.offset - pos) }
            voices.render(buf, n, chunk)
            buf.copyInto(out, pos, 0, n)
            pos += n
        }
        return out
    }

    private fun Event.withOffset(o: Int): Event = when (this) {
        is Event.Drum -> copy(offset = o)
        is Event.Bass -> copy(offset = o)
        is Event.Strum -> copy(offset = o)
        is Event.Position -> copy(offset = o)
    }

    private fun energy(x: FloatArray, from: Double, len: Double): Double {
        val a = (from * sr).toInt()
        val b = ((from + len) * sr).toInt()
        var e = 0.0
        for (i in a until b) e += x[i].toDouble() * x[i]
        return e / (b - a)
    }

    @Test fun aPluckedStringDecays() {
        val out = render(Voices(sr), 2.2, listOf(Event.Strum(0, listOf(57), true, 1f)))
        val start = energy(out, 0.0, 0.05)
        val late = energy(out, 2.0, 0.05)
        assertTrue("start $start", start > 1e-4)
        assertTrue("late/start ${late / start}", late < 0.1 * start)
    }

    @Test fun aPluckedStringIsInTune() {
        // A3 = 220 Hz: autocorrelation peak with parabolic interpolation, within 3 cents.
        val out = render(Voices(sr), 0.8, listOf(Event.Strum(0, listOf(57), true, 0.8f)))
        val x = out.copyOfRange((0.2 * sr).toInt(), (0.6 * sr).toInt())
        fun ac(lag: Int): Double { var s = 0.0; for (i in 0 until x.size - lag) s += x[i].toDouble() * x[i + lag]; return s }
        val expected = sr / 220.0
        val lags = ((expected * 0.9).toInt()..(expected * 1.1).toInt())
        val best = lags.maxBy { ac(it) }
        val (a, b, c) = Triple(ac(best - 1), ac(best), ac(best + 1))
        val lag = best + 0.5 * (a - c) / (a - 2 * b + c)
        val cents = 1200 * ln(expected / lag) / ln(2.0)
        assertTrue("off by $cents cents", abs(cents) < 3)
    }

    @Test fun theMixNeverLeavesMinusOneToOne() {
        // Far more than any groove plays at once: every drum, a bass note and three full strums on the same frame.
        val pile = buildList {
            for (o in listOf(0, 7, 4_000, 9_000)) {
                DrumKind.entries.forEach { add(Event.Drum(o, it, 1f)) }
                add(Event.Bass(o, 33, 20_000, 1f))
                repeat(3) { add(Event.Strum(o, listOf(40, 47, 52, 56, 59, 64), true, 1f)) }
            }
        }.sortedBy { it.offset }
        val out = render(Voices(sr), 1.0, pile)
        assertTrue(out.all { it.isFinite() && it >= -1f && it <= 1f })
        assertTrue(out.maxOf { abs(it) } > 0.3f)
    }

    @Test fun chordsOffSilencesTheStrings() {
        val v = Voices(sr).apply { chords = false }
        val out = render(v, 0.5, listOf(Event.Strum(0, listOf(40, 47, 52), true, 1f)))
        assertEquals(0f, out.maxOf { abs(it) }, 1e-6f)
    }

    /**
     * Every lesson through the real sequencer: never clips, never silent, sensible loudness. With ORBIT_BACKING_WAV=1
     * the first 12 s of each are written to app/build/backing-renders/ (never committed) for listening.
     */
    @Test fun lessonsRenderAtGentleLevels() {
        val dir = System.getenv("ORBIT_BACKING_WAV")?.let { File("build/backing-renders").apply { mkdirs() } }
        for (l in Lessons.all) {
            val seq = Sequencer(sr).apply { configure(l, 0, l.bpm) }
            val voices = Voices(sr).apply { setGroove(l.groove) }
            val total = 12 * sr
            val out = FloatArray(total)
            val buf = FloatArray(960)
            var pos = 0
            while (pos < total) {
                val n = minOf(960, total - pos)
                voices.render(buf, n, seq.next(n))
                buf.copyInto(out, pos, 0, n)
                pos += n
            }
            val music = out.copyOfRange((60.0 / l.bpm * l.timeSig.beats * sr).toInt(), total) // after the count-in
            val peak = music.maxOf { abs(it) }
            val rms = sqrt(music.sumOf { it.toDouble() * it } / music.size)
            val hot = music.count { abs(it) > Voices.KNEE }.toDouble() / music.size
            println("${l.id}: peak %.3f rms %.3f (%.1f dBFS) above knee %.4f%%".format(peak, rms, 20 * kotlin.math.log10(rms), hot * 100))
            assertTrue("${l.id} peak $peak", peak <= 1f)
            assertTrue("${l.id} rms $rms", rms in 0.03..0.25)
            assertTrue("${l.id} limiter busy ${hot * 100}%", hot < 0.005)
            dir?.let { writeWav(File(it, "${l.id}.wav"), out) }
        }
    }

    private fun writeWav(f: File, x: FloatArray) {
        val data = java.nio.ByteBuffer.allocate(44 + x.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + x.size * 2).put("WAVE".toByteArray())
        data.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sr).putInt(sr * 2).putShort(2).putShort(16)
        data.put("data".toByteArray()).putInt(x.size * 2)
        for (s in x) data.putShort((s * Short.MAX_VALUE).toInt().toShort())
        f.writeBytes(data.array())
    }
}
