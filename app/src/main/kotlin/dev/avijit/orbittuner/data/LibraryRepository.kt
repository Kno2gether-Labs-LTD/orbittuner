package dev.avijit.orbittuner.data

import androidx.room.withTransaction
import dev.avijit.orbittuner.data.db.AppDb
import dev.avijit.orbittuner.data.db.CachedSong
import dev.avijit.orbittuner.data.db.Favourite
import dev.avijit.orbittuner.data.db.PlaylistEntity
import dev.avijit.orbittuner.data.db.PlaylistItemEntity
import dev.avijit.orbittuner.data.db.SyncState
import dev.avijit.orbittuner.music.FractionalIndex
import dev.avijit.orbittuner.net.ApiError
import dev.avijit.orbittuner.net.AuthStore
import dev.avijit.orbittuner.net.FavouriteItem
import dev.avijit.orbittuner.net.ItemSettings
import dev.avijit.orbittuner.net.Playlist
import dev.avijit.orbittuner.net.PlaylistItem
import dev.avijit.orbittuner.net.WireJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PlaylistSong(val songId: String, val position: String, val settings: ItemSettings, val song: CachedSong?)

/**
 * A live (non-deleted) playlist with its songs in play order; [PlaylistSong.song] is null until cached.
 * [PlaylistEntity.syncError] is non-null when the service rejected the last push (shown to the user; cleared by an edit).
 */
data class PlaylistWithSongs(val playlist: PlaylistEntity, val songs: List<PlaylistSong>)

/**
 * Local changes the service doesn't have: [pending] could still be pushed (offline, a failed or unfinished sync);
 * [rejected] were refused by the service (playlists with a syncError, favourites refused in the last sync).
 */
data class UnsyncedChanges(val pending: Int, val rejected: Int) {
    val any: Boolean get() = pending + rejected > 0
}

/** The playlist already holds the service's maximum of [LibraryRepository.MAX_ITEMS] songs. */
class PlaylistFullException : IllegalStateException("A playlist can hold at most ${LibraryRepository.MAX_ITEMS} songs")

/** Account the local library belongs to: service host + user id (unchanged by a revoked token); null without an account. */
fun AuthStore.syncIdentity(): String? = synchronized(this) {
    // Same monitor as AuthStore's @Synchronized save/markRevoked/clear, so host and user can't tear. Not snapshot():
    // that is null while revoked, and a revoked token keeps the identity until AccountManager replaces the account.
    val h = host.value ?: return null
    val u = user.value ?: return null
    "$h|${u.id}"
}

/** Identity (host|userId) and a service client bound to that account's host + token, snapshotted once per sync. */
class SyncSession(val identity: String, val api: ServiceApi, val credential: String = "")

private const val K_CURSOR = "playlists_cursor"
private const val K_IDENTITY = "identity"
private const val K_LAST_SYNC = "last_sync"

/** The account changed while a sync was running; the sync stops before touching anything else. */
private class AccountChanged : Exception("account changed during sync")

/** The account kept changing during one sync; retry later (not a credentials problem). */
class AccountChangedException : Exception("account changed repeatedly during sync")

/**
 * Favourites and playlists, edited offline and synced last-write-wins. Every local write marks the row dirty,
 * stamps updatedAt from [clock] and calls [requestSync] (the unique SyncWorker in production).
 *
 * Account binding: [identity] is the current account (see [syncIdentity]). The local library belongs to the
 * account recorded in SyncState; when the current account differs (another host/user, or auth cleared), favourites,
 * playlists and the cursor are wiped before any further write or sync ([accountChanged], every local write, and
 * every sync check this). Cached songs and song prefs are shared content and kept. A revoked token keeps the identity,
 * so a new token for the same account keeps and pushes local edits.
 * Drafts made while there was no account yet (first launch offline: nothing recorded) are kept and go to the first
 * account. After a sign-out the recorded account is cleared to "" (data wiped), so the next account starts empty and
 * adopts whatever was made in between.
 *
 * [session] snapshots host + token + user once at sync start; every request of that sync uses it, so a sync can
 * never send one account's data with another account's token.
 */
class LibraryRepository(
    private val db: AppDb,
    val songs: SongRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val requestSync: () -> Unit = {},
    private val identity: () -> String?,
    private val session: () -> SyncSession?,
) {
    private val dao = db.library()
    private val state = db.syncState()
    private val syncLock = Mutex()

    /** Favourites (songId, updatedAt) the service refused during the last sync: they stay dirty but aren't pending. */
    @Volatile private var lastRejectedFavourites: Set<Pair<String, Long>> = emptySet()

    /** Test seam: runs just before a dirty playlist is read for pushing (to exercise account-switch races). */
    internal var beforePlaylistRead: (suspend (String) -> Unit)? = null

    fun favourites(): Flow<List<CachedSong>> = dao.favouriteSongs().distinctUntilChanged()

    /** Sets the favourite flag; favouriting also caches the song (when reachable) so the list works offline. */
    suspend fun setFavourite(songId: String, fav: Boolean) {
        db.withTransaction {
            reconcileAccount()
            val prev = dao.favourite(songId)
            dao.upsertFavourite(Favourite(songId, fav, stamp(prev?.updatedAt), dirty = true))
        }
        requestSync()
        if (fav) try { songs.cache(songId) } catch (_: ApiError) { /* offline/unknown: cached later by sync */ }
    }

    /** Songs opened on this device, most recent first. */
    fun recent(limit: Int = 50): Flow<List<CachedSong>> = dao.recentSongs(limit).distinctUntilChanged()

    /** Songs pinned for offline use (Download for offline). */
    fun downloaded(): Flow<List<CachedSong>> = dao.pinnedSongs().distinctUntilChanged()

    /** Device time of the last successful sync with the current account, or null (never, or another account). */
    fun lastSync(): Flow<Long?> = state.watch(K_LAST_SYNC).map { it?.toLongOrNull() }.distinctUntilChanged()

    /** Removes [songId] from the offline downloads (it stays cached until the system needs the space). */
    suspend fun removeDownload(songId: String) = songs.setPinned(songId, false)

    fun playlists(): Flow<List<PlaylistWithSongs>> =
        combine(dao.livePlaylists(), dao.playlistSongs()) { lists, cached ->
            val byId = cached.associateBy { it.id }
            lists.map { pw ->
                PlaylistWithSongs(
                    pw.playlist,
                    pw.items.sortedWith(compareBy({ it.position }, { it.songId }))
                        .map { PlaylistSong(it.songId, it.position, decodeSettings(it.settingsJson), byId[it.songId]) },
                )
            }
        }.distinctUntilChanged()

    suspend fun createPlaylist(name: String): String {
        val now = clock()
        val id = Ulid.next(now)
        db.withTransaction {
            reconcileAccount()
            dao.upsertPlaylist(PlaylistEntity(id, cleanName(name), deleted = false, updatedAt = now, dirty = true))
        }
        requestSync()
        return id
    }

    suspend fun rename(id: String, name: String) = edit(id) { it.copy(name = cleanName(name)) }

    /** Tombstone: hidden at once, removed from the service on the next sync. */
    suspend fun delete(id: String) = edit(id) { p ->
        dao.clearItems(id)
        p.copy(deleted = true)
    }

    /**
     * Appends [songId] after the last item (no-op if already present) and caches the song for offline use.
     * Throws [PlaylistFullException] at [MAX_ITEMS] songs.
     */
    suspend fun addToPlaylist(id: String, songId: String) {
        edit(id) { p ->
            if (dao.item(id, songId) == null) {
                if (dao.itemCount(id) >= MAX_ITEMS) throw PlaylistFullException()
                val pos = positionBetween(id, prevSongId = dao.items(id).lastOrNull()?.songId, nextSongId = null)
                dao.upsertItem(PlaylistItemEntity(id, songId, pos, "{}"))
            }
            p
        }
        try { songs.cache(songId) } catch (_: ApiError) { /* offline/unknown: cached later by sync */ }
    }

    /**
     * Moves [songId] so that it sits directly after [prevSongId] and directly before [nextSongId].
     * Pass null for [prevSongId] to move it to the start, or null for [nextSongId] to move it to the end.
     * Both must be other songs of this playlist, with [prevSongId] currently ordered before [nextSongId].
     */
    suspend fun move(id: String, songId: String, prevSongId: String?, nextSongId: String?) = edit(id) { p ->
        require(songId != prevSongId && songId != nextSongId) { "cannot move relative to itself" }
        requireNotNull(dao.item(id, songId)) { "song not in playlist" }
        val pos = positionBetween(id, prevSongId, nextSongId)
        dao.upsertItem(dao.item(id, songId)!!.copy(position = pos))
        p
    }

    /** Stores per-item settings, clamped to the service's limits (transpose −11..11, capo 0..11, speed 0..1000). */
    suspend fun setItemSettings(id: String, songId: String, s: ItemSettings) = edit(id) { p ->
        val item = requireNotNull(dao.item(id, songId)) { "song not in playlist" }
        val clamped = ItemSettings(
            transpose = s.transpose?.coerceIn(-11, 11),
            capo = s.capo?.coerceIn(0, 11),
            scrollSpeed = s.scrollSpeed?.takeIf { it.isFinite() }?.coerceIn(0.0, 1000.0),
        )
        dao.upsertItem(item.copy(settingsJson = WireJson.encodeToString(ItemSettings.serializer(), clamped)))
        p
    }

    /**
     * Caches and pins every song of the playlist, reporting (done, total) after each. Throws (e.g. [ApiError.Offline])
     * if one can't be fetched; songs already done stay pinned.
     */
    suspend fun downloadForOffline(id: String, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }) {
        val items = dao.items(id)
        onProgress(0, items.size)
        items.forEachIndexed { i, item ->
            songs.cache(item.songId)
            songs.setPinned(item.songId, true)
            onProgress(i + 1, items.size)
        }
    }

    /** Dirty favourites and playlists, split into still-pushable and rejected (see [UnsyncedChanges]). */
    suspend fun unsyncedChanges(): UnsyncedChanges = db.withTransaction {
        val favs = dao.dirtyFavourites()
        val rejectedFavs = favs.count { (it.songId to it.updatedAt) in lastRejectedFavourites }
        UnsyncedChanges(
            pending = dao.dirtyPlaylists().size + favs.size - rejectedFavs,
            rejected = dao.rejectedPlaylistCount() + rejectedFavs,
        )
    }

    /** Any dirty favourite or playlist, including ones the service rejected. */
    suspend fun hasUnsyncedChanges(): Boolean = unsyncedChanges().any

    /**
     * The account may have changed (new account, sign-out, revoked): wipes the old account's library
     * now, before any UI write can land, then requests a sync when signed in. Call on every identity change.
     */
    suspend fun accountChanged(requestSync: Boolean = true) {
        db.withTransaction { reconcileAccount() }
        if (requestSync && identity() != null) this.requestSync()
    }

    /**
     * Pushes dirty favourites and playlists, then pulls playlists changed after the server cursor; repeats (up to
     * [MAX_PASSES]) while pushable dirty rows remain, so edits made during a sync go up too.
     * Uses one [session] snapshot for every request. If the account changes mid-sync, it stops (no further
     * requests or writes for the old account) and starts over once for the new account.
     * Throws [ApiError]: Offline (retry later), TokenRevoked/Unauthorized (AccountManager makes a new account). Dirty flags are only
     * cleared once the service has the write, so nothing is lost on failure.
     */
    suspend fun sync() = syncLock.withLock {
        var attempt = 0
        while (true) {
            try {
                syncOnce()
                break
            } catch (e: AccountChanged) {
                if (++attempt >= 2) throw AccountChangedException()
            }
        }
    }

    // ---- sync steps ----

    private suspend fun syncOnce() {
        db.withTransaction { reconcileAccount() }
        val s = session() ?: throw ApiError.Unauthorized
        try {
            syncWith(s)
        } catch (e: ApiError) {
            // Rejected credentials that have since been replaced (new token, same or other account): start over.
            val auth = e is ApiError.Unauthorized || e is ApiError.TokenRevoked
            if (auth && session()?.let { it.identity != s.identity || it.credential != s.credential } == true) throw AccountChanged()
            throw e
        }
    }

    private suspend fun syncWith(s: SyncSession) {
        guard(s)
        val rejectedFavs = HashSet<Pair<String, Long>>()
        for (pass in 0 until MAX_PASSES) {
            syncFavourites(s, rejectedFavs, firstPass = pass == 0)
            pushPlaylists(s)
            pullPlaylists(s)
            if (!hasPushable(rejectedFavs)) break
        }
        lastRejectedFavourites = rejectedFavs.toSet()
        guard(s)
        songs.cacheMissing(dao.missingSongIds(), s.api)
        guarded(s) { state.put(SyncState(K_LAST_SYNC, clock().toString())) }
    }

    /** Must be called inside a transaction: wipes the library if it belongs to an account other than [identity]. */
    private suspend fun reconcileAccount() {
        val current = identity()
        val stored = state.get(K_IDENTITY)
        if (!stored.isNullOrEmpty() && stored != current) {
            // Another account (or none): its data must never be pushed to whoever is signed in now.
            dao.wipeAccountData()
            // Review/hidden songs are visible only to the account that cached them.
            db.songs().evictUnpublished()
            state.delete(K_CURSOR)
            state.delete(K_LAST_SYNC)
            state.put(SyncState(K_IDENTITY, current.orEmpty()))
        } else if (stored.isNullOrEmpty() && current != null) {
            state.put(SyncState(K_IDENTITY, current))
        }
    }

    /**
     * Throws [AccountChanged] unless the current account, its credential (token) and the library's recorded account
     * are all still [s]'s.
     */
    private suspend fun guard(s: SyncSession) {
        val now = session()
        if (now == null || now.identity != s.identity || now.credential != s.credential ||
            identity() != s.identity || state.get(K_IDENTITY) != s.identity
        ) throw AccountChanged()
    }

    /** Runs [block] in a transaction after [guard], so no write for the old account lands after a switch. */
    private suspend fun <T> guarded(s: SyncSession, block: suspend () -> T): T = db.withTransaction { guard(s); block() }

    private suspend fun hasPushable(rejectedFavs: Set<Pair<String, Long>>): Boolean =
        dao.dirtyPlaylists().isNotEmpty() || dao.dirtyFavourites().any { (it.songId to it.updatedAt) !in rejectedFavs }

    private suspend fun syncFavourites(s: SyncSession, rejected: MutableSet<Pair<String, Long>>, firstPass: Boolean) {
        val dirty = dao.dirtyFavourites().filter { (it.songId to it.updatedAt) !in rejected }
        var merged = false
        for (chunk in dirty.chunked(MAX_FAVOURITES_PER_PUT)) {
            guard(s)
            val server = try {
                s.api.putFavourites(chunk.map { FavouriteItem(it.songId, it.favourite, it.updatedAt) })
            } catch (e: ApiError.Http) {
                if (e.code !in 400..499) throw e
                rejected += chunk.map { it.songId to it.updatedAt } // keep dirty; don't block playlists
                continue
            }
            mergeFavourites(s, server)
            merged = true
        }
        // Nothing pushed (or every chunk rejected): still pull other devices' favourites.
        if (!merged && firstPass) {
            guard(s)
            mergeFavourites(s, s.api.getFavourites())
        }
    }

    private suspend fun mergeFavourites(s: SyncSession, server: List<FavouriteItem>) = guarded(s) {
        for (f in server) {
            val local = dao.favourite(f.songId)
            // A dirty local edit newer than the server's copy survives (it was made during this sync).
            if (local == null || !local.dirty || f.updatedAt >= local.updatedAt) {
                dao.upsertFavourite(Favourite(f.songId, f.favourite, f.updatedAt, dirty = false))
            }
        }
    }

    private suspend fun pushPlaylists(s: SyncSession) {
        for (dirty in dao.dirtyPlaylists()) {
            guard(s)
            beforePlaylistRead?.invoke(dirty.id)
            // Row + items in one transaction that re-checks the account: never a torn or post-wipe snapshot.
            val (p, items) = guarded(s) {
                dao.playlist(dirty.id)?.takeIf { it.dirty && it.syncError == null }?.let { it to dao.items(it.id) }
            } ?: continue
            val dto = Playlist(
                p.id, p.name, p.deleted, p.updatedAt,
                if (p.deleted) emptyList() else items.map { PlaylistItem(it.songId, it.position, decodeSettings(it.settingsJson)) },
            )
            val server = try {
                s.api.putPlaylist(dto)
            } catch (e: ApiError.Stale) {
                e.current // someone wrote a newer copy: last write wins, adopt it
            } catch (e: ApiError.Http) {
                when {
                    e.code == 409 -> continue // conflict without a current copy: try again next sync
                    e.code in 400..499 -> { guarded(s) { dao.setSyncError(p.id, p.updatedAt, e.error ?: "http_${e.code}") }; continue }
                    else -> throw e
                }
            }
            adopt(s, server, pushedAt = p.updatedAt)
        }
    }

    private suspend fun pullPlaylists(s: SyncSession) {
        guard(s)
        val cursor = state.get(K_CURSOR)?.toLongOrNull() ?: 0L
        val page = s.api.getPlaylists(cursor)
        for (p in page.items) adopt(s, p, pushedAt = null)
        // The cursor is server-stamped; store it verbatim (never derived from device time).
        if (page.cursor != cursor) guarded(s) { state.put(SyncState(K_CURSOR, page.cursor.toString())) }
    }

    /**
     * Applies the server copy unless the local row is dirty with a newer edit: i.e. replaced when local is clean,
     * when it is exactly what we just pushed ([pushedAt]), or when the server's updatedAt is at least as new.
     */
    private suspend fun adopt(session: SyncSession, s: Playlist, pushedAt: Long?) = guarded(session) {
        val local = dao.playlist(s.id)
        val replace = local == null || !local.dirty || local.updatedAt == pushedAt || s.updatedAt >= local.updatedAt
        if (replace) {
            dao.replacePlaylist(
                PlaylistEntity(s.id, s.name, s.deleted, s.updatedAt, dirty = false),
                if (s.deleted) emptyList()
                else s.items.map { PlaylistItemEntity(s.id, it.songId, it.position, WireJson.encodeToString(ItemSettings.serializer(), it.settings)) },
            )
        }
    }

    // ---- helpers ----

    /** Applies [change] to a live playlist, marking it dirty (clearing any sync error) with a fresh updatedAt. */
    private suspend fun edit(id: String, change: suspend (PlaylistEntity) -> PlaylistEntity) {
        db.withTransaction {
            reconcileAccount()
            val p = dao.playlist(id)
            require(p != null && !p.deleted) { "no such playlist" }
            dao.upsertPlaylist(change(p).copy(updatedAt = stamp(p.updatedAt), dirty = true, syncError = null))
        }
        requestSync()
    }

    /**
     * FractionalIndex key between two items. If it would exceed the service's [MAX_POSITION] chars, the playlist is
     * first rebalanced to short, evenly spaced keys (order unchanged) and the key recomputed.
     */
    private suspend fun positionBetween(id: String, prevSongId: String?, nextSongId: String?): String {
        suspend fun pos(songId: String?) = songId?.let { requireNotNull(dao.item(id, it)) { "$it not in playlist" }.position }
        val key = FractionalIndex.between(pos(prevSongId), pos(nextSongId))
        if (key.length <= MAX_POSITION) return key
        var k: String? = null
        for (item in dao.items(id)) {
            k = FractionalIndex.between(k, null)
            dao.upsertItem(item.copy(position = k))
        }
        return FractionalIndex.between(pos(prevSongId), pos(nextSongId))
    }

    /** Now, but strictly after [prev] so the service (which requires a newer updatedAt) accepts the write. */
    private fun stamp(prev: Long?): Long = maxOf(clock(), (prev ?: -1L) + 1)

    private fun cleanName(name: String): String {
        val n = name.trim().take(MAX_NAME)
        require(n.isNotEmpty()) { "playlist name required" }
        return n
    }

    private fun decodeSettings(json: String): ItemSettings =
        try { WireJson.decodeFromString(ItemSettings.serializer(), json) } catch (_: Exception) { ItemSettings() }

    companion object {
        /** Service limits. */
        const val MAX_ITEMS = 500
        const val MAX_POSITION = 64
        const val MAX_NAME = 200
        const val MAX_FAVOURITES_PER_PUT = 1000
        const val MAX_PASSES = 3
    }
}
