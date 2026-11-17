package dev.avijit.orbittuner.metronome

import org.junit.Assert.assertEquals
import org.junit.Test

class ClickSchedulerTest {
    /** Pulls [buffers] buffers of [size] frames; returns (absolute frame, beat) for every click. */
    private fun ClickScheduler.pull(buffers: Int, size: Int, from: Long = 0): List<Pair<Long, Int>> =
        (0 until buffers).flatMap { b -> next(size).map { (from + b.toLong() * size + it.offset) to it.beat } }

    @Test fun clicksLandOnTheBeatAcrossBuffers() {
        val s = ClickScheduler(48_000).apply { configure(120, 4) }
        val clicks = s.pull(21, 4_800) // 100 800 frames
        assertEquals(listOf(0L to 0, 24_000L to 1, 48_000L to 2, 72_000L to 3, 96_000L to 0), clicks)
    }

    @Test fun tempoChangeIsMeasuredFromTheLastClick() {
        val s = ClickScheduler(48_000).apply { configure(120, 4) }
        val first = s.pull(6, 4_800) // up to 28 800: clicks at 0 and 24 000
        assertEquals(listOf(0L to 0, 24_000L to 1), first)
        s.configure(60, 4)
        val after = s.pull(20, 4_800, from = 28_800)
        assertEquals(72_000L to 2, after.first())
        assertEquals(120_000L to 3, after[1])
    }

    @Test fun twoForty() {
        val s = ClickScheduler(48_000).apply { configure(240, 4) }
        assertEquals(4, s.pull(10, 4_800).size)
    }

    @Test fun speedingUpPastTheNextClickGivesOneClickNotABurst() {
        val s = ClickScheduler(48_000).apply { configure(30, 4) } // 96 000 frames apart
        assertEquals(listOf(0L to 0), s.pull(10, 4_800)) // now at 48 000
        s.configure(240, 4) // 12 000 after the last click: already past
        val after = s.pull(5, 4_800, from = 48_000) // 48 000 until 72 000
        assertEquals(listOf(48_000L to 1, 60_000L to 2), after)
    }

    @Test fun reconfiguringTheSameValuesChangesNothing() {
        val s = ClickScheduler(48_000).apply { configure(120, 4) }
        val clicks = (0 until 21).flatMap { b ->
            s.configure(120, 4)
            s.next(4_800).map { b * 4_800L + it.offset }
        }
        assertEquals(listOf(0L, 24_000L, 48_000L, 72_000L, 96_000L), clicks)
    }

    @Test fun noDriftAtUnevenTempos() {
        // 70 BPM is 41 142.857 frames: after 70 clicks exactly one minute (2 880 000 frames) has passed.
        val s = ClickScheduler(48_000).apply { configure(70, 4) }
        val clicks = s.pull(601, 4_800)
        assertEquals(2_880_000L, clicks[70].first)
    }

    @Test fun changingBeatsStartsANewBar() {
        val s = ClickScheduler(48_000).apply { configure(120, 4) }
        s.pull(6, 4_800) // beats 0, 1 played
        s.configure(120, 3)
        assertEquals(listOf(48_000L to 0, 72_000L to 1, 96_000L to 2, 120_000L to 0), s.pull(20, 4_800, from = 28_800).take(4))
    }

    @Test fun resetStartsOver() {
        val s = ClickScheduler(48_000).apply { configure(120, 4) }
        s.pull(7, 4_800)
        s.reset()
        assertEquals(listOf(0L to 0, 24_000L to 1), s.pull(6, 4_800))
    }
}
