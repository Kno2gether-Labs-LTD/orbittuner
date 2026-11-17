package dev.avijit.orbittuner.songview

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.os.SystemClock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.avijit.orbittuner.metronome.MetronomeScreen
import dev.avijit.orbittuner.metronome.MetronomeViewModel
import dev.avijit.orbittuner.metronome.Tempo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.avijit.orbittuner.AppGraph
import dev.avijit.orbittuner.settings.songDefaults
import dev.avijit.orbittuner.music.Transposer
import dev.avijit.orbittuner.ui.theme.Literata
import dev.avijit.orbittuner.ui.theme.OrbitTunerTheme
import dev.avijit.orbittuner.ui.theme.SchibstedGrotesk
import dev.avijit.orbittuner.ui.theme.SheetType
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------------------------------
// Route
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Route string of the song view: `song/{id}?playlist={pid}&shift={n}&alt={m}`. [shift]: opened from Sing a line,
 * moved to the sung key (or, with an alternative, offering the close call).
 */
data class SongDest(val songId: String, val playlistId: String? = null, val shift: SungShift? = null) {
    /** This route once the Sing a line shift is used up. */
    fun withoutShift(): SongDest = if (shift == null) this else copy(shift = null)

    fun route(): String {
        val params = listOfNotNull(
            playlistId?.let { "playlist=$it" },
            shift?.let { "shift=${it.semitones}" },
            shift?.alternative?.let { "alt=$it" },
        )
        return "song/$songId" + if (params.isEmpty()) "" else params.joinToString("&", prefix = "?")
    }

    companion object {
        fun parse(route: String?): SongDest? {
            if (route == null || !route.startsWith("song/")) return null
            val rest = route.removePrefix("song/")
            val id = rest.substringBefore('?').ifEmpty { return null }
            val params = rest.substringAfter('?', "").split('&').filter { '=' in it }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
            val pid = params["playlist"]?.ifEmpty { null }
            val shift = params["shift"]?.toIntOrNull()?.let { SungShift(it, params["alt"]?.toIntOrNull()) }
            return SongDest(id, pid, shift)
        }
    }
}

/** Per-route ViewModel stores kept across rotation and cleared when the route closes (what a nav back stack does). */
class RouteStores : ViewModel() {
    val stores = HashMap<String, ViewModelStore>()
    override fun onCleared() = stores.values.forEach { it.clear() }
}

@Composable
internal fun rememberRouteOwner(key: String): ViewModelStoreOwner {
    val holder: RouteStores = viewModel()
    val activity = LocalContext.current.findActivity()
    val store = holder.stores.getOrPut(key) { ViewModelStore() }
    DisposableEffect(key) {
        onDispose { if (activity?.isChangingConfigurations != true) holder.stores.remove(key)?.clear() }
    }
    return remember(store) { object : ViewModelStoreOwner { override val viewModelStore = store } }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Song view wired to the app graph; its ViewModel lives as long as the route (keyed without the Sing a line shift, so
 * dropping a used-up shift keeps the same ViewModel, and a new shift for the open song reaches it via
 * [SongViewModel.applySung]). [onSungConsumed]: the shift is used up (undone, chosen,
 * dismissed...); the caller drops it from its saved route ([SongDest.withoutShift]) so it is never applied again.
 */
@Composable
fun SongRoute(graph: AppGraph, dest: SongDest, onBack: () -> Unit, onSungConsumed: () -> Unit = {}) {
    SongRouteHost(dest, onSungConsumed, create = { songViewModel(graph, dest.songId, dest.playlistId, dest.shift) }) { vm ->
        val keepOn by graph.prefs.keepScreenOn.collectAsStateWithLifecycle(initialValue = false)
        SongScreen(vm, onBack, keepScreenOn = keepOn, metronome = { songMetronome(graph) })
    }
}

/** [SongRoute] without the app graph: the route-scoped ViewModel and the Sing a line shift hand-over. */
@Composable
internal fun SongRouteHost(
    dest: SongDest, onSungConsumed: () -> Unit, create: () -> SongViewModel, content: @Composable (SongViewModel) -> Unit,
) {
    val owner = rememberRouteOwner(dest.withoutShift().route())
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        val vm: SongViewModel = viewModel { create() }
        val consumed by rememberUpdatedState(onSungConsumed)
        // A shift in the route: the one this ViewModel opened with (a no-op), or a new one sung for the song already
        // open (two-pane). Once it is used up, the caller drops it from the route.
        LaunchedEffect(dest.shift) {
            val shift = dest.shift ?: return@LaunchedEffect
            vm.applySung(shift)
            vm.sungConsumed.first { it }
            consumed()
        }
        content(vm)
    }
}

/** A song view model wired to the app graph (Settings supply the keyless spelling and the diagram instrument). */
fun songViewModel(graph: AppGraph, songId: String, playlistId: String?, sung: SungShift? = null) = SongViewModel(
    songId, playlistId, graph.songs, graph.library,
    portalHost = { graph.auth.host.value },
    flagger = { id, reason, note -> graph.api.flag(id, reason, note) },
    defaults = graph.prefs.songDefaults(),
    diagramPrefs = DataStoreDiagramPrefs.get(graph.context),
    sung = sung,
)

/** The song sheet's own metronome controls on the shared engine: it never saves to Settings. */
fun songMetronome(graph: AppGraph) = MetronomeViewModel(graph.metronome, graph.prefs, { SystemClock.elapsedRealtime() }, persist = false)

/** Setlist mode around one song: where it sits in the set, and the song after it (null on the last). */
data class SetlistChrome(val position: Int, val count: Int, val name: String, val next: String?, val onNext: () -> Unit)

// ---------------------------------------------------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------------------------------------------------

private enum class Form { Phone, Tablet, Landscape }

@Composable
fun SongScreen(
    vm: SongViewModel, onBack: () -> Unit, keepScreenOn: Boolean = false, setlist: SetlistChrome? = null,
    /** Builds the metronome controls for the toolbar's sheet; null hides the button. */
    metronome: (() -> MetronomeViewModel)? = null,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    // Hoisted (and saveable) so the place in the song survives rotation; the VM keeps the play state.
    val scroll = rememberScrollState()
    val config = LocalConfiguration.current
    // Tablet layouts need room both ways: a phone in landscape (short height) keeps the phone layout.
    val form = when {
        config.screenHeightDp < 480 -> Form.Phone
        config.screenWidthDp >= 840 && config.screenWidthDp > config.screenHeightDp -> Form.Landscape
        config.screenWidthDp >= 600 -> Form.Tablet
        else -> Form.Phone
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val s = state) {
            SongState.Loading -> Box(contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is SongState.Failed -> FailedView(s.offline, onRetry = vm::retry, onBack = onBack)
            is SongState.Ready -> ReadyView(vm, s.ui, scroll, form, onBack, keepScreenOn, setlist, metronome)
        }
    }
}

@Composable
private fun FailedView(offline: Boolean, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        IconButton(onClick = onBack, modifier = Modifier.padding(6.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (offline) "This song isn't on this device yet" else "This song couldn't be opened",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (offline) "Connect to the internet once to open it. After that it stays readable offline." else "It may have been removed from the library.",
                style = MaterialTheme.typography.bodyLarge, color = OrbitTunerTheme.colors.muted,
            )
            Spacer(Modifier.height(20.dp))
            TextButton(onClick = onRetry) { Text("Try again") }
        }
    }
}

/** The song view keeps the screen on while it scrolls hands-free, when Settings say so, or while the metronome clicks. */
internal fun keepScreenAwake(scrolling: Boolean, setting: Boolean, metronomePlaying: Boolean) =
    scrolling || setting || metronomePlaying

@Composable
private fun ReadyView(
    vm: SongViewModel, ui: SongUi, scroll: androidx.compose.foundation.ScrollState, form: Form, onBack: () -> Unit,
    keepScreenOn: Boolean = false, setlist: SetlistChrome? = null, metronome: (() -> MetronomeViewModel)? = null,
) {
    var diagramFor by rememberSaveable { mutableStateOf<String?>(null) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var metronomeOpen by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(enabled = diagramFor != null) { diagramFor = null }

    // Keep the screen on while scrolling hands-free (or whenever a song is open, if Settings say so).
    val view = LocalView.current
    var metronomePlaying by remember { mutableStateOf(false) }
    val awake = keepScreenAwake(ui.scroll.playing, keepScreenOn, metronomePlaying)
    DisposableEffect(awake) {
        view.keepScreenOn = awake
        onDispose { view.keepScreenOn = false }
    }

    val scope = rememberCoroutineScope()
    val share: () -> Unit = {
        val name = vm.exportFileName()
        val text = vm.exportText()
        scope.launch { shareChordPro(context, name, text) }
    }
    // Play from the top only when explicitly started at the end; never rewinds on its own (e.g. after rotation).
    val playPause: () -> Unit = {
        if (ui.scroll.playing) vm.setPlaying(false)
        else scope.launch {
            if (scroll.maxValue in 1..scroll.value) scroll.animateScrollTo(0)
            vm.setPlaying(true)
        }
    }
    val openUrl: (String) -> Unit = { url ->
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            scope.launch { snackbar.showSnackbar("No browser available to open the portal") }
        }
    }
    val diagramOpen = diagramFor != null || dialog == "flag"

    // While the diagram panel is up it is modal for accessibility too: the song behind it leaves the semantics tree.
    Column(Modifier.fillMaxSize().then(if (diagramOpen) Modifier.clearAndSetSemantics { } else Modifier)) {
        TopBar(ui, form, onBack, vm::toggleFavourite, share, onMenu = { action ->
            when (action) {
                MenuAction.Playlist -> dialog = "playlist"
                MenuAction.Share -> share()
                MenuAction.ChordsOnly -> vm.setChordsOnly(!ui.chordsOnly)
                MenuAction.Flag -> dialog = "flag"
                MenuAction.Portal -> Unit
            }
        }, portalUrl = vm.portalUrl(), openUrl = openUrl, setlist = setlist,
            onMetronome = if (metronome != null) ({ metronomeOpen = true }) else null)

        val strip = @Composable { mod: Modifier, pad: PaddingValues ->
            ChordStrip(
                ui.doc.chords, ui.instrument, highlight = ui.doc.meta["key"], onOpen = { diagramFor = it },
                modifier = mod, contentPadding = pad,
            )
        }
        when (form) {
            Form.Landscape -> Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Controls(vm, ui, Modifier.padding(start = 28.dp), stretch = false)
                strip(Modifier.weight(1f), PaddingValues(start = 16.dp, end = 28.dp))
            }
            Form.Tablet -> {
                Controls(vm, ui, Modifier.padding(start = 28.dp, end = 28.dp, top = 4.dp).widthIn(max = 620.dp).fillMaxWidth())
                strip(Modifier.padding(top = 12.dp, bottom = 4.dp), PaddingValues(horizontal = 28.dp))
            }
            Form.Phone -> {
                Controls(vm, ui, Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp).fillMaxWidth())
                strip(Modifier.padding(top = 12.dp, bottom = 4.dp), PaddingValues(horizontal = 16.dp))
            }
        }

        SungBannerView(
            ui, vm,
            Modifier.padding(horizontal = if (form == Form.Phone) 16.dp else 28.dp).padding(top = 8.dp)
                .then(if (form == Form.Phone) Modifier.fillMaxWidth() else Modifier.widthIn(max = 620.dp).fillMaxWidth()),
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            Sheet(vm, ui, scroll, form)
            // Soft fade into the dock.
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(150.dp)
                    .background(Brush.verticalGradient(0f to Color.Transparent, 0.7f to MaterialTheme.colorScheme.background)),
            )
            Dock(
                vm, ui, playPause,
                Modifier.align(if (form == Form.Phone) Alignment.BottomCenter else Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = if (form == Form.Phone) 16.dp else 28.dp, bottom = if (form == Form.Phone) 18.dp else 24.dp)
                    .then(if (form == Form.Phone) Modifier.fillMaxWidth() else Modifier.width(420.dp)),
            )
            if (setlist?.next != null) {
                NextChip(
                    setlist.next, setlist.onNext, atEnd = scroll.isAtEnd(),
                    modifier = if (form == Form.Phone) {
                        Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 104.dp)
                    } else {
                        Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 28.dp, bottom = 36.dp)
                    },
                )
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp))
        }
    }

    // Chord diagram panel.
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(diagramFor != null, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { diagramFor = null },
            )
        }
        AnimatedVisibility(
            diagramFor != null,
            modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = 560.dp),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            val name = diagramFor
            if (name != null) DiagramSheet(name, ui.instrument, vm::setInstrument, onClose = { diagramFor = null })
        }
    }

    // Flag sheet: same bottom-panel treatment as the chord diagram.
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(dialog == "flag", enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { dialog = null },
            )
        }
        AnimatedVisibility(
            dialog == "flag",
            modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = 560.dp),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            FlagSheet(ui.title, onSend = { reason, note -> vm.flag(reason, note); dialog = null }, onClose = { dialog = null })
        }
    }
    BackHandler(enabled = dialog == "flag") { dialog = null }

    if (metronome != null && metronomeOpen) {
        MetronomeSheet(
            metronome, ui.tempoBpm, ui.timeSig,
            onPlaying = { metronomePlaying = it },
            onDismiss = { metronomeOpen = false },
        )
    }

    when (dialog) {
        "playlist" -> PlaylistDialog(vm, onDismiss = { dialog = null })
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Top bar and controls
// ---------------------------------------------------------------------------------------------------------------------

private enum class MenuAction { Playlist, Share, ChordsOnly, Flag, Portal }

@Composable
private fun TopBar(
    ui: SongUi, form: Form, onBack: () -> Unit, onFavourite: () -> Unit, onShare: () -> Unit,
    onMenu: (MenuAction) -> Unit, portalUrl: String?, openUrl: (String) -> Unit, setlist: SetlistChrome? = null,
    onMetronome: (() -> Unit)? = null,
) {
    val oc = OrbitTunerTheme.colors
    val big = form != Form.Phone
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().statusBarsPadding()
            .padding(start = if (big) 14.dp else 6.dp, end = if (big) 20.dp else 6.dp, top = if (big) 10.dp else 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                ui.title, fontFamily = Literata, fontWeight = FontWeight.SemiBold, fontSize = if (big) 28.sp else 22.sp,
                lineHeight = if (big) 32.sp else 25.sp, letterSpacing = (-0.2).sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(ui.artist, ui.timeSig?.let { "$it time" }).joinToString(", ")
            if (setlist != null) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)) {
                            append("${setlist.position} of ${setlist.count}")
                        }
                        append(if (sub.isEmpty()) ", ${setlist.name}" else ", $sub")
                    },
                    style = MaterialTheme.typography.bodyMedium, color = oc.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            } else if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = oc.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onFavourite) {
            Icon(
                if (ui.isFavourite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (ui.isFavourite) "Remove from favourites" else "Add to favourites",
                tint = if (ui.isFavourite) MaterialTheme.colorScheme.primary else oc.muted,
            )
        }
        if (onMetronome != null) IconButton(onClick = onMetronome) { Icon(MetronomeIcon, contentDescription = "Metronome") }
        if (big) IconButton(onClick = onShare) { Icon(Icons.Filled.Share, contentDescription = "Share") }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Add to playlist") }, onClick = { menu = false; onMenu(MenuAction.Playlist) })
                DropdownMenuItem(text = { Text("Share as ChordPro") }, onClick = { menu = false; onMenu(MenuAction.Share) })
                DropdownMenuItem(
                    text = { Text("Chords only") },
                    trailingIcon = { if (ui.chordsOnly) Icon(Icons.Filled.Check, contentDescription = "On") },
                    onClick = { menu = false; onMenu(MenuAction.ChordsOnly) },
                )
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Flag this song") }, onClick = { menu = false; onMenu(MenuAction.Flag) })
                if (portalUrl != null) {
                    DropdownMenuItem(text = { Text("Edit in portal") }, onClick = { menu = false; openUrl(portalUrl) })
                }
            }
        }
    }
}

@Composable
private fun Controls(vm: SongViewModel, ui: SongUi, modifier: Modifier, stretch: Boolean = true) {
    val oc = OrbitTunerTheme.colors
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Key / transpose.
        var keyMenu by remember { mutableStateOf(false) }
        val base = ui.originalKey
        Stepper(
            value = ui.soundingKey ?: ui.inferredKey?.let { "≈ $it" } ?: signed(ui.transpose),
            spoken = ui.soundingKey ?: ui.inferredKey?.let { "about $it" } ?: signed(ui.transpose),
            caption = when {
                base == null && ui.inferredKey != null -> if (ui.transpose == 0) "key" else "key, ${signed(ui.transpose)}"
                base == null -> "transpose"
                ui.transpose == 0 -> "key"
                else -> "key, from $base"
            },
            minusLabel = "Transpose down", plusLabel = "Transpose up",
            onMinus = { vm.transposeBy(-1) }, onPlus = { vm.transposeBy(1) },
            onValue = { if (base != null) keyMenu = true },
            valueLabel = "Key",
        ) {
            DropdownMenu(keyMenu, onDismissRequest = { keyMenu = false }) {
                if (base != null) for (n in -5..6) {
                    val k = Transposer.transposeKey(base, n)
                    DropdownMenuItem(
                        text = { Text(k, fontWeight = if (n == ui.transpose) FontWeight.Bold else FontWeight.Normal) },
                        trailingIcon = { Text(if (n == 0) "original" else signed(n), color = oc.muted, style = MaterialTheme.typography.labelMedium) },
                        onClick = { keyMenu = false; vm.setKey(k) },
                    )
                }
            }
        }
        Box(Modifier.width(1.dp).height(30.dp).background(oc.line))
        var capoMenu by remember { mutableStateOf(false) }
        Stepper(
            value = ui.capo.toString(),
            caption = if (ui.capo > 0 && ui.doc.meta["key"] != null) "capo, ${ui.doc.meta["key"]} shapes" else "capo",
            minusLabel = "Capo down", plusLabel = "Capo up",
            onMinus = { vm.setCapo(ui.capo - 1) }, onPlus = { vm.setCapo(ui.capo + 1) },
            onValue = { capoMenu = true },
            valueLabel = "Capo",
        ) {
            DropdownMenu(capoMenu, onDismissRequest = { capoMenu = false }) {
                for (c in 0..MAX_CAPO) DropdownMenuItem(
                    text = { Text(if (c == 0) "No capo" else "Capo $c", fontWeight = if (c == ui.capo) FontWeight.Bold else FontWeight.Normal) },
                    onClick = { capoMenu = false; vm.setCapo(c) },
                )
            }
        }
        Spacer(if (stretch) Modifier.weight(1f) else Modifier.width(4.dp))
        TextSizeButton(ui.textScale, onScale = { vm.setTextScale(it) })
    }
}

private fun signed(n: Int) = if (n > 0) "+$n" else n.toString().replace('-', '−')

@Composable
private fun Stepper(
    value: String, caption: String, spoken: String = value, minusLabel: String, plusLabel: String,
    onMinus: () -> Unit, onPlus: () -> Unit, onValue: () -> Unit, valueLabel: String,
    menu: @Composable () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        StepButton("−", minusLabel, onMinus)
        Box {
            Column(
                Modifier.widthIn(min = 58.dp).clip(RoundedCornerShape(10.dp))
                    .clickable(onClickLabel = "Choose ${valueLabel.lowercase(Locale.ROOT)}", onClick = onValue)
                    .semantics { contentDescription = "$valueLabel $spoken" }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(value, fontFamily = SchibstedGrotesk, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 20.sp)
                Text(caption, fontSize = 11.5.sp, lineHeight = 13.sp, color = OrbitTunerTheme.colors.muted, maxLines = 1)
            }
            menu()
        }
        StepButton("+", plusLabel, onPlus)
    }
}

@Composable
private fun StepButton(symbol: String, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(OrbitTunerTheme.colors.raised)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, fontFamily = SchibstedGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
    }
}

@Composable
private fun TextSizeButton(scale: Float, onScale: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val oc = OrbitTunerTheme.colors
    Box {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontSize = 15.sp, color = oc.muted)) { append("A") }
                withStyle(SpanStyle(fontSize = 20.sp)) { append("A") }
            },
            fontFamily = Literata, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = "Text size") { open = true }
                .semantics { contentDescription = "Text size ${(scale * 100).roundToInt()} percent" }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        DropdownMenu(open, onDismissRequest = { open = false }) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                StepButton("−", "Smaller text") { onScale(((scale - 0.1f) * 10).roundToInt() / 10f) }
                Text("${(scale * 100).roundToInt()}%", Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.titleSmall)
                StepButton("+", "Larger text") { onScale(((scale + 0.1f) * 10).roundToInt() / 10f) }
            }
            Text(
                "Pinch the sheet to zoom", style = MaterialTheme.typography.labelSmall, color = oc.faint,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Sheet and auto-scroll
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun Sheet(vm: SongViewModel, ui: SongUi, scroll: androidx.compose.foundation.ScrollState, form: Form) {
    val base = when (form) {
        Form.Phone -> SheetType.Phone
        Form.Tablet -> SheetType.Tablet
        Form.Landscape -> SheetType.TabletLandscape
    }
    val styles = rememberSheetStyles(base, ui.textScale)
    var viewport by remember { mutableStateOf(0) }
    val speed by rememberUpdatedState(ui.scroll.speed * ui.textScale)
    val scale by rememberUpdatedState(ui.textScale)
    val lines = remember(ui.doc) { ui.doc.lyricLineCount() }

    // Tempo-based default speed, once the sheet has been measured (px/s at text scale 1).
    val max = scroll.maxValue
    LaunchedEffect(max, viewport, lines, ui.tempoBpm, ui.timeSig) {
        if (viewport > 0 && max != Int.MAX_VALUE) {
            val px = AutoScroller.defaultSpeed(ui.tempoBpm, ui.timeSig, lines, max + viewport, viewport)
            vm.setAutoSpeed(px / scale)
        }
    }
    LaunchedEffect(ui.scroll.playing) {
        if (ui.scroll.playing) AutoScroller.run(scroll, { speed }, onEnd = { vm.setPlaying(false) })
    }

    // Reading place as a fraction of the scroll range: saved across rotation/fold and re-applied whenever the sheet
    // reflows (new width, columns or text size), so the same lines stay in view.
    val anchor = rememberSaveable { mutableFloatStateOf(-1f) }
    LaunchedEffect(scroll) {
        var lastMax = -1
        snapshotFlow { scroll.value to scroll.maxValue }.collect { (v, m) ->
            if (m <= 0 || m == Int.MAX_VALUE) return@collect
            if (m != lastMax && anchor.floatValue >= 0f) {
                lastMax = m
                val target = (anchor.floatValue * m).roundToInt().coerceIn(0, m)
                if (target != v) {
                    scroll.scrollTo(target)
                    return@collect
                }
            }
            lastMax = m
            anchor.floatValue = v.toFloat() / m
        }
    }

    // Pinch scales the drawn sheet live (cheap) and re-lays it out once, at the end.
    var pinch by remember { mutableFloatStateOf(1f) }

    val playing by rememberUpdatedState(ui.scroll.playing)
    val pad = when (form) {
        Form.Phone -> PaddingValues(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 150.dp)
        Form.Tablet -> PaddingValues(start = 40.dp, end = 40.dp, top = 14.dp, bottom = 150.dp)
        Form.Landscape -> PaddingValues(start = 40.dp, end = 40.dp, top = 18.dp, bottom = 150.dp)
    }
    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { viewport = it.height }
            .pinchToZoom(
                onZoom = { z -> pinch = (pinch * z).coerceIn(MIN_SCALE / scale, MAX_SCALE / scale) },
                onEnd = { vm.setTextScale(scale * pinch); pinch = 1f },
            )
            .pointerInput(Unit) { detectTapGestures { if (playing) vm.setPlaying(false) } }
            .testTag("sheet")
            .verticalScroll(scroll),
    ) {
        SongSheet(
            ui.doc, styles, ui.chordsOnly, columns = if (form == Form.Landscape) 2 else 1,
            viewportPx = viewport,
            modifier = Modifier.fillMaxWidth()
                .graphicsLayer {
                    scaleX = pinch
                    scaleY = pinch
                    transformOrigin = TransformOrigin(0f, ((scroll.value + viewport / 2f) / size.height.coerceAtLeast(1f)).coerceIn(0f, 1f))
                }
                .padding(pad),
        )
    }
}

/** True once the end of the song is on screen (also when the whole song fits). */
@Composable
private fun androidx.compose.foundation.ScrollState.isAtEnd(): Boolean {
    val st = this
    val end by remember(st) { derivedStateOf { st.maxValue != Int.MAX_VALUE && st.value >= st.maxValue - 4 } }
    return end
}

/** "Next: <title>" in setlist mode, shown once the end of the song is reached. */
@Composable
private fun NextChip(title: String, onNext: () -> Unit, atEnd: Boolean, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    AnimatedVisibility(atEnd, modifier = modifier, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut()) {
        Row(
            Modifier.clip(CircleShape).background(scheme.onBackground)
                .clickable(role = Role.Button, onClickLabel = "Open next song", onClick = onNext)
                .padding(start = 18.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Next: $title", color = scheme.background, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = scheme.background, modifier = Modifier.size(18.dp))
        }
    }
}

/** Two-finger pinch on the sheet; single-finger gestures pass through to scrolling. */
private fun Modifier.pinchToZoom(onZoom: (Float) -> Unit, onEnd: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var zoomed = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.none { it.pressed }) break
            if (event.changes.count { it.pressed } >= 2) {
                val z = event.calculateZoom()
                if (z != 1f) { onZoom(z); zoomed = true }
                event.changes.forEach { it.consume() }
            }
        }
        if (zoomed) onEnd()
    }
}

private const val MIN_MULT = 0.25f
private const val MAX_MULT = 2.5f

@Composable
private fun Dock(vm: SongViewModel, ui: SongUi, onPlayPause: () -> Unit, modifier: Modifier) {
    val oc = OrbitTunerTheme.colors
    val mult = (ui.scroll.speed / ui.defaultSpeed.coerceAtLeast(1f)).coerceIn(MIN_MULT, MAX_MULT)
    Row(
        modifier
            .shadow(24.dp, RoundedCornerShape(22.dp), ambientColor = Color.Black, spotColor = Color.Black)
            .clip(RoundedCornerShape(22.dp))
            .background(oc.raised)
            .padding(start = 10.dp, end = 18.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PlayButton(ui.scroll.playing, onClick = onPlayPause)
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 9.dp)) {
                Text(
                    buildAnnotatedString {
                        append("Auto-scroll ")
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold)) {
                            append(String.format(Locale.ROOT, "%.1f×", mult))
                        }
                    },
                    fontSize = 13.sp, color = oc.muted, modifier = Modifier.weight(1f),
                )
                Text(
                    ui.tempoBpm?.let { "from $it bpm" } ?: "3 min pace",
                    fontSize = 13.sp, color = oc.muted,
                )
            }
            SpeedRail(
                fraction = (mult - MIN_MULT) / (MAX_MULT - MIN_MULT),
                onChange = { f, done ->
                    val m = MIN_MULT + f * (MAX_MULT - MIN_MULT)
                    // Snap to the tempo estimate around 1.0x.
                    vm.setSpeed(if (kotlin.math.abs(m - 1f) < 0.04f) null else m * ui.defaultSpeed, persist = done)
                },
                label = String.format(Locale.ROOT, "%.1f times", mult),
            )
        }
    }
}

@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(scheme.primary)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (playing) "Pause" else "Play" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            val c = scheme.onPrimary
            if (playing) {
                val w = size.width * 0.26f
                val r = androidx.compose.ui.geometry.CornerRadius(w / 3, w / 3)
                drawRoundRect(c, Offset(size.width * 0.16f, size.height * 0.1f), androidx.compose.ui.geometry.Size(w, size.height * 0.8f), r)
                drawRoundRect(c, Offset(size.width * 0.58f, size.height * 0.1f), androidx.compose.ui.geometry.Size(w, size.height * 0.8f), r)
            } else {
                val p = Path().apply {
                    moveTo(size.width * 0.26f, size.height * 0.12f)
                    lineTo(size.width * 0.88f, size.height * 0.5f)
                    lineTo(size.width * 0.26f, size.height * 0.88f)
                    close()
                }
                drawPath(p, c)
            }
        }
    }
}

/** Thin rail with a round thumb (mockup style); drag or tap. [onChange] gets (fraction, finished). */
@Composable
private fun SpeedRail(fraction: Float, onChange: (Float, Boolean) -> Unit, label: String) {
    val oc = OrbitTunerTheme.colors
    val scheme = MaterialTheme.colorScheme
    val f by rememberUpdatedState(fraction.coerceIn(0f, 1f))
    var width by remember { mutableStateOf(1) }
    Box(
        Modifier.fillMaxWidth().height(22.dp)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .semantics {
                contentDescription = "Scroll speed"
                stateDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(f, 0f..1f)
                setProgress { v -> onChange(v.coerceIn(0f, 1f), true); true }
            }
            .pointerInput(Unit) {
                detectTapGestures { o -> onChange((o.x / width).coerceIn(0f, 1f), true) }
            }
            .pointerInput(Unit) {
                var cur = 0f
                detectHorizontalDragGestures(
                    onDragStart = { o -> cur = (o.x / width).coerceIn(0f, 1f); onChange(cur, false) },
                    onDragEnd = { onChange(cur, true) },
                    onDragCancel = { onChange(cur, true) },
                ) { change, _ ->
                    cur = (change.position.x / width).coerceIn(0f, 1f)
                    onChange(cur, false)
                    change.consume()
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val y = size.height / 2
            val h = 4.dp.toPx()
            val r = androidx.compose.ui.geometry.CornerRadius(h / 2, h / 2)
            val x = size.width * f
            drawRoundRect(oc.line, Offset(0f, y - h / 2), androidx.compose.ui.geometry.Size(size.width, h), r)
            drawRoundRect(scheme.primary, Offset(0f, y - h / 2), androidx.compose.ui.geometry.Size(x, h), r)
            drawCircle(oc.raised, 13.dp.toPx(), Offset(x, y))
            drawCircle(scheme.onBackground, 9.dp.toPx(), Offset(x, y))
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Dialogs
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun PlaylistDialog(vm: SongViewModel, onDismiss: () -> Unit) {
    val lists by vm.playlists.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            Column {
                if (lists.isEmpty()) {
                    Text("No playlists yet. Name one to start it with this song.", color = OrbitTunerTheme.colors.muted)
                } else {
                    LazyColumn(Modifier.fillMaxWidth().height((lists.size.coerceAtMost(5) * 48).dp)) {
                        items(lists, key = { it.first }) { (id, n) ->
                            Text(
                                n, style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                    .clickable { vm.addToPlaylist(id, n); onDismiss() }
                                    .padding(horizontal = 8.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(name, { name = it.take(80) }, label = { Text("New playlist") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.createPlaylistAndAdd(name); onDismiss() }, enabled = name.isNotBlank()) { Text("Create and add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------------------------------------------------------------
// Export
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Shares [text] as a `.cho` file (FileProvider) plus plain text, through the system chooser. The file is written off the
 * main thread; earlier exports are deleted first so the cache doesn't grow.
 */
suspend fun shareChordPro(context: Context, fileName: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_TITLE, fileName)
        putExtra(Intent.EXTRA_SUBJECT, fileName.removeSuffix(".cho"))
    }
    val uri = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, fileName).apply { writeText(text) }
            FileProvider.getUriForFile(context, context.packageName + ".exports", file)
        } catch (_: Exception) {
            null // No file (e.g. storage full): the text alone still shares.
        }
    }
    if (uri != null) {
        send.putExtra(Intent.EXTRA_STREAM, uri)
        send.clipData = ClipData.newRawUri(fileName, uri)
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, "Share $fileName")
    if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(chooser)
    } catch (_: ActivityNotFoundException) { /* nothing can share text: nothing to do */ }
}

// ---------------------------------------------------------------------------------------------------------------------
// Metronome sheet
// ---------------------------------------------------------------------------------------------------------------------

/** A metronome silhouette (a tapered case with a pendulum), drawn here because the icon set has none. */
private val MetronomeIcon: ImageVector by lazy {
    ImageVector.Builder("Metronome", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 2f); lineTo(8.2f, 2f); lineTo(4.5f, 20f); curveTo(4.35f, 20.9f, 5f, 22f, 6f, 22f)
            lineTo(18f, 22f); curveTo(19f, 22f, 19.65f, 20.9f, 19.5f, 20f); lineTo(15.8f, 2f); close()
            moveTo(9.7f, 4f); lineTo(14.3f, 4f); lineTo(17.4f, 20f); lineTo(6.6f, 20f); close()
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(11f, 18f); lineTo(11f, 9.5f); lineTo(15.5f, 5.5f); lineTo(16.8f, 6.9f); lineTo(13f, 10.3f); lineTo(13f, 18f); close()
        }
    }.build()
}

/**
 * The song's metronome in a bottom sheet, started at its `{tempo}` and `{time}`. The sheet has its own view model on
 * the shared engine (it never saves to Settings), and it never touches keep-screen-on: the song view manages that.
 * Closing the sheet, leaving the song or leaving the app stops the clicks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MetronomeSheet(
    factory: () -> MetronomeViewModel, tempo: Int?, timeSig: String?, onPlaying: (Boolean) -> Unit, onDismiss: () -> Unit,
) {
    val vm: MetronomeViewModel = viewModel(key = "song-metronome", initializer = { factory() })
    // Report up so the song view (which alone manages keep-screen-on) keeps the screen awake while it clicks.
    val engineState by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(engineState.playing) { onPlaying(engineState.playing) }
    DisposableEffect(Unit) { onDispose { onPlaying(false) } }
    // Load once per opening, and not under a metronome that is already playing (a rotation recreates the sheet).
    LaunchedEffect(vm, tempo, timeSig) {
        if (!vm.state.value.playing) vm.load(tempo, Tempo.beatsFor(timeSig))
    }
    val activity = LocalContext.current.findActivity()
    fun leave() { if (activity?.isChangingConfigurations != true) vm.stop() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { leave() }
    DisposableEffect(vm) { onDispose { leave() } }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(36.dp, 4.dp).clip(CircleShape).background(OrbitTunerTheme.colors.line))
        },
    ) {
        MetronomeScreen(vm, Modifier.fillMaxWidth().navigationBarsPadding(), compact = true)
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Sing a line banner
// ---------------------------------------------------------------------------------------------------------------------

/**
 * After Sing a line: "Moved to A to match your singing · Undo" (with a capo tip for keys that are awkward in open
 * position), or the close call "Sounds like A or E" with both keys to pick from. Slides away when there is nothing to say.
 */
@Composable
private fun SungBannerView(ui: SongUi, vm: SongViewModel, modifier: Modifier) {
    val reduceMotion = dev.avijit.orbittuner.tuner.rememberReduceMotion()
    // Keep the last banner while it animates out.
    val last = remember { arrayOfNulls<SungBanner>(1) }
    ui.sungBanner?.let { last[0] = it }
    AnimatedVisibility(
        ui.sungBanner != null, modifier,
        enter = if (reduceMotion) fadeIn(androidx.compose.animation.core.snap()) else androidx.compose.animation.expandVertically() + fadeIn(),
        exit = if (reduceMotion) fadeOut(androidx.compose.animation.core.snap()) else androidx.compose.animation.shrinkVertically() + fadeOut(),
    ) {
        val b = ui.sungBanner ?: last[0] ?: return@AnimatedVisibility
        val oc = OrbitTunerTheme.colors
        val accent = MaterialTheme.colorScheme.primary
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface)
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                .testTag("sung-banner"),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(oc.raised), contentAlignment = Alignment.Center) {
                    Icon(dev.avijit.orbittuner.songs.SongsIcons.Mic, contentDescription = null, tint = accent, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(12.dp))
                when (b) {
                    is SungBanner.Moved -> {
                        Text(
                            buildAnnotatedString {
                                append("Moved to ")
                                withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) { append(b.key) }
                                append(" to match your singing")
                            },
                            style = BannerText, modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                        )
                        TextButton(onClick = vm::undoSungShift, modifier = Modifier.height(48.dp)) {
                            Text("Undo", fontFamily = SchibstedGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        }
                    }
                    is SungBanner.CloseCall -> {
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text("Sounds like ${b.a.key} or ${b.b.key}", style = BannerText)
                            Text("Pick the one that fits your voice", style = MaterialTheme.typography.bodyMedium, color = oc.muted)
                        }
                        Box(
                            Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = vm::dismissSungShift)
                                .semantics { contentDescription = "Keep the song's key" },
                            contentAlignment = Alignment.Center,
                        ) { Icon(dev.avijit.orbittuner.songs.SongsIcons.Clear, contentDescription = null, tint = oc.muted, modifier = Modifier.size(18.dp)) }
                    }
                }
            }
            when (b) {
                is SungBanner.Moved -> b.capo?.let { hint ->
                    Row(Modifier.padding(start = 46.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Capo ${hint.capo} and ${hint.shapes} shapes are easier",
                            style = MaterialTheme.typography.bodyMedium, color = oc.muted, modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { vm.setCapo(hint.capo) }, modifier = Modifier.height(48.dp)) {
                            Text("Use capo ${hint.capo}", fontFamily = SchibstedGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        }
                    }
                }
                is SungBanner.CloseCall -> Row(
                    Modifier.padding(start = 46.dp, end = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (c in listOf(b.a, b.b)) KeyChoice(c.key, onClick = { vm.chooseSungShift(c.semitones) })
                }
            }
        }
    }
}

private val BannerText = androidx.compose.ui.text.TextStyle(fontFamily = SchibstedGrotesk, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp)

@Composable
private fun KeyChoice(key: String, onClick: () -> Unit) {
    Box(
        Modifier.height(48.dp).widthIn(min = 72.dp).clip(RoundedCornerShape(12.dp)).background(OrbitTunerTheme.colors.raised)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = "Play in $key" }
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(key, fontFamily = SchibstedGrotesk, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
    }
}
