package dev.avijit.orbittuner.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.avijit.orbittuner.AppGraph
import dev.avijit.orbittuner.songview.SetlistChrome
import dev.avijit.orbittuner.songview.SongScreen
import dev.avijit.orbittuner.songview.SongViewModel
import dev.avijit.orbittuner.songview.rememberRouteOwner
import dev.avijit.orbittuner.songview.songViewModel
import kotlinx.coroutines.launch

/** One ViewModelStore per setlist page, kept across rotation; [keepOnly] clears the rest. */
class SetlistPageStores : ViewModel() {
    private val stores = HashMap<String, ViewModelStore>()

    fun owner(songId: String): ViewModelStoreOwner {
        val store = stores.getOrPut(songId) { ViewModelStore() }
        return object : ViewModelStoreOwner { override val viewModelStore = store }
    }

    /** Clears every page store not in [songIds]; returns the cleared song ids. */
    fun keepOnly(songIds: Set<String>): List<String> {
        val gone = stores.keys.filter { it !in songIds }
        gone.forEach { stores.remove(it)?.clear() }
        return gone
    }

    override fun onCleared() = stores.values.forEach { it.clear() }
}

/** One song of a setlist. */
data class SetlistItem(val songId: String, val title: String)

/** Route string of setlist mode: `setlist/{playlistId}?start={index}`. */
data class SetlistDest(val playlistId: String, val start: Int = 0) {
    fun route(): String = "setlist/$playlistId?start=$start"

    companion object {
        fun parse(route: String?): SetlistDest? {
            if (route == null || !route.startsWith("setlist/")) return null
            val rest = route.removePrefix("setlist/")
            val id = rest.substringBefore('?').ifEmpty { return null }
            return SetlistDest(id, rest.substringAfter("?start=", "0").toIntOrNull()?.coerceAtLeast(0) ?: 0)
        }
    }
}

/** Setlist mode wired to the app graph; every page's view model lives as long as the route. */
@Composable
fun SetlistRoute(graph: AppGraph, dest: SetlistDest, onBack: () -> Unit) {
    val lists by remember(graph) { graph.library.playlists() }.collectAsStateWithLifecycle(initialValue = null)
    val keepOn by graph.prefs.keepScreenOn.collectAsStateWithLifecycle(initialValue = false)
    val playlist = lists?.firstOrNull { it.playlist.id == dest.playlistId }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            lists == null -> CircularProgressIndicator(Modifier.wrapCenter())
            playlist == null || playlist.songs.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("This playlist has no songs to play", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onBack) { Text("Back to library") }
            }
            else -> {
                val owner = rememberRouteOwner(dest.route())
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    SetlistPager(
                        name = playlist.playlist.name,
                        items = playlist.songs.map { SetlistItem(it.songId, it.song?.title ?: "Next song") },
                        startIndex = dest.start,
                        page = { songId -> viewModel(key = "setlist:$songId") { songViewModel(graph, songId, dest.playlistId) } },
                        onBack = onBack,
                        keepScreenOn = keepOn,
                        metronome = { dev.avijit.orbittuner.songview.songMetronome(graph) },
                    )
                }
            }
        }
    }
}

private fun Modifier.wrapCenter() = fillMaxSize().padding(48.dp)

/**
 * Songs of a playlist as pages to swipe through. [page] supplies each song's view model (opened with the playlist,
 * so every page keeps its own key, capo and speed). Changing page stops auto-scroll everywhere else; near the end of
 * a song a "Next: <title>" chip moves on.
 */
@Composable
fun SetlistPager(
    name: String,
    items: List<SetlistItem>,
    startIndex: Int,
    page: @Composable (songId: String) -> SongViewModel,
    onBack: () -> Unit,
    keepScreenOn: Boolean = false,
    metronome: (() -> dev.avijit.orbittuner.metronome.MetronomeViewModel)? = null,
) {
    if (items.isEmpty()) return
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, items.lastIndex)) { items.size }
    val scope = rememberCoroutineScope()
    val vms = remember { HashMap<String, SongViewModel>() }
    val stores: SetlistPageStores = viewModel()
    val currentItems by androidx.compose.runtime.rememberUpdatedState(items)

    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { cur ->
            val id = currentItems.getOrNull(cur)?.songId
            vms.forEach { (songId, vm) -> if (songId != id) vm.setPlaying(false) }
            // Pages more than one away go quiet: their view models are cleared (settings are saved per item, so
            // coming back reloads them as they were).
            val near = (cur - 1..cur + 1).mapNotNull { currentItems.getOrNull(it)?.songId }.toSet()
            stores.keepOnly(near).forEach { vms.remove(it) }
        }
    }

    HorizontalPager(
        pager,
        modifier = Modifier.fillMaxSize().testTag("setlist-pager"),
        key = { items[it].songId },
        beyondViewportPageCount = 0,
    ) { i ->
        val item = items[i]
        val owner = remember(stores, item.songId) { stores.owner(item.songId) }
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
            val vm = page(item.songId)
            SideEffect { vms[item.songId] = vm }
            val next = items.getOrNull(i + 1)
            SongScreen(
                vm, onBack, keepScreenOn = keepScreenOn, metronome = metronome,
                setlist = SetlistChrome(
                    position = i + 1, count = items.size, name = name, next = next?.title,
                    onNext = { scope.launch { pager.animateScrollToPage(i + 1) } },
                ),
            )
        }
    }
}
