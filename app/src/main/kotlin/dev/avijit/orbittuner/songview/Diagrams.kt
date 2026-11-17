package dev.avijit.orbittuner.songview

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.avijit.orbittuner.music.Accidental
import dev.avijit.orbittuner.music.ChordParser
import dev.avijit.orbittuner.music.Note
import dev.avijit.orbittuner.ui.theme.SchibstedGrotesk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * One fingering from chords-db: [frets] per string, low to high (-1 muted, 0 open, else 1..4 relative to [baseFret]);
 * [barres] are relative fret numbers held by one finger across strings.
 */
data class FretShape(val frets: List<Int>, val fingers: List<Int>, val baseFret: Int, val barres: List<Int>)

/**
 * Guitar and ukulele fingerings from the bundled chords-db (tombatossals/chords-db, MIT, assets/chords/), first
 * position only. Lookup is by root pitch class + chords-db suffix, so enharmonic spellings (A#/Bb) both resolve.
 */
object ChordShapes {
    private val cache = ConcurrentHashMap<DiagramInstrument, Map<String, FretShape>>()

    fun isLoaded(instrument: DiagramInstrument): Boolean = cache.containsKey(instrument)

    /** Blocking (parses a few hundred KB on first use per instrument); call off the main thread. */
    fun load(ctx: Context, instrument: DiagramInstrument): Map<String, FretShape> {
        if (instrument == DiagramInstrument.Piano) return emptyMap()
        return cache.getOrPut(instrument) {
            val file = if (instrument == DiagramInstrument.Guitar) "chords/guitar.json" else "chords/ukulele.json"
            parse(ctx.assets.open(file).bufferedReader().use { it.readText() })
        }
    }

    /** Index of "pc|suffix" -> first position. */
    fun parse(json: String): Map<String, FretShape> {
        val out = HashMap<String, FretShape>()
        val chords = Json.parseToJsonElement(json).jsonObject["chords"]?.jsonObject ?: return out
        for ((_, list) in chords) for (entry in list.jsonArray) {
            val o = entry.jsonObject
            val key = o["key"]?.jsonPrimitive?.content ?: continue
            val suffix = o["suffix"]?.jsonPrimitive?.content ?: continue
            val pc = ChordParser.parse(key)?.root ?: continue
            val pos = o["positions"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
            val frets = pos["frets"]?.jsonArray?.map { it.jsonPrimitive.int } ?: continue
            out.getOrPut("$pc|$suffix") {
                FretShape(
                    frets = frets,
                    fingers = pos["fingers"]?.jsonArray?.map { it.jsonPrimitive.int } ?: List(frets.size) { 0 },
                    baseFret = pos["baseFret"]?.jsonPrimitive?.int ?: 1,
                    barres = pos["barres"]?.jsonArray?.map { it.jsonPrimitive.int } ?: emptyList(),
                )
            }
        }
        return out
    }

    /** First position for [name] (e.g. "F#m7", "C/E"), or null when the library has no such chord. */
    fun find(index: Map<String, FretShape>, name: String): FretShape? {
        val c = ChordParser.parse(unwrap(name)) ?: return null
        // Unknown to our alias table: try the quality verbatim as a chords-db suffix (e.g. "7sus4", "13b9").
        val suffix = ChordTheory.suffix(c.quality) ?: return index["${c.root}|${c.quality}"]
        if (c.bass != null) {
            val slashBase = when (suffix) { "major" -> ""; "minor" -> "m"; else -> null }
            if (slashBase != null) {
                for (acc in Accidental.entries) index["${c.root}|$slashBase/${Note(c.bass).name(acc)}"]?.let { return it }
            }
        }
        return index["${c.root}|$suffix"] ?: index["${c.root}|${c.quality}"] ?: if (suffix == "sus4") index["${c.root}|sus"] else null
    }

    private fun unwrap(name: String) = if (name.length > 2 && name.first() == '(' && name.last() == ')') name.substring(1, name.length - 1) else name
}

/** Chord qualities: chords-db suffix names and chord tones. */
object ChordTheory {
    private val ALIASES = mapOf(
        "" to "major", "M" to "major", "maj" to "major",
        "m" to "minor", "min" to "minor", "-" to "minor", "mi" to "minor",
        "M7" to "maj7", "Maj7" to "maj7", "MAJ7" to "maj7", "ma7" to "maj7", "Δ" to "maj7", "Δ7" to "maj7",
        "M9" to "maj9", "Maj9" to "maj9",
        "min7" to "m7", "-7" to "m7", "mi7" to "m7", "min6" to "m6", "min9" to "m9", "-6" to "m6", "-9" to "m9",
        "°" to "dim", "o" to "dim", "°7" to "dim7", "o7" to "dim7",
        "ø" to "m7b5", "ø7" to "m7b5", "m7(b5)" to "m7b5", "-7b5" to "m7b5", "min7b5" to "m7b5",
        "+" to "aug", "aug5" to "aug", "#5" to "aug", "+7" to "aug7", "7+" to "aug7", "7#5" to "aug7", "7(#5)" to "aug7",
        "sus" to "sus4", "4" to "sus4", "2" to "sus2", "7sus" to "7sus4",
        "add2" to "add9", "madd2" to "madd9", "6/9" to "69", "m6/9" to "m69",
        "mM7" to "mmaj7", "mMaj7" to "mmaj7", "m(maj7)" to "mmaj7", "-maj7" to "mmaj7", "minmaj7" to "mmaj7",
        "7(b9)" to "7b9", "7(#9)" to "7#9", "7(b5)" to "7b5",
    )

    /** Chord tones by chords-db suffix: (semitones above the root, scale degree) pairs; the degree picks the letter. */
    private val TONES: Map<String, List<Pair<Int, Int>>> = run {
        val r = 0 to 1; val M3 = 4 to 3; val m3 = 3 to 3; val P5 = 7 to 5; val d5 = 6 to 5; val A5 = 8 to 5
        val m7 = 10 to 7; val M7 = 11 to 7; val d7 = 9 to 7; val M6 = 9 to 6; val M9 = 2 to 9; val P11 = 5 to 11; val M13 = 9 to 13
        mapOf(
            "major" to listOf(r, M3, P5), "minor" to listOf(r, m3, P5), "dim" to listOf(r, m3, d5), "dim7" to listOf(r, m3, d5, d7),
            "aug" to listOf(r, M3, A5), "sus2" to listOf(r, 2 to 2, P5), "sus4" to listOf(r, 5 to 4, P5), "5" to listOf(r, P5),
            "6" to listOf(r, M3, P5, M6), "m6" to listOf(r, m3, P5, M6), "69" to listOf(r, M3, P5, M6, M9), "m69" to listOf(r, m3, P5, M6, M9),
            "7" to listOf(r, M3, P5, m7), "maj7" to listOf(r, M3, P5, M7), "m7" to listOf(r, m3, P5, m7), "m7b5" to listOf(r, m3, d5, m7),
            "mmaj7" to listOf(r, m3, P5, M7), "7sus4" to listOf(r, 5 to 4, P5, m7), "aug7" to listOf(r, M3, A5, m7), "7b5" to listOf(r, M3, d5, m7),
            "9" to listOf(r, M3, P5, m7, M9), "maj9" to listOf(r, M3, P5, M7, M9), "m9" to listOf(r, m3, P5, m7, M9),
            "add9" to listOf(r, M3, P5, M9), "madd9" to listOf(r, m3, P5, M9), "add11" to listOf(r, M3, P5, P11),
            "11" to listOf(r, M3, P5, m7, M9, P11), "m11" to listOf(r, m3, P5, m7, M9, P11), "13" to listOf(r, M3, P5, m7, M9, M13),
            "maj13" to listOf(r, M3, P5, M7, M9, M13), "7b9" to listOf(r, M3, P5, m7, 1 to 9), "7#9" to listOf(r, M3, P5, m7, 3 to 9),
            "maj7#5" to listOf(r, M3, A5, M7), "maj7b5" to listOf(r, M3, d5, M7), "maj11" to listOf(r, M3, P5, M7, M9, P11),
        )
    }

    /** chords-db suffix for a parsed quality (verbatim suffixes like "m7" pass through), or null if unknown. */
    fun suffix(quality: String): String? {
        val q = quality.trim()
        ALIASES[q]?.let { return it }
        if (q in TONES) return q
        // Parenthesised spellings, e.g. "7(b9)" or "m(maj7)".
        val flat = q.replace("(", "").replace(")", "")
        return ALIASES[flat] ?: flat.takeIf { it in TONES }
    }

    /** Pitch classes of a chord (bass first if it is not already a chord tone), or null when unknown. */
    fun pitchClasses(name: String): List<Int>? {
        val c = ChordParser.parse(unwrapName(name)) ?: return null
        val tones = TONES[suffix(c.quality) ?: return null] ?: return null
        val pcs = tones.map { (c.root + it.first) % 12 }
        return if (c.bass != null && c.bass !in pcs) listOf(c.bass) + pcs else pcs
    }

    /**
     * Chord tones spelled by interval from the root as written, so thirds, fifths and sevenths take the right letters:
     * Gm -> "G B♭ D", Cm7 -> "C E♭ G B♭", F#m7b5 -> "F♯ A C E". A bass not in the chord leads, e.g. "C/B" -> "B C E G".
     */
    fun spelledTones(name: String): List<String>? {
        val token = unwrapName(name)
        val c = ChordParser.parse(token) ?: return null
        val tones = TONES[suffix(c.quality) ?: return null] ?: return null
        val rootLetter = LETTERS.indexOf(token[0].uppercaseChar().let { if (it == 'H') 'B' else it })
        val out = tones.map { (semi, degree) -> spell((rootLetter + degree - 1) % 7, (c.root + semi) % 12) }
        val bass = c.bass ?: return out
        if (out.any { pcOf(it) == bass }) return out
        val slash = token.substringAfterLast('/')
        return listOf(spell(LETTERS.indexOf(slash[0].uppercaseChar().let { if (it == 'H') 'B' else it }), bass)) + out
    }

    private const val LETTERS = "CDEFGAB"
    private val NATURAL = intArrayOf(0, 2, 4, 5, 7, 9, 11)

    private fun spell(letter: Int, pc: Int): String {
        var d = ((pc - NATURAL[letter]) % 12 + 12) % 12
        if (d > 6) d -= 12
        val acc = when (d) { -2 -> "\u266D\u266D"; -1 -> "\u266D"; 1 -> "\u266F"; 2 -> "\uD834\uDD2A"; else -> "" }
        return LETTERS[letter] + acc
    }

    private fun pcOf(spelled: String): Int {
        var pc = NATURAL[LETTERS.indexOf(spelled[0])]
        for (ch in spelled.drop(1)) pc += when (ch) { '\u266D' -> -1; '\u266F' -> 1; else -> 0 }
        if (spelled.contains("\uD834\uDD2A")) pc += 2
        return ((pc % 12) + 12) % 12
    }

    private fun unwrapName(name: String) = if (name.length > 2 && name.first() == '(' && name.last() == ')') name.substring(1, name.length - 1) else name

    fun root(name: String): Int? = ChordParser.parse(name.removePrefix("(").removeSuffix(")"))?.root
}

/** Colours a diagram needs (the sheet passes theme tokens). */
data class DiagramColors(
    val ink: Color, val grid: Color, val accent: Color, val keyWhite: Color, val keyBlack: Color, val label: Color, val bg: Color,
)

/** Fretboard grid: strings vertical (low on the left), [frets] rows, nut or "Nfr" label, dots, barres, open/muted marks. */
@Composable
fun FretDiagram(shape: FretShape, colors: DiagramColors, width: Dp, modifier: Modifier = Modifier, frets: Int = 4) {
    val strings = shape.frets.size
    val measurer = rememberTextMeasurer(8)
    val height = width * 1.1f
    Canvas(modifier.size(width, height)) {
        val padX = size.width * 0.16f
        val top = size.height * 0.2f
        val bottom = size.height * 0.96f
        val gridW = size.width - padX * 2
        val sx = gridW / (strings - 1)
        val fh = (bottom - top) / frets
        val thin = (size.width / 70f).coerceAtLeast(1f)
        for (i in 0 until strings) {
            val x = padX + sx * i
            drawLine(colors.grid, Offset(x, top), Offset(x, bottom), thin)
        }
        for (j in 0..frets) {
            val y = top + fh * j
            val w = if (j == 0 && shape.baseFret == 1) thin * 3.2f else thin
            drawLine(colors.grid, Offset(padX - thin / 2, y), Offset(padX + gridW + thin / 2, y), w, cap = StrokeCap.Round)
        }
        if (shape.baseFret > 1) {
            val label = measurer.measure("${shape.baseFret}", TextStyle(fontFamily = SchibstedGrotesk, fontSize = (fh * 0.6f).toSp(), color = colors.label))
            drawText(label, topLeft = Offset(padX - label.size.width - sx * 0.35f, top + fh / 2 - label.size.height / 2))
        }
        val r = (minOf(sx, fh) * 0.34f)
        for (b in shape.barres) {
            val idx = shape.frets.indices.filter { shape.frets[it] == b }
            if (idx.size < 2) continue
            val y = top + fh * (b - 0.5f)
            val x0 = padX + sx * idx.first()
            val x1 = padX + sx * idx.last()
            drawRoundRect(colors.ink, Offset(x0 - r, y - r), Size(x1 - x0 + r * 2, r * 2), CornerRadius(r, r))
        }
        val mark = top - fh * 0.42f
        shape.frets.forEachIndexed { i, f ->
            val x = padX + sx * i
            when {
                f < 0 -> mutedMark(colors.label, Offset(x, mark), r * 0.7f, thin)
                f == 0 -> drawCircle(colors.label, r * 0.72f, Offset(x, mark), style = Stroke(thin))
                else -> drawCircle(colors.ink, r, Offset(x, top + fh * (f - 0.5f)))
            }
        }
    }
}

private fun DrawScope.mutedMark(color: Color, c: Offset, h: Float, w: Float) {
    drawLine(color, Offset(c.x - h, c.y - h), Offset(c.x + h, c.y + h), w, cap = StrokeCap.Round)
    drawLine(color, Offset(c.x - h, c.y + h), Offset(c.x + h, c.y - h), w, cap = StrokeCap.Round)
}

/** One octave C..B with the chord's pitch classes lit; the root carries a dot. */
@Composable
fun PianoDiagram(pcs: List<Int>, root: Int?, colors: DiagramColors, width: Dp, modifier: Modifier = Modifier) {
    val height = width * 0.62f
    Canvas(modifier.size(width, height)) {
        val whites = intArrayOf(0, 2, 4, 5, 7, 9, 11)
        val blacks = mapOf(1 to 1, 3 to 2, 6 to 4, 8 to 5, 10 to 6) // pc -> white index it sits before
        val kw = size.width / 7f
        val gap = (size.width / 120f).coerceAtLeast(1f)
        val rad = CornerRadius(kw * 0.18f, kw * 0.18f)
        whites.forEachIndexed { i, pc ->
            val lit = pc in pcs
            drawRoundRect(if (lit) colors.accent else colors.keyWhite, Offset(i * kw + gap / 2, 0f), Size(kw - gap, size.height), rad)
            if (pc == root) drawCircle(colors.keyBlack, kw * 0.14f, Offset(i * kw + kw / 2, size.height * 0.84f))
        }
        val bw = kw * 0.62f
        val bh = size.height * 0.6f
        for ((pc, at) in blacks) {
            val lit = pc in pcs
            val x = at * kw - bw / 2
            drawRoundRect(colors.bg, Offset(x - gap, 0f), Size(bw + gap * 2, bh + gap), rad) // separation edge
            drawRoundRect(if (lit) colors.accent else colors.keyBlack, Offset(x, 0f), Size(bw, bh), rad)
            if (pc == root) drawCircle(colors.keyWhite, kw * 0.12f, Offset(x + bw / 2, bh * 0.8f))
        }
    }
}

internal val DiagramLabelStyle = TextStyle(fontFamily = SchibstedGrotesk, fontWeight = FontWeight.Bold, fontSize = 15.sp)
internal val MiniDiagramWidth = 46.dp
