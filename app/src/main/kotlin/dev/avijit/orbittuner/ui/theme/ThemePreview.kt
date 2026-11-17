package dev.avijit.orbittuner.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
private fun Swatch(name: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(22.dp).background(color, RoundedCornerShape(6.dp)))
        Text(name, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun ThemeSwatches() {
    val o = OrbitTunerTheme.colors
    val t = OrbitTunerTheme.tunerColors
    val s = OrbitTunerTheme.sheetType
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Orbit Tuner", style = MaterialTheme.typography.headlineLarge)
            Text("Song title", style = MaterialTheme.typography.titleMedium)
            Text("Am", style = s.chords.copy(color = o.chord))
            Text("Twinkle twinkle little star", style = s.lyrics)
            Swatch("accent", MaterialTheme.colorScheme.primary)
            Swatch("chord", o.chord); Swatch("chorus panel", o.chorusPanel); Swatch("raised", o.raised)
            Swatch("line", o.line); Swatch("muted", o.muted); Swatch("faint", o.faint)
            Swatch("in tune", t.inTune); Swatch("flat", t.flat); Swatch("sharp", t.sharp); Swatch("needle", t.needle)
        }
    }
}

@Preview(name = "Dark", showBackground = true)
@Composable
private fun SwatchesDark() = OrbitTunerTheme(darkTheme = true) { ThemeSwatches() }

@Preview(name = "Light", showBackground = true)
@Composable
private fun SwatchesLight() = OrbitTunerTheme(darkTheme = false) { ThemeSwatches() }
