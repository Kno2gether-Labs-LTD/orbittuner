package dev.avijit.orbittuner.music

import kotlin.math.ln
import kotlin.math.pow

object Pitch {
    fun midiToHz(midi: Double, a4: Double = 440.0): Double = a4 * 2.0.pow((midi - 69.0) / 12.0)
    fun hzToMidi(hz: Double, a4: Double = 440.0): Double = 69.0 + 12.0 * ln(hz / a4) / ln(2.0)
    fun centsBetween(hz: Double, targetHz: Double): Double = 1200.0 * ln(hz / targetHz) / ln(2.0)
}
