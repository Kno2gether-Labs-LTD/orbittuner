package dev.avijit.orbittuner.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import dev.avijit.orbittuner.AppGraph
import dev.avijit.orbittuner.add.AddDest
import dev.avijit.orbittuner.add.AddSongRoute
import dev.avijit.orbittuner.library.LibraryRoute
import dev.avijit.orbittuner.library.SetlistDest
import dev.avijit.orbittuner.library.SetlistRoute
import dev.avijit.orbittuner.settings.SettingsRoute
import dev.avijit.orbittuner.songs.SongsRoute
import dev.avijit.orbittuner.songview.SongDest
import dev.avijit.orbittuner.songview.SongRoute
import dev.avijit.orbittuner.tuner.TunerRoute

enum class Dest(val label: String) {
    Tune("Tune"), Songs("Songs"), Library("Library"), Tools("Tools"), Settings("Settings"),
}

@Composable
fun AppShell(graph: AppGraph) {
    var current by rememberSaveable { mutableStateOf(Dest.Tune) }
    // Nested route over the tabs: "song/{id}?playlist={pid}" (Songs, Library), "setlist/{pid}?start={i}" (Library) or
    // "add" (Add song, from Songs and Library).
    var songRoute by rememberSaveable { mutableStateOf<String?>(null) }
    val song = SongDest.parse(songRoute)
    val setlist = SetlistDest.parse(songRoute)
    val adding = songRoute == AddDest.ROUTE
    BackHandler(enabled = song != null || setlist != null || adding) { songRoute = null }
    NavigationSuiteScaffold(
        layoutType = shellLayoutType(songOpen = song != null || setlist != null || adding),
        navigationSuiteItems = {
            Dest.entries.forEach { dest ->
                item(
                    selected = dest == current,
                    onClick = { current = dest; songRoute = null },
                    icon = { Icon(dest.icon(), contentDescription = null) },
                    label = { Text(dest.label) },
                )
            }
        },
    ) {
        if (adding) AddSongRoute(
            graph, onBack = { songRoute = null }, onOpenSong = { songRoute = SongDest(it).route() },
            onOpenAccount = { songRoute = null; current = Dest.Settings },
        )
        else if (song != null) SongRoute(
            graph, song, onBack = { songRoute = null },
            // A used-up Sing a line shift leaves the saved route, so process death doesn't apply it again.
            onSungConsumed = { if (SongDest.parse(songRoute)?.songId == song.songId) songRoute = song.withoutShift().route() },
        )
        else if (setlist != null) SetlistRoute(graph, setlist, onBack = { songRoute = null })
        else if (current == Dest.Tune) TunerRoute(graph)
        else if (current == Dest.Library) LibraryRoute(
            graph, onOpenSong = { songRoute = it.route() },
            onPlaySet = { pid, start -> songRoute = SetlistDest(pid, start).route() },
            onOpenAccount = { current = Dest.Settings },
            onAddSong = { songRoute = AddDest.ROUTE },
        )
        else if (current == Dest.Settings) SettingsRoute(graph)
        else if (current == Dest.Tools) ToolsRoute(graph)
        else if (current == Dest.Songs) SongsRoute(
            graph, onOpenSong = { songRoute = it.route() }, onOpenSettings = { current = Dest.Settings },
            onAddSong = { songRoute = AddDest.ROUTE },
        )
        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = when (current) {
                    Dest.Tune -> "Tune placeholder"
                    Dest.Songs -> "Songs placeholder"
                    Dest.Library -> "Library placeholder"
                    Dest.Tools -> "Tools placeholder"
                    Dest.Settings -> "Settings placeholder"
                },
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

private fun Dest.icon(): ImageVector = when (this) {
    Dest.Tune -> Icons.Filled.Star
    Dest.Songs -> Icons.AutoMirrored.Filled.List
    Dest.Library -> Icons.Filled.Favorite
    Dest.Tools -> Icons.Filled.Build
    Dest.Settings -> Icons.Filled.Settings
}
