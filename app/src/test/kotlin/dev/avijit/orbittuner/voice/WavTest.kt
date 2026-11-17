package dev.avijit.orbittuner.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class WavTest {
    private fun le(b: ByteArray) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
    private fun ascii(b: ByteArray, at: Int) = String(b, at, 4, Charsets.US_ASCII)

    private fun tone(hz: Double, seconds: Double, amp: Double = 0.5) =
        FloatArray((48_000 * seconds).toInt()) { (amp * sin(2 * PI * hz * it / 48_000)).toFloat() }

    @Test fun oneSecondAt48kIs32044Bytes() {
        val wav = Wav.encode16k(FloatArray(48_000), 48_000)
        assertEquals(32_044, wav.size)
    }

    @Test fun headerFields() {
        val wav = Wav.encode16k(tone(440.0, 1.0), 48_000)
        val b = le(wav)
        assertEquals("RIFF", ascii(wav, 0))
        assertEquals(32_036, b.getInt(4))
        assertEquals("WAVE", ascii(wav, 8))
        assertEquals("fmt ", ascii(wav, 12))
        assertEquals(16, b.getInt(16))
        assertEquals(1, b.getShort(20).toInt()) // PCM
        assertEquals(1, b.getShort(22).toInt()) // mono
        assertEquals(16_000, b.getInt(24))
        assertEquals(32_000, b.getInt(28)) // byte rate
        assertEquals(2, b.getShort(32).toInt()) // block align
        assertEquals(16, b.getShort(34).toInt()) // bits
        assertEquals("data", ascii(wav, 36))
        assertEquals(32_000, b.getInt(40))
    }

    @Test fun onlyTheFirstNSamplesAreUsed() {
        val buf = tone(440.0, 1.0)
        assertEquals(44 + 2 * 8_000, Wav.encode16k(buf, 24_000).size)
        assertEquals(44, Wav.encode16k(buf, 2).size)
    }

    private fun rms16(wav: ByteArray): Double {
        val b = le(wav)
        val n = (wav.size - 44) / 2
        var e = 0.0
        // skip the filter's start-up samples
        for (i in 100 until n) { val v = b.getShort(44 + 2 * i) / 32768.0; e += v * v }
        return sqrt(e / (n - 100))
    }

    @Test fun passesVoiceAndCutsAboveNyquist() {
        val voice = rms16(Wav.encode16k(tone(440.0, 1.0), 48_000))
        assertEquals(0.5 / sqrt(2.0), voice, 0.02)
        // 13 kHz would alias down to 3 kHz without the low-pass.
        val high = rms16(Wav.encode16k(tone(13_000.0, 1.0), 48_000))
        assertTrue("13 kHz leaked: $high", high < 0.02)
    }

    @Test fun clipsInsteadOfWrapping() {
        val loud = FloatArray(4_800) { 2f }
        val b = le(Wav.encode16k(loud, loud.size))
        for (i in 20 until 1_580) assertTrue(abs(b.getShort(44 + 2 * i).toInt()) >= 32_000)
    }
}
