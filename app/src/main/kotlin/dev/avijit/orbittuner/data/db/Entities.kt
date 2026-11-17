package dev.avijit.orbittuner.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/** A song kept on the device. Opened songs stay readable offline. */
@Entity(tableName = "cached_song")
data class CachedSong(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String?,
    val chordpro: String,
    val language: String,
    val categoriesJson: String,
    val originalKey: String?,
    val version: Int,
    /** Server updatedAt of the cached copy. */
    val updatedAt: Long,
    val lastOpenedAt: Long,
    val pinnedOffline: Boolean,
    /** Service status of the cached copy: published, or review/hidden (visible only to its creator and staff). */
    @ColumnInfo(defaultValue = "published") val status: String = "published",
)

/**
 * Local full-text index over cached songs, kept in sync by hand (one row per song, keyed by [songId]).
 * unicode61 folds case and diacritics, like the service.
 */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61, notIndexed = ["songId"])
@Entity(tableName = "cached_song_fts")
data class CachedSongFts(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "rowid") val rowid: Int = 0,
    val songId: String,
    val title: String,
    val artist: String,
    val lyrics: String,
)

@Entity(tableName = "favourite")
data class Favourite(
    @PrimaryKey val songId: String,
    val favourite: Boolean,
    val updatedAt: Long,
    val dirty: Boolean,
)

/**
 * [deleted] playlists are tombstones: hidden from the UI, kept so the delete syncs and older copies can't resurrect it.
 * [syncError] is set when the service rejected the last push (4xx); the playlist is not pushed again until edited.
 */
@Entity(tableName = "playlist")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val deleted: Boolean,
    val updatedAt: Long,
    val dirty: Boolean,
    val syncError: String? = null,
)

@Entity(tableName = "playlist_item", primaryKeys = ["playlistId", "songId"], indices = [Index("songId")])
data class PlaylistItemEntity(
    val playlistId: String,
    val songId: String,
    /** FractionalIndex key; byte order (SQLite BINARY collation) is play order. */
    val position: String,
    val settingsJson: String,
)

@Entity(tableName = "song_prefs")
data class SongPrefs(
    @PrimaryKey val songId: String,
    val transpose: Int,
    val capo: Int,
    val scrollSpeed: Double,
    val textScale: Float,
)

@Entity(tableName = "sync_state")
data class SyncState(@PrimaryKey val key: String, val value: String)

// ---- query shapes ----

data class SongHit(@Embedded val song: CachedSong, val yours: Boolean)

data class PlaylistWithItems(
    @Embedded val playlist: PlaylistEntity,
    @Relation(parentColumn = "id", entityColumn = "playlistId") val items: List<PlaylistItemEntity>,
)

data class SongStamp(val id: String, val updatedAt: Long)
