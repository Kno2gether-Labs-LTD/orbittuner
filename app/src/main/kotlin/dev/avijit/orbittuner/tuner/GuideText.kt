package dev.avijit.orbittuner.tuner

import kotlin.math.abs

/** What the Guide meter says: a plain instruction [word], a [sub] line, and whether the news is [good] (in tune). */
data class GuideLine(val word: String, val sub: String, val good: Boolean)

/** The word the Guide shows once the engine has ticked the string (the meter draws its check mark then). */
internal const val GUIDE_PERFECT = "Perfect"

/**
 * The Guide meter's words for a beginner: which way to turn the peg, never cents. Within [near] cents the hint gets
 * gentler. A string the engine has ticked ([tuned]) and that is still in tune reads "Perfect" and points to the next
 * untuned string in tuning order (wrapping), or says all are done. Chromatic has no strings, so no "Next".
 *
 * With no pitch, [Readout.stringIndex] (when set) is the string to ask for.
 */
fun guideLine(readout: Readout, strings: List<NoteLabel>, tuned: Set<Int>, chromatic: Boolean, near: Int = 12): GuideLine {
    val idx = readout.stringIndex?.takeIf { !chromatic && it in strings.indices }
    if (!readout.hasPitch) {
        val word = when {
            chromatic || strings.isEmpty() -> "Play a note"
            idx != null -> "Play the ${strings[idx].name} string"
            else -> "Play any string"
        }
        return GuideLine(word, "Pluck it and let it ring", good = false)
    }
    val c = readout.cents
    return when {
        readout.inTune && idx != null && idx in tuned -> {
            val next = strings.indices.map { (idx + 1 + it) % strings.size }.firstOrNull { it !in tuned }
            GuideLine(GUIDE_PERFECT, if (next == null) "All strings tuned" else "Next: ${strings[next].name} string", good = true)
        }
        readout.inTune && idx != null -> GuideLine("Hold it…", "Keep plucking", good = true)
        readout.inTune -> GuideLine("In tune", "Right on the note", good = true)
        c < 0 -> GuideLine("Tighten", if (abs(c) <= near) "A tiny bit more" else "Turn the peg to raise the pitch", good = false)
        else -> GuideLine("Loosen", if (abs(c) <= near) "Just a touch" else "Turn the peg to lower the pitch", good = false)
    }
}
