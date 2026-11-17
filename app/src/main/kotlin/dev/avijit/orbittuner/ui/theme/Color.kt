package dev.avijit.orbittuner.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** Approved palette ("phosphor bronze on night indigo"), from docs/design/orbittuner-mockup.html. */
private object Dark {
    val bg = Color(0xFF0E0C1D); val surface = Color(0xFF161329); val raised = Color(0xFF201C3A)
    val line = Color(0xFF2E2951); val text = Color(0xFFF2EEE6); val muted = Color(0xFFA39DBF)
    val faint = Color(0xFF6D6791); val accent = Color(0xFFFF8A5C); val accentHi = Color(0xFFFFC48F)
    val accentLo = Color(0xFFE0455A); val onAccent = Color(0xFF220F08); val chord = Color(0xFFFFA274)
    val chorus = Color(0xFF1B1832); val flat = Color(0xFF8CB6FF); val sharp = Color(0xFFFF6B7F)
    val inTune = Color(0xFF6FE3A6)
}

private object Light {
    val bg = Color(0xFFF3F2F8); val surface = Color(0xFFFFFFFF); val raised = Color(0xFFEAE8F3)
    val line = Color(0xFFD8D5E6); val text = Color(0xFF16132A); val muted = Color(0xFF565172)
    val faint = Color(0xFF8A85A6); val accent = Color(0xFFC2471F); val accentHi = Color(0xFFE8794A)
    val accentLo = Color(0xFFB3283F); val onAccent = Color(0xFFFFFFFF); val chord = Color(0xFFAD3A14)
    val chorus = Color(0xFFECEBF6); val flat = Color(0xFF2C5FC8); val sharp = Color(0xFFC42642)
    val inTune = Color(0xFF0F8657)
}

/** Brand ring gradient stops (same in both themes). */
val BrandRing = listOf(Color(0xFFFFD29A), Color(0xFFFF8A5C), Color(0xFFE0455A))

val DarkColorScheme: ColorScheme = darkColorScheme(
    primary = Dark.accent, onPrimary = Dark.onAccent,
    primaryContainer = Dark.raised, onPrimaryContainer = Dark.accentHi,
    secondary = Dark.accentHi, onSecondary = Dark.onAccent,
    tertiary = Dark.accentLo, onTertiary = Dark.text,
    background = Dark.bg, onBackground = Dark.text,
    surface = Dark.surface, onSurface = Dark.text,
    surfaceVariant = Dark.raised, onSurfaceVariant = Dark.muted,
    surfaceContainerLowest = Dark.bg, surfaceContainerLow = Dark.surface, surfaceContainer = Dark.surface,
    surfaceContainerHigh = Dark.raised, surfaceContainerHighest = Dark.raised,
    outline = Dark.faint, outlineVariant = Dark.line,
    error = Dark.sharp, onError = Dark.onAccent,
)

val LightColorScheme: ColorScheme = lightColorScheme(
    primary = Light.accent, onPrimary = Light.onAccent,
    primaryContainer = Light.raised, onPrimaryContainer = Light.accent,
    secondary = Light.accentHi, onSecondary = Light.onAccent,
    tertiary = Light.accentLo, onTertiary = Light.onAccent,
    background = Light.bg, onBackground = Light.text,
    surface = Light.surface, onSurface = Light.text,
    surfaceVariant = Light.raised, onSurfaceVariant = Light.muted,
    surfaceContainerLowest = Light.surface, surfaceContainerLow = Light.bg, surfaceContainer = Light.surface,
    surfaceContainerHigh = Light.raised, surfaceContainerHighest = Light.raised,
    outline = Light.faint, outlineVariant = Light.line,
    error = Light.sharp, onError = Light.onAccent,
)

/** Colours the Material scheme has no slot for. */
@Immutable
data class OrbitColors(
    val chord: Color,
    val chorusPanel: Color,
    val muted: Color,
    val faint: Color,
    val line: Color,
    val raised: Color,
)

/** Tuner states: in tune (inside the +-3 cent slot), flat, sharp, and the string/needle itself. */
@Immutable
data class TunerColors(val inTune: Color, val flat: Color, val sharp: Color, val needle: Color)

val DarkOrbitColors = OrbitColors(Dark.chord, Dark.chorus, Dark.muted, Dark.faint, Dark.line, Dark.raised)
val LightOrbitColors = OrbitColors(Light.chord, Light.chorus, Light.muted, Light.faint, Light.line, Light.raised)
val DarkTunerColors = TunerColors(Dark.inTune, Dark.flat, Dark.sharp, Dark.accent)
val LightTunerColors = TunerColors(Light.inTune, Light.flat, Light.sharp, Light.accent)
