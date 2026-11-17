package dev.avijit.orbittuner.backing

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.avijit.orbittuner.AppGraph
import dev.avijit.orbittuner.metronome.findActivity
import dev.avijit.orbittuner.songview.ChordDiagram
import dev.avijit.orbittuner.songview.DataStoreDiagramPrefs
import dev.avijit.orbittuner.songview.DiagramInstrument
import dev.avijit.orbittuner.songview.diagramColors
import dev.avijit.orbittuner.songview.rememberShapes
import dev.avijit.orbittuner.tuner.rememberReduceMotion
import dev.avijit.orbittuner.ui.theme.Literata
import dev.avijit.orbittuner.ui.theme.OrbitTunerTheme
import dev.avijit.orbittuner.ui.theme.SchibstedGrotesk

/** The Tools tab's backing-tracks pane, on the app's one engine. */
@Composable
fun BackingRoute(graph: AppGraph, modifier: Modifier = Modifier) {
    val vm: BackingViewModel = viewModel { BackingViewModel(graph.backing, graph.prefs) }
    BackingHost(vm, modifier)
}

/**
 * The lesson list, or the player for the open lesson. Like the metronome, a track never plays on in the background:
 * leaving the app (ON_STOP) or this pane stops it, but a rotation or Fold unfold (a configuration change) does not.
 * The screen stays on while it plays; Back from the player returns to the list (and stops).
 */
@Composable
internal fun BackingHost(vm: BackingViewModel, modifier: Modifier = Modifier) {
    val activity = LocalContext.current.findActivity()
    fun leave() { if (activity?.isChangingConfigurations != true) vm.stop() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { leave() }
    DisposableEffect(vm) { onDispose { leave() } }

    val sel by vm.selection.collectAsStateWithLifecycle()
    val engine by vm.engineState.collectAsStateWithLifecycle()
    val shown = BackingViewModel.display(sel, engine)
    val view = LocalView.current
    DisposableEffect(shown.playing) {
        view.keepScreenOn = shown.playing
        onDispose { view.keepScreenOn = false }
    }
    BackHandler(enabled = sel.lesson != null) { vm.close() }

    val reduceMotion = rememberReduceMotion()
    Crossfade(sel.lesson, modifier, animationSpec = if (reduceMotion) snap() else tween(180), label = "backing") { lesson ->
        if (lesson == null) LessonList(onOpen = vm::open, modifier = Modifier.fillMaxSize())
        else Player(
            lesson, sel, shown,
            onBack = vm::close, onToggle = vm::toggle, onKey = vm::shiftKey, onTempo = vm::nudgeTempo, onChords = vm::setChords,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

// ---------------------------------------------------------------- lesson list

private val LEVEL_LINES = mapOf(
    Level.Easy to "Open chords and steady strums.",
    Level.Medium to "Blues, minor keys and a first taste of swing.",
    Level.Advanced to "Faster, jazzier, and a bar of seven.",
)

@Composable
private fun LessonList(onOpen: (String) -> Unit, modifier: Modifier) {
    val oc = OrbitTunerTheme.colors
    val groups = remember { Level.entries.map { level -> level to Lessons.all.filter { it.level == level } } }
    LazyColumn(
        modifier.testTag("lessons"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Text(
                "Play along with a band that waits for you. Each lesson loops until you stop it.",
                style = MaterialTheme.typography.bodyMedium,
                color = oc.muted,
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(top = 4.dp, bottom = 6.dp),
            )
        }
        groups.forEach { (level, lessons) ->
            item(key = "level-$level") { LevelHeader(level) }
            // One card per level: six rows is cheap, and a single rounded clip keeps every row's touch area whole.
            item(key = "lessons-$level") {
                Column(
                    Modifier.widthIn(max = 560.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface),
                ) {
                    lessons.forEachIndexed { i, l -> LessonRow(l, number = i + 1, last = i == lessons.lastIndex, onOpen = onOpen) }
                }
            }
        }
    }
}

@Composable
private fun LevelHeader(level: Level) {
    val oc = OrbitTunerTheme.colors
    Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(top = 26.dp, bottom = 12.dp, start = 4.dp)) {
        Text(
            level.name,
            style = TextStyle(fontFamily = Literata, fontWeight = FontWeight.Medium, fontSize = 26.sp, lineHeight = 30.sp),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.testTag("level-${level.name}").semantics { heading() },
        )
        Text(LEVEL_LINES.getValue(level), style = MaterialTheme.typography.bodySmall, color = oc.muted, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun LessonRow(lesson: Lesson, number: Int, last: Boolean, onOpen: (String) -> Unit) {
    val oc = OrbitTunerTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .clickable(role = Role.Button) { onOpen(lesson.id) }
                .testTag("lesson-${lesson.id}")
                .padding(start = 8.dp, end = 18.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                number.toString(),
                style = TextStyle(fontFamily = Literata, fontStyle = FontStyle.Italic, fontSize = 17.sp, fontFeatureSettings = "tnum"),
                color = oc.faint,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(36.dp).clearAndSetSemantics {},
            )
            Column(Modifier.weight(1f)) {
                Text(lesson.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                Text(
                    "${lesson.groove.label} · ${tempoLabel(lesson, lesson.bpm)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = oc.muted,
                    modifier = Modifier.padding(top = 2.dp).semantics {
                        contentDescription = "${lesson.groove.label}, ${tempoDescription(lesson, lesson.bpm)}"
                    },
                )
            }
            Text(
                lesson.key,
                style = TextStyle(fontFamily = Literata, fontWeight = FontWeight.Medium, fontSize = 20.sp),
                color = oc.chord,
                modifier = Modifier.padding(start = 12.dp).semantics { contentDescription = "in ${lesson.key}" },
            )
        }
        if (!last) Box(Modifier.padding(start = 44.dp).fillMaxWidth().height(1.dp).background(oc.line))
    }
}

// ---------------------------------------------------------------- player

@Composable
private fun Player(
    lesson: Lesson,
    sel: BackingViewModel.Selection,
    shown: BackingViewModel.Display,
    onBack: () -> Unit,
    onToggle: () -> Unit,
    onKey: (Int) -> Unit,
    onTempo: (Int) -> Unit,
    onChords: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val oc = OrbitTunerTheme.colors
    BoxWithConstraints(modifier) {
        val short = maxHeight < 680.dp
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                PlayerHeader(lesson, onBack)
                Spacer(Modifier.height(if (short) 0.dp else 4.dp))
                Stage(lesson, shown, short)
                Spacer(Modifier.height(if (short) 14.dp else 22.dp))
                PlayButton(shown.playing, onToggle, size = if (short) 64.dp else 76.dp)
                Spacer(Modifier.height(if (short) 18.dp else 26.dp))
                Controls(lesson, sel, onKey, onTempo, onChords)
                Practice(lesson, sel.keyOffset, Modifier.padding(top = 26.dp))
            }
        }
    }
}

@Composable
private fun PlayerHeader(lesson: Lesson, onBack: () -> Unit) {
    val oc = OrbitTunerTheme.colors
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onBack)
                .semantics { contentDescription = "Back to lessons" },
            contentAlignment = Alignment.Center,
        ) {
            val ink = MaterialTheme.colorScheme.onBackground
            Canvas(Modifier.size(18.dp)) {
                val w = size.width
                val p = Path().apply { moveTo(w * 0.62f, w * 0.14f); lineTo(w * 0.26f, w * 0.5f); lineTo(w * 0.62f, w * 0.86f) }
                drawPath(p, ink, style = Stroke(w * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        Column(Modifier.weight(1f).padding(start = 6.dp)) {
            Text(
                "${lesson.level.name} · ${lesson.groove.label}".uppercase(),
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.1.em),
                color = oc.muted,
            )
            Text(
                lesson.title,
                style = TextStyle(fontFamily = Literata, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { heading() },
            )
        }
    }
}

/** Big chord, what comes next, the beat (or the count-in), the bar, and the chord's shape. */
@Composable
private fun Stage(lesson: Lesson, shown: BackingViewModel.Display, short: Boolean) {
    val oc = OrbitTunerTheme.colors
    val reduceMotion = rememberReduceMotion()
    val beats = lesson.timeSig.beats
    val chordSize = chordSize(shown.chord, if (short) 96.sp else 124.sp)
    Text(
        shown.chord,
        style = TextStyle(
            fontFamily = Literata, fontWeight = FontWeight.Medium, fontSize = chordSize, lineHeight = chordSize,
            letterSpacing = (-0.02).em,
        ),
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 1,
        modifier = Modifier.testTag("chord").semantics { contentDescription = "Now ${shown.chord}" },
    )
    // The change is coming: on the bar's last beat, "Next" lights up so a beginner can move their hand in time.
    val changing = shown.playing && !shown.countIn && shown.beat == beats - 1 && shown.nextChord != shown.chord
    val nextColor by animateColorAsState(
        if (changing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
        animationSpec = if (reduceMotion || changing) snap() else tween(220),
        label = "next",
    )
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = if (changing) nextColor else oc.muted)) { append("Next: ") }
            withStyle(SpanStyle(color = nextColor, fontFamily = Literata, fontWeight = FontWeight.SemiBold)) { append(shown.nextChord) }
        },
        style = TextStyle(fontFamily = SchibstedGrotesk, fontSize = 22.sp, lineHeight = 28.sp),
        modifier = Modifier.padding(top = if (short) 2.dp else 6.dp).testTag("next-chord"),
    )
    Box(Modifier.padding(top = if (short) 14.dp else 22.dp).height(34.dp), contentAlignment = Alignment.Center) {
        if (shown.countIn) CountIn(beats, shown.beat) else Dots(beats, if (shown.playing) shown.beat else -1)
    }
    Text(
        when {
            shown.countIn -> "Count-in"
            shown.playing -> "Bar ${shown.bar + 1} of ${lesson.bars.size}"
            else -> "${lesson.bars.size} bars, ${lesson.timeSig}, round and round"
        },
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.04.em, fontFeatureSettings = "tnum"),
        color = oc.faint,
        modifier = Modifier.padding(top = 6.dp),
    )
    if (lesson.groove != Groove.Drone) {
        Box(Modifier.padding(top = if (short) 10.dp else 18.dp).height(if (short) 78.dp else 98.dp), contentAlignment = Alignment.Center) {
            val prefs = DataStoreDiagramPrefs.get(LocalContext.current)
            val chosen by prefs.instrument.collectAsState(null)
            val instrument = chosen ?: DiagramInstrument.Guitar
            val shapes = rememberShapes(instrument)
            if (shown.chord.isNotEmpty()) ChordDiagram(shown.chord, instrument, shapes, if (short) 70.dp else 88.dp, diagramColors())
        }
    }
}

/** Long names (Bbmaj7) shrink so they stay on one line. */
private fun chordSize(chord: String, base: TextUnit): TextUnit = when {
    chord.length <= 2 -> base
    chord.length == 3 -> base * 0.86f
    chord.length == 4 -> base * 0.74f
    else -> base * 0.62f
}

/** How a bar's beats group: 6/8 is two threes, 7/8 is 2+2+3 (as the groove plays it). */
private fun groups(beats: Int): List<Int> = when (beats) {
    6 -> listOf(3, 3)
    7 -> listOf(2, 2, 3)
    else -> listOf(beats)
}

/** One dot per beat, grouped as the meter feels; the beat being heard lights up, the bar's first one in green. */
@Composable
private fun Dots(beats: Int, beat: Int) {
    val oc = OrbitTunerTheme.colors
    val reduceMotion = rememberReduceMotion()
    Row(horizontalArrangement = Arrangement.spacedBy(26.dp), verticalAlignment = Alignment.CenterVertically) {
        var index = 0
        groups(beats).forEach { n ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(n) { j ->
                    val i = index++
                    val on = beat == i
                    val color by animateColorAsState(
                        when {
                            !on -> oc.raised
                            i == 0 -> OrbitTunerTheme.tunerColors.inTune
                            else -> MaterialTheme.colorScheme.primary
                        },
                        animationSpec = if (reduceMotion || on) snap() else tween(140),
                        label = "dot",
                    )
                    val scale by animateFloatAsState(
                        if (on && !reduceMotion) 1.25f else 1f,
                        animationSpec = if (reduceMotion || on) snap() else tween(140),
                        label = "dot-scale",
                    )
                    Box(
                        Modifier
                            .testTag("beat-dot")
                            .size(if (i == 0) 20.dp else if (j == 0) 16.dp else 13.dp)
                            .scale(scale)
                            .clip(CircleShape)
                            .background(color),
                    )
                }
            }
        }
    }
}

/** "1 2 3 4" (or the meter's count): the beat just heard is lit, the ones gone stay, the ones to come are faint. */
@Composable
private fun CountIn(beats: Int, beat: Int) {
    val oc = OrbitTunerTheme.colors
    Row(
        Modifier.testTag("count-in").semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(if (beats > 4) 16.dp else 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until beats) {
            Text(
                (i + 1).toString(),
                style = TextStyle(fontFamily = Literata, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 32.sp),
                color = when {
                    i == beat -> MaterialTheme.colorScheme.primary
                    i < beat -> oc.muted
                    else -> oc.faint
                },
            )
        }
    }
}

@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit, size: Dp) {
    val fill = MaterialTheme.colorScheme.primary
    val glyph = MaterialTheme.colorScheme.onPrimary
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(fill)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = if (playing) "Stop backing track" else "Play backing track"
                stateDescription = if (playing) "Playing" else "Stopped"
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 0.34f)) {
            val w = this.size.width
            if (playing) {
                val side = w * 0.78f
                val o = (w - side) / 2
                drawRoundRect(glyph, Offset(o, o), Size(side, side), CornerRadius(side * 0.14f))
            } else {
                val p = Path().apply {
                    moveTo(w * 0.18f, w * 0.04f)
                    lineTo(w * 0.98f, w * 0.5f)
                    lineTo(w * 0.18f, w * 0.96f)
                    close()
                }
                drawPath(p, glyph)
                drawPath(p, glyph, style = Stroke(width = w * 0.08f, join = StrokeJoin.Round))
            }
        }
    }
}

// ---------------------------------------------------------------- controls

@Composable
private fun Controls(
    lesson: Lesson,
    sel: BackingViewModel.Selection,
    onKey: (Int) -> Unit,
    onTempo: (Int) -> Unit,
    onChords: (Boolean) -> Unit,
) {
    val oc = OrbitTunerTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        StepRow(
            label = "Key",
            note = if (sel.keyOffset == 0) null else "Lesson: ${lesson.key} · " + (if (sel.keyOffset > 0) "+" else "−") + "${kotlin.math.abs(sel.keyOffset)}",
            value = lesson.keyIn(sel.keyOffset), valueTag = "key", valueDescription = "Key ${lesson.keyIn(sel.keyOffset)}",
            minus = "−", minusDescription = "Key down", minusEnabled = sel.keyOffset > Backing.MIN_KEY,
            plus = "+", plusDescription = "Key up", plusEnabled = sel.keyOffset < Backing.MAX_KEY,
            onMinus = { onKey(-1) }, onPlus = { onKey(+1) },
        )
        Divider()
        StepRow(
            label = "Tempo",
            note = if (sel.bpm == lesson.bpm) null else "Lesson: ${tempoLabel(lesson, lesson.bpm)}",
            value = tempoLabel(lesson, sel.bpm), valueTag = "tempo", valueDescription = tempoDescription(lesson, sel.bpm),
            minus = "−5", minusDescription = "Slower by 5", minusEnabled = sel.bpm > Backing.MIN_BPM,
            plus = "+5", plusDescription = "Faster by 5", plusEnabled = sel.bpm < Backing.MAX_BPM,
            onMinus = { onTempo(-5) }, onPlus = { onTempo(+5) },
        )
        // The drone is the whole track: nothing to switch off.
        if (lesson.groove != Groove.Drone) {
            Divider()
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Chords", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        if (sel.chords) "Turn off to strum them yourself" else "Drums and bass only",
                        style = MaterialTheme.typography.bodySmall, color = oc.muted,
                    )
                }
                Switch(
                    checked = sel.chords, onCheckedChange = onChords,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = OrbitTunerTheme.tunerColors.inTune, checkedThumbColor = MaterialTheme.colorScheme.background,
                        uncheckedTrackColor = oc.raised, uncheckedBorderColor = oc.line, uncheckedThumbColor = oc.muted,
                    ),
                    modifier = Modifier.semantics { contentDescription = "Chords" },
                )
            }
        }
    }
}

@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(OrbitTunerTheme.colors.line))

@Composable
private fun StepRow(
    label: String, note: String?,
    value: String, valueTag: String, valueDescription: String,
    minus: String, minusDescription: String, minusEnabled: Boolean,
    plus: String, plusDescription: String, plusEnabled: Boolean,
    onMinus: () -> Unit, onPlus: () -> Unit,
) {
    val oc = OrbitTunerTheme.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = oc.muted)
        }
        StepButton(minus, minusDescription, minusEnabled, onMinus)
        Text(
            value,
            style = TextStyle(
                fontFamily = Literata, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 24.sp, fontFeatureSettings = "tnum",
            ),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.width(92.dp).testTag(valueTag).semantics { contentDescription = valueDescription },
        )
        StepButton(plus, plusDescription, plusEnabled, onPlus)
    }
}

@Composable
private fun StepButton(label: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    val oc = OrbitTunerTheme.colors
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .border(1.dp, oc.line, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = TextStyle(fontFamily = SchibstedGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, fontFeatureSettings = "tnum"),
            color = if (enabled) MaterialTheme.colorScheme.onBackground else oc.faint,
        )
    }
}

// ---------------------------------------------------------------- practice notes

@Composable
private fun Practice(lesson: Lesson, keyOffset: Int, modifier: Modifier) {
    val oc = OrbitTunerTheme.colors
    Column(modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text(
            "WHAT TO PRACTISE",
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.1.em),
            color = oc.muted,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            lesson.practice,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            "SOLO WITH",
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.1.em),
            color = oc.muted,
            modifier = Modifier.padding(top = 22.dp).semantics { heading() },
        )
        Text(
            lesson.scaleHintIn(keyOffset),
            style = TextStyle(fontFamily = Literata, fontStyle = FontStyle.Italic, fontSize = 20.sp, lineHeight = 26.sp),
            color = oc.chord,
            modifier = Modifier.padding(top = 6.dp).testTag("scale-hint"),
        )
        if (keyOffset != 0) {
            Text(
                "(chords named in the lesson's key, ${lesson.key})",
                style = MaterialTheme.typography.bodySmall,
                color = oc.muted,
                modifier = Modifier.padding(top = 6.dp).testTag("practice-key-note"),
            )
        }
    }
}
