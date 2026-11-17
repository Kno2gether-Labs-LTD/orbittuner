package dev.avijit.orbittuner.backing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong

class SequencerTest {
    private val sr = 48_000

    private fun lesson(
        bars: List<String>, groove: Groove = Groove.Rock8, timeSig: TimeSig = TimeSig(groove.beats, if (groove.beats == 4) 4 else 8),
        bpm: Int = 120, key: String = "Am",
    ) = Lesson("t", "Test", Level.Easy, groove, timeSig, bpm, key, bars, "practice", "scale")

    /** An event and the absolute frame it starts on. */
    private data class At(val frame: Long, val event: Event)

    private fun Sequencer.pull(frames: Long, size: Int = 480, from: Long = 0): List<At> {
        val out = ArrayList<At>()
        var pos = from
        while (pos < from + frames) {
            val n = minOf(size.toLong(), from + frames - pos).toInt()
            for (e in next(n)) {
                assertTrue("offset ${e.offset} in buffer of $n", e.offset in 0 until n)
                out += At(pos + e.offset, e)
            }
            pos += n
        }
        return out
    }

    private fun List<At>.bars() = filter { (it.event as? Event.Position)?.beat == 0 }

    @Test fun oneBarAt120In44Is96000Frames() {
        val s = Sequencer(sr).apply { configure(lesson(listOf("Am", "G")), 0, 120) }
        val starts = s.pull(300_000).bars().map { it.frame }
        assertEquals(listOf(0L, 96_000L, 192_000L, 288_000L), starts)
    }

    @Test fun firstBarIsACountInWithSticksOnly() {
        val s = Sequencer(sr).apply { configure(lesson(listOf("Am", "G")), 0, 120) }
        val events = s.pull(200_000)
        val countIn = events.filter { it.frame < 96_000 }
        assertFalse(countIn.any { it.event is Event.Strum || it.event is Event.Bass })
        val drums = countIn.mapNotNull { it.event as? Event.Drum }
        assertEquals(4, drums.size)
        assertTrue(drums.all { it.kind == DrumKind.Sticks || it.kind == DrumKind.Hat })
        val positions = countIn.mapNotNull { it.event as? Event.Position }
        assertEquals(listOf(0, 1, 2, 3), positions.map { it.beat })
        assertTrue(positions.all { it.bar == -1 && it.chord == "Am" && it.nextChord == "G" })
        // The track itself starts at bar 0 with the first chord.
        val after = events.filter { it.frame >= 96_000 }
        assertTrue(after.any { it.event is Event.Strum } && after.any { it.event is Event.Bass })
        assertEquals(Event.Position(0, 0, 0, "Am", "G"), after.first { it.event is Event.Position }.event)
    }

    @Test fun chordChangesLandOnBarStarts() {
        val s = Sequencer(sr).apply { configure(lesson(listOf("Am", "G", "F", "E")), 0, 120) }
        val positions = s.pull(96_000L * 10).filter { it.frame >= 96_000 && it.event is Event.Position }
        var last = ""
        for (p in positions) {
            val pos = p.event as Event.Position
            if (pos.chord != last) {
                assertEquals("change to ${pos.chord}", 0, pos.beat)
                assertEquals(0L, p.frame % 96_000)
                last = pos.chord
            }
        }
        assertEquals(listOf("Am", "G", "F", "E", "Am", "G", "F", "E", "Am"), positions.filter { (it.event as Event.Position).beat == 0 }.map { (it.event as Event.Position).chord })
        assertEquals("G", (positions.first().event as Event.Position).nextChord)
        // Bass plays the bar's root: A, G, F, E.
        val roots = Sequencer(sr).apply { configure(lesson(listOf("Am", "G", "F", "E")), 0, 120) }.pull(96_000L * 5)
            .filter { it.frame >= 96_000 && it.frame % 96_000 == 0L }.mapNotNull { (it.event as? Event.Bass)?.midi?.rem(12) }
        assertEquals(listOf(9, 7, 5, 4), roots)
    }

    @Test fun twoChordsInABarSplitIt() {
        val s = Sequencer(sr).apply { configure(lesson(listOf("G C", "D")), 0, 120) }
        val pos = s.pull(96_000L * 3).filter { it.frame >= 96_000 }.mapNotNull { e -> (e.event as? Event.Position)?.let { it.beat to it.chord } }
        assertEquals(listOf(0 to "G", 1 to "G", 2 to "C", 3 to "C", 0 to "D", 1 to "D", 2 to "D", 3 to "D"), pos)
    }

    @Test fun sixEightBarIsSixEighthBeats() {
        val s = Sequencer(sr).apply { configure(lesson(listOf("D", "G"), Groove.SixEight, TimeSig(6, 8), key = "D"), 0, 120) }
        val events = s.pull(144_000L * 3)
        assertEquals(listOf(0L, 144_000L, 288_000L), events.bars().map { it.frame })
        assertEquals((0..5).toList() + (0..5).toList() + (0..5).toList(), events.mapNotNull { (it.event as? Event.Position)?.beat })
    }

    @Test fun sevenEightBarIsSevenBeats() {
        val s = Sequencer(sr).apply { configure(lesson(listOf("A", "G"), Groove.SevenEight, TimeSig(7, 8), key = "A"), 0, 120) }
        val events = s.pull(168_000L * 3)
        assertEquals(listOf(0L, 168_000L, 336_000L), events.bars().map { it.frame })
        assertEquals(21, events.count { it.event is Event.Position })
    }

    @Test fun swingPushesOffBeatEighthsToTwoThirds() {
        val s = Sequencer(sr).apply { configure(lesson(listOf("Dm7", "G7"), Groove.Swing, key = "C"), 0, 120) }
        val inBeat = s.pull(96_000L * 3).filter { it.frame >= 96_000 && it.event !is Event.Position }.map { it.frame % 24_000 }.toSet()
        assertEquals(setOf(0L, 16_000L), inBeat)

        val straight = Sequencer(sr).apply { configure(lesson(listOf("Am")), 0, 120) }
        val straightBeat = straight.pull(96_000L * 3).filter { it.frame >= 96_000 && it.event !is Event.Position }
            .filter { it.event !is Event.Strum }.map { it.frame % 24_000 }.toSet()
        assertEquals(setOf(0L, 12_000L), straightBeat)
    }

    @Test fun tempoChangeWhilePlayingWaitsForTheNextBarLine() {
        val l = lesson(listOf("Am", "G"))
        val s = Sequencer(sr).apply { configure(l, 0, 120) }
        val first = s.pull(144_000) // count-in plus half of bar 0
        s.configure(l, 0, 60)
        val rest = s.pull(600_000, from = 144_000)
        // Bar 0 keeps 120 BPM (beats at 120 000 and 144 000 land after the change), bar 1 is 60 BPM.
        val beats = (first + rest).filter { it.event is Event.Position }.map { it.frame }
        assertEquals(listOf(0L, 24_000L, 48_000L, 72_000L, 96_000L, 120_000L, 144_000L, 168_000L, 192_000L, 240_000L, 288_000L, 336_000L, 384_000L), beats.take(13))
        assertEquals(listOf(0L, 96_000L, 192_000L, 384_000L, 576_000L), (first + rest).bars().map { it.frame })
    }

    @Test fun keyChangeWhilePlayingWaitsForTheNextBarLine() {
        val l = lesson(listOf("Am"))
        val s = Sequencer(sr).apply { configure(l, 0, 120) }
        val first = s.pull(144_000)
        s.configure(l, 2, 120)
        val rest = s.pull(96_000, from = 144_000)
        val chords = (first + rest).filter { it.frame >= 96_000 }.mapNotNull { e -> (e.event as? Event.Position)?.let { e.frame to it.chord } }
        assertEquals(listOf(96_000L to "Am", 120_000L to "Am", 144_000L to "Am", 168_000L to "Am", 192_000L to "Bm", 216_000L to "Bm"), chords)
        // The bass follows at the same bar line: A then B.
        val bass = (first + rest).mapNotNull { (it.event as? Event.Bass)?.let { b -> it.frame to b.midi % 12 } }
        assertTrue(bass.filter { it.first < 192_000 }.all { it.second == 9 || it.second == 4 })
        assertEquals(11, bass.first { it.first >= 192_000 }.second)
    }

    @Test fun keyUpTwoTurnsAmIntoBm() {
        val s = Sequencer(sr).apply { configure(lesson(listOf("Am", "C")), 2, 120) }
        val p = s.pull(200_000).mapNotNull { it.event as? Event.Position }
        assertEquals("Bm", p.first().chord)
        assertEquals("D", p.first().nextChord)
        val flat = Sequencer(sr).apply { configure(lesson(listOf("Dm", "C"), key = "Dm"), -2, 120) }
        assertEquals("Cm", flat.pull(1_000).mapNotNull { it.event as? Event.Position }.first().chord)
        assertEquals("Bb", flat.pull(100_000, from = 1_000).mapNotNull { it.event as? Event.Position }.first().nextChord)
    }

    @Test fun tenMinutesWithoutDrift() {
        // 70 BPM 4/4 is 164 571.43 frames a bar; odd buffer sizes on purpose.
        val barLen = 4 * sr * 60.0 / 70
        val s = Sequencer(sr).apply { configure(lesson(listOf("Em", "D")), 0, 70) }
        val starts = s.pull(10L * 60 * sr, size = 441).bars().map { it.frame }
        assertEquals(175, starts.size)
        starts.forEachIndexed { n, f -> assertEquals("bar $n", (n * barLen).roundToLong(), f) }
    }

    @Test fun strumsAreGuitarVoicingsAndBassIsLow() {
        val s = Sequencer(sr).apply { configure(lesson(listOf("Am", "E7", "C", "G")), 0, 120) }
        val events = s.pull(96_000L * 5)
        val strums = events.mapNotNull { it.event as? Event.Strum }
        assertTrue(strums.isNotEmpty())
        for (st in strums) {
            assertTrue(st.midis.toString(), st.midis.all { it in 40..76 })
            assertTrue(st.midis.size in 3..6)
            assertTrue(st.vel in 0f..1f)
            // Down strums go low to high, up strums high to low.
            assertEquals(if (st.down) st.midis.sorted() else st.midis.sortedDescending(), st.midis)
        }
        val bass = events.mapNotNull { it.event as? Event.Bass }
        assertTrue(bass.all { it.midi in 28..52 && it.durFrames > 0 })
    }

    @Test fun droneIsAPluckedTanpuraCycle() {
        val d = Lessons.all.first { it.groove == Groove.Drone }
        val s = Sequencer(sr).apply { configure(d, 0, 120) }
        val strums = s.pull(96_000L * 2).filter { it.frame >= 96_000 }.mapNotNull { it.event as? Event.Strum }
        assertEquals(4, strums.size)
        assertTrue(strums.all { it.midis.size == 1 })
        assertEquals(listOf(9, 2, 2, 2), strums.map { it.midis.single() % 12 }) // Pa, Sa, Sa, low Sa
    }
}
