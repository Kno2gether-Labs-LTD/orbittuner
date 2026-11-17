package dev.avijit.orbittuner.chordpro

import dev.avijit.orbittuner.music.Accidental
import dev.avijit.orbittuner.music.Transposer

data class Segment(val chord: String?, val text: String)

/**
 * A blank line (paragraph break) is `Lyrics(emptyList())`. Unlike the service parser, the app keeps blank lines
 * inside a section (never leading or trailing, never doubled) so songs render with their stanza spacing.
 */
sealed interface Line {
    data class Lyrics(val segments: List<Segment>) : Line
    data class Comment(val text: String) : Line
    data class Tab(val text: String) : Line
    data class Directive(val name: String, val value: String) : Line
}

enum class SectionType { VERSE, CHORUS, BRIDGE, TAB, NONE }

data class Section(val type: SectionType, val label: String?, val lines: List<Line>)

data class ChordProDoc(val meta: Map<String, String>, val sections: List<Section>, val chords: List<String>)

object ChordPro {
    const val MAX_DOC_CHARS = 200_000
    const val MAX_LINE_CHARS = 2_000

    private val ALIASES = mapOf("t" to "title", "st" to "subtitle")
    private val COMMENTS = setOf("c", "comment", "ci", "comment_italic", "cb", "comment_box")
    private val META = setOf(
        "title", "subtitle", "artist", "composer", "lyricist", "album", "year",
        "key", "tempo", "time", "capo", "duration", "copyright",
    )
    private val SECTION_START = mapOf(
        "start_of_verse" to SectionType.VERSE, "sov" to SectionType.VERSE,
        "start_of_chorus" to SectionType.CHORUS, "soc" to SectionType.CHORUS,
        "start_of_bridge" to SectionType.BRIDGE, "sob" to SectionType.BRIDGE,
        "start_of_tab" to SectionType.TAB, "sot" to SectionType.TAB,
    )
    private val SECTION_END = setOf(
        "end_of_verse", "eov", "end_of_chorus", "eoc", "end_of_bridge", "eob", "end_of_tab", "eot",
    )
    private val START_NAME = mapOf(
        SectionType.VERSE to "start_of_verse", SectionType.CHORUS to "start_of_chorus",
        SectionType.BRIDGE to "start_of_bridge", SectionType.TAB to "start_of_tab",
    )
    private val END_NAME = mapOf(
        SectionType.VERSE to "end_of_verse", SectionType.CHORUS to "end_of_chorus",
        SectionType.BRIDGE to "end_of_bridge", SectionType.TAB to "end_of_tab",
    )

    private val BLANK = Line.Lyrics(emptyList())

    private class Builder(val type: SectionType, val label: String?) {
        val lines = ArrayList<Line>()
    }

    /** Linear-time. Never throws. */
    fun parse(src: String): ChordProDoc {
        val meta = LinkedHashMap<String, String>()
        val builders = ArrayList<Builder>()
        val chords = LinkedHashSet<String>()
        var current: Builder? = null

        fun target(): Builder = current ?: Builder(SectionType.NONE, null).also { current = it; builders.add(it) }

        val capped = cap(src, MAX_DOC_CHARS)
        for (rawLine in capped.lines()) {
            val raw = cap(rawLine, MAX_LINE_CHARS)
            val inTab = current?.type == SectionType.TAB
            val trimmed = raw.trim()

            val dir = if (trimmed.length >= 2 && trimmed.first() == '{' && trimmed.last() == '}') directive(trimmed) else null
            if (dir != null) {
                var name = dir.first.lowercase()
                val value = dir.second
                name = ALIASES[name] ?: name
                val start = SECTION_START[name]
                when {
                    start != null -> {
                        current = Builder(start, value.ifEmpty { null }).also { builders.add(it) }
                    }
                    name in SECTION_END -> current = null
                    name in COMMENTS -> target().lines.add(Line.Comment(value))
                    name in META -> meta[name] = value
                    else -> target().lines.add(Line.Directive(name, value))
                }
                continue
            }

            if (inTab) {
                current!!.lines.add(Line.Tab(raw))
                continue
            }
            if (trimmed.startsWith("#")) continue

            val segments = if (trimmed.isEmpty()) emptyList() else parseLyricsLine(raw)
            if (segments.isEmpty()) {
                val b = current
                if (b != null && b.lines.isNotEmpty() && b.lines.last() != BLANK) b.lines.add(BLANK)
                continue
            }
            for (s in segments) s.chord?.let { chords.add(it) }
            target().lines.add(Line.Lyrics(segments))
        }

        val sections = builders.map { b ->
            val lines = b.lines
            if (b.type == SectionType.TAB) {
                while (lines.isNotEmpty() && (lines.last() as? Line.Tab)?.text?.isBlank() == true) lines.removeAt(lines.lastIndex)
            }
            while (lines.isNotEmpty() && lines.last() == BLANK) lines.removeAt(lines.lastIndex)
            Section(b.type, b.label, lines.toList())
        }
        if (meta["subtitle"]?.isNotEmpty() == true && meta["artist"].isNullOrEmpty()) meta["artist"] = meta["subtitle"]!!
        return ChordProDoc(meta, sections, chords.toList())
    }

    /** Truncates to [max] chars without splitting a surrogate pair. */
    private fun cap(s: String, max: Int): String {
        if (s.length <= max) return s
        val end = if (s[max - 1].isHighSurrogate()) max - 1 else max
        return s.substring(0, end)
    }

    /** Equivalent of /^\{\s*([A-Za-z_][\w-]*)(?:\s*:\s*|\s+)?([^}]*)\}$/ without backtracking. [t] is trimmed. */
    private fun directive(t: String): Pair<String, String>? {
        val inner = t.substring(1, t.length - 1)
        if ('}' in inner) return null
        var i = 0
        while (i < inner.length && inner[i].isWhitespace()) i++
        val nameStart = i
        if (i >= inner.length || !(inner[i] in 'A'..'Z' || inner[i] in 'a'..'z' || inner[i] == '_')) return null
        while (i < inner.length && (inner[i] in 'A'..'Z' || inner[i] in 'a'..'z' || inner[i] in '0'..'9' || inner[i] == '_' || inner[i] == '-')) i++
        val name = inner.substring(nameStart, i)
        var rest = inner.substring(i).trimStart()
        if (rest.startsWith(":")) rest = rest.substring(1)
        return name to rest.trim()
    }

    private fun parseLyricsLine(raw: String): List<Segment> {
        val out = ArrayList<Segment>()
        fun add(chord: String?, text: String) {
            val last = out.lastOrNull()
            if (chord == null && last != null) out[out.lastIndex] = Segment(last.chord, last.text + text)
            else if (chord != null || text.isNotEmpty()) out.add(Segment(chord, text))
        }
        var pending: String? = null
        var pos = 0
        while (true) {
            val open = raw.indexOf('[', pos)
            val close = if (open >= 0) raw.indexOf(']', open + 1) else -1
            if (open < 0 || close < 0) break
            val text = raw.substring(pos, open)
            if (pending != null) out.add(Segment(pending, text)) else add(null, text)
            pending = raw.substring(open + 1, close).trim().ifEmpty { null }
            pos = close + 1
        }
        val tail = raw.substring(pos)
        if (pending != null) out.add(Segment(pending, tail)) else add(null, tail)
        // A line of only empty plain text (e.g. "[ ]") is a blank line.
        return if (out.all { it.chord == null && it.text.isBlank() }) emptyList() else out
    }

    fun transpose(doc: ChordProDoc, semitones: Int, acc: Accidental): ChordProDoc {
        val chords = LinkedHashSet<String>()
        val sections = doc.sections.map { sec ->
            sec.copy(
                lines = sec.lines.map { line ->
                    if (line !is Line.Lyrics) line
                    else Line.Lyrics(
                        line.segments.map { s ->
                            val c = s.chord?.let { Transposer.transposeToken(it, semitones, acc) }
                            c?.let { chords.add(it) }
                            Segment(c, s.text)
                        },
                    )
                },
            )
        }
        val meta = LinkedHashMap(doc.meta)
        meta["key"]?.let { meta["key"] = Transposer.transposeKey(it, semitones) }
        return ChordProDoc(meta, sections, chords.toList())
    }

    private fun lyricsText(line: Line.Lyrics): String {
        val body = line.segments.joinToString("") { s -> (s.chord?.let { "[$it]" } ?: "") + s.text }
        // An empty "[ ]" marker keeps a leading '#' or '{' from being read as a comment/directive line.
        val first = line.segments.firstOrNull()
        val lead = first?.takeIf { it.chord == null }?.text?.trimStart()?.firstOrNull()
        return if (lead == '#' || lead == '{') "[ ]$body" else body
    }

    /** ChordPro text that parses back to an equal document. */
    fun serialize(doc: ChordProDoc): String {
        val out = ArrayList<String>()
        for ((k, v) in doc.meta) out.add("{$k: $v}")
        var prevNone = false
        for (sec in doc.sections) {
            if (out.isNotEmpty()) out.add("")
            if (sec.type == SectionType.NONE) {
                // Adjacent implicit sections need an explicit boundary or they would merge on re-parse.
                if (prevNone) out.add("{end_of_verse}")
            } else {
                out.add("{${START_NAME.getValue(sec.type)}${sec.label?.let { ": $it" } ?: ""}}")
            }
            for (line in sec.lines) {
                out.add(
                    when (line) {
                        is Line.Lyrics -> lyricsText(line)
                        is Line.Comment -> "{c: ${line.text}}"
                        is Line.Tab -> line.text
                        is Line.Directive -> "{${line.name}: ${line.value}}"
                    },
                )
            }
            if (sec.type != SectionType.NONE) out.add("{${END_NAME.getValue(sec.type)}}")
            prevNone = sec.type == SectionType.NONE
        }
        return out.joinToString("\n") + "\n"
    }
}
