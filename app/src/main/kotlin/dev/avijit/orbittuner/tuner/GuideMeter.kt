package dev.avijit.orbittuner.tuner

import android.provider.Settings
import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.avijit.orbittuner.BuildConfig
import dev.avijit.orbittuner.ui.theme.Literata
import dev.avijit.orbittuner.ui.theme.OrbitTunerTheme
import dev.avijit.orbittuner.ui.theme.SchibstedGrotesk
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign

/** Meter height for the layout: the approved frames use 230 dp on both phone and tablet. */
val MeterHeight: Dp = 230.dp

/** Guide meter height on short screens (under 640 dp), so the string row stays above the bottom nav. */
val GuideMeterHeightCompact: Dp = 170.dp

/**
 * The beginner meter (concept B): a plain instruction ([line]: "Tighten", "Hold it…"), then a track from "too low" to
 * "too high" with the target slot in the middle. The dot follows [live] on a log-ish scale so the last few cents
 * feel roomy. While the engine reads in tune, a ring traced around the slot fills over [TunerEngine.TUNED_AFTER_MS],
 * so the ring closes as the string's tick and haptic land; once ticked, the dot carries a check.
 *
 * The dot moves in the draw phase only (one redraw per analysis hop, no recomposition); [readout] drives colour,
 * the ring and semantics. With no pitch the dot drifts back to the centre and dims.
 */
@Composable
fun GuideMeter(live: StateFlow<TunerState>, readout: StateFlow<Readout>, line: GuideLine, modifier: Modifier = Modifier) {
    val r by readout.collectAsStateWithLifecycle()
    val tc = OrbitTunerTheme.tunerColors
    val oc = OrbitTunerTheme.colors
    val ink = MaterialTheme.colorScheme.onBackground
    val bg = MaterialTheme.colorScheme.background
    val reduceMotion = rememberReduceMotion()
    val done = line.word == GUIDE_PERFECT
    val holding = r.hasPitch && r.inTune

    val dot = remember { Animatable(0f) }
    val presence = remember { Animatable(0f) }
    val hold = remember { Animatable(0f) }

    LaunchedEffect(live, reduceMotion) {
        live.filterIsInstance<TunerState.Reading>().collectLatest { s ->
            val target = s.cents.toFloat().coerceIn(-50f, 50f)
            if (reduceMotion) dot.snapTo(target)
            else dot.animateTo(target, spring(dampingRatio = 1f, stiffness = 220f), dot.velocity)
        }
    }
    LatencyProbe(live)
    LaunchedEffect(r.hasPitch, reduceMotion) {
        val p = if (r.hasPitch) 1f else 0f
        if (reduceMotion) presence.snapTo(p) else presence.animateTo(p, tween(if (p == 1f) 160 else 420))
        if (!r.hasPitch) dot.snapTo(0f)
    }
    LaunchedEffect(holding, done) {
        when {
            // A string already ticked reads "Perfect" at once: close the ring quickly rather than re-counting.
            done -> hold.animateTo(1f, tween(120))
            // Linear on purpose: the ring is a countdown that must close exactly when the engine ticks the string.
            holding -> hold.animateTo(1f, tween(((1f - hold.value) * TunerEngine.TUNED_AFTER_MS).toInt(), easing = LinearEasing))
            reduceMotion -> hold.snapTo(0f)
            else -> hold.animateTo(0f, tween((hold.value * 400).toInt()))
        }
    }

    val wordColor by animateColorAsState(
        when { !r.hasPitch -> ink; line.good -> tc.inTune; else -> tc.needle },
        tween(if (reduceMotion) 0 else 180), label = "guide-word",
    )
    val dotColor by animateColorAsState(if (line.good) tc.inTune else tc.needle, tween(if (reduceMotion) 0 else 180), label = "guide-dot")

    val measurer = rememberTextMeasurer()
    val endStyle = remember(oc.muted) {
        TextStyle(fontFamily = SchibstedGrotesk, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = oc.muted)
    }
    val low = remember(measurer, endStyle) { measurer.measure("too low", endStyle) }
    val high = remember(measurer, endStyle) { measurer.measure("too high", endStyle) }
    val ring = remember { Path() }
    val trace = remember { Path() }
    val measure = remember { PathMeasure() }
    val check = remember { Path() }

    Column(
        modifier
            .testTag("meter")
            // One node for TalkBack: the instruction is the state ("Tighten", "Hold it…"), the sub line follows.
            .semantics(mergeDescendants = true) {
                contentDescription = "Tuning guide"
                stateDescription = line.word
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            line.word,
            style = TextStyle(fontFamily = Literata, fontWeight = FontWeight.Medium, fontSize = 34.sp, lineHeight = 40.sp),
            color = wordColor, maxLines = 1, textAlign = TextAlign.Center,
        )
        Text(
            line.sub,
            style = MaterialTheme.typography.bodyMedium,
            color = oc.muted, maxLines = 1, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp),
        )
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val w = size.width
            val dotR = 10.dp.toPx()
            val slotH = 2 * (dotR + 5.dp.toPx())
            val gap = 8.dp.toPx()
            // The track, slot and end words form one block, centred in the space under the words.
            val block = slotH + gap + low.size.height
            val y = ((size.height - block) / 2f).coerceAtLeast(0f) + slotH / 2
            val x0 = 20.dp.toPx()
            val x1 = w - 20.dp.toPx()
            val mid = w / 2
            val half = (x1 - x0) / 2
            val p = presence.value

            drawLine(ink.copy(alpha = 0.07f), Offset(x0, y), Offset(x1, y), strokeWidth = 10.dp.toPx(), cap = StrokeCap.Round)

            // Target slot: wide enough that a dot at the in-tune edge still sits inside it.
            val inEdge = scale(TunerEngine.IN_TUNE_CENTS.toFloat()) * half
            val slotHalf = inEdge + dotR + 3.dp.toPx()
            val rr = slotH / 2
            val top = y - rr
            val bottom = y + rr
            ring.reset()
            ring.moveTo(mid, top)
            ring.lineTo(mid + slotHalf - rr, top)
            ring.arcTo(Rect(mid + slotHalf - 2 * rr, top, mid + slotHalf, bottom), -90f, 180f, false)
            ring.lineTo(mid - slotHalf + rr, bottom)
            ring.arcTo(Rect(mid - slotHalf, top, mid - slotHalf + 2 * rr, bottom), 90f, 180f, false)
            ring.close()
            drawPath(ring, tc.inTune.copy(alpha = if (holding) 0.6f else 0.38f), style = Stroke(1.5.dp.toPx()))
            val h = hold.value
            if (h > 0f) {
                measure.setPath(ring, false)
                trace.reset()
                measure.getSegment(0f, measure.length * h, trace, true)
                drawPath(trace, tc.inTune, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
            }

            val labelTop = bottom + gap
            drawText(low, topLeft = Offset(x0 - 4.dp.toPx(), labelTop))
            drawText(high, topLeft = Offset(x1 + 4.dp.toPx() - high.size.width, labelTop))

            // Scaled by presence: as the pitch fades the dot glides home, and with none it rests exactly at centre.
            val bx = mid + scale(dot.value * p) * half
            val col = lerp(oc.faint, dotColor, p)
            drawCircle(col.copy(alpha = 0.16f * p), radius = dotR + 6.dp.toPx(), center = Offset(bx, y))
            drawCircle(col.copy(alpha = 0.55f + 0.45f * p), radius = dotR, center = Offset(bx, y))
            if (done && p > 0f) {
                val s = dotR / 10f
                check.reset()
                check.moveTo(bx - 4.6f * s, y + 0.4f * s)
                check.lineTo(bx - 1.2f * s, y + 3.8f * s)
                check.lineTo(bx + 4.8f * s, y - 3.6f * s)
                drawPath(check, bg, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** Position on the track, −1…1: a gentle power curve, so the last few cents near the slot get more room. */
private fun scale(cents: Float): Float = sign(cents) * (min(50f, abs(cents)) / 50f).pow(0.6f)

/** True when the system turned animations off; the meters then jump instead of easing. */
@Composable
internal fun rememberReduceMotion(): Boolean {
    val ctx = LocalContext.current
    return remember {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** Debug builds: logs OT-LAT, analysis-window end to the frame that draws it (the spec's binding < 50 ms budget). */
@Composable
internal fun LatencyProbe(live: StateFlow<TunerState>) {
    if (!BuildConfig.DEBUG) return
    LaunchedEffect(live) {
        live.collect { s ->
            if (s is TunerState.Reading && s.windowEndNanos != 0L) {
                withFrameNanos { }
                Log.d("OT-LAT", "window-end->frame ${(System.nanoTime() - s.windowEndNanos) / 1000} us")
            }
        }
    }
}
