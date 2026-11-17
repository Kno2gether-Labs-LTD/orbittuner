package dev.avijit.orbittuner.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ThemeTest {
    @get:Rule val rule = createComposeRule()

    private class Seen(val chord: Color, val bg: Color, val primary: Color, val inTune: Color, val headline: FontFamily?, val body: FontFamily?)

    private val darkState = mutableStateOf(true)
    private var seen: Seen? = null

    private fun capture(dark: Boolean): Seen {
        if (seen == null) {
            rule.setContent {
                OrbitTunerTheme(darkTheme = darkState.value) {
                    seen = Seen(
                        OrbitTunerTheme.colors.chord, MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.primary,
                        OrbitTunerTheme.tunerColors.inTune, MaterialTheme.typography.headlineLarge.fontFamily,
                        MaterialTheme.typography.bodyLarge.fontFamily,
                    )
                }
            }
        }
        darkState.value = dark
        rule.waitForIdle()
        return seen!!
    }

    @Test fun themesProvideDistinctColours() {
        val dark = capture(true)
        val light = capture(false)
        assertEquals(Color(0xFFFFA274), dark.chord)
        assertEquals(Color(0xFFAD3A14), light.chord)
        assertNotEquals(dark.chord, light.chord)
        assertNotEquals(dark.bg, light.bg)
        assertNotEquals(dark.primary, light.primary)
        assertNotEquals(dark.inTune, light.inTune)
    }

    @Test fun fontsLoad() {
        val seen = capture(true)
        assertNotNull(seen.headline)
        assertNotNull(seen.body)
        assertNotEquals(FontFamily.Default, seen.headline)
        assertNotEquals(FontFamily.Default, seen.body)
        assertNotEquals(seen.headline, seen.body)
        assertNotEquals(FontFamily.Default, SheetType.Phone.lyrics.fontFamily)
        assertEquals(168f, SheetType.Phone.tunerNote.fontSize.value)
        assertEquals(190f, SheetType.Tablet.tunerNote.fontSize.value)
    }
}
