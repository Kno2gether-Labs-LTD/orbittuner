package dev.avijit.orbittuner.voice

import androidx.room.withTransaction
import dev.avijit.orbittuner.data.categories
import dev.avijit.orbittuner.data.db.AppDb
import dev.avijit.orbittuner.data.db.CachedSong

/**
 * What the device knows about the user's taste; never sent to the service except [topLanguages] and [topArtists] as
 * voice hints. [openedAt]: song id -> last opened (epoch ms). [categoryCounts]: categories of songs the user opened,
 * favourited or keeps in a playlist (one per song), plus categories they browsed in Explore.
 */
data class HistorySignals(
    val favourites: Set<String> = emptySet(),
    val playlists: Set<String> = emptySet(),
    val openedAt: Map<String, Long> = emptyMap(),
    val topArtists: List<String> = emptyList(),
    val topLanguages: List<String> = emptyList(),
    val categoryCounts: Map<String, Int> = emptyMap(),
) {
    companion object {
        const val TOP_ARTISTS = 5
        const val TOP_LANGUAGES = 3
    }
}

/**
 * Reads [HistorySignals] from the local library: favourites, live playlists and opened songs. Top artists and languages
 * count each history song once, ties going to the most recently opened, then by name. [browsed] is the Explore
 * category tally ([dev.avijit.orbittuner.settings.Prefs.browsedCategories]).
 */
suspend fun loadHistorySignals(db: AppDb, browsed: Map<String, Int>): HistorySignals {
    val dao = db.library()
    val (favs, playlists, songs) = db.withTransaction {
        Triple(dao.favouriteIds().toSet(), dao.playlistSongIds().toSet(), dao.historySongs())
    }
    val opened = songs.filter { it.lastOpenedAt > 0 }.associate { it.id to it.lastOpenedAt }

    fun top(keyOf: (CachedSong) -> String?, n: Int): List<String> {
        data class Tally(val name: String, var count: Int, var last: Long)
        val tallies = LinkedHashMap<String, Tally>()
        for (s in songs) {
            val name = keyOf(s)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val t = tallies.getOrPut(name.lowercase()) { Tally(name, 0, 0) }
            t.count++
            t.last = maxOf(t.last, s.lastOpenedAt)
        }
        return tallies.values.sortedWith(
            compareByDescending<Tally> { it.count }.thenByDescending { it.last }.thenBy { it.name.lowercase() },
        ).take(n).map { it.name }
    }

    val cats = HashMap<String, Int>()
    for (s in songs) for (c in s.categories().map { it.trim() }.filter { it.isNotEmpty() }.distinct()) cats.merge(c, 1, Int::plus)
    for ((c, n) in browsed) if (c.isNotBlank() && n > 0) cats.merge(c.trim(), n, Int::plus)

    return HistorySignals(
        favourites = favs,
        playlists = playlists,
        openedAt = opened,
        topArtists = top({ it.artist }, HistorySignals.TOP_ARTISTS),
        topLanguages = top({ it.language.lowercase() }, HistorySignals.TOP_LANGUAGES),
        categoryCounts = cats,
    )
}
