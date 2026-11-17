package dev.avijit.orbittuner.metronome

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.avijit.orbittuner.AppGraph
import dev.avijit.orbittuner.tuner.rememberReduceMotion
import dev.avijit.orbittuner.ui.theme.Literata
import dev.avijit.orbittuner.ui.theme.OrbitTunerTheme
import dev.avijit.orbittuner.ui.theme.SchibstedGrotesk

/**
 * The Tools tab. The metronome never plays on in the background: leaving the app (ON_STOP) or the tab stops it,
 * which ends the audio thread and releases its track; coming back shows the play button.
 */
@Composable
fun MetronomeRoute(graph: AppGraph, heading: Boolean = true) {
    val vm: MetronomeViewModel = viewModel {
        MetronomeViewModel(graph.metronome, graph.prefs, { SystemClock.elapsedRealtime() })
    }
    // The song sheet shares the engine and may have loaded a song's tempo: show this tab's own values again.
    LaunchedEffect(vm) { vm.restore() }
    // A rotation or Fold unfold recreates the activity (ON_STOP, then dispose) without leaving the app: keep playing.
    val activity = LocalContext.current.findActivity()
    fun leave() { if (activity?.isChangingConfigurations != true) vm.stop() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { leave() }
    DisposableEffect(vm) { onDispose { leave() } }
    // The screen stays on while it plays (it would stop on its own when the screen locks). Only this tab manages
    // it: the song sheet leaves that to the song view.
    val playing by vm.state.collectAsStateWithLifecycle()
    val view = LocalView.current
    DisposableEffect(playing.playing) {
        view.keepScreenOn = playing.playing
        onDispose { view.keepScreenOn = false }
    }
    MetronomeScreen(vm, Modifier.fillMaxSize().statusBarsPadding(), heading = heading)
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Big tempo with its Italian name, beat dots, −5 −1 +1 +5, bar length, accent, tap tempo and a round play button.
 * [compact] is the song view's sheet: no heading, a smaller number, tighter spacing, no full-height layout.
 * [heading] false leaves the "METRONOME" label out (the Tools switch already names it).
 */
@Composable
fun MetronomeScreen(vm: MetronomeViewModel, modifier: Modifier = Modifier, compact: Boolean = false, heading: Boolean = !compact) {
    val s by vm.state.collectAsStateWithLifecycle()

    BoxWithConstraints(modifier) {
        val minHeight = if (compact) 0.dp else maxHeight
        // Short screens (Fold cover, 640 dp phones): a smaller number and tighter gaps keep play on screen.
        val short = maxHeight < 720.dp
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = minHeight)
                .padding(horizontal = 20.dp, vertical = if (compact) 8.dp else 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact || short) 14.dp else 20.dp, Alignment.CenterVertically),
        ) {
            if (heading) {
                Text(
                    "METRONOME",
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.1.em),
                    color = OrbitTunerTheme.colors.muted,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Column(Modifier.widthIn(max = 440.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Readout(s, numberSize = if (compact || short) 80.sp else 112.sp)
                Dots(s, Modifier.padding(top = if (compact || short) 12.dp else 22.dp))
            }
            Steppers(s.bpm, vm::nudge, Modifier.widthIn(max = 440.dp))
            Options(s, onBeats = vm::setBeats, onAccent = vm::setAccent, modifier = Modifier.widthIn(max = 440.dp))
            Row(
                Modifier.widthIn(max = 440.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                TapButton(vm::tap, Modifier.weight(1f))
                PlayButton(s.playing, vm::toggle, size = if (compact || short) 64.dp else 76.dp)
            }
        }
    }
}

// ---------------------------------------------------------------- readout

@Composable
private fun Readout(s: MetronomeState, numberSize: androidx.compose.ui.unit.TextUnit) {
    val oc = OrbitTunerTheme.colors
    val name = Tempo.name(s.bpm)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            s.bpm.toString(),
            style = TextStyle(
                fontFamily = Literata, fontWeight = FontWeight.Medium, fontSize = numberSize,
                lineHeight = numberSize, fontFeatureSettings = "tnum", letterSpacing = (-0.02).em,
            ),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.alignByBaseline().testTag("bpm").semantics {
                contentDescription = "${s.bpm} beats per minute"
                liveRegion = LiveRegionMode.Polite
            },
        )
        Text(
            "BPM",
            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.08.em),
            color = oc.muted,
            modifier = Modifier.alignByBaseline().clearAndSetSemantics {},
        )
    }
    Text(
        name,
        style = TextStyle(fontFamily = Literata, fontStyle = FontStyle.Italic, fontSize = 19.sp, lineHeight = 24.sp),
        color = oc.muted,
    )
    // Kept in the layout while playing (faded out), so nothing jumps when the clicks start.
    Text(
        "Start slow. Speed up once it feels easy.",
        style = MaterialTheme.typography.bodyMedium,
        color = oc.faint,
        modifier = Modifier
            .padding(top = 6.dp)
            .alpha(if (s.playing) 0f else 1f)
            .then(if (s.playing) Modifier.clearAndSetSemantics {} else Modifier),
    )
}

/** One dot per beat; the beat being heard lights up. An accented bar has a larger first dot that lights green. */
@Composable
private fun Dots(s: MetronomeState, modifier: Modifier) {
    val oc = OrbitTunerTheme.colors
    val accentColor = OrbitTunerTheme.tunerColors.inTune
    val beatColor = MaterialTheme.colorScheme.primary
    val reduceMotion = rememberReduceMotion()
    Row(
        modifier.height(30.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until s.beatsPerBar) {
            val on = s.playing && s.beat == i
            val first = i == 0 && s.accent
            val color by animateColorAsState(
                when {
                    !on -> oc.raised
                    first -> accentColor
                    else -> beatColor
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
                    .size(if (first) 22.dp else 16.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

// ---------------------------------------------------------------- controls

@Composable
private fun Steppers(bpm: Int, onNudge: (Int) -> Unit, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        listOf(-5, -1, 1, 5).forEach { d ->
            val enabled = if (d < 0) bpm > Tempo.MIN else bpm < Tempo.MAX
            val label = if (d < 0) "−${-d}" else "+$d"
            val what = (if (d < 0) "Slower by " else "Faster by ") + kotlin.math.abs(d)
            StepButton(label, what, enabled) { onNudge(d) }
        }
    }
}

@Composable
private fun StepButton(label: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    val oc = OrbitTunerTheme.colors
    Box(
        Modifier
            .size(56.dp)
            .clip(CircleShape)
            .border(1.dp, oc.line, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = TextStyle(fontFamily = SchibstedGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, fontFeatureSettings = "tnum"),
            color = if (enabled) MaterialTheme.colorScheme.onBackground else oc.faint,
        )
    }
}

@Composable
private fun Options(s: MetronomeState, onBeats: (Int) -> Unit, onAccent: (Boolean) -> Unit, modifier: Modifier) {
    val oc = OrbitTunerTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        OptionRow("Beats per bar") {
            Row(
                Modifier.selectableGroup().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.background).padding(2.dp),
            ) {
                Tempo.BEAT_CHOICES.forEach { n ->
                    val on = n == s.beatsPerBar
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (on) oc.raised else Color.Transparent)
                            .selectable(selected = on, role = Role.RadioButton, onClick = { onBeats(n) })
                            .testTag("beats-$n")
                            .semantics { contentDescription = "$n beats per bar" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            n.toString(),
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
                            color = if (on) MaterialTheme.colorScheme.onBackground else oc.muted,
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(oc.line))
        OptionRow("Accent the first beat") {
            Switch(
                checked = s.accent, onCheckedChange = onAccent,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = OrbitTunerTheme.tunerColors.inTune, checkedThumbColor = MaterialTheme.colorScheme.background,
                    uncheckedTrackColor = oc.raised, uncheckedBorderColor = oc.line, uncheckedThumbColor = oc.muted,
                ),
                modifier = Modifier.semantics { contentDescription = "Accent the first beat" },
            )
        }
    }
}

@Composable
private fun OptionRow(label: String, value: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = OrbitTunerTheme.colors.muted, modifier = Modifier.weight(1f))
        value()
    }
}

/**
 * Counts on finger-down, not on release, so the tempo follows the taps themselves. Screen readers and tests use
 * its click action.
 */
@Composable
private fun TapButton(onTap: () -> Unit, modifier: Modifier) {
    val oc = OrbitTunerTheme.colors
    var pressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .height(56.dp)
            .clip(shape)
            .background(if (pressed) oc.raised else Color.Transparent)
            .border(1.dp, if (pressed) MaterialTheme.colorScheme.primary else oc.line, shape)
            .pointerInput(onTap) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    onTap()
                    waitForUpOrCancellation()
                    pressed = false
                }
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick { onTap(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("Tap tempo", style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp), color = MaterialTheme.colorScheme.onBackground)
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
                contentDescription = if (playing) "Stop metronome" else "Start metronome"
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
                // A triangle nudged right of centre, so it looks centred in the circle.
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
