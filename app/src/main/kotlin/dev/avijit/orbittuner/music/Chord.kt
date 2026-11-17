package dev.avijit.orbittuner.music

/** [quality] is the verbatim suffix after the root, e.g. "m7", "maj7#11", "sus4". */
data class Chord(val root: Int, val quality: String, val bass: Int?)

/**
 * Parses chord tokens. Accepted shape: root [A-H a-h] + optional accidental (#, b, sharp/flat signs) + suffix.
 *
 * Accidentals are greedy: "Bb5" is B-flat + "5" (the common power chord), so "Cb5" is C-flat + "5".
 * "H" is German B; "H#" is B-sharp (pc 0). Lower-case roots are accepted.
 *
 * The suffix is accepted when empty, when it starts with a non-ASCII-letter (digit, "(", "+", "-", degree/half-dim/delta
 * signs, "/"), or when it starts with a known quality word. Anything else ("Chorus", "Dog", "Bridge") is not a chord.
 *
 * Bass: split on the LAST "/"; if the tail is exactly a root (+accidental) it is the bass, otherwise the slash stays part
 * of the quality ("C6/9" has quality "6/9"; "D/F#m" has quality "/F#m", no bass).
 */
object ChordParser {
    private val BASE = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11, 'H' to 11)
    private val WORDS = listOf("min", "maj", "Maj", "MAJ", "dim", "aug", "sus", "add", "dom", "alt", "omit", "no", "m", "M")

    /** Parses a root note at the start of [s]; returns (pc, charsConsumed) or null. */
    private fun root(s: String): Pair<Int, Int>? {
        if (s.isEmpty()) return null
        var pc = BASE[s[0].uppercaseChar()] ?: return null
        var n = 1
        if (s.length > 1) when (s[1]) {
            '#', '\u266F' -> { pc += 1; n = 2 }
            'b', '\u266D' -> { pc -= 1; n = 2 }
        }
        return (((pc % 12) + 12) % 12) to n
    }

    private fun validSuffix(q: String): Boolean {
        if (q.isEmpty()) return true
        val c = q[0]
        if (!(c in 'a'..'z' || c in 'A'..'Z')) return true
        return WORDS.any { q.startsWith(it) }
    }

    /** Returns null when the token is not a chord (pass-through). */
    fun parse(token: String): Chord? {
        val (r, n) = root(token) ?: return null
        val rest = token.substring(n)
        var quality = rest
        var bass: Int? = null
        val slash = rest.lastIndexOf('/')
        if (slash >= 0) {
            val b = root(rest.substring(slash + 1))
            if (b != null && b.second == rest.length - slash - 1) {
                bass = b.first
                quality = rest.substring(0, slash)
            }
        }
        if (!validSuffix(quality)) return null
        return Chord(r, quality, bass)
    }
}
