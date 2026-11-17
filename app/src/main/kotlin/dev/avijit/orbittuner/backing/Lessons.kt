package dev.avijit.orbittuner.backing

import dev.avijit.orbittuner.music.Note
import dev.avijit.orbittuner.music.Transposer

enum class Level { Easy, Medium, Advanced }

/** A time signature; [beats] is what the sequencer counts (6/8 is six eighth-note beats, and BPM counts those). */
data class TimeSig(val beats: Int, val unit: Int) {
    override fun toString() = "$beats/$unit"
}

/**
 * One backing-track lesson. [bars] holds one entry per bar: a chord ("Am"), or several that split the bar evenly
 * ("G C" is two beats each in 4/4). [bpm] and [key] are the defaults; the player transposes and changes tempo.
 */
data class Lesson(
    val id: String,
    val title: String,
    val level: Level,
    val groove: Groove,
    val timeSig: TimeSig,
    val bpm: Int,
    val key: String,
    val bars: List<String>,
    val practice: String,
    val scaleHint: String,
) {
    /** The key shown after transposing by [keyOffset] semitones, e.g. "Am" +2 -> "Bm". */
    fun keyIn(keyOffset: Int): String = Transposer.transposeKey(key, keyOffset)

    /** A chord token as displayed in the transposed key (sharps or flats follow that key). */
    fun chordIn(token: String, keyOffset: Int): String =
        if (keyOffset == 0) token else Transposer.transposeToken(token, keyOffset, Transposer.keyAccidental(keyIn(keyOffset)))

    /**
     * [scaleHint] with every note name (a letter A-G, optionally followed by ♯ or ♭) moved by [keyOffset] semitones
     * and spelled by the transposed key's rule, e.g. "E minor pentatonic" +2 -> "F♯ minor pentatonic".
     * Letters inside words ("Dorian", "Bhairav-ish") are left alone.
     */
    fun scaleHintIn(keyOffset: Int): String {
        if (keyOffset == 0) return scaleHint
        val acc = Transposer.keyAccidental(keyIn(keyOffset))
        return NOTE.replace(scaleHint) { m ->
            val base = BASE.getValue(m.groupValues[1][0])
            val shift = when (m.groupValues[2]) { "♯" -> 1; "♭" -> -1; else -> 0 }
            Note(base + shift + keyOffset).name(acc).replace("#", "♯").replace("b", "♭")
        }
    }

    private companion object {
        val NOTE = Regex("(?<![A-Za-z])([A-G])([♯♭])?(?![A-Za-z])")
        val BASE = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)
    }
}

/** The curated lessons, easy to advanced (spec 2026-11-17). */
object Lessons {
    private val FOUR = TimeSig(4, 4)
    private val SIX_EIGHT = TimeSig(6, 8)
    private val SEVEN_EIGHT = TimeSig(7, 8)

    val all: List<Lesson> = listOf(
        // ---- Easy ----
        Lesson(
            "two-chord", "Two-chord groove", Level.Easy, Groove.Rock8, FOUR, 70, "Em", listOf("Em", "Em", "D", "D"),
            "Strum steady down-strokes on every eighth.\nChange chords on the 1, keep your hand moving.",
            "E minor pentatonic",
        ),
        Lesson(
            "three-chord-folk", "Three-chord folk", Level.Easy, Groove.Pop, FOUR, 80, "G", listOf("G", "C", "D", "D"),
            "Pattern: down, down-up, up-down-up.\nLift into the next chord on the last up-stroke.",
            "G major pentatonic",
        ),
        Lesson(
            "four-chords", "The four chords", Level.Easy, Groove.Pop, FOUR, 76, "G", listOf("G", "D", "Em", "C"),
            "The progression behind a thousand pop songs.\nKeep the strum light and even.",
            "G major pentatonic",
        ),
        Lesson(
            "bollywood-ballad", "Bollywood ballad", Level.Easy, Groove.Ballad, FOUR, 72, "C", listOf("C", "Am", "F", "G"),
            "Let each chord ring; one strum on 1 and 3 is enough.\nTry the F as a small 4-string shape.",
            "C major scale",
        ),
        Lesson(
            "bengali-6-8", "Bengali 6/8", Level.Easy, Groove.SixEight, SIX_EIGHT, 180, "D", listOf("D", "G", "A", "D"),
            "Count six: ONE two three FOUR five six.\nStrum down on 1 and 4, feel the sway.",
            "D major pentatonic",
        ),
        Lesson(
            "slow-minor", "Slow minor", Level.Easy, Groove.Ballad, FOUR, 66, "Am", listOf("Am", "Dm", "E", "E"),
            "Slow and dark: listen for the pull from E back to Am.\nPlay the E as E7 if it's easier.",
            "A minor pentatonic",
        ),
        // ---- Medium ----
        Lesson(
            "blues-in-a", "12-bar blues in A", Level.Medium, Groove.Shuffle, FOUR, 90, "A",
            listOf("A7", "D7", "A7", "A7", "D7", "D7", "A7", "A7", "E7", "D7", "A7", "E7"),
            "Count the 12 bars: 4 of I, 2 of IV, 2 of I, then V–IV–I–V.\nLean into the shuffle: long-short, long-short.",
            "A blues scale (A minor pentatonic + E♭)",
        ),
        Lesson(
            "andalusian", "Andalusian", Level.Medium, Groove.Rock8, FOUR, 100, "Am", listOf("Am", "G", "F", "E"),
            "Walk down: Am, G, F, E, with the bass falling a step at a time.\nAccent the E: it pulls you back to Am.",
            "A minor; A Phrygian dominant over the E",
        ),
        Lesson(
            "minor-pop", "Minor pop", Level.Medium, Groove.Pop, FOUR, 96, "Am", listOf("Am", "F", "C", "G"),
            "Same four chords, starting from the minor.\nMute with the palm on the up-strokes for drive.",
            "A minor pentatonic",
        ),
        Lesson(
            "two-five-one-c", "ii–V–I in C", Level.Medium, Groove.Swing, FOUR, 100, "C", listOf("Dm7", "G7", "Cmaj7", "Cmaj7"),
            "Short chord stabs on 2 and 4, like a jazz rhythm player.\nHear the 7th of each chord step down into the next.",
            "C major; target chord tones",
        ),
        Lesson(
            "sufi-groove", "Sufi groove", Level.Medium, Groove.SixEight, SIX_EIGHT, 192, "Dm", listOf("Dm", "C", "Bb", "C"),
            "A swaying 6/8 for qawwali-style melodies.\nKeep the strum loose; let the B♭ sound open.",
            "D natural minor (Aeolian)",
        ),
        Lesson(
            "funk-vamp", "Funk vamp", Level.Medium, Groove.Funk, FOUR, 96, "E", listOf("E9"),
            "One chord, all rhythm: keep your hand moving in sixteenths.\nOnly let some strokes touch the strings.",
            "E Mixolydian; E minor pentatonic",
        ),
        // ---- Advanced ----
        Lesson(
            "minor-blues", "Minor blues with turnaround", Level.Advanced, Groove.Shuffle, FOUR, 110, "Am",
            listOf("Am7", "Am7", "Am7", "Am7", "Dm7", "Dm7", "Am7", "Am7", "F7", "E7", "Am7", "E7"),
            "12-bar minor blues; F7 to E7 is the turnaround.\nOutline each chord's 3rd as it changes.",
            "A minor pentatonic; A harmonic minor over E7",
        ),
        Lesson(
            "two-five-one-keys", "ii–V–I through keys", Level.Advanced, Groove.Swing, FOUR, 140, "C",
            listOf("Dm7", "G7", "Cmaj7", "Cmaj7", "Cm7", "F7", "Bbmaj7", "Bbmaj7", "Bbm7", "Eb7", "Abmaj7", "Abmaj7"),
            "C, then B♭, then A♭: each new key is a whole step down.\nFind the nearest chord shape, don't jump around.",
            "Major scale of each key",
        ),
        Lesson(
            "fast-funk", "Fast funk", Level.Advanced, Groove.Funk, FOUR, 108, "Em", listOf("Em7", "A9"),
            "Tight sixteenths: Em7 to A9 with almost no hand movement.\nGhost the strokes between the accents.",
            "E Dorian",
        ),
        Lesson(
            "rock-riffs", "Rock riffs", Level.Advanced, Groove.Rock8, FOUR, 130, "E", listOf("E", "G", "A", "C"),
            "Driving down-strokes; try power chords on the low strings.\nPalm-mute, then open up on the C.",
            "E minor pentatonic",
        ),
        Lesson(
            "drone", "Drone for improvisation", Level.Advanced, Groove.Drone, FOUR, 70, "D", listOf("D"),
            "A tanpura-like drone on D and A: no changes, just you.\nPlay slow phrases and let them resolve to D.",
            "D Bhairav-ish / Dorian",
        ),
        Lesson(
            "seven-eight", "7/8 groove", Level.Advanced, Groove.SevenEight, SEVEN_EIGHT, 210, "A", listOf("A", "G"),
            "Count 1-2, 1-2, 1-2-3: seven quick beats.\nStrum on each group's 1 until it feels natural.",
            "A Mixolydian",
        ),
    )

    fun byId(id: String): Lesson? = all.firstOrNull { it.id == id }
}
