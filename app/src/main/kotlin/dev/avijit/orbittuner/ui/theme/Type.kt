package dev.avijit.orbittuner.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.avijit.orbittuner.R

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, vararg weights: Int) = FontFamily(
    weights.map { w -> Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w))) },
)

/** Literata (lyrics, titles, tuner note) and Schibsted Grotesk (UI, chords); both SIL OFL 1.1, bundled as variable fonts. */
val Literata: FontFamily = variable(R.font.literata_variable, 400, 440, 500, 600)
val SchibstedGrotesk: FontFamily = variable(R.font.schibsted_grotesk_variable, 400, 500, 600, 700)

val OrbitTypography = Typography(
    displayLarge = TextStyle(fontFamily = Literata, fontWeight = FontWeight.Medium, fontSize = 57.sp, lineHeight = 64.sp),
    headlineLarge = TextStyle(fontFamily = Literata, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.01).em),
    headlineMedium = TextStyle(fontFamily = Literata, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontFamily = Literata, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Literata, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = SchibstedGrotesk, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = SchibstedGrotesk, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = SchibstedGrotesk, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = SchibstedGrotesk, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = SchibstedGrotesk, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = SchibstedGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = SchibstedGrotesk, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = SchibstedGrotesk, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
)

/** Song-sheet and tuner styles; `compact` = phone, otherwise tablet (landscape sheet sizes are 22sp lyrics / 19sp chords). */
@Immutable
data class SheetType(
    val lyrics: TextStyle,
    val chords: TextStyle,
    val tunerNote: TextStyle,
) {
    companion object {
        private fun lyrics(size: Int) = TextStyle(
            fontFamily = Literata, fontWeight = FontWeight(440), fontSize = size.sp, lineHeight = (size * 1.5f).sp,
        )
        private fun chords(size: Int) = TextStyle(
            fontFamily = SchibstedGrotesk, fontWeight = FontWeight.Bold, fontSize = size.sp, lineHeight = (size * 1.45f).sp,
        )
        private fun note(size: Int) = TextStyle(
            fontFamily = Literata, fontWeight = FontWeight.Medium, fontSize = size.sp, lineHeight = size.sp,
        )

        val Phone = SheetType(lyrics(20), chords(18), note(168))
        val Tablet = SheetType(lyrics(26), chords(22), note(190))
        val TabletLandscape = SheetType(lyrics(22), chords(19), note(190))
    }
}

