package dev.avijit.orbittuner.backing

import dev.avijit.orbittuner.music.ChordParser
import dev.avijit.orbittuner.songview.ChordTheory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LessonsTest {
    @Test fun eighteenLessonsSixPerLevel() {
        assertEquals(18, Lessons.all.size)
        for (level in Level.entries) assertEquals(level.name, 6, Lessons.all.count { it.level == level })
        assertEquals(18, Lessons.all.map { it.id }.toSet().size)
        // Easy -> Medium -> Advanced, in that order.
        assertEquals(Lessons.all.sortedBy { it.level.ordinal }, Lessons.all)
    }

    @Test fun lessonsAreTheSpecsList() {
        val expected = listOf(
            Triple("Two-chord groove", Groove.Rock8, 70), Triple("Three-chord folk", Groove.Pop, 80),
            Triple("The four chords", Groove.Pop, 76), Triple("Bollywood ballad", Groove.Ballad, 72),
            Triple("Bengali 6/8", Groove.SixEight, 180), Triple("Slow minor", Groove.Ballad, 66),
            Triple("12-bar blues in A", Groove.Shuffle, 90), Triple("Andalusian", Groove.Rock8, 100),
            Triple("Minor pop", Groove.Pop, 96), Triple("ii–V–I in C", Groove.Swing, 100),
            Triple("Sufi groove", Groove.SixEight, 192), Triple("Funk vamp", Groove.Funk, 96),
            Triple("Minor blues with turnaround", Groove.Shuffle, 110), Triple("ii–V–I through keys", Groove.Swing, 140),
            Triple("Fast funk", Groove.Funk, 108), Triple("Rock riffs", Groove.Rock8, 130),
            Triple("Drone for improvisation", Groove.Drone, 70), Triple("7/8 groove", Groove.SevenEight, 210),
        )
        assertEquals(expected, Lessons.all.map { Triple(it.title, it.groove, it.bpm) })
        val firstChords = Lessons.all.map { it.bars.first().split(' ').first() }
        assertEquals(listOf("Em", "G", "G", "C", "D", "Am", "A7", "Am", "Am", "Dm7", "Dm", "E9", "Am7", "Dm7", "Em7", "E", "D", "A"), firstChords)
        assertEquals(TimeSig(6, 8), Lessons.all.first { it.title == "Bengali 6/8" }.timeSig)
        assertEquals(TimeSig(7, 8), Lessons.all.first { it.title == "7/8 groove" }.timeSig)
        assertTrue(Lessons.all.first { it.groove == Groove.Drone }.scaleHint.contains("Bhairav"))
    }

    @Test fun everyChordParsesAndCanBeVoiced() {
        for (l in Lessons.all) {
            assertNotNull(l.key, ChordParser.parse(l.key))
            assertTrue(l.id, l.bars.isNotEmpty())
            for (bar in l.bars) for (token in bar.split(' ')) {
                assertNotNull("${l.id}: $token", ChordParser.parse(token))
                assertNotNull("${l.id}: $token tones", ChordTheory.pitchClasses(token))
            }
            assertTrue(l.id, l.practice.isNotBlank() && l.scaleHint.isNotBlank())
        }
    }

    @Test fun grooveFitsTheLessonsMeter() {
        for (l in Lessons.all) assertEquals(l.id, l.groove.beats, l.timeSig.beats)
        for (g in Groove.entries) {
            val steps = g.beats * g.stepsPerBeat
            for ((kind, line) in g.drums) assertEquals("$g $kind", steps, line.length)
            for ((kind, line) in g.fill) assertEquals("$g fill $kind", steps, line.length)
            assertEquals("$g bass", steps, g.bass.length)
            assertEquals("$g strum", steps, g.strum.length)
        }
    }

    @Test fun scaleHintFollowsTheKey() {
        val two = Lessons.byId("two-chord")!!
        assertEquals("E minor pentatonic", two.scaleHintIn(0))
        assertEquals("F♯ minor pentatonic", two.scaleHintIn(2))
        // Em +1 is Fm (flat key): spelled F, and a flat root would be A♭ not G♯.
        assertEquals("F minor pentatonic", two.scaleHintIn(1))
        val drone = Lessons.byId("drone")!!
        assertEquals(drone.scaleHint, drone.scaleHintIn(0))
        assertEquals("E Bhairav-ish / Dorian", drone.scaleHintIn(2))
        // Every note in a multi-note hint moves; "Dorian"/"Bhairav" letters do not.
        assertEquals("B blues scale (B minor pentatonic + F)", Lessons.byId("blues-in-a")!!.scaleHintIn(2))
        assertEquals("Major scale of each key", Lessons.byId("two-five-one-keys")!!.scaleHintIn(3))
    }

    @Test fun compoundMetersCountEighthsAtLivelyTempos() {
        for (l in Lessons.all.filter { it.countsEighths }) assertTrue(l.id, l.bpm in Backing.MIN_BPM..Backing.MAX_BPM)
    }
}
