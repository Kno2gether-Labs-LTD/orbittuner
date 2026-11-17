package dev.avijit.orbittuner.songs

import android.content.res.Configuration
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.selection.selectable
import androidx.window.core.layout.WindowWidthSizeClass
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldDefaults
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldDestinationItem
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth
import androidx.compose.material3.adaptive.layout.calculateThreePaneScaffoldValue
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.avijit.orbittuner.AppGraph
import dev.avijit.orbittuner.add.DiscoverQuery
import dev.avijit.orbittuner.add.WebTier
import dev.avijit.orbittuner.add.discoverQuery
import dev.avijit.orbittuner.add.asContributions
import dev.avijit.orbittuner.net.AuthStore
import dev.avijit.orbittuner.songview.SongDest
import dev.avijit.orbittuner.voice.SingLineViewModel
import dev.avijit.orbittuner.voice.singLineHost
import androidx.compose.ui.text.TextRange
import dev.avijit.orbittuner.songview.SongRoute
import dev.avijit.orbittuner.ui.theme.BrandRing
import dev.avijit.orbittuner.ui.theme.Literata
import dev.avijit.orbittuner.ui.theme.OrbitTunerTheme
import dev.avijit.orbittuner.ui.theme.SchibstedGrotesk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.Locale

// ---------------------------------------------------------------------------------------------------------------------
// Route
// ---------------------------------------------------------------------------------------------------------------------

/** Signed in with a usable token (no account yet, or a revoked one being replaced, counts as not connected). */
fun AuthStore.hasAccountFlow(): Flow<Boolean> = combine(host, user, needsNewAccount) { h, u, revoked -> h != null && u != null && !revoked }

/** The Songs tab wired to the app graph. On a phone a tap opens the song route; on a tablet it opens beside the list. */
@Composable
fun SongsRoute(graph: AppGraph, onOpenSong: (SongDest) -> Unit, onOpenSettings: () -> Unit, onAddSong: (() -> Unit)? = null) {
    val vm: SearchViewModel = viewModel {
        SearchViewModel(
            localSearch = graph.songs::localSearch,
            remoteSearch = graph.songs::remoteSearch,
            facets = graph.api::facets,
            browse = graph.api::browse,
            hasAccount = graph.auth.hasAccountFlow(),
            contributions = graph.api.asContributions(),
            // Fetched, not cached: a web result joins "On this device" only once it is opened.
            fetchSong = { id -> graph.api.song(id).toRow() },
            onAiUsed = { graph.accounts.refresh() },
            accountId = combine(graph.auth.host, graph.auth.user) { h, u -> u?.let { "$h|${it.id}" } },
            history = graph::historySignals,
            onCategoryBrowsed = graph::recordBrowsedCategory,
        )
    }
    val sing: SingLineViewModel = viewModel {
        SingLineViewModel(recorder = graph::voiceRecorder, search = graph.voiceSearch::run, shiftFor = { graph.voiceSearch.shiftFor(it) })
    }
    SearchScreen(
        vm, onOpenSong = { onOpenSong(SongDest(it)) }, onOpenSettings = onOpenSettings,
        detail = { dest, close, sungDone -> SongRoute(graph, dest, onBack = close, onSungConsumed = sungDone) },
        onAddSong = onAddSong,
        singLine = sing,
        onOpenDest = onOpenSong,
    )
}

// ---------------------------------------------------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Tiered search over an Explore view. At ≥ 600 dp the list sits in a [ListDetailPaneScaffold] with [detail] (the song
 * view) on the right; below that a tap calls [onOpenSong].
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun SearchScreen(
    vm: SearchViewModel,
    onOpenSong: (String) -> Unit,
    onOpenSettings: () -> Unit,
    /** The song beside the list. [sungDone]: its Sing a line shift is used up, so it leaves the saved route. */
    detail: @Composable (dest: SongDest, close: () -> Unit, sungDone: () -> Unit) -> Unit,
    /** Opens Add song; null hides the entry points. */
    onAddSong: (() -> Unit)? = null,
    /** Sing a line (Beta): the mic in the search bar; null hides it. */
    singLine: SingLineViewModel? = null,
    /** Phone: opens a song with its route options (a Sing a line result carries the sung-key shift). */
    onOpenDest: (SongDest) -> Unit = { onOpenSong(it.songId) },
) {
    // Same source as the scaffold's directive, so "two panes" and the layout never disagree.
    val windowInfo = currentWindowAdaptiveInfo()
    val twoPane = windowInfo.windowSizeClass.windowWidthSizeClass != WindowWidthSizeClass.COMPACT
    // The song beside the list, as its route (so a Sing a line shift survives rotation too).
    var selectedRoute by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedDest = SongDest.parse(selectedRoute)
    val selected = selectedDest?.songId
    val focus = LocalFocusManager.current
    val openDest: (SongDest) -> Unit = { d ->
        focus.clearFocus()
        if (twoPane) selectedRoute = d.route() else if (d.shift == null && d.playlistId == null) onOpenSong(d.songId) else onOpenDest(d)
    }
    val open: (String) -> Unit = { id -> openDest(SongDest(id)) }

    // Back closes the song beside the list; folding a tablet to phone width hands the open song to the phone route.
    BackHandler(enabled = twoPane && selected != null) { selectedRoute = null }
    LaunchedEffect(twoPane) {
        val d = selectedDest
        if (!twoPane && d != null) { selectedRoute = null; if (d.shift == null) onOpenSong(d.songId) else onOpenDest(d) }
    }
    if (!twoPane) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            SongsList(vm, Selection(null, enabled = false), onOpen = open, onOpenSettings = onOpenSettings, showTitle = false, onAddSong = onAddSong, singLine = singLine, onOpenDest = openDest)
        }
        return
    }
    val oc = OrbitTunerTheme.colors
    val directive = calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth(windowInfo)
        .copy(horizontalPartitionSpacerSize = 0.dp, defaultPanePreferredWidth = LIST_PANE_WIDTH)
    val value = calculateThreePaneScaffoldValue(
        directive.maxHorizontalPartitions,
        ListDetailPaneScaffoldDefaults.adaptStrategies(),
        ThreePaneScaffoldDestinationItem(ListDetailPaneScaffoldRole.Detail, selectedRoute),
    )
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        ListDetailPaneScaffold(
            directive = directive,
            value = value,
            listPane = {
                AnimatedPane(Modifier.drawWithContent {
                    drawContent()
                    drawLine(oc.line, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1.dp.toPx())
                }) {
                    SongsList(vm, Selection(selected, enabled = true), onOpen = open, onOpenSettings = onOpenSettings, showTitle = true, onAddSong = onAddSong, singLine = singLine, onOpenDest = openDest)
                }
            },
            detailPane = {
                AnimatedPane {
                    val d = selectedDest
                    // Keyed without the shift: dropping a used-up shift must not remount the song (or its ViewModel).
                    if (d == null) EmptyDetail() else key(d.withoutShift().route()) {
                        PaneSized {
                            detail(d, { selectedRoute = null }) {
                                if (SongDest.parse(selectedRoute)?.songId == d.songId) selectedRoute = d.withoutShift().route()
                            }
                        }
                    }
                }
            },
        )
    }
}

private val LIST_PANE_WIDTH = 360.dp

/**
 * Tells a screen that sizes itself from the window (the song view picks its phone/tablet form from `screenWidthDp`)
 * which form fits the pane. The width is quantized to one value per form, so the configuration doesn't change on every
 * frame of a pane animation: [COMPACT_PANE_DP] below 600 dp, else [WIDE_PANE_DP] (tablet form; deliberately under the
 * song view's 840 dp landscape threshold, so a pane never gets the two-column layout).
 *
 * TODO: remove once SongScreen chooses its form from its own constraints (BoxWithConstraints) instead of
 *  LocalConfiguration; then the pane needs no override.
 */
@Composable
private fun PaneSized(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val base = LocalConfiguration.current
        val bucket = if (maxWidth < 600.dp) COMPACT_PANE_DP else WIDE_PANE_DP
        val pane = remember(base, bucket) { Configuration(base).apply { screenWidthDp = bucket } }
        CompositionLocalProvider(LocalConfiguration provides pane) { content() }
    }
}

private const val COMPACT_PANE_DP = 411
private const val WIDE_PANE_DP = 720

/** Which row is open beside the list; [enabled] only in two-pane mode (a phone has no "selected" row). */
internal class Selection(val id: String?, val enabled: Boolean) {
    fun of(rowId: String): Boolean? = if (enabled) rowId == id else null
}

@Composable
private fun SongsList(
    vm: SearchViewModel, selection: Selection, onOpen: (String) -> Unit, onOpenSettings: () -> Unit, showTitle: Boolean,
    onAddSong: (() -> Unit)?, singLine: SingLineViewModel? = null, onOpenDest: (SongDest) -> Unit = { onOpen(it.songId) },
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val explore by vm.explore.collectAsStateWithLifecycle()
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(vm.ui.value.query)) }
    val searching = field.text.isNotBlank()
    // After process death the field text is restored but the new ViewModel starts empty: hand the text back to it.
    LaunchedEffect(Unit) { if (vm.ui.value.query != field.text) vm.setQuery(field.text) }
    // While a new query loads, the previous Library rows stay (dimmed) instead of flashing to placeholders.
    // Plain (non-snapshot) memory: it is only read in the same composition that updates it.
    val lastLibrary = remember { arrayOfNulls<LibraryTier.Results>(1) }
    val library = ui.library
    if (library is LibraryTier.Results) lastLibrary[0] = library
    if (!searching) lastLibrary[0] = null
    val stale = if (library == LibraryTier.Loading) lastLibrary[0] else null
    // Web import polling runs only while the list is on screen (opening a song on a phone pauses it).
    DisposableEffect(vm) {
        vm.resumeWeb()
        onDispose { vm.pauseWeb() }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        if (showTitle) {
            Text(
                "Songs", style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 6.dp).semantics { heading() },
            )
        }
        val onMic = singLine?.let { sing ->
            singLineHost(
                sing, onOpenSong = onOpenDest,
                onSearchWeb = { q -> field = TextFieldValue(q, TextRange(q.length)); vm.findOnWeb(q) },
                onSignIn = onOpenSettings,
            )
        }
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchField(field, onChange = { field = it; vm.setQuery(it.text) }, Modifier.weight(1f), onMic = onMic)
            if (onAddSong != null) {
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface)
                        .clickable(role = Role.Button, onClick = onAddSong).semantics { contentDescription = "Add a song" },
                    contentAlignment = Alignment.Center,
                ) { Icon(SongsIcons.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) }
            }
        }
        LazyColumn(
            Modifier.fillMaxSize().testTag("songs-list"),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            if (searching) {
                item(key = "filters") { SearchFilters(explore, vm::selectLanguage, vm::selectCategory) }
                searchTiers(ui, stale, selection, onOpen, onOpenSettings, vm::loadMore, vm::retry)
                webTier(ui, selection, onOpen, vm::findOnWeb, vm::retryWeb, onOpenSettings, onAddSong)
            } else {
                exploreContent(explore, selection, onOpen, onOpenSettings, vm::selectLanguage, vm::selectCategory, vm::loadMoreExplore, vm::retry)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Search field
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun SearchField(value: TextFieldValue, onChange: (TextFieldValue) -> Unit, modifier: Modifier = Modifier, onMic: (() -> Unit)? = null) {
    val oc = OrbitTunerTheme.colors
    val scheme = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    Row(
        modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(16.dp)).background(scheme.surface).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(SongsIcons.Search, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = FieldStyle.copy(color = scheme.onSurface),
            cursorBrush = SolidColor(scheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            modifier = Modifier.weight(1f).testTag("search-field"),
            decorationBox = { inner ->
                // The placeholder lives in the field's decoration, so it is read as the field's hint, not as a label override.
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.text.isEmpty()) Text("Songs, artists or lyrics", style = FieldStyle, color = oc.faint, maxLines = 1)
                    inner()
                }
            },
        )
        if (value.text.isNotEmpty()) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button) { onChange(TextFieldValue("")) }
                    .semantics { contentDescription = "Clear search" },
                contentAlignment = Alignment.Center,
            ) { Icon(SongsIcons.Clear, contentDescription = null, tint = oc.muted, modifier = Modifier.size(20.dp)) }
        }
        if (onMic != null) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = { focus.clearFocus(); onMic() })
                    .semantics { contentDescription = "Sing a line" },
                contentAlignment = Alignment.Center,
            ) { Icon(SongsIcons.Mic, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(24.dp)) }
        }
    }
}

private val FieldStyle = TextStyle(fontFamily = SchibstedGrotesk, fontWeight = FontWeight.Normal, fontSize = 18.sp, lineHeight = 24.sp)

// ---------------------------------------------------------------------------------------------------------------------
// Tiers
// ---------------------------------------------------------------------------------------------------------------------

private fun LazyListScope.searchTiers(
    ui: SearchUi, stale: LibraryTier.Results?, selection: Selection, onOpen: (String) -> Unit, onOpenSettings: () -> Unit,
    onMore: () -> Unit, onRetry: () -> Unit,
) {
    val q = ui.query
    if (ui.yours.isNotEmpty()) {
        item(key = "h-yours") { TierHeader(SongsIcons.Yours, "Your songs", ui.yours.size.toString()) }
        items(ui.yours, key = { "y-" + it.id }) { SongRowItem(it, q, selection.of(it.id), onOpen) }
    }
    if (ui.cached.isNotEmpty()) {
        item(key = "h-device") { TierHeader(SongsIcons.Device, "On this device", ui.cached.size.toString()) }
        items(ui.cached, key = { "c-" + it.id }) { SongRowItem(it, q, selection.of(it.id), onOpen) }
    }
    if (ui.library == LibraryTier.Idle) {
        // One letter searches only this device.
        if (ui.yours.isEmpty() && ui.cached.isEmpty()) item(key = "l-short") { Note("Keep typing to search the library") }
    } else {
        val shown = (ui.yours + ui.cached).mapTo(HashSet()) { it.id }
        libraryTier(
            ui.library, q, selection, onOpen, onOpenSettings, onMore, onRetry,
            emptyText = if (ui.yours.isEmpty() && ui.cached.isEmpty()) "No songs match “${q.trim()}”" else "Nothing more in the library",
            keyPrefix = "l",
            stale = stale?.let { it.copy(rows = it.rows.filterNot { r -> r.id in shown }) },
        )
    }
}

/** "Find on the web" (offered when the library has fewer than 3 results), then the "From the web" tier. */
private fun LazyListScope.webTier(
    ui: SearchUi, selection: Selection, onOpen: (String) -> Unit, onFind: () -> Unit, onRetry: () -> Unit,
    onOpenSettings: () -> Unit, onAddSong: (() -> Unit)?,
) {
    if (!ui.webAvailable || ui.query.trim().length < SearchViewModel.MIN_REMOTE_CHARS) return
    val q = ui.query
    when (val web = ui.web) {
        WebTier.Idle -> {
            val lib = ui.library
            val few = lib is LibraryTier.Results && !lib.hasMore && lib.rows.size < 3
            when (discoverQuery(q)) {
                DiscoverQuery.Ok -> if (few) item(key = "web") { FindOnTheWeb(q.trim(), onFind) }
                // A pasted link: the web search wants a song name; links are imported from Add song.
                DiscoverQuery.Link -> item(key = "web-link") {
                    if (onAddSong != null) NoteWithAction("That looks like a link. Import it from Add song, under Link.", "Add song", onAddSong)
                    else Note("That looks like a link. Search for the song name instead.")
                }
                else -> Unit
            }
        }
        WebTier.Searching -> {
            item(key = "web-h") { TierHeader(SongsIcons.Globe, "From the web", "Searching…") }
            item(key = "web-searching") { WorkingNote("Searching chord sites for “${q.trim()}”. This can take up to a minute.") }
        }
        is WebTier.Importing -> {
            item(key = "web-h") { TierHeader(SongsIcons.Globe, "From the web", "${web.rows.size} so far") }
            items(web.rows, key = { "w-" + it.row.id }) { SongRowItem(it.row, q, selection.of(it.row.id), onOpen, badge = if (it.inReview) "In review" else null) }
            if (web.pending > 0) item(key = "web-pending") {
                WorkingNote(if (web.pending == 1) "Importing 1 more page…" else "Importing ${web.pending} more pages…")
            }
        }
        is WebTier.Done -> {
            item(key = "web-h") { TierHeader(SongsIcons.Globe, "From the web", web.rows.size.toString()) }
            items(web.rows, key = { "w-" + it.row.id }) { SongRowItem(it.row, q, selection.of(it.row.id), onOpen, badge = if (it.inReview) "In review" else null) }
            web.note?.let { n -> item(key = "web-note") { Note(n) } }
        }
        is WebTier.Failed -> {
            item(key = "web-h") { TierHeader(SongsIcons.Globe, "From the web", "") }
            item(key = "web-fail") {
                when {
                    web.limit -> NoteWithAction(web.message, "Get more", onOpenSettings)
                    web.canRetry -> NoteWithAction(web.message, "Try again", onRetry)
                    else -> Note(web.message)
                }
            }
        }
    }
    if (onAddSong != null && ui.web !is WebTier.Searching && discoverQuery(q) != DiscoverQuery.Link) item(key = "add-own") {
        NoteWithAction("Can't find it? Add it yourself.", "Add song", onAddSong)
    }
}

/** The service-backed list: header, then rows or the state of the connection. Shared with Explore's browse list. */
internal fun LazyListScope.libraryTier(
    tier: LibraryTier, query: String, selection: Selection, onOpen: (String) -> Unit, onOpenSettings: () -> Unit,
    onMore: () -> Unit, onRetry: () -> Unit, emptyText: String, keyPrefix: String, title: String = "Library",
    stale: LibraryTier.Results? = null,
) {
    val count = when (tier) {
        is LibraryTier.Results -> if (tier.hasMore) "${tier.rows.size}+" else tier.rows.size.toString()
        LibraryTier.Loading -> "Searching…"
        else -> ""
    }
    item(key = "$keyPrefix-h") { TierHeader(SongsIcons.Library, title, count) }
    when (tier) {
        LibraryTier.Idle -> Unit
        LibraryTier.Loading ->
            if (stale != null && stale.rows.isNotEmpty()) {
                items(stale.rows, key = { "$keyPrefix-" + it.id }) { SongRowItem(it, query, selection.of(it.id), onOpen, dim = true) }
            } else {
                items(3, key = { "$keyPrefix-sk$it" }) { SkeletonRow(it) }
            }
        is LibraryTier.Results -> {
            if (tier.rows.isEmpty()) item(key = "$keyPrefix-empty") {
                // With more pages, an empty first page only means its matches are already listed above.
                Note(if (tier.hasMore) "Matches so far are already listed above" else emptyText)
            }
            items(tier.rows, key = { "$keyPrefix-" + it.id }) { SongRowItem(it, query, selection.of(it.id), onOpen) }
            if (tier.moreFailed) item(key = "$keyPrefix-morefail") {
                NoteWithAction("Couldn't load more", "Retry", onMore)
            } else if (tier.hasMore) item(key = "$keyPrefix-more") {
                TextButton(onClick = onMore, modifier = Modifier.padding(start = 76.dp, top = 2.dp)) { Text("Show more", style = MaterialTheme.typography.labelLarge) }
            }
        }
        LibraryTier.Offline -> item(key = "$keyPrefix-off") {
            NoteWithAction("You're offline — showing songs on this device", "Try again", onRetry, icon = SongsIcons.Offline)
        }
        LibraryTier.NoAccount -> item(key = "$keyPrefix-account") { NoAccountCard(onOpenSettings) }
        LibraryTier.AccountDisabled -> item(key = "$keyPrefix-disabled") {
            NoteWithAction("This account is disabled, so the song library can't be searched. Songs on this device still work.", "Account", onOpenSettings)
        }
        LibraryTier.Unavailable -> item(key = "$keyPrefix-err") {
            NoteWithAction("The song library isn't answering right now", "Try again", onRetry)
        }
    }
}

@Composable
internal fun NoteWithAction(text: String, action: String, onAction: () -> Unit, icon: ImageVector? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Note(text, modifier = Modifier.weight(1f), icon = icon)
        TextButton(onClick = onAction, modifier = Modifier.padding(end = 12.dp)) { Text(action, style = MaterialTheme.typography.labelLarge) }
    }
}

@Composable
internal fun TierHeader(icon: ImageVector?, title: String, count: String) {
    val oc = OrbitTunerTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 6.dp).semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
        }
        Text(title, style = HeaderStyle, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
        if (count.isNotEmpty()) Text(count, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = oc.faint)
    }
}

internal val HeaderStyle = TextStyle(fontFamily = Literata, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp)

@Composable
internal fun SongRowItem(row: SongRow, query: String, selected: Boolean?, onOpen: (String) -> Unit, dim: Boolean = false, badge: String? = null) {
    val oc = OrbitTunerTheme.colors
    val scheme = MaterialTheme.colorScheme
    val isSelected = selected == true
    Row(
        Modifier.fillMaxWidth().heightIn(min = 68.dp)
            .alpha(if (dim) 0.45f else 1f)
            .background(if (isSelected) scheme.surface else scheme.background)
            .then(
                // Two-pane: a selectable list (the open song is "selected"); phone: a plain button that navigates.
                if (selected != null) Modifier.selectable(selected = selected, role = Role.Button, onClick = { onOpen(row.id) })
                else Modifier.clickable(role = Role.Button, onClickLabel = "Open song") { onOpen(row.id) },
            )
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .testTag("row-" + row.id),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KeyTile(row.key, if (isSelected) oc.raised else scheme.surface)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            HighlightedTitle(row.title, query)
            val sub = subtitle(row)
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = oc.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (badge != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                badge,
                style = TextStyle(fontFamily = SchibstedGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                color = scheme.primary, maxLines = 1,
                modifier = Modifier.clip(CircleShape).background(scheme.primary.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun KeyTile(key: String?, bg: androidx.compose.ui.graphics.Color) {
    val oc = OrbitTunerTheme.colors
    Box(
        Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(bg)
            .semantics { contentDescription = if (key != null) "Key of $key" else "Key unknown" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            key ?: "·",
            style = TextStyle(fontFamily = SchibstedGrotesk, fontWeight = FontWeight.Bold, fontSize = if ((key?.length ?: 1) > 2) 14.sp else 16.sp),
            color = if (key != null) oc.chord else oc.faint, maxLines = 1,
        )
    }
}

/** Category, then artist; the language is added when it isn't the device's own. */
private fun subtitle(row: SongRow): String {
    val parts = listOfNotNull(row.categories.firstOrNull(), row.artist?.takeIf { it.isNotBlank() }).toMutableList()
    if (row.language != Locale.getDefault().language || parts.isEmpty()) parts += languageName(row.language)
    return parts.joinToString(", ")
}

/** Display name of an ISO language code in the device language ("hi" -> "Hindi"); the code itself when unknown. */
@Suppress("DEPRECATION")
fun languageName(code: String): String =
    Locale(code).getDisplayLanguage().takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }
        ?.replaceFirstChar { it.titlecase(Locale.getDefault()) }
        ?: code.uppercase(Locale.ROOT)

private val WORD = Regex("[\\p{L}\\p{M}\\p{N}]+")

/** Character ranges of [text] matched by the query's terms (each term is a word prefix, like the full-text search). */
fun matchRanges(text: String, query: String): List<IntRange> {
    val terms = WORD.findAll(query).map { it.value.lowercase(Locale.ROOT) }.toList()
    if (terms.isEmpty()) return emptyList()
    return WORD.findAll(text).mapNotNull { w ->
        val word = w.value.lowercase(Locale.ROOT)
        val len = terms.filter { word.startsWith(it) }.maxOfOrNull { it.length } ?: return@mapNotNull null
        w.range.first until w.range.first + len
    }.toList()
}

/** The title with matched text in bold, underlined in the accent colour (drawn, so it can sit below the descenders). */
@Composable
private fun HighlightedTitle(title: String, query: String) {
    val accent = MaterialTheme.colorScheme.primary
    val ranges = remember(title, query) { matchRanges(title, query) }
    val text = remember(title, ranges) {
        AnnotatedString(title, spanStyles = ranges.map { AnnotatedString.Range(SpanStyle(fontWeight = FontWeight.Bold), it.first, it.last + 1) })
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text, style = TitleStyle, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout = it },
        modifier = Modifier.drawBehind {
            val l = layout ?: return@drawBehind
            if (l.lineCount == 0) return@drawBehind
            val visibleEnd = l.getLineEnd(0, visibleEnd = true)
            val y = l.getLineBaseline(0) + 4.dp.toPx()
            for (r in ranges) {
                val end = minOf(r.last + 1, visibleEnd)
                if (r.first >= end) continue
                val a = l.getHorizontalPosition(r.first, usePrimaryDirection = true)
                val b = l.getHorizontalPosition(end, usePrimaryDirection = true)
                drawLine(accent, Offset(minOf(a, b), y), Offset(maxOf(a, b), y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
        },
    )
}

private val TitleStyle = TextStyle(fontFamily = SchibstedGrotesk, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 22.sp)

@Composable
private fun SkeletonRow(i: Int) {
    val fill = OrbitTunerTheme.colors.raised.copy(alpha = 0.55f)
    Row(Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(fill))
        Spacer(Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.width(listOf(180, 140, 200)[i % 3].dp).height(13.dp).clip(RoundedCornerShape(6.dp)).background(fill))
            Box(Modifier.width(listOf(110, 90, 120)[i % 3].dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(fill))
        }
    }
}

@Composable
internal fun Note(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val oc = OrbitTunerTheme.colors
    Row(modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = oc.muted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = oc.muted)
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Cards
// ---------------------------------------------------------------------------------------------------------------------

@Composable
internal fun NoAccountCard(onOpenSettings: () -> Unit) {
    val oc = OrbitTunerTheme.colors
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface).padding(20.dp).testTag("no-account-card"),
    ) {
        Text("Connecting to the song library", style = HeaderStyle.copy(fontSize = 18.sp, lineHeight = 24.sp))
        Spacer(Modifier.height(6.dp))
        Text(
            "OrbitTuner sets up your account as soon as you're online, then every song is a search away. Songs already on this device work offline.",
            style = MaterialTheme.typography.bodyMedium, color = oc.muted,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onOpenSettings, shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        ) { Text("Account settings", style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp)) }
    }
}

/** The offer to search the web for [query]: a dashed card, like the mockup's. */
@Composable
private fun FindOnTheWeb(query: String, onFind: () -> Unit) {
    val oc = OrbitTunerTheme.colors
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp).fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .dashedOutline(accent.copy(alpha = 0.55f), 18.dp)
            .clickable(role = Role.Button, onClick = onFind)
            .padding(16.dp)
            .testTag("find-on-web")
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(SongsIcons.Globe, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Find on the web", style = TextStyle(fontFamily = SchibstedGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 16.sp), color = MaterialTheme.colorScheme.onBackground)
            Text(
                "Search chord sites for “$query” and add the best matches",
                style = MaterialTheme.typography.bodyMedium, color = oc.muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(SongsIcons.Down, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = -90f })
    }
}

/** A muted line with a small spinner: work in progress on the service. */
@Composable
private fun WorkingNote(text: String) {
    val oc = OrbitTunerTheme.colors
    Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary, trackColor = oc.raised)
        }
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = oc.muted)
    }
}

/** A 1.5 dp dashed rounded outline, like the mockup's `border: 1.5px dashed`. */
private fun Modifier.dashedOutline(color: androidx.compose.ui.graphics.Color, radius: androidx.compose.ui.unit.Dp): Modifier = drawBehind {
    val w = 1.5.dp.toPx()
    drawRoundRect(
        color, topLeft = Offset(w / 2, w / 2), size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius.toPx() - w / 2),
        style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
    )
}

@Composable
private fun EmptyDetail() {
    val oc = OrbitTunerTheme.colors
    Column(
        Modifier.fillMaxSize().padding(32.dp).testTag("empty-detail"),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(96.dp).drawBehind {
            val stroke = 9.dp.toPx()
            val r = size.minDimension / 2 - stroke
            drawCircle(Brush.linearGradient(BrandRing), radius = r, style = Stroke(stroke), alpha = 0.55f)
            val planet = Offset(center.x + r * 0.70f, center.y - r * 0.70f)
            drawCircle(BrandRing[0], radius = 8.dp.toPx(), center = planet, alpha = 0.8f)
        })
        Spacer(Modifier.height(24.dp))
        Text("Pick a song", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(6.dp))
        Text("Its chords open here, ready to play.", style = MaterialTheme.typography.bodyLarge, color = oc.muted)
    }
}
