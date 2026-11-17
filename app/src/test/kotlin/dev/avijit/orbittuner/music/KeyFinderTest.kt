package dev.avijit.orbittuner.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class KeyFinderTest {
    private val G = 7

    // ---- sheet key ----

    @Test fun infersMajorKeyFromChords() {
        assertEquals(Key(G, false), KeyFinder.sheetKey(null, listOf("G", "C", "D", "Em", "G", "D", "G")))
    }

    @Test fun infersMinorKeyFromChords() {
        assertEquals(Key(9, true), KeyFinder.sheetKey(null, listOf("Am", "F", "C", "G", "Am")))
    }

    @Test fun declaredKeyWinsOverChords() {
        assertEquals(Key(3, false), KeyFinder.sheetKey("Eb", listOf("G", "C", "D", "Em", "G")))
        assertEquals(Key(4, true), KeyFinder.sheetKey("Em", listOf("G", "C", "D")))
    }

    @Test fun unreadableDeclaredKeyFallsBackToChords() {
        assertEquals(Key(G, false), KeyFinder.sheetKey("Chorus", listOf("G", "C", "D", "G")))
    }

    @Test fun noChordsGivesNoKey() {
        assertNull(KeyFinder.sheetKey(null, emptyList()))
        assertNull(KeyFinder.sheetKey("", emptyList()))
    }

    @Test fun nonsenseTokensGiveNoKey() {
        assertNull(KeyFinder.sheetKey(null, listOf("Chorus", "Verse", "la", "xyz")))
    }

    @Test fun keyNames() {
        assertEquals("Eb", Key(3, false).name(Accidental.Flat))
        assertEquals("F#m", Key(6, true).name(Accidental.Sharp))
    }

    // ---- chord profile ----

    @Test fun chordProfileWeightsRootDouble() {
        val p = KeyFinder.chordProfile(listOf("C", "Am"))
        assertEquals(3.0, p[0], 1e-9)             // C: root of C (2) + third of Am (1)
        assertEquals(2.0, p[4], 1e-9)             // E: third of C (1) + fifth of Am (1)
        assertEquals(1.0, p[7], 1e-9)             // G: fifth of C
        assertEquals(2.0, p[9], 1e-9)             // A: root of Am
        assertEquals(0.0, p[2], 1e-9)
    }

    @Test fun chordProfileOfNothingIsAllZero() {
        assertTrue(KeyFinder.chordProfile(listOf("Chorus", "xyz")).all { it == 0.0 })
    }

    // ---- shift ----

    /** A G-major melody (G A B C D E F#) leaning on G, D and B, as a histogram of seconds per pitch class. */
    private fun gMajorMelody(): DoubleArray {
        val h = DoubleArray(12)
        h[7] = 4.0; h[2] = 3.0; h[11] = 2.5; h[9] = 1.0; h[0] = 1.0; h[4] = 1.0; h[6] = 0.8
        return h
    }

    private fun up(h: DoubleArray, n: Int) = DoubleArray(12) { h[((it - n) % 12 + 12) % 12] }

    private val cSheet = listOf("C", "F", "G", "C", "Am", "G", "C")
    private val gSheet = listOf("G", "C", "D", "G", "Em", "D", "G")

    @Test fun gMelodyOverCSheetShiftsDownFive() {
        val s = KeyFinder.bestShift(gMajorMelody(), KeyFinder.chordProfile(cSheet), 10.0)!!
        assertEquals(-5, s.semitones)
        assertTrue(s.confident)
        assertNull(s.alternative)
    }

    @Test fun melodySungTwoUpShiftsUpTwo() {
        val s = KeyFinder.bestShift(up(gMajorMelody(), 2), KeyFinder.chordProfile(gSheet), 10.0)!!
        assertEquals(2, s.semitones)
        assertTrue(s.confident)
    }

    @Test fun shortSingingIsNotConfident() {
        val s = KeyFinder.bestShift(gMajorMelody(), KeyFinder.chordProfile(cSheet), 3.0)!!
        assertEquals(-5, s.semitones)
        assertFalse(s.confident)
    }

    @Test fun ambiguousMelodyOffersAlternative() {
        // One-chord sheet: sung = the sheet at 0 plus the sheet at +2, so shifts 0 and +2 fit exactly equally.
        val sheet = KeyFinder.chordProfile(listOf("C"))
        val sung = DoubleArray(12) { sheet[it] + up(sheet, 2)[it] }
        val s = KeyFinder.bestShift(sung, sheet, 10.0)!!
        assertFalse(s.confident)
        assertEquals(setOf(0, 2), setOf(s.semitones, s.alternative))
    }

    @Test fun allZeroProfilesGiveNoShift() {
        val zero = DoubleArray(12)
        assertNull(KeyFinder.bestShift(zero, KeyFinder.chordProfile(cSheet), 10.0))
        assertNull(KeyFinder.bestShift(gMajorMelody(), zero, 10.0))
        assertNull(KeyFinder.bestShift(zero, zero, 10.0))
        assertNull(KeyFinder.bestShift(DoubleArray(12) { 1.0 }, KeyFinder.chordProfile(cSheet), 10.0))
    }

    // ---- sung profile ----

    private val hop = 0.02

    private fun hz(midi: Double) = Pitch.midiToHz(midi)

    @Test fun octavesLandInSameBin() {
        val p = SungProfile()
        p.add(220.0, 0.5)  // A3
        p.add(440.0, 0.5)  // A4
        p.add(880.0, 0.25) // A5
        p.add(hz(69.3), 0.25) // A4, 30 cents sharp
        val h = p.histogram()
        assertEquals(1.5, h[9], 1e-9)
        assertEquals(1.5, h.sum(), 1e-9)
        assertEquals(1.5, p.voicedSeconds, 1e-9)
    }

    @Test fun unvoicedHopsAreIgnored() {
        val p = SungProfile()
        p.add(0.0, 0.5)
        p.add(Double.NaN, 0.5)
        p.add(-3.0, 0.5)
        assertEquals(0.0, p.voicedSeconds, 1e-9)
        assertTrue(p.histogram().all { it == 0.0 })
        assertFalse(p.isSinging)
    }

    /** Held notes of 0.4 s each with [depth]-cent vibrato at 5.5 Hz plus random ±[jitter] cents, a breath between notes. */
    private fun sing(p: SungProfile, seconds: Double, depth: Double = 20.0, jitter: Double = 0.0) {
        val rnd = java.util.Random(7)
        val melody = listOf(67, 69, 71, 67, 74, 72, 71, 69)
        var t = 0.0
        var i = 0
        while (t < seconds) {
            val note = melody[i++ % melody.size]
            var held = 0.0
            while (held < 0.4) {
                val cents = depth * sin(2 * Math.PI * 5.5 * (t + held)) + jitter * (2 * rnd.nextDouble() - 1)
                p.add(hz(note + cents / 100.0), hop)
                held += hop
            }
            p.add(0.0, hop) // breath between notes
            t += held + hop
        }
    }

    /** Speech-like contour: pitch keeps sliding over about 8 semitones; [rate] 1.0 is speech speed. */
    private fun glide(p: SungProfile, rate: Double) {
        var t = 0.0
        while (t < 8.0) {
            p.add(hz(50.0 + 4.0 * sin(2 * Math.PI * rate * t) + 2.0 * sin(2 * Math.PI * 2.3 * rate * t)), hop)
            t += hop
        }
    }

    @Test fun steadyNotesCountAsSinging() {
        val p = SungProfile()
        sing(p, 6.0)
        assertTrue(p.voicedSeconds >= 4.0)
        assertTrue(p.isSinging)
    }

    @Test fun wideVibratoCountsAsSinging() {
        val p = SungProfile()
        sing(p, 8.0, depth = 40.0)
        assertTrue(p.isSinging)
    }

    @Test fun vibratoWithDetectorJitterCountsAsSinging() {
        val p = SungProfile()
        sing(p, 8.0, depth = 25.0, jitter = 10.0)
        assertTrue(p.isSinging)
    }

    @Test fun glidingSpeechIsNotSinging() {
        val p = SungProfile()
        glide(p, 1.0)
        assertTrue(p.voicedSeconds >= 4.0)
        assertFalse(p.isSinging)
    }

    @Test fun slowGlideIsNotSinging() {
        val p = SungProfile()
        glide(p, 0.25)
        assertTrue(p.voicedSeconds >= 4.0)
        assertFalse(p.isSinging)
    }

    @Test fun underFourSecondsIsNotSinging() {
        val p = SungProfile()
        sing(p, 3.0)
        assertTrue(p.voicedSeconds < 4.0)
        assertFalse(p.isSinging)
    }

    @Test fun twinkleInCSungInDShiftsUpTwo() {
        // "Twinkle Twinkle" chord sheet in C.
        val sheet = KeyFinder.chordProfile(
            listOf("C", "F", "C", "G", "C", "G", "C", "C", "G", "C", "G", "C", "G", "C", "G", "C", "F", "C", "G", "C")
        )
        // The melody sung in D (MIDI), each note 0.45 s with vibrato.
        val d = 62; val e = 64; val fs = 66; val g = 67; val a = 69; val b = 71
        val line1 = listOf(d, d, a, a, b, b, a, g, g, fs, fs, e, e, d)
        val line2 = listOf(a, a, g, g, fs, fs, e, a, a, g, g, fs, fs, e)
        val tune = line1 + line2 + line1
        val p = SungProfile()
        var t = 0.0
        for (n in tune) {
            var held = 0.0
            while (held < 0.45) {
                p.add(hz(n + 0.3 * sin(2 * Math.PI * 5.5 * (t + held))), hop)
                held += hop
            }
            p.add(0.0, hop)
            t += held + hop
        }
        assertTrue(p.isSinging)
        val s = KeyFinder.bestShift(p.histogram(), sheet, p.voicedSeconds)!!
        assertEquals(2, s.semitones)
        assertTrue(s.confident)
    }

    @Test fun profileFeedsShift() {
        val p = SungProfile()
        sing(p, 8.0)
        val s = KeyFinder.bestShift(p.histogram(), KeyFinder.chordProfile(cSheet), p.voicedSeconds)
        assertNotNull(s)
        assertEquals(-5, s!!.semitones)
    }

    // ---- display (A3) ----

    @Test fun displayNameFollowsTheTransposerSpelling() {
        assertEquals("Ebm", Key(3, true).displayName())
        assertEquals("Bb", Key(10, false).displayName())
        assertEquals("A", Key(9, false).displayName())
        assertEquals("F#m", Key(6, true).displayName())
        assertEquals("C#m", Key(1, true).displayName())
        // Every key reads the same as Transposer.transposeKey would spell it.
        for (t in 0 until 12) {
            assertEquals(Transposer.transposeKey("C", t), Key(t, false).displayName())
            assertEquals(Transposer.transposeKey("Am", t), Key((9 + t) % 12, true).displayName())
        }
    }

    @Test fun awkwardKeysSuggestTheCapoForOpenShapes() {
        assertEquals(CapoHint(1, "D"), Capo.suggest(Key(3, false)))   // Eb: capo 1, D shapes
        assertEquals(CapoHint(1, "A"), Capo.suggest(Key(10, false)))  // Bb
        assertEquals(CapoHint(1, "G"), Capo.suggest(Key(8, false)))   // Ab
        assertEquals(CapoHint(1, "C"), Capo.suggest(Key(1, false)))   // Db
        assertEquals(CapoHint(2, "E"), Capo.suggest(Key(6, false)))   // F#/Gb
        assertEquals(CapoHint(1, "Dm"), Capo.suggest(Key(3, true)))   // Ebm
        assertEquals(CapoHint(2, "Em"), Capo.suggest(Key(6, true)))   // F#m
        assertEquals(CapoHint(4, "Am"), Capo.suggest(Key(1, true)))   // C#m
    }

    @Test fun friendlyKeysNeedNoCapo() {
        for (k in listOf(Key(0, false), Key(7, false), Key(2, false), Key(9, false), Key(4, false), Key(5, false), Key(9, true), Key(4, true), Key(11, false)))
            assertNull(k.toString(), Capo.suggest(k))
    }
}
