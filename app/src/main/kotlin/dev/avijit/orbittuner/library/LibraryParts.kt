package dev.avijit.orbittuner.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.avijit.orbittuner.data.db.CachedSong
import dev.avijit.orbittuner.ui.theme.OrbitTunerTheme
import dev.avijit.orbittuner.ui.theme.SchibstedGrotesk
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val CoverTop = Color(0xFF241A6B)
private val CoverBottom = Color(0xFF0A0820)
private val Planet = Color(0xFFFF8A5C)
private val PlanetLead = Color(0xFFFFC48F)

/** A stable turn (radians) for a playlist, so each cover's planets sit differently. */
fun coverTurn(id: String): Float = ((id.hashCode().toLong() and 0xffff) % 628) / 100f

/**
 * Playlist cover (mockup `cover()`): night-indigo tile with a faint orbit and one planet per song, at most 8;
 * the first song is the larger, brighter planet. Same in both themes.
 */
@Composable
fun PlaylistCover(count: Int, turn: Float, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val u = this.size.width / 56f
        drawRoundRect(Brush.verticalGradient(listOf(CoverTop, CoverBottom)), cornerRadius = CornerRadius(15 * u, 15 * u))
        val c = Offset(28 * u, 28 * u)
        drawCircle(Planet.copy(alpha = 0.35f), 17 * u, c, style = Stroke(1.5f * u))
        val n = min(count, 8)
        for (i in 0 until n) {
            val a = -PI / 3 + i * (2 * PI / n) + turn
            val p = Offset(c.x + 17 * u * cos(a).toFloat(), c.y + 17 * u * sin(a).toFloat())
            if (i == 0) drawCircle(PlanetLead, 4.2f * u, p) else drawCircle(Planet.copy(alpha = 0.75f), 2.4f * u, p)
        }
    }
}

/** Two short bars: the drag handle of a setlist row. */
@Composable
fun GripIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(20.dp)) {
        val w = 1.8.dp.toPx()
        listOf(0.4f, 0.6f).forEach { f ->
            drawLine(color, Offset(size.width * 0.2f, size.height * f), Offset(size.width * 0.8f, size.height * f), w, StrokeCap.Round)
        }
    }
}

/** Arrow into a tray (download). */
@Composable
fun DownloadIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(20.dp)) {
        val s = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val w = size.width; val h = size.height
        drawPath(Path().apply { moveTo(w * .5f, h * .14f); lineTo(w * .5f, h * .62f) }, color, style = s)
        drawPath(Path().apply { moveTo(w * .3f, h * .44f); lineTo(w * .5f, h * .64f); lineTo(w * .7f, h * .44f) }, color, style = s)
        drawPath(Path().apply { moveTo(w * .16f, h * .66f); lineTo(w * .16f, h * .84f); lineTo(w * .84f, h * .84f); lineTo(w * .84f, h * .66f) }, color, style = s)
    }
}

/** Box with an arrow out of it (export / share). */
@Composable
fun ExportIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(20.dp)) {
        val s = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val w = size.width; val h = size.height
        drawPath(Path().apply { moveTo(w * .5f, h * .62f); lineTo(w * .5f, h * .12f) }, color, style = s)
        drawPath(Path().apply { moveTo(w * .3f, h * .3f); lineTo(w * .5f, h * .1f); lineTo(w * .7f, h * .3f) }, color, style = s)
        drawPath(Path().apply { moveTo(w * .32f, h * .46f); lineTo(w * .18f, h * .46f); lineTo(w * .18f, h * .88f); lineTo(w * .82f, h * .88f); lineTo(w * .82f, h * .46f); lineTo(w * .68f, h * .46f) }, color, style = s)
    }
}

/** The song's own key: the service's originalKey, else the ChordPro `{key}`. */
fun CachedSong.keyName(): String? =
    originalKey?.takeIf { it.isNotBlank() } ?: KEY_META.find(chordpro)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

private val KEY_META = Regex("""\{\s*key\s*:\s*([^}]+)\}""", RegexOption.IGNORE_CASE)

/** "today", "yesterday", "3 days ago", then "12 Mar" (or "12 Mar 2025" in another year). */
fun relativeDay(at: Long, now: Long = System.currentTimeMillis()): String {
    fun day(ms: Long) = Calendar.getInstance().apply {
        timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val a = day(at); val b = day(now)
    val days = ((b.timeInMillis - a.timeInMillis + 43_200_000L) / 86_400_000L).toInt()
    return when {
        days <= 0 -> "today"
        days == 1 -> "yesterday"
        days < 7 -> "$days days ago"
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(at))
        else -> SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(at))
    }
}

/** Song row (mockup `.it`): key tile, title, and a muted line. */
@Composable
fun SongRow(song: CachedSong, subtitle: String, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    val oc = OrbitTunerTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 68.dp).clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Text(song.keyName() ?: "–", fontFamily = SchibstedGrotesk, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = oc.chord)
        }
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = oc.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

/** Centred note for an empty list. */
@Composable
fun EmptyNote(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        PlaylistCover(0, 0f, 64.dp)
        Spacer(Modifier.size(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.width(0.dp).size(6.dp))
        Text(
            body, style = MaterialTheme.typography.bodyMedium, color = OrbitTunerTheme.colors.muted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
