package dev.avijit.orbittuner.tuner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GuideTextTest {
    private val strings = listOf("E", "A", "D", "G", "B", "E").mapIndexed { i, n -> NoteLabel(n, listOf(2, 2, 3, 3, 3, 4)[i]) }
    private val a2 = NoteLabel("A", 2)

    private fun playing(cents: Int, index: Int? = 1, inTune: Boolean = false) =
        Readout(note = a2, cents = cents, hz = 110.0, targetHz = 110.0, stringIndex = index, inTune = inTune)

    private fun line(r: Readout, tuned: Set<Int> = emptySet(), chromatic: Boolean = false) =
        guideLine(r, strings, tuned, chromatic)

    @Test fun noPitchAsksForTheTargetString() {
        assertEquals("Play the A string", line(Readout(stringIndex = 1)).word)
    }

    @Test fun chromaticNoPitchAsksForANote() {
        assertEquals("Play a note", line(Readout(), chromatic = true).word)
    }

    @Test fun flatSaysTighten() {
        assertEquals(GuideLine("Tighten", "Turn the peg to raise the pitch", good = false), line(playing(-20)))
        assertEquals("A tiny bit more", line(playing(-8)).sub)
    }

    @Test fun sharpSaysLoosen() {
        assertEquals(GuideLine("Loosen", "Turn the peg to lower the pitch", good = false), line(playing(25)))
        assertEquals("Just a touch", line(playing(5)).sub)
    }

    @Test fun inTuneButNotYetTickedSaysHold() {
        assertEquals(GuideLine("Hold it…", "Keep plucking", good = true), line(playing(2, inTune = true)))
    }

    @Test fun tickedStringIsPerfectAndPointsToTheNextUntuned() {
        assertEquals(GuideLine("Perfect", "Next: D string", good = true), line(playing(2, inTune = true), tuned = setOf(0, 1)))
        assertEquals("All strings tuned", line(playing(1, inTune = true), tuned = (0..5).toSet()).sub)
    }

    @Test fun nextWrapsPastTheLastString() {
        val r = playing(0, index = 5, inTune = true)
        assertEquals("Next: A string", line(r, tuned = setOf(0, 2, 3, 4, 5)).sub)
    }

    @Test fun chromaticInTuneNeverPointsToANextString() {
        val l = line(playing(1, index = null, inTune = true), tuned = setOf(0, 1), chromatic = true)
        assertEquals("In tune", l.word)
        assertFalse(l.sub.contains("Next"))
    }

    @Test fun autoWithNothingLockedStillGuides() {
        assertEquals("Tighten", line(playing(-20, index = null)).word)
    }
}
