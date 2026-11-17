package dev.avijit.orbittuner.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

private const val YOURS =
    "(EXISTS(SELECT 1 FROM favourite f WHERE f.songId = s.id AND f.favourite = 1) OR " +
        "EXISTS(SELECT 1 FROM playlist_item i JOIN playlist p ON p.id = i.playlistId WHERE i.songId = s.id AND p.deleted = 0))"

@Dao
abstract class SongDao {
    @Query("SELECT * FROM cached_song WHERE id = :id")
    abstract suspend fun get(id: String): CachedSong?

    @Upsert protected abstract suspend fun upsert(song: CachedSong)
    @Query("DELETE FROM cached_song_fts WHERE songId = :id") protected abstract suspend fun deleteFts(id: String)
    @Insert protected abstract suspend fun insertFts(row: CachedSongFts)

    /** Writes the song and its full-text row together. */
    @Transaction
    open suspend fun store(song: CachedSong, lyrics: String) {
        upsert(song)
        deleteFts(song.id)
        insertFts(CachedSongFts(songId = song.id, title = song.title, artist = song.artist.orEmpty(), lyrics = lyrics))
    }

    @Query("DELETE FROM cached_song_fts WHERE songId IN (SELECT id FROM cached_song WHERE status != 'published')")
    protected abstract suspend fun deleteUnpublishedFts()
    @Query("DELETE FROM song_prefs WHERE songId IN (SELECT id FROM cached_song WHERE status != 'published')")
    protected abstract suspend fun deleteUnpublishedPrefs()
    @Query("DELETE FROM cached_song WHERE status != 'published'")
    protected abstract suspend fun deleteUnpublishedSongs()

    /** Drops cached songs that aren't published (and their search rows and prefs): they belong to one account. */
    @Transaction
    open suspend fun evictUnpublished() {
        deleteUnpublishedFts()
        deleteUnpublishedPrefs()
        deleteUnpublishedSongs()
    }

    @Query("UPDATE cached_song SET lastOpenedAt = :at WHERE id = :id")
    abstract suspend fun touch(id: String, at: Long)

    @Query("UPDATE cached_song SET pinnedOffline = :pinned WHERE id = :id")
    abstract suspend fun setPinned(id: String, pinned: Boolean)

    @Query("SELECT id, updatedAt FROM cached_song ORDER BY updatedAt, id")
    abstract suspend fun stamps(): List<SongStamp>

    /** [match] must come from ftsMatch(): quoted prefix terms only, never raw user text. */
    @Query(
        "SELECT s.*, $YOURS AS yours FROM cached_song s " +
            "WHERE s.id IN (SELECT songId FROM cached_song_fts WHERE cached_song_fts MATCH :match) " +
            "ORDER BY yours DESC, s.lastOpenedAt DESC, s.title COLLATE NOCASE LIMIT 200",
    )
    abstract fun search(match: String): Flow<List<SongHit>>

    @Query("SELECT s.*, $YOURS AS yours FROM cached_song s ORDER BY yours DESC, s.lastOpenedAt DESC, s.title COLLATE NOCASE LIMIT 200")
    abstract fun recent(): Flow<List<SongHit>>

    @Query("SELECT * FROM song_prefs WHERE songId = :id")
    abstract fun prefs(id: String): Flow<SongPrefs?>

    @Upsert abstract suspend fun putPrefs(prefs: SongPrefs)
}

@Dao
abstract class LibraryDao {
    // ---- favourites ----
    @Query("SELECT * FROM favourite WHERE songId = :songId")
    abstract suspend fun favourite(songId: String): Favourite?

    @Upsert abstract suspend fun upsertFavourite(f: Favourite)

    @Query("SELECT * FROM favourite WHERE dirty = 1")
    abstract suspend fun dirtyFavourites(): List<Favourite>

    @Query("SELECT s.* FROM cached_song s JOIN favourite f ON f.songId = s.id WHERE f.favourite = 1 ORDER BY s.title COLLATE NOCASE")
    abstract fun favouriteSongs(): Flow<List<CachedSong>>

    // ---- playlists ----
    @Query("SELECT * FROM playlist WHERE id = :id")
    abstract suspend fun playlist(id: String): PlaylistEntity?

    @Upsert abstract suspend fun upsertPlaylist(p: PlaylistEntity)

    /** Dirty playlists still worth pushing (not rejected by the service since their last edit). */
    @Query("SELECT COUNT(*) FROM playlist WHERE dirty = 1 AND syncError IS NOT NULL")
    abstract suspend fun rejectedPlaylistCount(): Int

    @Query("SELECT * FROM playlist WHERE dirty = 1 AND syncError IS NULL")
    abstract suspend fun dirtyPlaylists(): List<PlaylistEntity>

    @Query("UPDATE playlist SET syncError = :error WHERE id = :id AND updatedAt = :updatedAt")
    abstract suspend fun setSyncError(id: String, updatedAt: Long, error: String)

    @Query("SELECT COUNT(*) FROM playlist_item WHERE playlistId = :id")
    abstract suspend fun itemCount(id: String): Int

    /** Drops the account-bound library (favourites, playlists, items); the song cache and prefs stay. */
    @Transaction
    open suspend fun wipeAccountData() {
        deleteAllItems(); deleteAllPlaylists(); deleteAllFavourites()
    }

    @Query("DELETE FROM playlist_item") protected abstract suspend fun deleteAllItems()
    @Query("DELETE FROM playlist") protected abstract suspend fun deleteAllPlaylists()
    @Query("DELETE FROM favourite") protected abstract suspend fun deleteAllFavourites()

    @Query("SELECT * FROM playlist_item WHERE playlistId = :id ORDER BY position, songId")
    abstract suspend fun items(id: String): List<PlaylistItemEntity>

    @Query("SELECT * FROM playlist_item WHERE playlistId = :id AND songId = :songId")
    abstract suspend fun item(id: String, songId: String): PlaylistItemEntity?

    @Query("SELECT MAX(position) FROM playlist_item WHERE playlistId = :id")
    abstract suspend fun lastPosition(id: String): String?

    @Upsert abstract suspend fun upsertItem(item: PlaylistItemEntity)

    @Query("DELETE FROM playlist_item WHERE playlistId = :id")
    abstract suspend fun clearItems(id: String)

    @Insert abstract suspend fun insertItems(items: List<PlaylistItemEntity>)

    /** Replaces a playlist and all its items in one transaction. */
    @Transaction
    open suspend fun replacePlaylist(p: PlaylistEntity, items: List<PlaylistItemEntity>) {
        upsertPlaylist(p)
        clearItems(p.id)
        insertItems(items)
    }

    @Transaction
    @Query("SELECT * FROM playlist WHERE deleted = 0 ORDER BY name COLLATE NOCASE, id")
    abstract fun livePlaylists(): Flow<List<PlaylistWithItems>>

    @Query("SELECT * FROM cached_song WHERE id IN (SELECT i.songId FROM playlist_item i JOIN playlist p ON p.id = i.playlistId WHERE p.deleted = 0)")
    abstract fun playlistSongs(): Flow<List<CachedSong>>

    /** Songs the user has (favourited or in a live playlist) that aren't cached yet. */
    @Query(
        "SELECT songId FROM favourite WHERE favourite = 1 AND songId NOT IN (SELECT id FROM cached_song) " +
            "UNION SELECT i.songId FROM playlist_item i JOIN playlist p ON p.id = i.playlistId " +
            "WHERE p.deleted = 0 AND i.songId NOT IN (SELECT id FROM cached_song)",
    )
    abstract suspend fun missingSongIds(): List<String>

    // ---- library lists (cached songs) ----
    @Query("SELECT * FROM cached_song WHERE lastOpenedAt > 0 ORDER BY lastOpenedAt DESC, id LIMIT :limit")
    abstract fun recentSongs(limit: Int): Flow<List<CachedSong>>

    @Query("SELECT * FROM cached_song WHERE pinnedOffline = 1 ORDER BY title COLLATE NOCASE, id")
    abstract fun pinnedSongs(): Flow<List<CachedSong>>

    // ---- history (voice ranking, Explore "For you") ----
    @Query("SELECT songId FROM favourite WHERE favourite = 1")
    abstract suspend fun favouriteIds(): List<String>

    @Query("SELECT DISTINCT i.songId FROM playlist_item i JOIN playlist p ON p.id = i.playlistId WHERE p.deleted = 0")
    abstract suspend fun playlistSongIds(): List<String>

    /** Cached songs the user opened, favourited or keeps in a live playlist. */
    @Query(
        "SELECT * FROM cached_song WHERE lastOpenedAt > 0 " +
            "OR id IN (SELECT songId FROM favourite WHERE favourite = 1) " +
            "OR id IN (SELECT i.songId FROM playlist_item i JOIN playlist p ON p.id = i.playlistId WHERE p.deleted = 0) " +
            "ORDER BY lastOpenedAt DESC, id",
    )
    abstract suspend fun historySongs(): List<CachedSong>
}

@Dao
interface SyncStateDao {
    @Query("SELECT value FROM sync_state WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("DELETE FROM sync_state WHERE `key` = :key")
    suspend fun delete(key: String)

    @Upsert suspend fun put(s: SyncState)

    @Query("SELECT value FROM sync_state WHERE `key` = :key")
    fun watch(key: String): Flow<String?>
}
