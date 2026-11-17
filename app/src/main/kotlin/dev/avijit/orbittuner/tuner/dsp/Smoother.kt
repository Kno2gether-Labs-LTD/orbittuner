package dev.avijit.orbittuner.tuner.dsp

/**
 * Exponential moving average for the cents needle. The first push after construction or
 * [reset] is taken as-is, so the needle doesn't sweep in from zero on a new note.
 */
class Smoother(val alpha: Double = 0.35) {
    private var value = 0.0
    private var primed = false

    fun push(cents: Double): Double {
        value = if (primed) value + alpha * (cents - value) else cents
        primed = true
        return value
    }

    fun reset() {
        primed = false
        value = 0.0
    }
}
