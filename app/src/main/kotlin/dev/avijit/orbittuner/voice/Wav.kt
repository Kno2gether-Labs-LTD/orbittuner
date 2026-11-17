package dev.avijit.orbittuner.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** The WAV the voice endpoint takes: 16 kHz, mono, 16-bit PCM. */
object Wav {
    const val RATE = 16_000
    const val HEADER_BYTES = 44
    private const val FACTOR = 3 // 48 kHz -> 16 kHz
    private const val TAPS = 47
    /** Low-pass corner: under the 8 kHz Nyquist of the output with room for the filter's roll-off. */
    private const val CUTOFF_HZ = 6_800.0

    /** Windowed-sinc (Hamming) low-pass at 48 kHz, unity gain at DC. */
    private val kernel: DoubleArray = run {
        val fc = CUTOFF_HZ / 48_000.0
        val mid = (TAPS - 1) / 2
        val k = DoubleArray(TAPS) { i ->
            val x = (i - mid).toDouble()
            val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
            sinc * (0.54 - 0.46 * cos(2 * PI * i / (TAPS - 1)))
        }
        val sum = k.sum()
        for (i in k.indices) k[i] /= sum
        k
    }

    /**
     * The first [n] samples of 48 kHz mono float audio (-1..1), low-passed and decimated by 3, as a 16 kHz PCM16 WAV.
     * Out-of-range samples clip rather than wrap. The filter treats samples outside 0 until [n] as silence.
     */
    fun encode16k(samples48k: FloatArray, n: Int): ByteArray {
        val len = n.coerceIn(0, samples48k.size)
        val out = len / FACTOR
        val buf = ByteBuffer.allocate(HEADER_BYTES + out * 2).order(ByteOrder.LITTLE_ENDIAN)
        header(buf, out * 2)
        val mid = (TAPS - 1) / 2
        for (j in 0 until out) {
            val centre = j * FACTOR
            var acc = 0.0
            val from = maxOf(0, centre - mid)
            val to = minOf(len - 1, centre + mid)
            for (i in from..to) acc += kernel[i - centre + mid] * samples48k[i]
            buf.putShort((acc.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).roundToInt().toShort())
        }
        return buf.array()
    }

    private fun header(b: ByteBuffer, dataBytes: Int) {
        b.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataBytes).put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
            .putShort(1) // PCM
            .putShort(1) // mono
            .putInt(RATE).putInt(RATE * 2) // byte rate
            .putShort(2).putShort(16) // block align, bits per sample
        b.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataBytes)
    }
}
