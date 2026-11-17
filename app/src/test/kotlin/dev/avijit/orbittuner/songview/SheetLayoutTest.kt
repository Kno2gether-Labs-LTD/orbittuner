package dev.avijit.orbittuner.songview

import dev.avijit.orbittuner.chordpro.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Flow rules with a fixed-width font: every char is 10 px, chords too. */
class SheetLayoutTest {
    private fun flow(segs: List<Segment>, width: Float = 1000f) =
        flowLine(segs, width, indent = 15f, chordGap = 5f, chordsOnly = false, measureText = { it.length * 10f }, measureChord = { it.length * 10f })

    @Test fun chordHangsOverFollowingWordsWithoutPadding() {
        // "F#m" (30 px) over "as the " (70 px): no gap after "as ".
        val row = flow(listOf(Segment(null, "Bright "), Segment("F#m", "as the "), Segment("E", "sun"))).single()
        val x = row.cells.associate { it.text to it.x }
        assertEquals(x.getValue("as ") + 30f, x.getValue("the "))
        assertEquals(x.getValue("the ") + 40f, x.getValue("sun"))
    }

    @Test fun wideChordPadsAndHyphenatesSplitWord() {
        // "re[Dsus4]lie[A]ved": Dsus4 (50 + 5) is wider than "lie" (30).
        val cells = flow(listOf(Segment(null, "re"), Segment("Dsus4", "lie"), Segment("A", "ved"))).single().cells
        val lie = cells.first { it.text == "lie" }
        val ved = cells.first { it.text == "ved" }
        assertEquals(lie.x + 55f, ved.x)
        assertTrue(lie.hyphen)
    }

    @Test fun wrapsAtWordsKeepingChordsOnTheirSyllable() {
        val rows = flow(listOf(Segment("G", "Amazing "), Segment("C", "grace "), Segment("D", "how")), width = 150f)
        assertEquals(listOf("Amazing ", "grace "), rows[0].cells.map { it.text })
        assertEquals("D", rows[1].cells.single().chord)
        assertEquals(15f, rows[1].cells.single().x)
        // Chords never overlap on any row.
        for (r in rows) r.cells.filter { it.chord != null }.zipWithNext { a, b -> assertTrue(b.x >= a.x + a.chord!!.length * 10f) }
    }
}
