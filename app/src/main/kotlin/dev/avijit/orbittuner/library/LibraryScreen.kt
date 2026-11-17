package dev.avijit.orbittuner.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.avijit.orbittuner.AppGraph
import dev.avijit.orbittuner.net.AccountState
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import dev.avijit.orbittuner.settings.AccountResetNotice
import dev.avijit.orbittuner.data.LibraryRepository
import dev.avijit.orbittuner.data.PlaylistWithSongs
import dev.avijit.orbittuner.songview.SongDest
import dev.avijit.orbittuner.ui.theme.Literata
import dev.avijit.orbittuner.ui.theme.OrbitTunerTheme
import dev.avijit.orbittuner.ui.theme.SchibstedGrotesk

/** Library tab wired to the app graph. */
@Composable
fun LibraryRoute(
    graph: AppGraph, onOpenSong: (SongDest) -> Unit, onPlaySet: (playlistId: String, start: Int) -> Unit, onOpenAccount: () -> Unit,
    onAddSong: (() -> Unit)? = null,
) {
    val vm: LibraryViewModel = viewModel {
        LibraryViewModel(
            graph.library, graph.auth, exportZip = { ids -> graph.api.exportZip(ids) },
            accountDisabled = graph.accounts.state.map { it == AccountState.Disabled },
            resetNotice = graph.prefs.accountResetNotice,
            dismissResetNotice = { graph.appScope.launch { graph.prefs.setAccountResetNotice(false) } },
        )
    }
    LibraryScreen(vm, onOpenSong, onPlaySet, onOpenAccount, onAddSong)
}

/**
 * Library: Playlists (create, rename, delete, open), Favourites, Recent and Downloaded. Phones show the list, then
 * the playlist full screen; wide windows show both side by side (mockup `library()`).
 */
@Composable
fun LibraryScreen(
    vm: LibraryViewModel,
    onOpenSong: (SongDest) -> Unit,
    onPlaySet: (playlistId: String, start: Int) -> Unit,
    onOpenAccount: () -> Unit,
    /** Opens Add song; null hides the button. */
    onAddSong: (() -> Unit)? = null,
) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val selectedId by vm.selected.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(vm) {
        vm.exports.collect { if (!shareExportZip(context, it)) snackbar.showSnackbar("Couldn't save the export on this device") }
    }
    val selected = playlists?.firstOrNull { it.playlist.id == selectedId }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 720.dp
            if (wide) {
                // Two panes: keep a playlist showing on the right.
                LaunchedEffect(playlists, selectedId) {
                    val all = playlists ?: return@LaunchedEffect
                    if (all.none { it.playlist.id == selectedId }) vm.select(all.firstOrNull()?.playlist?.id)
                }
                Row(Modifier.fillMaxSize()) {
                    LibraryPane(vm, onOpenSong, onOpenAccount, onAddSong, wide = true, modifier = Modifier.width(340.dp).fillMaxHeight())
                    Box(Modifier.width(1.dp).fillMaxHeight().background(OrbitTunerTheme.colors.line))
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (selected != null) {
                            PlaylistDetail(vm, selected, onPlaySet, onBack = null)
                        } else if (playlists != null) {
                            EmptyNote(
                                "No playlist open", "Make a playlist for Sunday, a gig or practice. Each song keeps its own key, capo and speed.",
                                Modifier.align(Alignment.Center),
                            )
                        }
                    }
                }
            } else if (selected != null) {
                BackHandler { vm.select(null) }
                PlaylistDetail(vm, selected, onPlaySet, onBack = { vm.select(null) })
            } else {
                LibraryPane(vm, onOpenSong, onOpenAccount, onAddSong, wide = false, modifier = Modifier.fillMaxSize())
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp))
        }
    }
}

@Composable
private fun LibraryPane(
    vm: LibraryViewModel, onOpenSong: (SongDest) -> Unit, onOpenAccount: () -> Unit, onAddSong: (() -> Unit)?, wide: Boolean, modifier: Modifier,
) {
    val tab by vm.tab.collectAsStateWithLifecycle()
    val disabled by vm.accountDisabled.collectAsStateWithLifecycle()
    val resetNotice by vm.resetNotice.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val favourites by vm.favourites.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val downloaded by vm.downloaded.collectAsStateWithLifecycle()
    val selectedId by vm.selected.collectAsStateWithLifecycle()
    val oc = OrbitTunerTheme.colors

    LazyColumn(modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        item(key = "title") {
            Row(
                Modifier.statusBarsPadding().padding(start = 20.dp, end = 12.dp, top = 22.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Library", style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                if (onAddSong != null) AddSongButton(onAddSong)
            }
        }
        if (disabled) item(key = "account") { AccountBanner(onOpenAccount) }
        if (resetNotice) item(key = "account-reset") { AccountResetNotice(vm::dismissResetNotice, Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) }
        item(key = "tabs") { Tabs(tab, vm::setTab, compact = wide) }
        when (tab) {
            LibraryTab.Playlists -> {
                val all = playlists
                if (all != null) {
                    item(key = "pl-pad") { Spacer(Modifier.height(8.dp)) }
                    items(all, key = { "pl:" + it.playlist.id }) { p ->
                        PlaylistRow(vm, p, selected = wide && p.playlist.id == selectedId, onOpen = { vm.select(p.playlist.id) })
                    }
                    item(key = "new") { NewPlaylistRow(onCreate = { vm.createPlaylist(it, select = wide) }) }
                    if (all.isEmpty()) item(key = "pl-empty") {
                        Text(
                            "Group songs into a set for Sunday, a gig or practice. Add songs from a song's menu.",
                            style = MaterialTheme.typography.bodyMedium, color = oc.muted,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    }
                }
                if (recent.isNotEmpty()) {
                    item(key = "recent-h") { GroupHeader("Played recently") }
                    item(key = "recent") {
                        LazyRow(
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(recent.take(10), key = { it.id }) { s -> RecentCard(s.title, recentLine(s), onClick = { onOpenSong(SongDest(s.id)) }) }
                        }
                    }
                }
            }
            LibraryTab.Favourites -> {
                if (favourites.isEmpty()) item(key = "fav-empty") {
                    EmptyNote("No favourites yet", "Tap the heart on a song and it appears here, ready offline.")
                }
                items(favourites, key = { "fav:" + it.id }) { s -> SongRow(s, s.artist.orEmpty(), onClick = { onOpenSong(SongDest(s.id)) }) }
            }
            LibraryTab.Recent -> {
                if (recent.isEmpty()) item(key = "rec-empty") {
                    EmptyNote("Nothing played yet", "Songs you open show up here, newest first.")
                }
                items(recent, key = { "rec:" + it.id }) { s -> SongRow(s, recentLine(s, withKey = false, withArtist = true), onClick = { onOpenSong(SongDest(s.id)) }) }
            }
            LibraryTab.Downloaded -> {
                if (downloaded.isEmpty()) item(key = "dl-empty") {
                    EmptyNote("No downloads", "Open a playlist and choose Download to keep it on this device for gigs without signal.")
                }
                items(downloaded, key = { "dl:" + it.id }) { s ->
                    SongRow(s, s.artist.orEmpty(), onClick = { onOpenSong(SongDest(s.id)) }) {
                        IconButton(onClick = { vm.removeDownload(s.id) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove download of ${s.title}", tint = oc.faint)
                        }
                    }
                }
            }
        }
    }
}

private fun recentLine(s: dev.avijit.orbittuner.data.db.CachedSong, withKey: Boolean = true, withArtist: Boolean = false): String =
    listOfNotNull(
        s.artist?.takeIf { withArtist && it.isNotBlank() },
        s.keyName()?.takeIf { withKey }?.let { "Key $it" },
        relativeDay(s.lastOpenedAt),
    ).joinToString(", ").replaceFirstChar { it.uppercase() }

@Composable
private fun GroupHeader(text: String) {
    Text(
        text, fontFamily = Literata, fontWeight = FontWeight.SemiBold, fontSize = 17.sp,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun Tabs(current: LibraryTab, onSelect: (LibraryTab) -> Unit, compact: Boolean) {
    val oc = OrbitTunerTheme.colors
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().drawBehind {
            drawLine(oc.line, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx())
        }.padding(start = 20.dp, end = 12.dp, top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 22.dp),
    ) {
        LibraryTab.entries.forEach { t ->
            val on = t == current
            Text(
                t.label, fontFamily = SchibstedGrotesk, fontWeight = FontWeight.SemiBold, fontSize = if (compact) 14.sp else 15.sp,
                color = if (on) MaterialTheme.colorScheme.onBackground else oc.muted,
                maxLines = 1,
                modifier = Modifier
                    .clickable(role = Role.Tab) { onSelect(t) }
                    .semantics { selected = on }
                    .drawBehind {
                        if (on) {
                            val h = 2.5.dp.toPx()
                            drawRect(accent, Offset(0f, size.height - h), androidx.compose.ui.geometry.Size(size.width, h))
                        }
                    }
                    .padding(bottom = 12.dp),
            )
        }
    }
}

/** "+ Add song": a quiet pill beside the title. */
@Composable
private fun AddSongButton(onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp)).background(scheme.surface)
            .clickable(role = Role.Button, onClick = onClick).padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
            .testTag("add-song"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text("Add song", fontFamily = SchibstedGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

/** Shown while the account is disabled; the library keeps working, so it doesn't block anything. */
@Composable
private fun AccountBanner(onOpenAccount: () -> Unit) {
    val warn = OrbitTunerTheme.tunerColors.sharp
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(warn.copy(alpha = 0.10f)).border(1.dp, warn.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .testTag("account-banner").padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = warn, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text("Not syncing", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "This account is disabled, so nothing syncs. Your favourites and playlists stay on this device.",
                style = MaterialTheme.typography.bodySmall, color = OrbitTunerTheme.colors.muted,
            )
        }
        TextButton(onClick = onOpenAccount) { Text("Account") }
    }
}

@Composable
private fun PlaylistRow(vm: LibraryViewModel, p: PlaylistWithSongs, selected: Boolean, onOpen: () -> Unit) {
    val oc = OrbitTunerTheme.colors
    val n = p.songs.size
    val offline = n > 0 && p.songs.all { it.song?.pinnedOffline == true }
    var menu by remember { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    Row(
        Modifier.fillMaxWidth().background(if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.background)
            .clickable(onClick = onOpen).padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PlaylistCover(n, coverTurn(p.playlist.id), 56.dp)
        Column(Modifier.weight(1f)) {
            Text(
                p.playlist.name, fontFamily = Literata, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 22.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(songCount(n), style = MaterialTheme.typography.bodyMedium, color = oc.muted)
                if (p.playlist.syncError != null) SyncErrorBadge()
                else if (offline) Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = OrbitTunerTheme.tunerColors.inTune, modifier = Modifier.size(15.dp))
                    Text(" Offline", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = OrbitTunerTheme.tunerColors.inTune)
                }
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Options for ${p.playlist.name}", tint = oc.faint) }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; dialog = "rename" })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; dialog = "delete" })
            }
        }
    }
    PlaylistDialogs(vm, p, dialog, onDismiss = { dialog = null })
}

fun songCount(n: Int) = if (n == 1) "1 song" else "$n songs"

/** The service rejected this playlist's last change; editing it again retries. */
@Composable
fun SyncErrorBadge(modifier: Modifier = Modifier) {
    val warn = OrbitTunerTheme.tunerColors.sharp
    Text(
        "Not synced", fontFamily = SchibstedGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = warn,
        modifier = modifier.clip(CircleShape).background(warn.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Rename and delete dialogs for [p] ([which] = "rename" or "delete"). */
@Composable
fun PlaylistDialogs(vm: LibraryViewModel, p: PlaylistWithSongs, which: String?, onDismiss: () -> Unit) {
    when (which) {
        "rename" -> {
            var name by rememberSaveable(p.playlist.id) { mutableStateOf(p.playlist.name) }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Rename playlist") },
                text = { OutlinedTextField(name, { name = it.take(LibraryRepository.MAX_NAME) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
                confirmButton = { TextButton(onClick = { vm.rename(p.playlist.id, name); onDismiss() }, enabled = name.isNotBlank()) { Text("Rename") } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            )
        }
        "delete" -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Delete ${p.playlist.name}?") },
            text = { Text("The playlist is removed from all your devices. The songs stay in the library.") },
            confirmButton = {
                TextButton(onClick = { vm.delete(p.playlist.id); onDismiss() }) { Text("Delete", color = OrbitTunerTheme.tunerColors.sharp) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }
}

/** "+ New playlist"; tapping turns it into an inline name field (no dialog). */
@Composable
private fun NewPlaylistRow(onCreate: (String) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    val accent = MaterialTheme.colorScheme.primary
    if (!editing) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button) { editing = true }.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
            Text("New playlist", fontFamily = SchibstedGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = accent)
        }
        return
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val submit = {
        if (name.isNotBlank()) { onCreate(name.trim()); name = ""; editing = false }
    }
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface).padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PlaylistCover(0, 0f, 40.dp)
        Box(Modifier.weight(1f)) {
            if (name.isEmpty()) Text("Playlist name", fontFamily = Literata, fontSize = 17.sp, color = OrbitTunerTheme.colors.faint)
            BasicTextField(
                name, { name = it.take(LibraryRepository.MAX_NAME) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = Literata, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp).focusRequester(focus).testTag("new-playlist-name"),
            )
        }
        TextButton(onClick = { name = ""; editing = false }) { Text("Cancel", color = OrbitTunerTheme.colors.muted) }
        TextButton(onClick = submit, enabled = name.isNotBlank()) { Text("Create") }
    }
}

@Composable
private fun RecentCard(title: String, line: String, onClick: () -> Unit) {
    Column(
        Modifier.width(150.dp).heightIn(min = 92.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick).padding(14.dp),
    ) {
        Text(title, fontFamily = Literata, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Text(line, fontSize = 13.sp, color = OrbitTunerTheme.colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
