package dev.avijit.orbittuner.songview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.avijit.orbittuner.ui.theme.OrbitTunerTheme
import dev.avijit.orbittuner.ui.theme.SchibstedGrotesk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Theme tokens for diagrams. */
@Composable
fun diagramColors(): DiagramColors {
    val c = OrbitTunerTheme.colors
    val scheme = MaterialTheme.colorScheme
    return DiagramColors(
        ink = scheme.onBackground, grid = c.faint, accent = c.chord, keyWhite = scheme.onBackground.copy(alpha = 0.88f),
        keyBlack = scheme.background, label = c.faint, bg = scheme.surface,
    )
}

/** Fingerings index for [instrument], loaded off the main thread (empty until ready, and for piano). */
@Composable
fun rememberShapes(instrument: DiagramInstrument): Map<String, FretShape> {
    val ctx = LocalContext.current.applicationContext
    val shapes by produceState(emptyMap<String, FretShape>(), instrument) {
        value = withContext(Dispatchers.IO) {
            try { ChordShapes.load(ctx, instrument) } catch (_: Exception) { emptyMap() }
        }
    }
    return shapes
}

/** The chord drawn for [instrument]; unknown chords draw nothing (the caller shows the name only). */
@Composable
fun ChordDiagram(name: String, instrument: DiagramInstrument, shapes: Map<String, FretShape>, width: Dp, colors: DiagramColors): Boolean {
    if (instrument == DiagramInstrument.Piano) {
        val pcs = remember(name) { ChordTheory.pitchClasses(name) } ?: return false
        val notes = remember(name) { ChordTheory.spelledTones(name)?.joinToString(" ") }
        PianoDiagram(
            pcs, ChordTheory.root(name), colors, width,
            Modifier.semantics { contentDescription = "Piano diagram for $name" + (notes?.let { ": $it" } ?: "") },
        )
        return true
    }
    val shape = remember(name, shapes) { ChordShapes.find(shapes, name) } ?: return false
    val frets = remember(shape) {
        shape.frets.joinToString(" ") { f -> if (f < 0) "x" else if (f == 0) "0" else (f + shape.baseFret - 1).toString() }
    }
    FretDiagram(
        shape, colors, width,
        Modifier.semantics { contentDescription = "${instrument.name} diagram for $name, frets low to high: $frets" },
    )
    return true
}

/** The song's distinct (transposed) chords as small diagram cards; tapping one opens [onOpen]. */
@Composable
fun ChordStrip(
    chords: List<String>,
    instrument: DiagramInstrument,
    highlight: String?,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
) {
    if (chords.isEmpty()) return
    val shapes = rememberShapes(instrument)
    val colors = diagramColors()
    val oc = OrbitTunerTheme.colors
    LazyRow(
        modifier.testTag("chord-strip"),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(chords, key = { it }) { name ->
            val on = name == highlight
            Column(
                Modifier
                    .width(64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .then(if (on) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier)
                    .clickable(role = Role.Button, onClickLabel = "Show diagram") { onOpen(name) }
                    .semantics { selected = on }
                    .padding(top = 7.dp, bottom = 5.dp, start = 6.dp, end = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(name, style = DiagramLabelStyle, color = oc.chord, maxLines = 1)
                Box(Modifier.heightIn(min = 34.dp), contentAlignment = Alignment.Center) {
                    ChordDiagram(name, instrument, shapes, MiniDiagramWidth, colors)
                }
            }
        }
    }
}

/** Bottom panel with one chord drawn large, an instrument switch and its notes. */
@Composable
fun DiagramSheet(
    name: String,
    instrument: DiagramInstrument,
    onInstrument: (DiagramInstrument) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shapes = rememberShapes(instrument)
    val colors = diagramColors()
    val oc = OrbitTunerTheme.colors
    val tones = remember(name) { ChordTheory.spelledTones(name) }
    Column(
        modifier
            .testTag("diagram-sheet")
            .semantics { paneTitle = "Chord diagram, $name" }
            .fillMaxWidth()
            .shadow(24.dp, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .padding(start = 24.dp, end = 12.dp, top = 10.dp, bottom = 24.dp),
    ) {
        Box(Modifier.fillMaxWidth().padding(end = 12.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(36.dp, 4.dp).clip(CircleShape).background(oc.line))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, fontFamily = SchibstedGrotesk, fontWeight = FontWeight.Bold, fontSize = 34.sp, color = oc.chord)
                if (tones != null) {
                    Text(tones.joinToString("  "), style = MaterialTheme.typography.bodyMedium, color = oc.muted)
                }
            }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close diagram", tint = oc.muted) }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 168.dp, height = 186.dp), contentAlignment = Alignment.Center) {
                val drawn = ChordDiagram(name, instrument, shapes, if (instrument == DiagramInstrument.Piano) 168.dp else 150.dp, colors)
                if (!drawn) {
                    Text(
                        "No diagram for this chord yet",
                        style = MaterialTheme.typography.bodyMedium, color = oc.muted,
                        modifier = Modifier.semantics { contentDescription = "No diagram" },
                    )
                }
            }
            Spacer(Modifier.width(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(end = 12.dp)) {
                DiagramInstrument.entries.forEach { i ->
                    val on = i == instrument
                    Text(
                        i.name,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (on) MaterialTheme.colorScheme.primary else oc.raised)
                            .clickable(role = Role.Tab) { onInstrument(i) }
                            .semantics { selected = on }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
        if (instrument != DiagramInstrument.Piano) {
            Text(
                "Fingerings from chords-db (MIT), first position.",
                style = MaterialTheme.typography.labelSmall, color = oc.faint, modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}
