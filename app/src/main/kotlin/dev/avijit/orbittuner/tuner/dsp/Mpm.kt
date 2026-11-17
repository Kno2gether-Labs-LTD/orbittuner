package dev.avijit.orbittuner.tuner.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class PitchResult(val hz: Double, val clarity: Double /* 0..1 */, val rms: Double)

/**
 * McLeod Pitch Method (McLeod & Wyvill, "A Smarter Way to Find Pitch", 2005).
 *
 * n(τ) = 2·r(τ) / m(τ), where r is the autocorrelation (via zero-padded FFT, O(n log n)) and
 * m(τ) = Σ x[j]² + x[j+τ]² over the overlapping part. Picks the first key maximum whose value is
 * ≥ K · the highest key maximum, then refines the lag by interpolation.
 *
 * Not thread-safe: all working buffers are allocated once and reused on every [detect] call.
 */
class Mpm(val sampleRate: Int, val windowSize: Int) {
    private val fftSize: Int
    private val log2n: Int
    private val re: DoubleArray
    private val im: DoubleArray
    private val cosT: DoubleArray
    private val sinT: DoubleArray
    private val bitRev: IntArray
    private val x = DoubleArray(windowSize)
    private val nsdf = DoubleArray(windowSize)
    private val keyMax = IntArray(windowSize / 2)

    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(windowSize >= 16) { "windowSize too small" }
        var n = 1
        var bits = 0
        while (n < 2 * windowSize) { n = n shl 1; bits++ }
        fftSize = n
        log2n = bits
        re = DoubleArray(n)
        im = DoubleArray(n)
        cosT = DoubleArray(n / 2) { cos(2 * PI * it / n) }
        sinT = DoubleArray(n / 2) { -sin(2 * PI * it / n) }
        bitRev = IntArray(n) { i ->
            var r = 0
            var v = i
            repeat(bits) { r = (r shl 1) or (v and 1); v = v shr 1 }
            r
        }
    }

    fun detect(buf: FloatArray, offset: Int = 0): PitchResult? {
        require(offset >= 0 && offset + windowSize <= buf.size) { "window out of range" }
        val w = windowSize

        // Remove DC (preserves periodicity) and compute RMS.
        var mean = 0.0
        for (i in 0 until w) mean += buf[offset + i]
        mean /= w
        var energy = 0.0
        for (i in 0 until w) {
            val v = buf[offset + i] - mean
            x[i] = v
            energy += v * v
        }
        val rms = sqrt(energy / w)
        if (rms < RMS_GATE) return null

        autocorrelate()
        computeNsdf(energy)

        val tau = pickPeak() ?: return null
        val clarity = nsdf[tau]
        if (clarity < CLARITY_GATE) return null
        val lag = refine(tau, interpolate(tau))
        return PitchResult(sampleRate / lag, clarity.coerceIn(0.0, 1.0), rms)
    }

    /** r(τ) for τ in 0 until windowSize, left in [re]. */
    private fun autocorrelate() {
        java.util.Arrays.fill(re, 0.0)
        java.util.Arrays.fill(im, 0.0)
        System.arraycopy(x, 0, re, 0, windowSize)
        fft(inverse = false)
        for (i in 0 until fftSize) {
            re[i] = re[i] * re[i] + im[i] * im[i]
            im[i] = 0.0
        }
        fft(inverse = true) // unscaled; divide below
    }

    private fun computeNsdf(energy: Double) {
        val w = windowSize
        val scale = 1.0 / fftSize
        var m = 2 * energy
        for (tau in 0 until w) {
            val r = re[tau] * scale
            nsdf[tau] = if (m > 1e-12) 2 * r / m else 0.0
            // m(τ+1) drops x[τ]² from the head and x[w-1-τ]² from the tail.
            m -= x[tau] * x[tau] + x[w - 1 - tau] * x[w - 1 - tau]
        }
    }

    /**
     * Key maxima: the highest value between each positive-going zero crossing and the next
     * negative-going one, after the initial lobe at τ = 0. Lags beyond half the window have too
     * little overlap to trust and are ignored.
     */
    private fun pickPeak(): Int? {
        val maxLag = windowSize / 2
        var tau = 1
        while (tau < maxLag && nsdf[tau] > 0) tau++ // skip the τ = 0 lobe
        var highest = 0.0
        var count = 0
        while (tau < maxLag) {
            while (tau < maxLag && nsdf[tau] <= 0) tau++
            if (tau >= maxLag) break
            var best = tau
            while (tau < maxLag && nsdf[tau] > 0) {
                if (nsdf[tau] > nsdf[best]) best = tau
                tau++
            }
            // A lobe cut off by maxLag is only kept if its max is interior.
            if (best + 1 >= maxLag) break
            if (count < keyMax.size) keyMax[count++] = best
            if (nsdf[best] > highest) highest = nsdf[best]
        }
        if (count == 0) return null
        val threshold = K * highest
        for (i in 0 until count) if (nsdf[keyMax[i]] >= threshold) return keyMax[i]
        return null
    }

    /** Parabolic interpolation of the peak lag around integer lag [tau]. */
    private fun interpolate(tau: Int): Double {
        val a = nsdf[tau - 1]
        val b = nsdf[tau]
        val c = nsdf[tau + 1]
        val denom = a - 2 * b + c
        if (denom >= 0.0) return tau.toDouble()
        return tau + 0.5 * (a - c) / denom
    }

    /**
     * For short periods (high notes) parabolic error is a sizeable fraction of a cent: a 4 kHz
     * period is ~12 samples, so 0.01 samples ≈ 1.5 cents. The peak near the m-th multiple of the
     * period carries the same absolute interpolation error, so dividing its lag by m shrinks the
     * error m-fold. Only accepted when that peak is as clear as the chosen one (within K) and
     * agrees with the single-period estimate.
     */
    private fun refine(tau: Int, lag: Double): Double {
        if (tau >= REFINE_BELOW_LAG) return lag
        val m = ((windowSize / 4) / lag).toInt()
        if (m < 2) return lag
        val centre = Math.round(m * lag).toInt()
        if (centre + 3 >= windowSize) return lag
        var best = centre - 2
        for (t in centre - 1..centre + 2) if (nsdf[t] > nsdf[best]) best = t
        if (best == centre - 2 || best == centre + 2) return lag // not a local max in range
        if (nsdf[best] < K * nsdf[tau]) return lag
        val refined = interpolate(best) / m
        return if (abs(refined - lag) < 0.5) refined else lag
    }

    /** In-place iterative radix-2 FFT on [re]/[im]. Inverse is unscaled. */
    private fun fft(inverse: Boolean) {
        val n = fftSize
        for (i in 0 until n) {
            val j = bitRev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        val sign = if (inverse) -1.0 else 1.0
        var size = 2
        while (size <= n) {
            val half = size shr 1
            val step = n / size
            var start = 0
            while (start < n) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosT[k]
                    val wi = sign * sinT[k]
                    val l = j + half
                    val tr = re[l] * wr - im[l] * wi
                    val ti = re[l] * wi + im[l] * wr
                    re[l] = re[j] - tr
                    im[l] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                start += size
            }
            size = size shl 1
        }
    }

    companion object {
        const val K = 0.93
        const val RMS_GATE = 0.005
        const val CLARITY_GATE = 0.80
        /** Lags below this (≈ 480 Hz at 48 kHz) get multi-period refinement. */
        private const val REFINE_BELOW_LAG = 100
    }
}
