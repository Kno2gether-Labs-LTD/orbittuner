package dev.avijit.orbittuner.tuner.dsp

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** JVM microbenchmark: proxy for the < 50 ms analysis-to-display budget. */
class MpmBench {
    @Test fun detectOn2048WindowMedianUnder5ms() {
        val mpm = Mpm(Synth.SR, 2048)
        val buf = Synth.tone(110.0, 2048, Random(42), listOf(1 to 0.5, 2 to 0.3, 3 to 0.2))
        var sink = 0.0
        repeat(50) { sink += mpm.detect(buf)?.hz ?: 0.0 }
        val times = LongArray(200) {
            val t0 = System.nanoTime()
            sink += mpm.detect(buf)?.hz ?: 0.0
            System.nanoTime() - t0
        }
        times.sort()
        val medianMs = (times[99] + times[100]) / 2.0 / 1e6
        println("MPM bench: median %.4f ms over 200 runs (sink=%.1f)".format(medianMs, sink))
        assertTrue("median $medianMs ms", medianMs < 5.0)
    }
}
