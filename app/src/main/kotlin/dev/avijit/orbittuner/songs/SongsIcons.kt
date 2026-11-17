package dev.avijit.orbittuner.songs

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The mockup's 24-unit line icons (1.8 stroke, round caps), drawn from the same path data. Tinted by `Icon`. */
internal object SongsIcons {
    val Search = icon(stroke = circle(11f, 11f, 6.5f) + "M16 16l4 4")
    val Clear = icon(stroke = "M7 7l10 10M17 7L7 17")
    val Yours = icon(stroke = circle(12f, 12f, 8f), fill = circle(12f, 12f, 4f))
    val Device = icon(stroke = "M9.5 3h5a2.5 2.5 0 0 1 2.5 2.5v13a2.5 2.5 0 0 1-2.5 2.5h-5A2.5 2.5 0 0 1 7 18.5v-13A2.5 2.5 0 0 1 9.5 3z M11 18h2")
    val Library = icon(stroke = circle(12f, 12f, 7.5f), fill = circle(17.3f, 6.7f, 2f))
    val Globe = icon(stroke = circle(12f, 12f, 8f) + "M4 12h16M12 4c2.5 2.5 2.5 13.5 0 16M12 4c-2.5 2.5-2.5 13.5 0 16")
    val Down = icon(stroke = "M7 10l5 5 5-5")
    val Add = icon(stroke = "M12 5v14M5 12h14")
    val Link = icon(stroke = "M10 14a4 4 0 0 0 5.66 0l3-3a4 4 0 0 0-5.66-5.66l-1 1M14 10a4 4 0 0 0-5.66 0l-3 3a4 4 0 0 0 5.66 5.66l1-1")
    val Paste = icon(stroke = "M9 3.5h6v3H9z M8 5H6.5A1.5 1.5 0 0 0 5 6.5v12A1.5 1.5 0 0 0 6.5 20h11a1.5 1.5 0 0 0 1.5-1.5v-12A1.5 1.5 0 0 0 17.5 5H16 M8.5 11h7M8.5 14.5h5")
    val Pen = icon(stroke = "M4.5 19.5l4-1 10-10a2.1 2.1 0 0 0-3-3l-10 10z M13.5 7.5l3 3")
    val Check = icon(stroke = "M5.5 12.5l4 4L18.5 7.5")
    val Sparkle = icon(stroke = "M12 3.5l1.7 5 5 1.7-5 1.7-1.7 5-1.7-5-5-1.7 5-1.7z M18.5 15.5l.7 2 2 .7-2 .7-.7 2-.7-2-2-.7 2-.7z")
    val Back = icon(stroke = "M15 5l-7 7 7 7")
    val Mic = icon(stroke = "M12 3.5a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0v-5a3 3 0 0 1 3-3z M6.5 11a5.5 5.5 0 0 0 11 0 M12 16.5v3.5")
    val Offline = icon(stroke = "M4 9.5a12 12 0 0 1 16 0M7 13a7.5 7.5 0 0 1 10 0M10 16.5a3 3 0 0 1 4 0M4 4l16 16")

    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

    private fun icon(stroke: String, fill: String? = null): ImageVector = ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = addPathNodes(stroke), stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        if (fill != null) addPath(pathData = addPathNodes(fill), fill = SolidColor(Color.Black))
    }.build()
}
