package dev.avijit.orbittuner.music

enum class Accidental { Sharp, Flat }

data class Note(val pc: Int) {
    fun name(acc: Accidental): String {
        val i = ((pc % 12) + 12) % 12
        return if (acc == Accidental.Sharp) SHARPS[i] else FLATS[i]
    }

    companion object {
        private val SHARPS = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        private val FLATS = arrayOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")
    }
}
