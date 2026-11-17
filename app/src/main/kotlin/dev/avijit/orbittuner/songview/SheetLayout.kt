package dev.avijit.orbittuner.songview

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.avijit.orbittuner.chordpro.ChordProDoc
import dev.avijit.orbittuner.chordpro.Line
import dev.avijit.orbittuner.chordpro.Section
import dev.avijit.orbittuner.chordpro.SectionType
import dev.avijit.orbittuner.chordpro.Segment
import dev.avijit.orbittuner.ui.theme.Literata
import dev.avijit.orbittuner.ui.theme.OrbitTunerTheme
import dev.avijit.orbittuner.ui.theme.SchibstedGrotesk
import kotlin.math.max

// ---------------------------------------------------------------------------------------------------------------------
// Flow model (pure): a lyric line becomes rows of cells, each a chord over a piece of text.
// ---------------------------------------------------------------------------------------------------------------------

/**
 * One chord-over-text cell at [x]; [width] includes any padding added after the text to make room for the next chord.
 * [hyphen] is set when that padding splits a word, so a faint dash bridges the gap.
 */
internal data class FlowCell(val chord: String?, val text: String, val x: Float, val width: Float, val textWidth: Float, val hyphen: Boolean)

internal data class FlowRow(val cells: List<FlowCell>, val hasChords: Boolean)

/** A word (with trailing spaces) of a segment; only a segment's first word carries its chord. */
private class Piece(val chord: String?, val text: String, val textW: Float, val chordW: Float, val breakAfter: Boolean, val joinsNext: Boolean)

private val WORD = Regex("\\S*\\s*")

/**
 * Lays out [segments] as cells in rows no wider than [maxWidth]. A chord may hang over the following words of its
 * segment; only when it is wider than the text up to the next chord is padding added (chord + [chordGap]), so chords
 * never overlap. Lines wrap only between words (a word split by a chord change stays together, with a hyphen if padded);
 * continuation rows start at [indent]. A single word wider than the row breaks between cells.
 * With [chordsOnly] the text is dropped and chords are spaced by [chordGap].
 */
internal fun flowLine(
    segments: List<Segment>,
    maxWidth: Float,
    indent: Float,
    chordGap: Float,
    chordsOnly: Boolean,
    measureText: (String) -> Float,
    measureChord: (String) -> Float,
): List<FlowRow> {
    val pieces = ArrayList<Piece>()
    val segs = if (chordsOnly) segments.filter { it.chord != null }.map { Segment(it.chord, "") } else segments
    for ((si, seg) in segs.withIndex()) {
        val words = if (seg.text.isEmpty()) listOf("") else WORD.findAll(seg.text).map { it.value }.filter { it.isNotEmpty() }.toList()
        val nextText = segs.getOrNull(si + 1)?.text
        words.forEachIndexed { wi, word ->
            val chord = if (wi == 0) seg.chord else null
            val endsSpace = word.isNotEmpty() && word.last().isWhitespace()
            // Word continues into the next segment (e.g. "re[D]lieved"): no break here.
            val joinsNext = wi == words.lastIndex && word.isNotEmpty() && !endsSpace &&
                !nextText.isNullOrEmpty() && !nextText.first().isWhitespace()
            pieces.add(
                Piece(
                    chord, word, if (word.isEmpty()) 0f else measureText(word),
                    chord?.let { measureChord(it) + chordGap } ?: 0f,
                    breakAfter = chordsOnly || !joinsNext, joinsNext = joinsNext,
                ),
            )
        }
    }
    if (pieces.isEmpty()) return emptyList()

    // Atoms: runs of pieces between break opportunities.
    val atoms = ArrayList<List<Piece>>()
    var cur = ArrayList<Piece>()
    for (p in pieces) {
        cur.add(p)
        if (p.breakAfter) { atoms.add(cur); cur = ArrayList() }
    }
    if (cur.isNotEmpty()) atoms.add(cur)

    val rows = ArrayList<FlowRow>()
    var row = ArrayList<FlowCell>()
    var rowPieces = ArrayList<Piece>()
    var x = 0f
    var chordEnd = 0f // where the last chord on this row (plus gap) ends
    fun newRow() {
        if (row.isNotEmpty()) rows.add(FlowRow(row, row.any { it.chord != null }))
        row = ArrayList()
        rowPieces = ArrayList()
        x = indent
        chordEnd = indent
    }
    /** Right edge after placing [atom] from the current position (trailing spaces may hang past the edge). */
    fun extent(atom: List<Piece>): Float {
        var px = x
        var ce = chordEnd
        var right = px
        for (p in atom) {
            if (p.chord != null) { px = max(px, ce); ce = px + p.chordW; right = max(right, px + p.chordW - chordGap) }
            val visible = if (p === atom.last()) p.text.trimEnd().let { if (it.isEmpty()) 0f else measureText(it) } else p.textW
            right = max(right, px + visible)
            px += p.textW
        }
        return right
    }
    fun place(p: Piece) {
        if (p.chord != null && x < chordEnd && row.isNotEmpty()) {
            val pad = chordEnd - x
            val prev = row.removeAt(row.lastIndex)
            row.add(prev.copy(width = prev.width + pad, hyphen = rowPieces.last().joinsNext && pad > chordGap * 0.5f))
            x = chordEnd
        } else if (p.chord != null) {
            x = max(x, chordEnd)
        }
        row.add(FlowCell(p.chord, p.text, x, p.textW, p.textW, hyphen = false))
        rowPieces.add(p)
        if (p.chord != null) chordEnd = x + p.chordW
        x += p.textW
    }
    for (atom in atoms) {
        if (extent(atom) > maxWidth && row.isNotEmpty()) newRow()
        if (extent(atom) <= maxWidth || row.isNotEmpty()) {
            atom.forEach(::place)
        } else {
            // Wider than a whole row: break between its cells.
            for (p in atom) {
                if (row.isNotEmpty() && extent(listOf(p)) > maxWidth) newRow()
                place(p)
            }
        }
    }
    newRow()
    return rows
}

// ---------------------------------------------------------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------------------------------------------------------

/** Text styles of the sheet at the current zoom. */
@Immutable
data class SheetStyles(
    val lyrics: TextStyle,
    val chords: TextStyle,
    val label: TextStyle,
    val comment: TextStyle,
    val tab: TextStyle,
    val chordColor: Color,
    val textColor: Color,
    val muted: Color,
    val faint: Color,
    val line: Color,
    val chorusPanel: Color,
)

@Composable
fun rememberSheetStyles(base: dev.avijit.orbittuner.ui.theme.SheetType, scale: Float): SheetStyles {
    val c = OrbitTunerTheme.colors
    val text = MaterialTheme.colorScheme.onBackground
    return remember(base, scale, c, text) {
        fun TextStyle.scaled() = copy(fontSize = fontSize * scale, lineHeight = lineHeight * scale)
        SheetStyles(
            lyrics = base.lyrics.scaled().copy(color = text),
            chords = base.chords.scaled().copy(color = c.chord),
            label = TextStyle(fontFamily = Literata, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Medium, fontSize = 15.sp * scale, lineHeight = 20.sp * scale, color = c.muted),
            comment = TextStyle(fontFamily = SchibstedGrotesk, fontStyle = FontStyle.Italic, fontSize = 15.sp * scale, lineHeight = 22.sp * scale, color = c.muted),
            tab = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp * scale, lineHeight = 19.sp * scale, color = text),
            chordColor = c.chord, textColor = text, muted = c.muted, faint = c.faint, line = c.line, chorusPanel = c.chorusPanel,
        )
    }
}

/** Measured row: text layouts ready to draw. */
private class DrawnCell(val x: Float, val chord: TextLayoutResult?, val text: TextLayoutResult?, val hyphenX: Float?)
private class DrawnRow(val y: Float, val chordH: Float, val cells: List<DrawnCell>)
private class LineLayout(val width: Int, val height: Int, val rows: List<DrawnRow>)

/** One lyric line: chords bold above their syllable, wrapped at word boundaries, chords never overlapping. */
@Composable
fun SheetLyricLine(segments: List<Segment>, styles: SheetStyles, chordsOnly: Boolean, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val density = LocalDensity.current
    val holder = remember(segments, styles, chordsOnly) { arrayOfNulls<LineLayout>(1) }
    val plain = remember(segments, chordsOnly) {
        if (chordsOnly) segments.mapNotNull { it.chord }.joinToString("  ") else segments.joinToString("") { it.text }
    }
    Box(
        modifier
            .fillMaxWidth()
            .semantics { text = AnnotatedString(plain) }
            .layout { measurable, constraints ->
                val width = constraints.maxWidth
                val laid = holder[0]?.takeIf { it.width == width } ?: measureLine(segments, styles, chordsOnly, width, measurer, density).also { holder[0] = it }
                val p = measurable.measure(Constraints.fixed(width, laid.height))
                layout(width, laid.height) { p.place(0, 0) }
            }
            .drawBehind {
                val laid = holder[0] ?: return@drawBehind
                for (r in laid.rows) for (c in r.cells) {
                    c.chord?.let { drawText(it, topLeft = Offset(c.x, r.y)) }
                    c.text?.let { drawText(it, topLeft = Offset(c.x, r.y + r.chordH)) }
                    c.hyphenX?.let { hx ->
                        val ty = r.y + r.chordH + (c.text?.size?.height ?: 0) * 0.56f
                        val w = styles.lyrics.fontSize.toPx() * 0.3f
                        drawLine(styles.faint, Offset(hx - w / 2, ty), Offset(hx + w / 2, ty), strokeWidth = (w / 6f).coerceAtLeast(1f))
                    }
                }
            },
    )
}

private fun measureLine(
    segments: List<Segment>, styles: SheetStyles, chordsOnly: Boolean, width: Int,
    measurer: TextMeasurer, density: androidx.compose.ui.unit.Density,
): LineLayout {
    val textCache = HashMap<String, TextLayoutResult>()
    val chordCache = HashMap<String, TextLayoutResult>()
    fun text(s: String) = textCache.getOrPut(s) { measurer.measure(s, styles.lyrics, softWrap = false, maxLines = 1) }
    fun chord(s: String) = chordCache.getOrPut(s) { measurer.measure(s, styles.chords, softWrap = false, maxLines = 1) }
    val (lyricH, chordH, gap, indent) = with(density) {
        listOf(styles.lyrics.lineHeight.toPx(), styles.chords.lineHeight.toPx(), styles.chords.fontSize.toPx() * 0.45f, styles.lyrics.fontSize.toPx() * 1.1f)
    }
    val rows = flowLine(
        segments, width.toFloat(), indent, gap, chordsOnly,
        measureText = { text(it).size.width.toFloat() }, measureChord = { chord(it).size.width.toFloat() },
    )
    var y = 0f
    val drawn = rows.map { row ->
        val ch = if (row.hasChords) chordH else 0f
        val cells = row.cells.map { c ->
            DrawnCell(
                c.x,
                c.chord?.let(::chord),
                if (chordsOnly || c.text.isEmpty()) null else text(c.text),
                if (c.hyphen) c.x + c.textWidth + (c.width - c.textWidth) / 2 else null,
            )
        }
        // Rows with chords but no words (instrumental "[G] [C] [D]" lines) get no empty lyric row.
        val hasText = !chordsOnly && row.cells.any { it.text.isNotBlank() }
        DrawnRow(y, ch, cells).also { y += ch + if (hasText) lyricH else 0f }
    }
    return LineLayout(width, kotlin.math.ceil(y).toInt(), drawn)
}

/** Sections of a doc, with labels, chorus panels, comments and tab blocks. */
@Composable
fun SheetSection(section: Section, styles: SheetStyles, chordsOnly: Boolean, modifier: Modifier = Modifier) {
    val chorus = section.type == SectionType.CHORUS
    val label = section.label ?: when (section.type) {
        SectionType.CHORUS -> "Chorus"
        SectionType.BRIDGE -> "Bridge"
        SectionType.TAB -> "Tab"
        else -> null
    }
    val railColor = styles.chordColor.copy(alpha = 0.55f)
    val body = if (chorus) {
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(styles.chorusPanel)
            .drawBehind {
                val w = 3.dp.toPx()
                drawRoundRect(railColor, Offset(0f, 0f), Size(w, size.height), CornerRadius(w / 2, w / 2))
            }
            .padding(start = 16.dp, end = 14.dp, top = 8.dp, bottom = 10.dp)
    } else modifier.fillMaxWidth()
    Column(body) {
        if (label != null) {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = styles.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!chorus) {
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f).height(1.dp).background(styles.line))
                }
            }
        }
        val blankH = with(LocalDensity.current) { (styles.lyrics.lineHeight.toPx() * 0.5f).toDp() }
        for (block in remember(section.lines) { blocks(section.lines) }) {
            when (block) {
                is List<*> -> TabBlock(block.filterIsInstance<Line.Tab>(), styles)
                is Line.Lyrics ->
                    if (block.segments.isEmpty()) Spacer(Modifier.height(blankH))
                    else SheetLyricLine(block.segments, styles, chordsOnly)
                is Line.Comment -> if (block.text.isNotBlank()) Text(block.text, style = styles.comment, modifier = Modifier.padding(vertical = 4.dp))
                else -> Unit
            }
        }
    }
}

/** Lines with consecutive tab lines grouped into one list (a stave scrolls sideways as one block). */
private fun blocks(lines: List<Line>): List<Any> {
    val out = ArrayList<Any>()
    var tab: ArrayList<Line.Tab>? = null
    for (l in lines) {
        if (l is Line.Tab) {
            (tab ?: ArrayList<Line.Tab>().also { tab = it; out.add(it) }).add(l)
        } else {
            tab = null
            if (l !is Line.Directive) out.add(l)
        }
    }
    return out
}

/** A tab stave: all its lines share one horizontal scroll so the columns stay aligned. */
@Composable
private fun TabBlock(lines: List<Line.Tab>, styles: SheetStyles) {
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp)) {
        for (l in lines) Text(l.text, style = styles.tab, softWrap = false, maxLines = 1)
    }
}

/**
 * The whole sheet. With [columns] = 2 the sections are split into two height-balanced columns divided by a hairline,
 * but only when each column fits within [viewportPx] (a column you'd have to scroll defeats the point); otherwise one
 * column. Sections are measured first at column width to decide.
 */
@Composable
fun SongSheet(doc: ChordProDoc, styles: SheetStyles, chordsOnly: Boolean, columns: Int, viewportPx: Int, modifier: Modifier = Modifier) {
    val sections = doc.sections.filter { s -> s.lines.any { it !is Line.Directive } }
    val content: @Composable () -> Unit = { sections.forEach { SheetSection(it, styles, chordsOnly) } }
    val lineColor = styles.line
    SubcomposeLayout(modifier) { c ->
        val gap = 22.dp.roundToPx()
        val ruleGap = 56.dp.roundToPx()
        fun stack(ps: List<Placeable>): Int = ps.sumOf { it.height } + gap * (ps.size - 1).coerceAtLeast(0)
        if (columns >= 2 && sections.size >= 2 && viewportPx > 0 && c.hasBoundedWidth) {
            val colW = ((c.maxWidth - ruleGap) / 2).coerceAtLeast(0)
            val ps = subcompose("cols", content).map { it.measure(Constraints(maxWidth = colW)) }
            val split = balancedSplit(ps.map { it.height + gap })
            val left = ps.take(split)
            val right = ps.drop(split)
            val h = maxOf(stack(left), stack(right))
            if (h <= viewportPx) {
                val rule = subcompose("rule") { Box(Modifier.background(lineColor)) }.first()
                    .measure(Constraints.fixed(1.dp.roundToPx(), h))
                return@SubcomposeLayout layout(c.maxWidth, h) {
                    var y = 0
                    left.forEach { it.place(0, y); y += it.height + gap }
                    y = 0
                    right.forEach { it.place(colW + ruleGap, y); y += it.height + gap }
                    rule.place(colW + ruleGap / 2, 0)
                }
            }
        }
        val ps = subcompose("single", content).map { it.measure(c.copy(minHeight = 0)) }
        val h = stack(ps)
        layout(c.maxWidth, h) {
            var y = 0
            ps.forEach { it.place(0, y); y += it.height + gap }
        }
    }
}

/** Index splitting [weights] so the first part is as close to half as possible (at least one item each side). */
internal fun balancedSplit(weights: List<Int>): Int {
    val total = weights.sum()
    var acc = 0
    var best = 1
    var bestDiff = Int.MAX_VALUE
    for (i in 1 until weights.size) {
        acc += weights[i - 1]
        val diff = kotlin.math.abs(total - 2 * acc)
        if (diff < bestDiff) { bestDiff = diff; best = i }
    }
    return best
}

/** Sung lines, for the tempo-based speed estimate. */
fun ChordProDoc.lyricLineCount(): Int = sections.sumOf { s -> s.lines.count { it is Line.Lyrics && it.segments.isNotEmpty() } }
