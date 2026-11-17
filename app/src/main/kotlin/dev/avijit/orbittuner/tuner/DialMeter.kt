package dev.avijit.orbittuner.tuner

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.avijit.orbittuner.ui.theme.Literata
import dev.avijit.orbittuner.ui.theme.OrbitTunerTheme
import dev.avijit.orbittuner.ui.theme.SchibstedGrotesk
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Dial meter height on short screens (under 640 dp). */
val DialMeterHeightCompact: Dp = 190.dp

/** Concept A's face is drawn for a 340 x 220 box; everything but the type scales with the space given. */
private const val FACE_W = 340f
private const val FACE_H = 220f
/** The arc spans 0.62 pi radians for −50…+50 cents. */
private const val SPAN = (PI * 0.62).toFloat()

/**
 * The precise meter (concept A): an engraved dial, −50…+50 cents, ticks every 2 cents (major every 10, labelled),
 * a ±3 cent in-tune sector, and a tapered needle with some weight to it (a lightly damped spring that settles in
 * well under half a second). Copper off pitch, tune green inside the sector. With no pitch the needle eases back to
 * centre and dims. The needle follows [live] in the draw phase; [readout] drives colour and semantics.
 */
@Composable
fun DialMeter(live: StateFlow<TunerState>, readout: StateFlow<Readout>, modifier: Modifier = Modifier) {
    val r by readout.collectAsStateWithLifecycle()
    val tc = OrbitTunerTheme.tunerColors
    val oc = OrbitTunerTheme.colors
    val ink = MaterialTheme.colorScheme.onBackground
    val hubFill = MaterialTheme.colorScheme.background
    val reduceMotion = rememberReduceMotion()

    val needle = remember { Animatable(0f) }
    val presence = remember { Animatable(0f) }
    LaunchedEffect(live, reduceMotion) {
        live.filterIsInstance<TunerState.Reading>().collectLatest { s ->
            val target = s.cents.toFloat().coerceIn(-52f, 52f)
            if (reduceMotion) needle.snapTo(target)
            else needle.animateTo(target, spring(dampingRatio = 0.78f, stiffness = 140f), needle.velocity)
        }
    }
    LatencyProbe(live)
    LaunchedEffect(r.hasPitch, reduceMotion) {
        val p = if (r.hasPitch) 1f else 0f
        if (reduceMotion) presence.snapTo(p) else presence.animateTo(p, tween(if (p == 1f) 160 else 420))
        if (!r.hasPitch) needle.snapTo(0f)
    }

    val measurer = rememberTextMeasurer()
    val labelStyle = remember(oc.muted) {
        TextStyle(
            fontFamily = SchibstedGrotesk, fontWeight = FontWeight.Medium, fontSize = 11.sp,
            color = oc.muted.copy(alpha = 0.8f), fontFeatureSettings = "tnum",
        )
    }
    val wordStyle = remember(oc.muted) { TextStyle(fontFamily = Literata, fontSize = 13.sp, color = oc.muted) }
    val labels = remember(measurer, labelStyle) {
        (-50..50 step 10).filter { it != 0 }.associateWith { v ->
            measurer.measure(if (v > 0) "+$v" else "−${-v}", labelStyle)
        }
    }
    val flat = remember(measurer, wordStyle) { measurer.measure("flat", wordStyle) }
    val sharp = remember(measurer, wordStyle) { measurer.measure("sharp", wordStyle) }
    val sector = remember { Path() }
    val blade = remember { Path() }

    Canvas(
        modifier
            .testTag("meter")
            .semantics {
                contentDescription = "Tuning dial"
                stateDescription = r.stateDescription
            },
    ) {
        val k = min(size.width / FACE_W.dp.toPx(), size.height / FACE_H.dp.toPx())
        fun u(v: Float) = v.dp.toPx() * k
        val cx = size.width / 2
        val cy = size.height - u(20f)
        val rad = u(160f)
        fun ang(v: Float) = (-PI / 2).toFloat() + v / 50f * (SPAN / 2)
        fun at(a: Float, d: Float) = Offset(cx + cos(a) * d, cy + sin(a) * d)

        val p = presence.value
        val ok = r.hasPitch && r.inTune
        val inCents = TunerEngine.IN_TUNE_CENTS.toFloat()

        // In-tune sector.
        sector.reset()
        sector.moveTo(cx, cy)
        val sr = rad - u(6f)
        sector.arcTo(
            Rect(cx - sr, cy - sr, cx + sr, cy + sr),
            Math.toDegrees(ang(-inCents).toDouble()).toFloat(),
            Math.toDegrees((ang(inCents) - ang(-inCents)).toDouble()).toFloat(),
            false,
        )
        sector.close()
        drawPath(sector, tc.inTune.copy(alpha = if (ok) 0.16f else 0.06f))

        // Engraved ticks every 2 cents.
        for (v in -50..50 step 2) {
            val a = ang(v.toFloat())
            val major = v % 10 == 0
            val zero = v == 0
            val len = u(if (zero) 26f else if (major) 18f else 9f)
            val col = when { zero -> if (ok) tc.inTune else ink; major -> ink; else -> oc.muted }
            drawLine(
                col.copy(alpha = if (major) 0.95f else 0.45f), at(a, rad - len), at(a, rad),
                strokeWidth = (if (zero) 2.2f else if (major) 1.4f else 1f).dp.toPx(),
            )
            labels[v]?.let { t ->
                val c = at(a, rad - u(32f))
                drawText(t, topLeft = Offset(c.x - t.size.width / 2f, c.y - t.size.height / 2f))
            }
        }

        // Flat and sharp at the ends, like a meter face.
        val wordY = cy - u(4f) - flat.firstBaseline
        drawText(flat, topLeft = Offset(u(18f) + (size.width - u(FACE_W)) / 2, wordY))
        drawText(sharp, topLeft = Offset((size.width + u(FACE_W)) / 2 - u(18f) - sharp.size.width, wordY))

        // Needle: tapered blade over a soft drop shadow, then the hub.
        val live = if (ok) tc.inTune else tc.needle
        val col = lerp(oc.faint, live, p)
        // Scaled by presence: the needle swings home as the pitch fades and rests at centre with none.
        val a = ang(needle.value * p)
        val tip = rad - u(4f)
        val tail = u(18f)
        val w = u(2.6f)
        val px = cos(a + PI.toFloat() / 2)
        val py = sin(a + PI.toFloat() / 2)
        blade.reset()
        blade.moveTo(cx + cos(a) * tip, cy + sin(a) * tip)
        blade.lineTo(cx + px * w - cos(a) * tail, cy + py * w - sin(a) * tail)
        blade.lineTo(cx - px * w - cos(a) * tail, cy - py * w - sin(a) * tail)
        blade.close()
        translate(top = 2.dp.toPx()) { drawPath(blade, Color.Black.copy(alpha = 0.22f)) }
        drawPath(blade, col)
        drawCircle(hubFill, radius = u(9f), center = Offset(cx, cy))
        drawCircle(col, radius = u(9f), center = Offset(cx, cy), style = Stroke(2.dp.toPx()))
    }
}
