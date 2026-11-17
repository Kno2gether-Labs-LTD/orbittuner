package dev.avijit.orbittuner.songview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.avijit.orbittuner.net.FlagReason
import dev.avijit.orbittuner.ui.theme.Literata
import dev.avijit.orbittuner.ui.theme.OrbitTunerTheme
import dev.avijit.orbittuner.ui.theme.SchibstedGrotesk

private val REASON_HINTS = mapOf(
    FlagReason.WrongChords to "Chords are off, missing or in the wrong place",
    FlagReason.WrongLyrics to "Typos, missing verses or the wrong words",
    FlagReason.NotASong to "Spam, a web page or something that isn't a song",
    FlagReason.Other to "Tell us in a note",
)

/** Bottom sheet: pick a reason, add an optional note, send to the moderators. */
@Composable
fun FlagSheet(songTitle: String, onSend: (FlagReason, String) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val oc = OrbitTunerTheme.colors
    val scheme = MaterialTheme.colorScheme
    var reason by rememberSaveable { mutableStateOf<FlagReason?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    Column(
        modifier
            .testTag("flag-sheet")
            .semantics { paneTitle = "Flag this song" }
            .fillMaxWidth()
            .shadow(24.dp, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(scheme.surface)
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 18.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(36.dp, 4.dp).clip(CircleShape).background(oc.line))
        }
        Spacer(Modifier.height(16.dp))
        Text("Flag this song", fontFamily = Literata, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp)
        Text(
            "What's wrong with “$songTitle”? A moderator checks every flag.",
            style = MaterialTheme.typography.bodyMedium, color = oc.muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(14.dp))
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlagReason.entries.forEach { r ->
                val on = r == reason
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(16.dp))
                        .background(if (on) scheme.primary.copy(alpha = 0.10f) else oc.raised.copy(alpha = 0.5f))
                        .border(1.dp, if (on) scheme.primary.copy(alpha = 0.55f) else oc.line.copy(alpha = 0f), RoundedCornerShape(16.dp))
                        .selectable(selected = on, role = Role.RadioButton) { reason = r }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Radio(on)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.label, fontFamily = SchibstedGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text(REASON_HINTS.getValue(r), style = MaterialTheme.typography.bodySmall, color = oc.muted)
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            note, { note = it.take(500) },
            placeholder = { Text("Add a note (optional)", color = oc.faint) },
            minLines = 2, maxLines = 4, shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = oc.line),
            modifier = Modifier.fillMaxWidth().testTag("flag-note"),
        )
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("Cancel", color = oc.muted) }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { reason?.let { onSend(it, note) } }, enabled = reason != null, shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = scheme.primary),
            ) { Text("Send flag", style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp)) }
        }
    }
}

@Composable
private fun Radio(on: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val oc = OrbitTunerTheme.colors
    Box(
        Modifier.size(22.dp).clip(CircleShape).border(2.dp, if (on) scheme.primary else oc.faint, CircleShape),
        contentAlignment = Alignment.Center,
    ) { if (on) Box(Modifier.size(11.dp).clip(CircleShape).background(scheme.primary)) }
}
