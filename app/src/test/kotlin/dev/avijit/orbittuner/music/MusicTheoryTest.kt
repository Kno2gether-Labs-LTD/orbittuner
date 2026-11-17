package dev.avijit.orbittuner.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicTheoryTest {
    private val sharp = Accidental.Sharp

    @Test fun pitchMaths() {
        assertEquals(440.0, Pitch.midiToHz(69.0), 1e-9)
        assertEquals(40.0, Pitch.hzToMidi(82.4069), 0.001)
        assertEquals(3.93, Pitch.centsBetween(441.0, 440.0), 0.01)
        assertEquals(432.0, Pitch.midiToHz(69.0, 432.0), 1e-9)
    }

    @Test fun noteNames() {
        assertEquals("C#", Note(1).name(Accidental.Sharp))
        assertEquals("Db", Note(1).name(Accidental.Flat))
        assertEquals("B", Note(11).name(Accidental.Flat))
    }

    @Test fun transposeBasics() {
        assertEquals("Bm7", Transposer.transposeToken("Am7", 2, sharp))
        assertEquals("E/G#", Transposer.transposeToken("D/F#", 2, sharp))
        assertEquals("B", Transposer.transposeToken("Bb", 1, sharp))
        assertEquals("Dm", Transposer.transposeToken("C#m", 1, Transposer.keyAccidental("F")))
        assertEquals("Ab", Transposer.transposeToken("G", 1, Transposer.keyAccidental("F")))
    }

    @Test fun transposeEdgeCases() {
        for (t in listOf("N.C.", "x", "(riff)", "")) assertEquals(t, Transposer.transposeToken(t, 3, sharp))
        assertEquals("Cmaj7#11", Transposer.transposeToken("Bbmaj7#11", 2, sharp))
        assertEquals("Cm", Transposer.transposeToken("am", 3, sharp))
        assertEquals("C7", Transposer.transposeToken("H7", 1, sharp))
        assertEquals("(A)", Transposer.transposeToken("(G)", 2, sharp))
        assertEquals("G", Transposer.transposeToken("A", -2, sharp))
    }

    @Test fun chordParser() {
        assertEquals(Chord(2, "m7", 6), ChordParser.parse("Dm7/F#"))
        assertEquals(Chord(10, "sus4", null), ChordParser.parse("A♯sus4"))
        assertNull(ChordParser.parse("N.C."))
    }

    @Test fun keys() {
        assertEquals(Accidental.Flat, Transposer.keyAccidental("Bbm"))
        assertEquals(Accidental.Sharp, Transposer.keyAccidental("G"))
        assertEquals(-3, Transposer.semitonesBetween("G", "E"))
        assertEquals(6, Transposer.semitonesBetween("C", "F#"))
        assertEquals("A", Transposer.transposeKey("G", 2))
        assertEquals("Bb", Transposer.transposeKey("G", 3))
    }

    @Test fun capo() {
        assertEquals(-2, Capo.shapes("G", 2))
        assertEquals("F", Transposer.transposeToken("G", Capo.shapes("G", 2), sharp))
    }

    @Test fun fractionalIndex() {
        assertEquals("a0", FractionalIndex.between(null, null))
        assertEquals("a0V", FractionalIndex.between("a0", "a1"))
        var prev = FractionalIndex.between(null, null)
        repeat(50) {
            val n = FractionalIndex.between(prev, null)
            assertTrue("$prev < $n", prev < n); prev = n
        }
        var p = "a1"
        repeat(50) {
            val n = FractionalIndex.between("a0", p)
            assertTrue("a0 < $n < $p", "a0" < n && n < p); p = n
        }
        var q = "a0"
        repeat(50) {
            val n = FractionalIndex.between(null, q)
            assertTrue("$n < $q", n < q); q = n
        }
    }

    private fun t(token: String, n: Int = 2) = Transposer.transposeToken(token, n, sharp)

    @Test fun qualityRuling() {
        assertEquals("D6/9", t("C6/9"))
        assertEquals("DMaj7", t("CMaj7"))
        assertEquals("D7alt", t("C7alt"))
        assertEquals("D\u03947", t("C\u03947"))
        assertEquals("Ddom7", t("Cdom7"))
        assertEquals("Dno3", t("Cno3"))
        assertEquals("Dsus2/A", t("Csus2/G"))
        assertEquals("Dm(maj7)", t("Cm(maj7)"))
        assertEquals(Chord(0, "6/9", null), ChordParser.parse("C6/9"))
        for (w in listOf("Chorus", "Dog", "Bridge", "Gone", "N.C.", "x", "(riff)")) assertEquals(w, t(w))
    }

    @Test fun greedyAccidental() {
        assertEquals("C5", t("Bb5"))
        assertEquals("F7", t("Eb7"))
        assertEquals("B", t("bb", 1))
        assertEquals("C#", t("H#", 1))
    }

    @Test fun keyHelpers() {
        assertEquals("Fm", Transposer.transposeKey("Dm", 3))
        assertEquals("C", Transposer.transposeKey("Bb", 2))
        assertEquals(0, Transposer.semitonesBetween("???", "G"))
        assertEquals(0, Transposer.semitonesBetween("G", ""))
    }

    @Test fun fractionalVectors() {
        assertEquals("a0l", FractionalIndex.between("a0V", "a1"))
        assertEquals("b00", FractionalIndex.between("az", null))
        assertEquals("Zz", FractionalIndex.between(null, "a0"))
        for ((a, b) in listOf<Pair<String?, String?>>("" to null, null to "", "!x" to null, "a1" to "a0", "a1" to "a1", "a00" to null)) {
            try { FractionalIndex.between(a, b); org.junit.Assert.fail("expected throw for $a,$b") }
            catch (e: IllegalArgumentException) { }
        }
    }
}
