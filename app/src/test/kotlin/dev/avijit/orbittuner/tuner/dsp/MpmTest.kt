package dev.avijit.orbittuner.tuner.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sin

internal object Synth {
    const val SR = 48_000

    /** Sum of sines: [partials] are (multiple of [f0], amplitude) pairs, each with a random phase. */
    fun tone(f0: Double, n: Int, rnd: Random, partials: List<Pair<Int, Double>> = listOf(1 to 0.5)): FloatArray {
        val phases = partials.map { rnd.nextDouble() * 2 * PI }
        return FloatArray(n) { i ->
            var s = 0.0
            partials.forEachIndexed { p, (mult, amp) -> s += amp * sin(2 * PI * f0 * mult * i / SR + phases[p]) }
            s.toFloat()
        }
    }

    fun cents(measured: Double, expected: Double) = 1200.0 * ln(measured / expected) / ln(2.0)
}

class MpmTest {
    private fun windowFor(hz: Double) = if (hz < 80.0) 8192 else 2048

    private fun assertDetects(hz: Double, partials: List<Pair<Int, Double>> = listOf(1 to 0.5), seeds: Int = 8) {
        val w = windowFor(hz)
        val mpm = Mpm(Synth.SR, w)
        var worst = 0.0
        for (seed in 0 until seeds) {
            val buf = Synth.tone(hz, w, Random(seed * 7919L + hz.toLong()), partials)
            val r = mpm.detect(buf)
            assertNotNull("no pitch for $hz Hz (seed $seed)", r)
            val c = Synth.cents(r!!.hz, hz)
            if (abs(c) > abs(worst)) worst = c
            assertTrue("$hz Hz detected as ${r.hz} (${"%.4f".format(c)} cents, seed $seed)", abs(c) <= 1.0)
            assertTrue("clarity ${r.clarity} for $hz Hz", r.clarity >= 0.80)
        }
        println("MPM accuracy: %.2f Hz window=%d worst=%.4f cents".format(hz, w, worst))
    }

    @Test fun b0() = assertDetects(30.87)
    @Test fun e1() = assertDetects(41.20)
    @Test fun e2() = assertDetects(82.41)
    @Test fun a2() = assertDetects(110.0)
    @Test fun a4() = assertDetects(440.0)
    @Test fun c6() = assertDetects(1046.5)
    @Test fun c8() = assertDetects(4186.0)

    @Test fun guitarLikeToneGivesFundamental() = assertDetects(
        82.41,
        listOf(1 to 0.5, 2 to 0.5 * 0.8, 3 to 0.5 * 0.6, 4 to 0.5 * 0.4, 5 to 0.5 * 0.3, 6 to 0.5 * 0.2),
    )

    @Test fun whiteNoiseIsNull() {
        val mpm = Mpm(Synth.SR, 2048)
        for (seed in 0 until 8L) {
            val rnd = Random(seed)
            val buf = FloatArray(2048) { ((rnd.nextDouble() * 2 - 1) * 0.1).toFloat() }
            assertNull("noise seed $seed", mpm.detect(buf))
        }
    }

    @Test fun silenceIsNull() {
        assertNull(Mpm(Synth.SR, 2048).detect(FloatArray(2048)))
        assertNull(Mpm(Synth.SR, 8192).detect(FloatArray(8192)))
    }

    @Test fun quietToneBelowRmsGateIsNull() {
        // amplitude 0.005 sine -> rms ~0.0035 < 0.005
        val buf = Synth.tone(440.0, 2048, Random(1), listOf(1 to 0.005))
        assertNull(Mpm(Synth.SR, 2048).detect(buf))
    }

    @Test fun offsetReadsWindowFromLargerBuffer() {
        val buf = FloatArray(3000).also { Synth.tone(440.0, 2048, Random(3)).copyInto(it, 500) }
        val r = Mpm(Synth.SR, 2048).detect(buf, 500)
        assertNotNull(r)
        assertEquals(0.0, Synth.cents(r!!.hz, 440.0), 1.0)
        assertEquals(0.5 / Math.sqrt(2.0), r.rms, 0.01)
    }

    @Test fun smootherReachesStepWithinSixPushes() {
        val s = Smoother()
        repeat(5) { s.push(0.0) }
        var v = 0.0
        repeat(6) { v = s.push(10.0) }
        assertTrue("smoothed $v", v >= 9.0)
        s.reset()
        assertEquals(-3.0, s.push(-3.0), 1e-12)
    }
}
