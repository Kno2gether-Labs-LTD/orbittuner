package dev.avijit.orbittuner.music

/** A capo suggestion: put the capo on fret [capo] and play [shapes] shapes. */
data class CapoHint(val capo: Int, val shapes: String)

object Capo {
    /** Semitones to apply to the chords so they are shapes played with the capo. */
    @Suppress("UNUSED_PARAMETER")
    fun shapes(soundingKey: String, capo: Int): Int = -capo

    /** Keys a beginner finds awkward in open position: Eb, Bb, Ab, Db, F# (Gb) and their minors (spec "Decisions"). */
    private val AWKWARD_TONICS = setOf(3, 10, 8, 1, 6)

    /** Open-position keys a capo can turn an awkward key into. */
    private val OPEN_MAJOR = setOf(0, 2, 4, 7, 9) // C D E G A
    private val OPEN_MINOR = setOf(9, 4, 2)      // Am Em Dm

    /**
     * For an awkward [key], the lowest capo that leaves open shapes (Eb: capo 1, D shapes); null when the key is
     * already friendly or no capo up to the stepper's limit helps.
     */
    fun suggest(key: Key, maxCapo: Int = 9): CapoHint? {
        if (key.tonic !in AWKWARD_TONICS) return null
        val open = if (key.minor) OPEN_MINOR else OPEN_MAJOR
        for (c in 1..maxCapo) {
            val shape = key.shifted(-c)
            if (shape.tonic in open) return CapoHint(c, shape.displayName())
        }
        return null
    }
}
