package dev.avijit.orbittuner.add

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.avijit.orbittuner.AppGraph
import dev.avijit.orbittuner.chordpro.ChordPro
import dev.avijit.orbittuner.net.AccountState
import dev.avijit.orbittuner.songs.SongsIcons
import dev.avijit.orbittuner.songview.SongSheet
import dev.avijit.orbittuner.songview.rememberRouteOwner
import dev.avijit.orbittuner.songview.rememberSheetStyles
import dev.avijit.orbittuner.ui.theme.Literata
import dev.avijit.orbittuner.ui.theme.OrbitTunerTheme
import dev.avijit.orbittuner.ui.theme.SchibstedGrotesk
import dev.avijit.orbittuner.ui.theme.SheetType
import kotlinx.coroutines.flow.map

/** Nested route over the tabs (AppShell): the Add song screen. */
object AddDest { const val ROUTE = "add" }

/** The Add song screen wired to the app graph. [onOpenAccount]: Settings, where Google sign-in lives. */
@Composable
fun AddSongRoute(graph: AppGraph, onBack: () -> Unit, onOpenSong: (String) -> Unit, onOpenAccount: () -> Unit) {
    // Lives as long as the route (survives rotation; a fresh form each time Add song opens).
    CompositionLocalProvider(LocalViewModelStoreOwner provides rememberRouteOwner(AddDest.ROUTE)) {
        val vm: AddSongViewModel = viewModel {
            AddSongViewModel(
                graph.api.asContributions(),
                me = graph.accounts.state.map { (it as? AccountState.Ready)?.me },
                onAiUsed = { graph.accounts.refresh() },
            )
        }
        AddSongScreen(vm, onBack, onOpenSong, onSignIn = onOpenAccount)
    }
}

private val KEYS = listOf("C", "Db", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")

/**
 * Add a song: Paste text / Link / Write. Write is a ChordPro-lite editor (title, artist, key, a body with a
 * tap-to-insert chord palette) with a live preview, side by side from 840 dp.
 */
@Composable
fun AddSongScreen(
    vm: AddSongViewModel,
    onBack: () -> Unit,
    onOpenSong: (String) -> Unit,
    onSignIn: () -> Unit,
    initialTab: AddTab = AddTab.Paste,
) {
    val result by vm.result.collectAsStateWithLifecycle()
    val allowance by vm.allowance.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    // Form fields (kept across tab switches and rotation).
    var pasteText by rememberSaveable { mutableStateOf("") }
    var pasteTitle by rememberSaveable { mutableStateOf("") }
    var pasteArtist by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var artist by rememberSaveable { mutableStateOf("") }
    var key by rememberSaveable { mutableStateOf<String?>(null) }
    var body by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }

    val clear = {
        when (tab) {
            AddTab.Paste -> { pasteText = ""; pasteTitle = ""; pasteArtist = "" }
            AddTab.Link -> url = ""
            AddTab.Write -> { title = ""; artist = ""; key = null; body = TextFieldValue("") }
        }
        vm.reset()
    }
    val submit = {
        when (tab) {
            AddTab.Paste -> vm.submitPaste(pasteText, pasteTitle, pasteArtist)
            AddTab.Link -> vm.submitLink(url)
            AddTab.Write -> vm.submitWrite(title, artist, key, body.text)
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 840.dp
            val big = maxWidth >= 600.dp
            Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
                TopBar(big, onBack)
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()
                        .padding(horizontal = if (big) 28.dp else 16.dp).padding(bottom = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val maxW: Dp = if (wide && tab == AddTab.Write) 1100.dp else 680.dp
                    Column(Modifier.widthIn(max = maxW).fillMaxWidth()) {
                        Tabs(tab, onSelect = { tab = it; vm.reset() }, Modifier.padding(top = 6.dp, bottom = 18.dp).widthIn(max = 520.dp))
                        when (val r = result) {
                            is AddResult.Published, is AddResult.InReview, is AddResult.Duplicate ->
                                ResultCard(r, onOpenSong, onAddAnother = clear)
                            else -> {
                                when (tab) {
                                    AddTab.Paste -> PasteForm(pasteText, { pasteText = it }, pasteTitle, { pasteTitle = it }, pasteArtist, { pasteArtist = it })
                                    AddTab.Link -> LinkForm(url) { url = it }
                                    AddTab.Write -> WriteForm(
                                        title, { title = it }, artist, { artist = it }, key, { key = it }, body, { body = it }, wide,
                                    )
                                }
                                if (tab != AddTab.Write) allowance?.let { AllowanceLine(it, Modifier.padding(top = 14.dp)) }
                                if (r is AddResult.Failed) FailureNote(r, onSignIn, Modifier.padding(top = 16.dp))
                                SubmitButton(
                                    tab, sending = r == AddResult.Sending, onClick = submit,
                                    modifier = Modifier.padding(top = 20.dp).align(if (big) Alignment.End else Alignment.CenterHorizontally),
                                    full = !big,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Chrome
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun TopBar(big: Boolean, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (big) 14.dp else 6.dp, end = 16.dp, top = if (big) 10.dp else 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(SongsIcons.Back, contentDescription = "Back") }
        Column(Modifier.padding(start = 4.dp)) {
            Text(
                "Add a song", fontFamily = Literata, fontWeight = FontWeight.SemiBold, fontSize = if (big) 28.sp else 22.sp,
                lineHeight = if (big) 32.sp else 26.sp, modifier = Modifier.semantics { heading() },
            )
            Text(
                "Grow the shared song library", style = MaterialTheme.typography.bodyMedium,
                color = OrbitTunerTheme.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Segmented control: a soft track with the selected tab raised. */
@Composable
private fun Tabs(current: AddTab, onSelect: (AddTab) -> Unit, modifier: Modifier = Modifier) {
    val oc = OrbitTunerTheme.colors
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(scheme.surface).padding(4.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AddTab.entries.forEach { t ->
            val on = t == current
            Row(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (on) oc.raised else Color.Transparent)
                    .selectable(selected = on, role = Role.Tab) { onSelect(t) }
                    .testTag("tab-" + t.name.lowercase()),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    when (t) { AddTab.Paste -> SongsIcons.Paste; AddTab.Link -> SongsIcons.Link; AddTab.Write -> SongsIcons.Pen },
                    contentDescription = null, tint = if (on) scheme.primary else oc.faint, modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    t.label, fontFamily = SchibstedGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1,
                    color = if (on) scheme.onBackground else oc.muted,
                )
            }
        }
    }
}

@Composable
private fun Intro(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = OrbitTunerTheme.colors.muted, modifier = Modifier.padding(bottom = 16.dp))
}

@Composable
private fun Field(
    value: String, onChange: (String) -> Unit, label: String, tag: String, modifier: Modifier = Modifier,
    keyboard: KeyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
) {
    val oc = OrbitTunerTheme.colors
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, shape = RoundedCornerShape(14.dp),
        keyboardOptions = keyboard,
        // No container fill: the floating label's cut-out in the outline would show the page colour as a patch.
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = oc.line, unfocusedLabelColor = oc.muted),
        modifier = modifier.fillMaxWidth().testTag(tag),
    )
}

private val EditorStyle = TextStyle(fontFamily = SchibstedGrotesk, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 26.sp)

// ---------------------------------------------------------------------------------------------------------------------
// Forms
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun PasteForm(
    text: String, onText: (String) -> Unit, title: String, onTitle: (String) -> Unit, artist: String, onArtist: (String) -> Unit,
) {
    val oc = OrbitTunerTheme.colors
    Intro("Paste chords over lyrics, or ChordPro. We lay it out as a clean sheet, using AI when the chords need untangling.")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Field(title, onTitle, "Title (optional)", "paste-title", Modifier.weight(1f))
        Field(artist, onArtist, "Artist (optional)", "paste-artist", Modifier.weight(1f))
    }
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        text, onText,
        placeholder = { Text("G          C        G\nAmazing grace, how sweet the sound", style = EditorStyle, color = oc.faint) },
        textStyle = EditorStyle, minLines = 9, shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = oc.line, unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp).testTag("paste-text"),
    )
}

@Composable
private fun LinkForm(url: String, onUrl: (String) -> Unit) {
    Intro("Paste a link to a chord page. We read the page and turn it into a clean, transposable sheet.")
    Field(url, onUrl, "Web address", "link-url", keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done))
    Text(
        "Chord sites work best. Pages behind a login can't be read.",
        style = MaterialTheme.typography.bodySmall, color = OrbitTunerTheme.colors.faint, modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@Composable
private fun ColumnScope.WriteForm(
    title: String, onTitle: (String) -> Unit, artist: String, onArtist: (String) -> Unit,
    key: String?, onKey: (String?) -> Unit, body: TextFieldValue, onBody: (TextFieldValue) -> Unit, wide: Boolean,
) {
    val editor = @Composable { mod: Modifier ->
        Column(mod) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(title, onTitle, "Title", "write-title", Modifier.weight(1f))
                Field(artist, onArtist, "Artist", "write-artist", Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            KeyPicker(key, onKey)
            Spacer(Modifier.height(16.dp))
            ChordPalette(key, onInsert = { onBody(insertChord(body, it)) })
            Spacer(Modifier.height(10.dp))
            val oc = OrbitTunerTheme.colors
            OutlinedTextField(
                body, onBody,
                placeholder = { Text("[G]Amazing [G7]grace, how [C]sweet the [G]sound", style = EditorStyle, color = oc.faint) },
                textStyle = EditorStyle, minLines = 10, shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = oc.line, unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp).testTag("write-body"),
            )
            Text(
                "Put a chord in [brackets] right before the syllable it falls on. Sections: {start_of_chorus} … {end_of_chorus}.",
                style = MaterialTheme.typography.bodySmall, color = oc.faint, modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            )
        }
    }
    val preview = @Composable { mod: Modifier -> Preview(title, artist, key, body.text, mod) }
    if (wide) {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            editor(Modifier.weight(1f))
            preview(Modifier.weight(1f))
        }
    } else {
        editor(Modifier.fillMaxWidth())
        Spacer(Modifier.height(20.dp))
        preview(Modifier.fillMaxWidth())
    }
}

@Composable
private fun SectionLabel(text: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text.uppercase(), fontFamily = SchibstedGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 1.2.sp,
            color = OrbitTunerTheme.colors.muted, modifier = Modifier.weight(1f),
        )
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.bodySmall, color = OrbitTunerTheme.colors.faint)
    }
}

@Composable
private fun KeyPicker(key: String?, onKey: (String?) -> Unit) {
    val minor = key?.endsWith("m") == true
    val root = key?.removeSuffix("m")
    SectionLabel("Key", trailing = key?.let { "Key of $it" } ?: "Optional")
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Chip(if (minor) "Minor" else "Major", on = minor, tag = "key-minor", accent = false) {
            if (root != null) onKey(if (minor) root else root + "m")
        }
        Box(Modifier.width(1.dp).height(36.dp).background(OrbitTunerTheme.colors.line).align(Alignment.CenterVertically))
        KEYS.forEach { k ->
            val on = k == root
            Chip(k + if (minor) "m" else "", on = on, tag = "key-$k") { onKey(if (on) null else k + if (minor) "m" else "") }
        }
    }
}

@Composable
private fun ChordPalette(key: String?, onInsert: (String) -> Unit) {
    val chords = remember(key) { chordPalette(key ?: "C") }
    SectionLabel("Tap to insert at the cursor", trailing = "In ${key ?: "C"}")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        chords.forEach { c -> ChordChip(c, onClick = { onInsert(c) }) }
    }
}

@Composable
private fun Chip(text: String, on: Boolean, tag: String, accent: Boolean = true, onClick: () -> Unit) {
    val oc = OrbitTunerTheme.colors
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.height(38.dp).widthIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
            .background(if (on && accent) scheme.primary else if (on) oc.raised else scheme.surface)
            .border(1.dp, if (on) Color.Transparent else oc.line, RoundedCornerShape(12.dp))
            .selectable(selected = on, role = Role.RadioButton, onClick = onClick)
            .testTag(tag).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, fontFamily = SchibstedGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1,
            color = if (on && accent) scheme.onPrimary else if (on) scheme.onBackground else oc.muted,
        )
    }
}

@Composable
private fun ChordChip(chord: String, onClick: () -> Unit) {
    val oc = OrbitTunerTheme.colors
    Box(
        Modifier.height(40.dp).widthIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(oc.raised)
            .clickable(role = Role.Button, onClickLabel = "Insert $chord") { onClick() }
            .testTag("chord-$chord").padding(horizontal = 12.dp)
            .semantics { contentDescription = "Insert $chord" },
        contentAlignment = Alignment.Center,
    ) {
        Text(chord, fontFamily = SchibstedGrotesk, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = oc.chord, maxLines = 1)
    }
}

/** The live sheet, rendered with the song view's own layout. */
@Composable
private fun Preview(title: String, artist: String, key: String?, body: String, modifier: Modifier) {
    val oc = OrbitTunerTheme.colors
    val doc = remember(title, artist, key, body) { ChordPro.parse(writeChordPro(title.ifBlank { "Untitled" }, artist, key, body)) }
    val styles = rememberSheetStyles(SheetType.Phone, 0.85f)
    Column(modifier) {
        SectionLabel("Preview", trailing = if (doc.chords.isEmpty()) null else doc.chords.joinToString("  "))
        Column(
            Modifier.fillMaxWidth().heightIn(min = 200.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface)
                .border(1.dp, oc.line.copy(alpha = 0.6f), RoundedCornerShape(20.dp)).padding(horizontal = 20.dp, vertical = 18.dp)
                .testTag("write-preview"),
        ) {
            Text(
                title.ifBlank { "Untitled" }, fontFamily = Literata, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 27.sp,
                color = if (title.isBlank()) oc.faint else MaterialTheme.colorScheme.onBackground,
            )
            val sub = listOfNotNull(artist.trim().takeIf { it.isNotEmpty() }, key?.let { "Key of $it" }).joinToString(", ")
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = oc.muted)
            Spacer(Modifier.height(14.dp))
            if (body.isBlank()) {
                Text("Your sheet appears here as you type.", style = MaterialTheme.typography.bodyMedium, color = oc.faint)
            } else {
                SongSheet(doc, styles, chordsOnly = false, columns = 1, viewportPx = 0)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Allowance, errors, submit, result
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun AllowanceLine(a: AiAllowance, modifier: Modifier = Modifier) {
    val oc = OrbitTunerTheme.colors
    val scheme = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(SongsIcons.Sparkle, contentDescription = null, tint = if (a.left > 0) scheme.primary else oc.faint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            if (a.left > 0) "${a.left} of ${a.limit} AI imports left today" else "No AI imports left today",
            style = MaterialTheme.typography.bodyMedium, color = oc.muted, modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            a.plan.replaceFirstChar { it.uppercase() } + " plan",
            fontFamily = SchibstedGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = oc.muted,
            modifier = Modifier.clip(CircleShape).background(oc.raised).padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun FailureNote(f: AddResult.Failed, onSignIn: () -> Unit, modifier: Modifier = Modifier) {
    val warn = OrbitTunerTheme.tunerColors.sharp
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(warn.copy(alpha = 0.09f))
            .border(1.dp, warn.copy(alpha = 0.30f), RoundedCornerShape(16.dp)).padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }.testTag("add-error"),
    ) {
        Text(f.message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(end = 8.dp))
        if (f.signInHint) {
            TextButton(onClick = onSignIn, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                Text("Sign in with Google to get more", style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp))
            }
        }
    }
}

@Composable
private fun SubmitButton(tab: AddTab, sending: Boolean, onClick: () -> Unit, modifier: Modifier, full: Boolean) {
    Button(
        onClick = onClick, enabled = !sending, shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 15.dp),
        modifier = modifier.then(if (full) Modifier.fillMaxWidth() else Modifier.widthIn(min = 200.dp)).testTag("submit"),
    ) {
        if (sending) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(10.dp))
            Text(if (tab == AddTab.Write) "Adding…" else "Reading…", style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp))
        } else {
            Text(if (tab == AddTab.Link) "Import song" else "Add song", style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp))
        }
    }
}

@Composable
private fun ResultCard(r: AddResult, onOpenSong: (String) -> Unit, onAddAnother: () -> Unit) {
    val oc = OrbitTunerTheme.colors
    val scheme = MaterialTheme.colorScheme
    val tc = OrbitTunerTheme.tunerColors
    val (badge, badgeColor) = when (r) {
        is AddResult.Published -> "Published" to tc.inTune
        is AddResult.InReview -> "In review" to scheme.primary
        else -> "Already in the library" to oc.muted
    }
    val songTitle = when (r) { is AddResult.Published -> r.song.title; is AddResult.InReview -> r.song.title; else -> null }
    val openId = when (r) { is AddResult.Published -> r.song.id; is AddResult.InReview -> r.song.id; is AddResult.Duplicate -> r.songId; else -> null }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(scheme.surface)
            .border(1.dp, oc.line.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
            .padding(24.dp).testTag("result-card").semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(badgeColor.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(
                    if (r is AddResult.InReview) SongsIcons.Sparkle else if (r is AddResult.Duplicate) SongsIcons.Library else SongsIcons.Check,
                    contentDescription = null, tint = badgeColor, modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Text(
                badge, fontFamily = SchibstedGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 0.4.sp, color = badgeColor,
                modifier = Modifier.clip(CircleShape).background(badgeColor.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        if (songTitle != null) {
            Text(songTitle, fontFamily = Literata, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp)
            Spacer(Modifier.height(6.dp))
        }
        val lines = when (r) {
            is AddResult.Published -> listOf("It's in the library for everyone now.")
            is AddResult.InReview -> listOf("Thanks — a moderator will check it", "Until then, only you can see it.")
            else -> listOf("Someone has added this song already, so there's no need for another copy.")
        }
        lines.forEachIndexed { i, l ->
            Text(
                l, style = if (i == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                color = if (i == 0) scheme.onBackground else oc.muted,
            )
        }
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (openId != null) {
                Button(
                    onClick = { onOpenSong(openId) }, shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = scheme.primary),
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
                ) {
                    Text(if (r is AddResult.Duplicate) "Open existing" else "Open song", style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp))
                }
            }
            TextButton(onClick = onAddAnother) { Text("Add another", style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp)) }
        }
    }
}
