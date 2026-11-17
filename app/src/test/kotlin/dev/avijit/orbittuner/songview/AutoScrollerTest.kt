package dev.avijit.orbittuner.songview

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoScrollerTest {
    @Test fun speedFromTempo() {
        // 40 lines x 4 beats x 2 bars / 80 bpm x 60 = 240 s for 5000 px of overflow.
        assertEquals(5000f / 240f, AutoScroller.defaultSpeed(80, "4/4", 40, 6000, 1000), 0.01f)
        assertEquals(20.8f, AutoScroller.defaultSpeed(80, "4/4", 40, 6000, 1000), 0.05f)
    }

    @Test fun noOverflowIsTheMinimum() {
        assertEquals(5f, AutoScroller.defaultSpeed(80, "4/4", 40, 1000, 1000), 0f)
    }

    @Test fun threeQuarterTimeUsesThreeBeats() {
        // 40 x 3 x 2 / 80 x 60 = 180 s.
        assertEquals(5000f / 180f, AutoScroller.defaultSpeed(80, "3/4", 40, 6000, 1000), 0.01f)
    }

    @Test fun withoutTempoAssumesThreeMinutes() {
        assertEquals(5000f / 180f, AutoScroller.defaultSpeed(null, null, 40, 6000, 1000), 0.01f)
        assertEquals(5000f / 180f, AutoScroller.defaultSpeed(0, "bogus", 40, 6000, 1000), 0.01f)
    }

    @Test fun clampsToTwoHundred() {
        assertEquals(200f, AutoScroller.defaultSpeed(200, "4/4", 1, 100_000, 1000), 0f)
    }
}
