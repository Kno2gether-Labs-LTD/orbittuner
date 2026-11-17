package dev.avijit.orbittuner.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.avijit.orbittuner.data.LibraryRepository
import dev.avijit.orbittuner.data.PlaylistWithSongs
import dev.avijit.orbittuner.data.db.CachedSong
import dev.avijit.orbittuner.net.ApiError
import dev.avijit.orbittuner.net.AuthStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LibraryTab(val label: String) { Playlists("Playlists"), Favourites("Favourites"), Recent("Recent"), Downloaded("Downloaded") }

/** Progress of a playlist's "Download for offline". */
data class DownloadProgress(val done: Int, val total: Int)

/** A playlist ZIP fetched from the service, ready to be written and shared. */
class ExportReady(val name: String, val bytes: ByteArray)

/**
 * Library tab state: the lists, which tab and playlist are showing (kept here so they survive opening a song), plus
 * playlist edits, downloads and exports. [move] and [exportZip] are seams for tests.
 */
class LibraryViewModel(
    private val library: LibraryRepository,
    auth: AuthStore,
    private val move: suspend (playlistId: String, songId: String, prevSongId: String?, nextSongId: String?) -> Unit = library::move,
    private val exportZip: suspend (ids: List<String>) -> ByteArray = { throw ApiError.Unauthorized },
    accountDisabled: Flow<Boolean> = flowOf(false),
    resetNotice: Flow<Boolean> = flowOf(false),
    private val dismissResetNotice: () -> Unit = {},
) : ViewModel() {

    private fun <T> Flow<T>.state(initial: T): StateFlow<T> = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    /** An admin disabled the account: the library still works locally, but nothing syncs. */
    val accountDisabled: StateFlow<Boolean> = accountDisabled.state(false)

    /** A revoked account was replaced and this device's favourites/playlists were cleared (until dismissed). */
    val resetNotice: StateFlow<Boolean> = resetNotice.state(false)
    fun dismissResetNotice() = dismissResetNotice.invoke()

    /** Null until the first read lands (so the screen doesn't flash an empty state). */
    val playlists: StateFlow<List<PlaylistWithSongs>?> = library.playlists().state(null)
    val favourites: StateFlow<List<CachedSong>> = library.favourites().state(emptyList())
    val recent: StateFlow<List<CachedSong>> = library.recent().state(emptyList())
    val downloaded: StateFlow<List<CachedSong>> = library.downloaded().state(emptyList())

    val hasAccount: StateFlow<Boolean> = combine(auth.host, auth.user) { h, u -> h != null && u != null }.state(auth.host.value != null)

    private val _tab = MutableStateFlow(LibraryTab.Playlists)
    val tab: StateFlow<LibraryTab> = _tab.asStateFlow()

    private val _selected = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = _selected.asStateFlow()

    private val _downloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadProgress>> = _downloads.asStateFlow()

    private val _exporting = MutableStateFlow<Set<String>>(emptySet())
    val exporting: StateFlow<Set<String>> = _exporting.asStateFlow()

    /** Bumped when a reorder couldn't be saved, so the screen drops its optimistic order. */
    private val _moveFailures = MutableStateFlow(0)
    val moveFailures: StateFlow<Int> = _moveFailures.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private val _exports = Channel<ExportReady>(Channel.BUFFERED)
    val exports: Flow<ExportReady> = _exports.receiveAsFlow()

    fun setTab(t: LibraryTab) { _tab.value = t }
    fun select(playlistId: String?) { _selected.value = playlistId }

    fun createPlaylist(name: String, select: Boolean = false) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val id = library.createPlaylist(name)
            if (select) _selected.value = id
        }
    }

    fun rename(id: String, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { library.rename(id, name) }
    }

    fun delete(id: String) {
        if (_selected.value == id) _selected.value = null
        viewModelScope.launch { library.delete(id) }
    }

    /**
     * The player dropped [songId] so the playlist now reads [order]; tells the repository its new neighbours
     * (null prev = moved to the start, null next = moved to the end).
     */
    fun moved(playlistId: String, order: List<String>, songId: String) {
        val i = order.indexOf(songId)
        if (i < 0) return
        val prev = order.getOrNull(i - 1)
        val next = order.getOrNull(i + 1)
        viewModelScope.launch {
            try {
                move(playlistId, songId, prev, next)
            } catch (_: IllegalArgumentException) {
                _moveFailures.update { it + 1 }
                _messages.trySend("The playlist changed meanwhile, so that move didn't stick")
            }
        }
    }

    fun download(playlistId: String) {
        if (playlistId in _downloads.value) return
        viewModelScope.launch {
            try {
                library.downloadForOffline(playlistId) { done, total ->
                    _downloads.update { it + (playlistId to DownloadProgress(done, total)) }
                }
                _messages.trySend("Available offline")
            } catch (_: ApiError) {
                _messages.trySend("Some songs couldn't be downloaded. Check your connection and try again.")
            } catch (_: IllegalArgumentException) {
                // The playlist was deleted meanwhile.
            } finally {
                _downloads.update { it - playlistId }
            }
        }
    }

    fun removeDownload(songId: String) {
        viewModelScope.launch { library.removeDownload(songId) }
    }

    /** Fetches the playlist as a ZIP of ChordPro files, in play order; the screen shares it. */
    fun export(playlist: PlaylistWithSongs) {
        val id = playlist.playlist.id
        if (id in _exporting.value || playlist.songs.isEmpty()) return
        _exporting.update { it + id }
        viewModelScope.launch {
            try {
                _exports.trySend(ExportReady(playlist.playlist.name, exportZip(playlist.songs.map { it.songId })))
            } catch (e: ApiError) {
                _messages.trySend(
                    when (e) {
                        ApiError.Offline -> "Exporting needs a connection to your song library"
                        ApiError.Unauthorized, ApiError.TokenRevoked -> "Export works once your account is set up. Check your connection."
                        else -> "The library couldn't export this playlist"
                    },
                )
            } finally {
                _exporting.update { it - id }
            }
        }
    }
}
