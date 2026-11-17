package dev.avijit.orbittuner.music

object Transposer {
    private val FLAT_KEYS = setOf("F", "Bb", "Eb", "Ab", "Db", "Gb", "Dm", "Gm", "Cm", "Fm", "Bbm", "Ebm")

    fun keyAccidental(key: String): Accidental =
        if (key in FLAT_KEYS) Accidental.Flat else Accidental.Sharp

    fun transposeToken(token: String, semitones: Int, acc: Accidental): String {
        if (token.isEmpty()) return token
        // Parenthesised chord, e.g. "(G)".
        if (token.length > 2 && token.first() == '(' && token.last() == ')') {
            val inner = ChordParser.parse(token.substring(1, token.length - 1))
            if (inner != null) return "(" + render(inner, semitones, acc) + ")"
            return token
        }
        val chord = ChordParser.parse(token) ?: return token
        return render(chord, semitones, acc)
    }

    private fun render(c: Chord, semitones: Int, acc: Accidental): String {
        val sb = StringBuilder(Note(c.root + semitones).name(acc)).append(c.quality)
        c.bass?.let { sb.append('/').append(Note(it + semitones).name(acc)) }
        return sb.toString()
    }

    /** Transposes a key such as "G" or "Bbm". Unknown keys come back unchanged. */
    fun transposeKey(key: String, semitones: Int): String {
        val c = ChordParser.parse(key) ?: return key
        val minor = c.quality == "m"
        if (c.quality.isNotEmpty() && !minor) return key
        val note = Note(c.root + semitones)
        val flat = note.name(Accidental.Flat) + c.quality
        return if (flat in FLAT_KEYS) flat else note.name(Accidental.Sharp) + c.quality
    }

    /** Shortest signed distance from [fromKey] to [toKey], in -5..6. Returns 0 if either key cannot be parsed. */
    fun semitonesBetween(fromKey: String, toKey: String): Int {
        val a = ChordParser.parse(fromKey)?.root ?: return 0
        val b = ChordParser.parse(toKey)?.root ?: return 0
        var d = ((b - a) % 12 + 12) % 12
        if (d > 6) d -= 12
        return d
    }
}
