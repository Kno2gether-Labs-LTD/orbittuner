package dev.avijit.orbittuner.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.avijit.orbittuner.data.PlaylistSong
import dev.avijit.orbittuner.data.PlaylistWithSongs
import dev.avijit.orbittuner.music.Transposer
import dev.avijit.orbittuner.ui.theme.Literata
import dev.avijit.orbittuner.ui.theme.OrbitTunerTheme
import dev.avijit.orbittuner.ui.theme.SchibstedGrotesk
import kotlin.math.roundToInt

/**
 * A playlist as a setlist (mockup: "Library, playlist as setlist"): cover and name, Play set / Download / Export,
 * then the songs in play order with each item's key and capo. Rows reorder by dragging their handle; tapping a row
 * plays the set from that song. [onBack] is null in the two-pane layout.
 */
@Composable
fun PlaylistDetail(
    vm: LibraryViewModel,
    p: PlaylistWithSongs,
    onPlaySet: (playlistId: String, start: Int) -> Unit,
    onBack: (() -> Unit)?,
) {
    val oc = OrbitTunerTheme.colors
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val exporting by vm.exporting.collectAsStateWithLifecycle()
    val moveFailures by vm.moveFailures.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    val id = p.playlist.id

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 480.dp
        val side = if (compact) 16.dp else 32.dp
        val order = remember(p.songs) { p.songs }
        Column(Modifier.fillMaxSize()) {
            // Top bar: back (phones) and the playlist menu.
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = 6.dp, end = 6.dp, top = if (onBack == null) 10.dp else 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to library") }
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Playlist options", tint = oc.muted) }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; dialog = "rename" })
                        DropdownMenuItem(text = { Text("Delete playlist") }, onClick = { menu = false; dialog = "delete" })
                    }
                }
            }
            // Header.
            Row(
                Modifier.fillMaxWidth().padding(start = side, end = side, top = 0.dp, bottom = 20.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 18.dp else 22.dp),
            ) {
                PlaylistCover(p.songs.size, coverTurn(id), if (compact) 88.dp else 112.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        p.playlist.name, fontFamily = Literata, fontWeight = FontWeight.SemiBold,
                        fontSize = if (compact) 28.sp else 34.sp, lineHeight = if (compact) 32.sp else 38.sp, letterSpacing = (-0.3).sp,
                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (p.songs.isEmpty()) "No songs yet." else songCount(p.songs.size) + ". Each song keeps its own key, capo and scroll speed.",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp), color = oc.muted,
                    )
                }
            }
            if (p.playlist.syncError != null) SyncErrorNote(Modifier.padding(start = side, end = side, bottom = 16.dp))
            // Actions.
            val progress = downloads[id]
            val offline = p.songs.isNotEmpty() && p.songs.all { it.song?.pinnedOffline == true }
            Row(Modifier.padding(start = side, end = side, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(
                    primary = true, enabled = p.songs.isNotEmpty(), onClick = { onPlaySet(id, 0) },
                    modifier = if (compact) Modifier.weight(1f) else Modifier,
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text("Play set")
                }
                when {
                    progress != null -> PillButton(enabled = false, onClick = {}, modifier = Modifier.testTag("download-progress")) {
                        val f by animateFloatAsState(if (progress.total == 0) 0f else progress.done / progress.total.toFloat(), label = "download")
                        CircularProgressIndicator(
                            progress = { f }, modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary, trackColor = oc.line,
                        )
                        Text("${progress.done} of ${progress.total}", color = MaterialTheme.colorScheme.onSurface)
                    }
                    offline -> PillButton(onClick = {}, enabled = true) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = OrbitTunerTheme.tunerColors.inTune, modifier = Modifier.size(20.dp))
                        Text(if (compact) "Offline" else "Available offline", color = OrbitTunerTheme.tunerColors.inTune)
                    }
                    else -> PillButton(onClick = { vm.download(id) }, enabled = p.songs.isNotEmpty()) {
                        DownloadIcon(MaterialTheme.colorScheme.onSurface)
                        Text("Download")
                    }
                }
                PillButton(
                    onClick = { vm.export(p) }, enabled = p.songs.isNotEmpty() && id !in exporting,
                    modifier = Modifier.semantics { contentDescription = "Export as ZIP" },
                ) {
                    if (id in exporting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else ExportIcon(MaterialTheme.colorScheme.onSurface)
                }
            }
            if (p.songs.isEmpty()) {
                Text(
                    "Open a song and choose Add to playlist from its menu. Songs keep the key, capo and speed you set here.",
                    style = MaterialTheme.typography.bodyMedium, color = oc.muted,
                    modifier = Modifier.padding(horizontal = side),
                )
            } else {
                Setlist(
                    order,
                    onOpen = { index -> onPlaySet(id, index) },
                    onMoved = { newOrder, songId -> vm.moved(id, newOrder, songId) },
                    resetKey = moveFailures,
                    side = if (compact) 4.dp else 20.dp,
                    compact = compact,
                )
            }
        }
    }
    PlaylistDialogs(vm, p, dialog, onDismiss = { dialog = null })
}

@Composable
private fun SyncErrorNote(modifier: Modifier) {
    val warn = OrbitTunerTheme.tunerColors.sharp
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(warn.copy(alpha = 0.10f)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SyncErrorBadge()
        Text(
            "Your song library didn't accept the last change. Edit the playlist to try again.",
            style = MaterialTheme.typography.bodySmall, color = OrbitTunerTheme.colors.muted,
        )
    }
}

/** Mockup `.pbtn`: 48dp, radius 14; primary is copper, the rest sit on the surface colour. */
@Composable
private fun PillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (primary) scheme.primary else scheme.surface
    val fg = if (primary) scheme.onPrimary else scheme.onSurface
    Row(
        modifier.height(48.dp).clip(RoundedCornerShape(14.dp)).background(if (enabled || !primary) bg else bg.copy(alpha = 0.4f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides fg,
            androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp, color = fg),
        ) { content() }
    }
}

/**
 * Songs in play order (mockup `.setl`). Dragging a row's handle lifts it; it settles where it is dropped and
 * [onMoved] gets the new order and the moved song. Rows are equal height, so the drop slot follows from the drag
 * distance alone. Move up / down are also offered as accessibility actions.
 */
@Composable
private fun Setlist(
    songs: List<PlaylistSong>,
    onOpen: (Int) -> Unit,
    onMoved: (List<String>, String) -> Unit,
    side: androidx.compose.ui.unit.Dp,
    compact: Boolean,
    resetKey: Int = 0,
) {
    val oc = OrbitTunerTheme.colors
    // Local order while the move is saved; reset when the playlist changes or a move fails ([resetKey]).
    var order by remember(songs, resetKey) { mutableStateOf(songs) }
    val listState = rememberLazyListState()
    // Row slots (top, height) measured when the drag started, by song id; the drop slot is the one under the finger.
    var slots by remember { mutableStateOf<Map<String, Pair<Int, Int>>>(emptyMap()) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var from by remember { mutableIntStateOf(0) }
    var dy by remember { mutableFloatStateOf(0f) }
    var rowPx by remember { mutableIntStateOf(1) }
    val latest by rememberUpdatedState(order)
    val moved by rememberUpdatedState(onMoved)
    // Read from state on every call: the drag callbacks below outlive the composition that created them.
    fun targetIndex(): Int {
        val list = latest
        val start = list.getOrNull(from)?.let { slots[it.songId] } ?: return (from + (dy / rowPx).roundToInt()).coerceIn(0, list.lastIndex)
        val centre = start.first + dy + start.second / 2f
        var best = from
        var first = -1; var last = -1
        list.forEachIndexed { i, s ->
            val (top, h) = slots[s.songId] ?: return@forEachIndexed
            if (first < 0) first = i
            last = i
            if (centre >= top && centre < top + h) best = i
        }
        if (first >= 0) {
            val (top0, _) = slots.getValue(list[first].songId)
            val (topN, hN) = slots.getValue(list[last].songId)
            if (centre < top0) best = first
            if (centre >= topN + hN) best = last
        }
        return best.coerceIn(0, list.lastIndex)
    }
    val target = if (dragging == null) -1 else targetIndex()

    /** Where the lifted row's own slot sits in the reordered list, relative to where it started. */
    fun slotShift(t: Int): Float {
        val list = latest
        val start = list.getOrNull(from)?.let { slots[it.songId] }
        val dest = list.getOrNull(t)?.let { slots[it.songId] }
        if (start == null || dest == null) return ((t - from) * rowPx).toFloat()
        val top = if (t <= from) dest.first else dest.first + dest.second - start.second
        return (top - start.first).toFloat()
    }
    // While dragging, the list is drawn in its would-be order with the lifted row following the finger.
    val shown = if (dragging == null || target == from) order else order.toMutableList().apply { add(target, removeAt(from)) }

    fun drop() {
        val id = dragging ?: return
        val t = targetIndex()
        dragging = null; dy = 0f
        if (t != from && from in latest.indices) {
            val next = latest.toMutableList().apply { add(t, removeAt(from)) }
            order = next
            moved(next.map { it.songId }, id)
        }
    }

    fun nudge(index: Int, by: Int) {
        val t = index + by
        if (t !in order.indices) return
        val next = order.toMutableList().apply { add(t, removeAt(index)) }
        val id = order[index].songId
        order = next
        moved(next.map { it.songId }, id)
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = side),
        state = listState,
        contentPadding = PaddingValues(bottom = 32.dp),
        userScrollEnabled = dragging == null,
    ) {
        item(key = "top-rule") { Box(Modifier.fillMaxWidth().height(1.dp).background(oc.line)) }
        itemsIndexed(shown, key = { _, s -> s.songId }) { index, s ->
            val lifted = s.songId == dragging
            val settled = if (lifted) dy - slotShift(target) else 0f
            val moveActions = listOfNotNull(
                if (index > 0) CustomAccessibilityAction("Move up") { nudge(index, -1); true } else null,
                if (index < shown.lastIndex) CustomAccessibilityAction("Move down") { nudge(index, 1); true } else null,
            )
            Row(
                Modifier
                    .then(if (lifted) Modifier.zIndex(1f) else Modifier.animateItem())
                    .graphicsLayer {
                        translationY = settled
                        if (lifted) { shadowElevation = 18f; scaleX = 1.01f; scaleY = 1.01f }
                    }
                    .fillMaxWidth()
                    .onSizeChanged { if (!lifted) rowPx = it.height.coerceAtLeast(1) }
                    .clip(if (lifted) RoundedCornerShape(14.dp) else RoundedCornerShape(0.dp))
                    .background(if (lifted) oc.raised else MaterialTheme.colorScheme.background)
                    .drawBehind {
                        if (!lifted) drawLine(oc.line, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx())
                    }
                    .clickable(enabled = dragging == null, onClickLabel = "Play from here") { onOpen(index) }
                    .semantics { customActions = moveActions }
                    .testTag("setlist-row")
                    .heightIn(min = 70.dp)
                    .padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
            ) {
                Text(
                    "${index + 1}", fontFamily = Literata, fontWeight = FontWeight.Medium, fontSize = 18.sp, color = oc.faint,
                    modifier = Modifier.width(26.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        s.song?.title ?: "Song not on this device yet", style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (s.song == null) oc.muted else Color.Unspecified,
                    )
                    val artist = s.song?.artist
                    if (!artist.isNullOrBlank()) Text(artist, style = MaterialTheme.typography.bodyMedium, color = oc.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val capo = s.settings.capo ?: 0
                Text(
                    if (capo > 0) "Capo $capo" else "No capo", fontSize = 13.sp, color = oc.muted,
                    modifier = Modifier.widthIn(min = 60.dp), maxLines = 1,
                )
                Text(
                    soundingKey(s).orEmpty(), fontFamily = SchibstedGrotesk, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = oc.chord,
                    textAlign = TextAlign.End, modifier = Modifier.width(36.dp),
                )
                // Drag handle: a 44dp target around the two bars.
                Box(
                    Modifier.size(44.dp).testTag("grip")
                        // Its own semantics node (not merged into the row): a separate handle for accessibility and tests.
                        .semantics(mergeDescendants = true) {
                            contentDescription = "Reorder ${s.song?.title ?: "song"}"
                            customActions = moveActions
                        }
                        .pointerInput(s.songId) {
                            detectDragGestures(
                                onDragStart = {
                                    slots = listState.layoutInfo.visibleItemsInfo
                                        .filter { it.key is String && it.key != "top-rule" }
                                        .associate { (it.key as String) to (it.offset to it.size) }
                                    from = latest.indexOfFirst { it.songId == s.songId }
                                    dy = 0f
                                    dragging = s.songId
                                },
                                onDragEnd = { drop() },
                                onDragCancel = { drop() },
                            ) { change, amount ->
                                change.consume()
                                dy += amount.y
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) { GripIcon(oc.faint) }
            }
        }
    }
}

/** Key the item sounds in: the song's key shifted by the item's transpose. */
private fun soundingKey(s: PlaylistSong): String? {
    val base = s.song?.keyName() ?: return null
    val t = s.settings.transpose ?: 0
    return if (t == 0) base else runCatching { Transposer.transposeKey(base, t) }.getOrDefault(base)
}
