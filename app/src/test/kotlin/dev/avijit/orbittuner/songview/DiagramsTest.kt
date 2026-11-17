package dev.avijit.orbittuner.songview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagramsTest {
    private fun tones(name: String) = ChordTheory.spelledTones(name)?.joinToString(" ")

    @Test fun spellsChordTonesByInterval() {
        assertEquals("G B♭ D", tones("Gm"))
        assertEquals("C E♭ G B♭", tones("Cm7"))
        assertEquals("F♯ A C E", tones("F#m7b5"))
        assertEquals("F♯ A C♯", tones("F#m"))
        assertEquals("B♭ D F A♭", tones("Bb7"))
        assertEquals("D♭ F A♭", tones("Db"))
        assertEquals("C E G♯", tones("Caug"))
        assertEquals("B C E G", tones("C/B"))
        assertEquals("C E G", tones("C/E"))
        assertNull(tones("Cxyz7"))
    }

    @Test fun fallsBackToTheRawChordsDbSuffix() {
        val shape = FretShape(listOf(-1, 3, 2, 0, 1, 0), List(6) { 0 }, 1, emptyList())
        // "13b9" is not in the alias table but is a chords-db suffix.
        assertEquals(shape, ChordShapes.find(mapOf("0|13b9" to shape), "C13b9"))
        assertEquals(shape, ChordShapes.find(mapOf("9|minor" to shape), "Am"))
    }
}
