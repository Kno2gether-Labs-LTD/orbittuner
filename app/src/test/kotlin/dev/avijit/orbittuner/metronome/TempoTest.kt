package dev.avijit.orbittuner.metronome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TempoTest {
    @Test fun names() {
        val expected = listOf(
            30 to "Grave", 39 to "Grave", 40 to "Largo", 59 to "Largo", 60 to "Larghetto", 65 to "Larghetto",
            66 to "Adagio", 75 to "Adagio", 76 to "Andante", 107 to "Andante", 108 to "Moderato", 119 to "Moderato",
            120 to "Allegro", 155 to "Allegro", 156 to "Vivace", 175 to "Vivace", 176 to "Presto", 199 to "Presto",
            200 to "Prestissimo", 240 to "Prestissimo",
        )
        expected.forEach { (bpm, name) -> assertEquals("$bpm", name, Tempo.name(bpm)) }
    }

    private class Clock(var now: Long = 1_000) : () -> Long { override fun invoke() = now }

    @Test fun tapsEveryHalfSecondGive120() {
        val c = Clock()
        val t = TapTempo(c)
        assertNull(t.tap())
        repeat(5) { c.now += 500; assertEquals(120, t.tap()) }
    }

    @Test fun meanOfTheLastFourIntervals() {
        val c = Clock()
        val t = TapTempo(c)
        t.tap()
        c.now += 1_000; assertEquals(60, t.tap())
        c.now += 400; t.tap()
        c.now += 400; assertEquals(100, t.tap()) // (1000 + 400 + 400) / 3 = 600 ms
        c.now += 400; t.tap()
        c.now += 400; assertEquals(150, t.tap()) // the 1 s interval has dropped out
    }

    @Test fun gapOverTwoSecondsResets() {
        val c = Clock()
        val t = TapTempo(c)
        t.tap(); c.now += 500; t.tap()
        c.now += 2_001
        assertNull(t.tap())
        c.now += 1_000
        assertEquals(60, t.tap())
    }

    @Test fun singleTapIsNull() = assertNull(TapTempo(Clock()).tap())

    @Test fun fastTapsClampTo240() {
        val c = Clock()
        val t = TapTempo(c)
        t.tap(); c.now += 100
        assertEquals(240, t.tap())
    }
}
