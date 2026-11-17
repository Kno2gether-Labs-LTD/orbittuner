package dev.avijit.orbittuner.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/** Radii: 12 controls, 16 bars, 20 cards. */
val OrbitShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

val LocalOrbitColors = staticCompositionLocalOf { DarkOrbitColors }
val LocalTunerColors = staticCompositionLocalOf { DarkTunerColors }
val LocalSheetType = staticCompositionLocalOf { SheetType.Phone }

/** Fixed brand palette (no dynamic colour); dark-first, light on request. */
@Composable
fun OrbitTunerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    sheetType: SheetType = SheetType.Phone,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalOrbitColors provides if (darkTheme) DarkOrbitColors else LightOrbitColors,
        LocalTunerColors provides if (darkTheme) DarkTunerColors else LightTunerColors,
        LocalSheetType provides sheetType,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = OrbitTypography,
            shapes = OrbitShapes,
            content = content,
        )
    }
}

object OrbitTunerTheme {
    val colors: OrbitColors @Composable @ReadOnlyComposable get() = LocalOrbitColors.current
    val tunerColors: TunerColors @Composable @ReadOnlyComposable get() = LocalTunerColors.current
    val sheetType: SheetType @Composable @ReadOnlyComposable get() = LocalSheetType.current
}
