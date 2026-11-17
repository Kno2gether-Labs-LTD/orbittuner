package dev.avijit.orbittuner.chordpro

import dev.avijit.orbittuner.music.Accidental
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChordProTest {
    private fun fixture(name: String): String =
        javaClass.getResource("/chordpro/$name")!!.readText()

    private val fixtures = listOf(
        "amazing-grace.cho", "unknown-directives.cho", "unicode.cho", "no-title.cho",
        "chords-over-lyrics.expected.cho",
    )

    private fun lyricsText(doc: ChordProDoc): String =
        doc.sections.flatMap { it.lines }.filterIsInstance<Line.Lyrics>()
            .filter { it.segments.isNotEmpty() }
            .joinToString("\n") { l -> l.segments.joinToString("") { it.text } }

    @Test fun parsesAmazingGrace() {
        val doc = ChordPro.parse(fixture("amazing-grace.cho"))
        assertEquals("Amazing Grace", doc.meta["title"])
        assertEquals("John Newton", doc.meta["artist"])
        assertEquals("G", doc.meta["key"])
        assertEquals("80", doc.meta["tempo"])
        assertEquals("3/4", doc.meta["time"])
        assertEquals(listOf("G", "G7", "C", "Em", "D"), doc.chords)
        assertTrue(lyricsText(doc).contains("Amazing grace how sweet the sound"))
        assertFalse(lyricsText(doc).contains("["))
        assertEquals(
            listOf(SectionType.VERSE to "Verse 1", SectionType.VERSE to "Verse 2"),
            doc.sections.map { it.type to it.label },
        )
        assertEquals(
            Line.Lyrics(
                listOf(
                    Segment("G", "Amazing "), Segment("G7", "grace how "),
                    Segment("C", "sweet the "), Segment("G", "sound"),
                ),
            ),
            doc.sections[0].lines[0],
        )
    }

    @Test fun shortDirectivesAndComment() {
        val doc = ChordPro.parse("{t: Song}\n{st: Someone}\n{c: Intro}\n[C]la")
        assertEquals(mapOf("title" to "Song", "subtitle" to "Someone", "artist" to "Someone"), doc.meta)
        assertEquals(Line.Comment("Intro"), doc.sections[0].lines[0])
    }

    @Test fun explicitArtistWins() {
        assertEquals("C", ChordPro.parse("{title: A}\n{subtitle: B}\n{artist: C}").meta["artist"])
    }

    @Test fun unknownDirectivesAndUnclosedTab() {
        val doc = ChordPro.parse(fixture("unknown-directives.cho"))
        val all = doc.sections.flatMap { it.lines }
        assertTrue(Line.Directive("x_custom", "foo") in all)
        assertTrue(Line.Comment("Intro") in all)
        val tab = doc.sections.last()
        assertEquals(SectionType.TAB, tab.type)
        assertEquals(4, tab.lines.size)
        assertTrue(tab.lines.all { it is Line.Tab })
        assertNull(doc.meta["x_custom"])
    }

    @Test fun pathologicalInputIsFastAndNeverThrows() {
        val srcs = listOf("{a" + " ".repeat(50000) + "x", "{a" + " ".repeat(50000) + "x}", "{" + " ".repeat(50000), "[" + "A".repeat(50000))
        for (s in srcs) {
            val t = System.nanoTime()
            ChordPro.parse(s)
            assertTrue((System.nanoTime() - t) / 1_000_000 < 500)
        }
        for (s in listOf("{", "}{", "[", "[]", "{start_of_chorus", "{end_of_verse}", "]]][[[", "\u0000\n\n")) ChordPro.parse(s)
    }

    @Test fun capsLinesAndDocument() {
        val doc = ChordPro.parse("{title: X}\n" + "a".repeat(5000))
        assertEquals(2000, lyricsText(doc).length)
        ChordPro.parse("a\n".repeat(200000))
        val big = ChordPro.parse("a".repeat(300000).chunked(1000).joinToString("\n"))
        assertTrue(big.sections.flatMap { it.lines }.size <= 200)
    }

    @Test fun hashLinesSkippedBlankLinesBecomeParagraphBreaks() {
        val doc = ChordPro.parse("{title: X}\n# note\n\n[C]hi\n\n\n[G]yo\n\n")
        assertEquals("hi\nyo", lyricsText(doc))
        assertEquals(
            listOf(
                Line.Lyrics(listOf(Segment("C", "hi"))),
                Line.Lyrics(emptyList()),
                Line.Lyrics(listOf(Segment("G", "yo"))),
            ),
            doc.sections[0].lines,
        )
    }

    @Test fun unclosedVerseRunsToEnd() {
        val doc = ChordPro.parse("{title: X}\n{start_of_verse: V}\n[C]one\n[G]two")
        assertEquals(1, doc.sections.size)
        assertEquals(SectionType.VERSE, doc.sections[0].type)
        assertEquals("V", doc.sections[0].label)
        assertEquals(2, doc.sections[0].lines.size)
    }

    @Test fun newStartClosesOpenSection() {
        val doc = ChordPro.parse("{soc}\n[C]a\n{sob: B}\n[G]b")
        assertEquals(listOf(SectionType.CHORUS, SectionType.BRIDGE), doc.sections.map { it.type })
        assertNull(doc.sections[0].label)
    }

    @Test fun chordAtEndOfLineAndCrlf() {
        val doc = ChordPro.parse("{title: X}\r\nHello [C]\r\n")
        assertEquals(
            Line.Lyrics(listOf(Segment(null, "Hello "), Segment("C", ""))),
            doc.sections[0].lines[0],
        )
    }

    @Test fun braceInsideDirectiveValueMakesLyrics() {
        val doc = ChordPro.parse("{c: a}b}")
        assertTrue(doc.sections[0].lines[0] is Line.Lyrics)
    }

    @Test fun transposeAmazingGrace() {
        val doc = ChordPro.parse(fixture("amazing-grace.cho"))
        val up = ChordPro.transpose(doc, 2, Accidental.Sharp)
        assertEquals("A", up.chords.first())
        assertEquals(listOf("A", "A7", "D", "F#m", "E"), up.chords)
        assertEquals("A", up.meta["key"])
        assertEquals("Amazing Grace", up.meta["title"])
    }

    @Test fun transposeLeavesUnknownTokens() {
        val doc = ChordPro.parse("[N.C.]x [Dog]y")
        assertEquals(doc, ChordPro.transpose(doc, 3, Accidental.Flat))
    }

    @Test fun roundTripsEveryFixture() {
        for (f in fixtures) {
            val doc = ChordPro.parse(fixture(f))
            assertEquals(f, doc, ChordPro.parse(ChordPro.serialize(doc)))
        }
    }

    @Test fun roundTripsTransposedAndEdgeCases() {
        val srcs = listOf(
            "[C][G]x\n[ ]\nfoo[ ]bar\nx[ [G]y\n{soc}\n{c: hi}\n\n[C]a\n{end_of_chorus}\nplain\n{sov}\n{sob}\n{x: }\n{sot}\n\n a\n\n",
            "{title: T}\n[C]a\n{eoc}\n[D]b\n{eov}\n[E]c",
        )
        for (s in srcs) {
            val doc = ChordPro.parse(s)
            assertEquals(doc, ChordPro.parse(ChordPro.serialize(doc)))
        }
        val t = ChordPro.transpose(ChordPro.parse(fixture("unicode.cho")), 5, Accidental.Flat)
        assertEquals(t, ChordPro.parse(ChordPro.serialize(t)))
        assertNotNull(t.meta["key"])
    }

    @Test fun roundTripsEmptyChordMarkersAndSpecialLeads() {
        for (s in listOf("[C]a[ ]b", "[ ]#x", "[ ]{c: x}", "a ]b [c", "[C]a[b", "{1x} sing", "[ ]  \n[C]x", "  [ ]#y")) {
            val doc = ChordPro.parse(s)
            assertEquals(s, doc, ChordPro.parse(ChordPro.serialize(doc)))
        }
        assertEquals(listOf(Segment("C", "ab")), (ChordPro.parse("[C]a[ ]b").sections[0].lines[0] as Line.Lyrics).segments)
        assertEquals(listOf(Segment(null, "#x")), (ChordPro.parse("[ ]#x").sections[0].lines[0] as Line.Lyrics).segments)
        assertEquals(listOf(Segment(null, "{1x} sing")), (ChordPro.parse("{1x} sing").sections[0].lines[0] as Line.Lyrics).segments)
    }

    /** Intentional difference from the service: a line holding only "[ ]" is a blank line, not an empty lyric. */
    @Test fun emptyChordOnlyLineIsBlankLine() {
        val doc = ChordPro.parse("[C]a\n[ ]\n[G]b")
        assertEquals(Line.Lyrics(emptyList()), doc.sections[0].lines[1])
    }

    @Test fun capsDoNotSplitSurrogatePairs() {
        val line = ChordPro.parse("a".repeat(1999) + "\uD83C\uDFB8tail").sections[0].lines[0] as Line.Lyrics
        assertEquals(1999, line.segments.single().text.length)
        val one = ChordPro.parse("x\n".repeat(99_998) + "a" + "\uD83C\uDFB8".repeat(10))
        val last = (one.sections[0].lines.last() as Line.Lyrics).segments.single().text
        assertFalse(last.last().isHighSurrogate())
    }

    @Test fun unicodeFixtureIsActuallyNonAscii() {
        assertTrue(fixture("unicode.cho").any { it.code > 127 })
    }
}
