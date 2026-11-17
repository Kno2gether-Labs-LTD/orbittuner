package dev.avijit.orbittuner.songview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepScreenAwakeTest {
    @Test fun aPlayingMetronomeKeepsTheScreenOn() {
        assertTrue(keepScreenAwake(scrolling = false, setting = false, metronomePlaying = true))
        assertTrue(keepScreenAwake(true, false, false))
        assertTrue(keepScreenAwake(false, true, false))
        assertFalse(keepScreenAwake(false, false, false))
    }
}
