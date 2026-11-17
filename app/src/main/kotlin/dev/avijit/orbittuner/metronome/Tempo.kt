package dev.avijit.orbittuner.metronome

/** Tempo range and the Italian names a metronome shows. */
object Tempo {
    const val MIN = 30
    const val MAX = 240
    const val DEFAULT_BPM = 80
    const val DEFAULT_BEATS = 4
    /** The bar lengths offered as chips. A song's time signature maps to one of these (anything else becomes 4); only the player can pick other lengths. */
    val BEAT_CHOICES = listOf(2, 3, 4, 6)
    const val MIN_BEATS = 2
    const val MAX_BEATS = 12

    /** Upper bounds (exclusive), from the approved concept. */
    private val names = listOf(
        40 to "Grave", 60 to "Largo", 66 to "Larghetto", 76 to "Adagio", 108 to "Andante",
        120 to "Moderato", 156 to "Allegro", 176 to "Vivace", 200 to "Presto",
    )

    fun name(bpm: Int): String = names.firstOrNull { bpm < it.first }?.second ?: "Prestissimo"

    fun clamp(bpm: Int): Int = bpm.coerceIn(MIN, MAX)

    /** Beats per bar for a song's `{time}`: its numerator when that is 2, 3, 4 or 6, else 4. */
    fun beatsFor(timeSig: String?): Int =
        timeSig?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it in BEAT_CHOICES } ?: DEFAULT_BEATS
}

/**
 * Tap tempo: the BPM from the mean of up to the last four intervals between taps. A pause of more than 2 s starts
 * over. Null until there are two taps.
 */
class TapTempo(private val clock: () -> Long) {
    private val taps = ArrayDeque<Long>()

    fun tap(): Int? {
        val now = clock()
        if (taps.isNotEmpty() && now - taps.last() > RESET_MS) taps.clear()
        taps.addLast(now)
        while (taps.size > MAX_INTERVALS + 1) taps.removeFirst()
        if (taps.size < 2) return null
        val mean = (taps.last() - taps.first()).toDouble() / (taps.size - 1)
        if (mean <= 0.0) return Tempo.MAX
        return Tempo.clamp(Math.round(60_000.0 / mean).toInt())
    }

    private companion object {
        const val RESET_MS = 2_000L
        const val MAX_INTERVALS = 4
    }
}
