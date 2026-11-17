package dev.avijit.orbittuner.tuner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class TuningsTest {
    private val instruments = Tunings.parse(File("src/main/assets/tunings.json").readText())

    @Test fun everyNoteParsesAndIsAscending() {
        for (i in instruments) for (t in i.tunings) {
            assertEquals("${i.id}/${t.id}", t.notes.size, t.midi.size)
            assertEquals("${i.id}/${t.id} ascending", t.midi.sorted(), t.midi)
        }
    }

    @Test fun catalogueSize() {
        assertTrue(instruments.size >= 22)
        assertTrue(instruments.sumOf { it.tunings.size } >= 70)
        assertTrue(instruments.filter { it.chromatic }.map { it.id }.containsAll(listOf("keyboard", "voice", "chromatic")))
    }

    @Test fun guitarStandard() {
        val g = instruments.first { it.id == "guitar" }.tunings.first { it.id == "standard" }
        assertEquals(listOf("E2", "A2", "D3", "G3", "B3", "E4"), g.notes)
        assertEquals(listOf(40, 45, 50, 55, 59, 64), g.midi)
    }

    @Test fun referencePitches() {
        fun t(i: String, t: String) = instruments.first { it.id == i }.tunings.first { it.id == t }.midi
        assertEquals(listOf(55, 62, 69, 76), t("violin", "standard"))
        assertEquals(listOf(36, 43, 50, 57), t("cello", "standard"))
        assertEquals(listOf(60, 64, 67, 69), t("ukulele", "soprano-c"))
        assertEquals(listOf(50, 55, 59, 62, 67), t("banjo", "open-g"))
    }

    @Test fun idsUnique() {
        assertEquals(instruments.size, instruments.map { it.id }.toSet().size)
        for (i in instruments) assertEquals(i.id, i.tunings.size, i.tunings.map { it.id }.toSet().size)
    }

    @Test fun correctedTuningsPinned() {
        fun t(i: String, t: String) = instruments.first { it.id == i }.tunings.first { it.id == t }
        assertEquals("D2 A2 D3 G3 A3 B3 D4 E4 G4", t("guitar-12", "drop-d").notes.joinToString(" "))
        assertEquals("A3 E4 A4 C#5", t("fiddle", "calico").notes.joinToString(" "))
        assertEquals("Calico (AEAC#)", t("fiddle", "calico").name)
        assertEquals("A3 D4 A4 E5", t("fiddle", "high-bass").notes.joinToString(" "))
        assertEquals("C3 F3 Bb3", t("shamisen", "sansagari").notes.joinToString(" "))
        assertEquals("D4 A4 B4 E5", t("cavaquinho", "portuguese").notes.joinToString(" "))
        assertEquals("Portuguese (DABE)", t("cavaquinho", "portuguese").name)
        assertEquals("D4 G4 B4 E5", t("cavaquinho", "brazilian-solo").notes.joinToString(" "))
        assertEquals("Brazilian solo (DGBE)", t("cavaquinho", "brazilian-solo").name)
        for (g in listOf("guitar", "electric-guitar")) assertTrue(instruments.first { it.id == g }.tunings.none { it.id == "cross" })
    }
}
