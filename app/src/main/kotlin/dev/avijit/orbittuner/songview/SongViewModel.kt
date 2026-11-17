package dev.avijit.orbittuner.songview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.avijit.orbittuner.BuildConfig
import dev.avijit.orbittuner.chordpro.ChordPro
import dev.avijit.orbittuner.chordpro.ChordProDoc
import dev.avijit.orbittuner.chordpro.Line
import dev.avijit.orbittuner.data.LibraryRepository
import dev.avijit.orbittuner.data.PlaylistFullException
import dev.avijit.orbittuner.data.SongRepository
import dev.avijit.orbittuner.data.db.CachedSong
import dev.avijit.orbittuner.data.db.SongPrefs
import dev.avijit.orbittuner.music.Accidental
import dev.avijit.orbittuner.music.Capo
import dev.avijit.orbittuner.music.CapoHint
import dev.avijit.orbittuner.music.Key
import dev.avijit.orbittuner.music.KeyFinder
import dev.avijit.orbittuner.music.Transposer
import dev.avijit.orbittuner.net.ApiError
import dev.avijit.orbittuner.net.FlagReason
import dev.avijit.orbittuner.net.ItemSettings
import dev.avijit.orbittuner.net.ServiceHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

enum class DiagramInstrument { Guitar, Ukulele, Piano }

/** [speed] is px per second at textScale 1 (the sheet scrolls at speed × textScale). */
data class ScrollUi(val playing: Boolean, val speed: Float)

/**
 * Everything the song screen draws. [doc] is already transposed and capo-shaped (chords are what the player fingers;
 * its `key` meta is the shape key). [soundingKey] is what the audience hears; [originalKey] is the song's own key.
 */
data class SongUi(
    val doc: ChordProDoc,
    val title: String,
    val artist: String?,
    val soundingKey: String?,
    val transpose: Int,
    val capo: Int,
    val scroll: ScrollUi,
    val textScale: Float,
    val chordsOnly: Boolean,
    val instrument: DiagramInstrument,
    val isFavourite: Boolean,
    val originalKey: String? = null,
    val tempoBpm: Int? = null,
    val timeSig: String? = null,
    /** True when the speed follows the tempo estimate (no saved speed). */
    val autoSpeed: Boolean = true,
    val defaultSpeed: Float = DEFAULT_SPEED,
    /** Songs with no `{key}`: the key guessed from the chords, as it sounds now (shown as "≈ G"). Never exported. */
    val inferredKey: String? = null,
    /** After Sing a line: the moved-key banner or the close-call chooser; null when there is nothing to say. */
    val sungBanner: SungBanner? = null,
)

/**
 * How a song opened from Sing a line should sit. With no [alternative] the song opens moved by [semitones] (a confident
 * guess); with one it is a close call and the player picks between the two (nothing moves until they do).
 */
data class SungShift(val semitones: Int, val alternative: Int? = null)

sealed interface SungBanner {
    /** "Moved to [key] to match your singing · Undo", with a [capo] tip when [key] is awkward for beginners. */
    data class Moved(val key: String, val capo: CapoHint?) : SungBanner
    /** "Sounds like {a} or {b}". */
    data class CloseCall(val a: Choice, val b: Choice) : SungBanner
    data class Choice(val semitones: Int, val key: String)
}

sealed interface SongState {
    data object Loading : SongState
    data class Failed(val offline: Boolean) : SongState
    data class Ready(val ui: SongUi) : SongState
}

/** Settings that shape every song: spelling for songs without a key, and the diagram instrument to start on. */
data class SongDefaults(
    val keylessAccidental: Accidental = Accidental.Sharp,
    val instrument: DiagramInstrument = DiagramInstrument.Guitar,
)

const val DEFAULT_SPEED = 20f
const val MIN_SCALE = 0.7f
const val MAX_SCALE = 2.0f
const val MAX_CAPO = 9
/** The service keeps flag notes up to 500 characters. */
private const val MAX_FLAG_NOTE = 500

/**
 * One song on screen. Transpose, capo, speed and text size are per song ([SongPrefs]); when opened from a playlist
 * ([playlistId]) the item's settings override them and transpose/capo/speed edits are saved back to the item
 * (text size always stays a song pref). Play state lives here so it survives rotation.
 */
class SongViewModel(
    private val songId: String,
    private val playlistId: String?,
    private val songs: SongRepository,
    private val library: LibraryRepository,
    private val portalHost: () -> String? = { null },
    /** POST /v1/songs/:id/report ([Api.flag]); errors are [ApiError]. */
    private val flagger: suspend (songId: String, reason: FlagReason, note: String?) -> Unit = { _, _, _ -> },
    private val defaults: Flow<SongDefaults> = flowOf(SongDefaults()),
    private val diagramPrefs: DiagramPrefs = InMemoryDiagramPrefs(),
    /** Opened from Sing a line: move to the sung key (or offer the close call). Never saved by itself; see [applySung]. */
    private val sung: SungShift? = null,
) : ViewModel() {

    private data class Settings(val transpose: Int, val capo: Int, val speed: Float?, val textScale: Float)

    /** [key]: the declared key, else the one guessed from the chords ([inferred]); null when neither is known. */
    private data class Loaded(
        val song: CachedSong, val doc: ChordProDoc, val tempo: Int?, val time: String?, val key: Key? = null, val inferred: Boolean = false,
    )

    /**
     * The Sing a line banner: moved by [semitones] (Undo returns to the transpose [before] and capo [capoBefore]), or a
     * close call between two shifts.
     */
    private sealed interface Sung {
        data class Moved(val semitones: Int, val before: Int, val capoBefore: Int) : Sung
        data class Close(val a: Int, val b: Int) : Sung
    }

    private data class View(val playing: Boolean, val autoSpeed: Float, val chordsOnly: Boolean, val sung: Sung? = null)

    /** The transposed/capo-shaped doc; recomputed only when the song, transpose or capo change. */
    private data class Shaped(val loaded: Loaded, val doc: ChordProDoc, val base: String?, val sounding: String?)

    private enum class Field { Transpose, Capo, Speed }

    private val loaded = MutableStateFlow<Loaded?>(null)
    private val failed = MutableStateFlow<Boolean?>(null)
    private val settings = MutableStateFlow(Settings(0, 0, null, 1f))
    private val view = MutableStateFlow(View(false, DEFAULT_SPEED, false))
    /** The song's own prefs as stored, so playlist-mode text-size saves keep its transpose/capo. */
    private var basePrefs: SongPrefs? = null
    /** Playlist mode: the item's settings as loaded, and the fields edited here; only those are written back. */
    private var itemBase = ItemSettings()
    private val edited = HashSet<Field>()
    /**
     * While a Sing a line key is on screen and not yet accepted: the transpose to save instead of the one shown.
     * The sung key is saved only once the player makes a key decision with it showing: moving the key, or setting a
     * capo (a capo keeps the sounding key, so "Use capo 1" in Eb means Eb with D shapes, and the two are kept
     * together). Speed and text-size saves keep the old key. Undo restores, and saves, the key and capo from before.
     */
    private var savedTranspose: Int? = null

    private val sungDone = MutableStateFlow(false)
    /**
     * True once the Sing a line shift has been used up: undone, chosen, dismissed, overridden by a key edit, or there
     * was nothing to show on opening. The screen then drops the shift from its saved route, so recreating the screen
     * (fold, process death) doesn't apply it again.
     */
    val sungConsumed: StateFlow<Boolean> = sungDone.asStateFlow()
    /** The Sing a line shift this screen shows or last showed; [applySung] replaces it. */
    private var currentSung: SungShift? = sung
    /** Settings are loaded, so [applySung] can place a shift right away. */
    private var settingsReady = false

    private data class Placed(val sung: Sung?, val transpose: Int, val savedTranspose: Int?)

    /**
     * Where a sung [shift] puts the song when the player's own key is [baseline] (with [capo]): a close call offers both
     * and moves nothing; a confident shift is a move whenever it lands elsewhere than [baseline] (so a sung 0 over a
     * saved +2 goes back to the own key), not saved, with Undo back to [baseline]; anything else is no move.
     */
    private fun place(shift: SungShift?, baseline: Int, capo: Int): Placed = when {
        shift == null -> Placed(null, baseline, null)
        shift.alternative != null -> Placed(Sung.Close(normalise(shift.semitones), normalise(shift.alternative)), baseline, null)
        normalise(shift.semitones) != normalise(baseline) ->
            Placed(Sung.Moved(normalise(shift.semitones), baseline, capo), normalise(shift.semitones), baseline)
        else -> Placed(null, baseline, null)
    }

    /**
     * A new Sing a line shift for this already-open song (sung again and picked while it shows). Placed like on opening,
     * against the player's own key: the saved one while a sung key is unaccepted, else what is on screen. The same shift
     * still pending is a no-op (it is the one this screen opened with or already applied).
     */
    fun applySung(shift: SungShift) {
        if (!sungDone.value && shift == currentSung) return
        currentSung = shift
        if (!settingsReady) return // load() places it
        val moved = view.value.sung as? Sung.Moved
        val pending = savedTranspose
        val baseline = pending ?: settings.value.transpose
        val capo = if (pending != null && moved != null) moved.capoBefore else settings.value.capo
        val placed = place(shift, baseline, capo)
        savedTranspose = placed.savedTranspose
        settings.update { it.copy(transpose = placed.transpose, capo = capo) }
        view.update { it.copy(sung = placed.sung) }
        sungDone.value = placed.sung == null
    }
    private val saveLock = Mutex()
    private val _messages = Channel<String>(Channel.BUFFERED)

    /** One-off notices for a snackbar ("Added to Sunday", "Thanks, we'll take a look"). */
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** Watched only once the song is loaded (opening it may write the cache in a transaction). */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val favourite: Flow<Boolean> = loaded.map { it != null }.distinctUntilChanged().flatMapLatest { ready ->
        if (!ready) flowOf(false) else library.favourites().map { list -> list.any { it.id == songId } }
    }.distinctUntilChanged()

    val playlists: StateFlow<List<Pair<String, String>>> =
        library.playlists().map { all -> all.map { it.playlist.id to it.playlist.name } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Spelling for songs without a key (a Settings preference). */
    private val keyless: StateFlow<Accidental> =
        defaults.map { it.keylessAccidental }.stateIn(viewModelScope, SharingStarted.Eagerly, Accidental.Sharp)

    private val shaped: Flow<Shaped?> =
        combine(loaded, settings.map { it.transpose to it.capo }.distinctUntilChanged(), keyless) { l, (t, c), k ->
            l?.let { shapeDoc(it, t, c, k) }
        }.distinctUntilChanged()

    /** The instrument picked in a diagram panel (global), else the Settings default. */
    private val instrument: Flow<DiagramInstrument> =
        combine(diagramPrefs.instrument, defaults.map { it.instrument }) { picked, def -> picked ?: def }.distinctUntilChanged()

    val state: StateFlow<SongState> =
        combine(combine(shaped, failed, ::Pair), settings, view, favourite, instrument) { (sh, f), s, v, fav, inst ->
            when {
                sh != null -> SongState.Ready(ui(sh, s, v, fav, inst))
                f != null -> SongState.Failed(offline = f)
                else -> SongState.Loading
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SongState.Loading)

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        try {
            val song = songs.open(songId)
            val prefs = songs.prefs(songId).first()
            basePrefs = prefs
            val item = playlistId?.let { pid ->
                library.playlists().first().firstOrNull { it.playlist.id == pid }?.songs?.firstOrNull { it.songId == songId }?.settings
            }
            itemBase = item ?: ItemSettings()
            val savedSpeed = item?.scrollSpeed?.toFloat() ?: prefs?.scrollSpeed?.toFloat()
            val saved = item?.transpose ?: prefs?.transpose ?: 0
            val capo = (item?.capo ?: prefs?.capo ?: 0).coerceIn(0, MAX_CAPO)
            val placed = place(currentSung, saved, capo)
            savedTranspose = placed.savedTranspose
            view.update { it.copy(sung = placed.sung) }
            sungDone.value = placed.sung == null
            settings.value = Settings(
                transpose = placed.transpose,
                capo = capo,
                speed = savedSpeed?.takeIf { it > 0f }?.coerceIn(AutoScroller.MIN_SPEED, AutoScroller.MAX_SPEED),
                textScale = (prefs?.textScale ?: 1f).takeIf { it in MIN_SCALE..MAX_SCALE } ?: 1f,
            )
            settingsReady = true
            val doc = ChordPro.parse(song.chordpro)
            val declared = song.originalKey?.takeIf { it.isNotBlank() } ?: doc.meta["key"]?.takeIf { it.isNotBlank() }
            val key = KeyFinder.sheetKey(declared, chordsInOrder(doc))
            val inferred = key != null && (declared == null || KeyFinder.sheetKey(declared, emptyList()) == null)
            loaded.value = Loaded(song, doc, doc.meta["tempo"]?.trim()?.toIntOrNull(), doc.meta["time"], key, inferred)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed.value = e == ApiError.Offline
        }
    }

    fun retry() {
        if (loaded.value != null) return
        failed.value = null
        viewModelScope.launch { load() }
    }

    // ---- shaping ----

    private fun baseKey(l: Loaded): String? = l.song.originalKey?.takeIf { it.isNotBlank() } ?: l.doc.meta["key"]?.takeIf { it.isNotBlank() }

    private fun shapeDoc(l: Loaded, transpose: Int, capo: Int, keyless: Accidental): Shaped {
        val base = baseKey(l)
        val sounding = base?.let { Transposer.transposeKey(it, transpose) }
        val shift = transpose + Capo.shapes(sounding.orEmpty(), capo)
        return Shaped(l, shaped(l.doc, base, shift, keyless), base, sounding)
    }

    private fun ui(sh: Shaped, s: Settings, v: View, isFavourite: Boolean, instrument: DiagramInstrument): SongUi = SongUi(
        doc = sh.doc,
        title = sh.loaded.song.title,
        artist = sh.loaded.song.artist ?: sh.loaded.doc.meta["artist"],
        soundingKey = sh.sounding,
        transpose = s.transpose,
        capo = s.capo,
        scroll = ScrollUi(v.playing, s.speed ?: v.autoSpeed),
        textScale = s.textScale,
        chordsOnly = v.chordsOnly,
        instrument = instrument,
        isFavourite = isFavourite,
        originalKey = sh.base,
        tempoBpm = sh.loaded.tempo,
        timeSig = sh.loaded.time,
        autoSpeed = s.speed == null,
        defaultSpeed = v.autoSpeed,
        inferredKey = sh.loaded.key?.takeIf { sh.loaded.inferred }?.shifted(s.transpose)?.displayName(),
        sungBanner = banner(sh.loaded.key, v.sung, s),
    )

    private fun banner(key: Key?, sung: Sung?, s: Settings): SungBanner? {
        if (key == null) return null
        return when (sung) {
            null -> null
            is Sung.Close -> SungBanner.CloseCall(
                SungBanner.Choice(sung.a, key.shifted(sung.a).displayName()),
                SungBanner.Choice(sung.b, key.shifted(sung.b).displayName()),
            )
            // Moving the key by hand afterwards makes the banner untrue: it goes.
            is Sung.Moved -> if (s.transpose != sung.semitones) null else {
                val now = key.shifted(sung.semitones)
                SungBanner.Moved(now.displayName(), Capo.suggest(now, MAX_CAPO)?.takeIf { it.capo != s.capo })
            }
        }
    }

    /** Every chord in play order (repeats kept), for guessing a key. */
    private fun chordsInOrder(doc: ChordProDoc): List<String> =
        doc.sections.flatMap { sec -> sec.lines.filterIsInstance<Line.Lyrics>().flatMap { l -> l.segments.mapNotNull { it.chord } } }

    private fun shaped(doc: ChordProDoc, base: String?, shift: Int, keyless: Accidental): ChordProDoc {
        if (shift == 0) return if (base != null && doc.meta["key"] == null) doc.copy(meta = doc.meta + ("key" to base)) else doc
        val shapeKey = base?.let { Transposer.transposeKey(it, shift) }
        // Without a key, the player's sharps/flats preference spells the chords.
        val acc = shapeKey?.let(Transposer::keyAccidental) ?: keyless
        val out = ChordPro.transpose(doc, shift, acc)
        return if (shapeKey != null) out.copy(meta = out.meta + ("key" to shapeKey)) else out
    }

    // ---- edits ----

    /** Sing a line: back to the key the song would have opened in. Not saved (the move never was). */
    fun undoSungShift() {
        val moved = view.value.sung as? Sung.Moved ?: return
        view.update { it.copy(sung = null) }
        sungDone.value = true
        savedTranspose = null
        // Key and capo go back together, on screen and on disk.
        edited += Field.Capo
        edit(Field.Transpose) { it.copy(transpose = moved.before, capo = moved.capoBefore) }
    }

    /**
     * Sing a line close call: move to [semitones] (one of the two offered), the song's own key (0) included, exactly like
     * a confident guess: not saved, the Moved banner with Undo back to the saved key and capo. Picking the key the song
     * already sits in just closes the chooser.
     */
    fun chooseSungShift(semitones: Int) {
        val close = view.value.sung as? Sung.Close ?: return
        if (semitones != close.a && semitones != close.b) return
        val before = settings.value.transpose
        if (normalise(semitones) == normalise(before)) {
            view.update { it.copy(sung = null) }
            sungDone.value = true
            return
        }
        if (savedTranspose == null) savedTranspose = before
        settings.update { it.copy(transpose = semitones) }
        view.update { it.copy(sung = Sung.Moved(semitones, before, settings.value.capo)) }
        sungDone.value = true
    }

    /** Sing a line close call: keep the song as it opened. */
    fun dismissSungShift() {
        view.update { it.copy(sung = null) }
        sungDone.value = true
    }

    fun transposeBy(delta: Int) {
        savedTranspose = null // the player's own key from here on: saved as usual
        if (view.value.sung != null) sungDone.value = true
        edit(Field.Transpose) { it.copy(transpose = normalise(it.transpose + delta)) }
    }

    /** Transposes so the song sounds in [key] (shortest way). No-op when the song has no known key. */
    fun setKey(key: String) {
        val base = loaded.value?.let(::baseKey) ?: return
        savedTranspose = null
        if (view.value.sung != null) sungDone.value = true
        edit(Field.Transpose) { it.copy(transpose = Transposer.semitonesBetween(base, key)) }
    }

    fun setCapo(capo: Int) {
        // A capo set while the sung key shows accepts that key: they are saved together.
        val sung = view.value.sung
        if (sung is Sung.Moved && settings.value.transpose == sung.semitones) savedTranspose = null
        edit(Field.Capo) { it.copy(capo = capo.coerceIn(0, MAX_CAPO)) }
    }

    /** Explicit speed (px/s at textScale 1); null returns to the tempo estimate. Saved unless [persist] is false (mid-drag). */
    fun setSpeed(pxPerSec: Float?, persist: Boolean = true) {
        settings.update { it.copy(speed = pxPerSec?.coerceIn(AutoScroller.MIN_SPEED, AutoScroller.MAX_SPEED)) }
        edited += Field.Speed
        if (persist) saveSettings()
    }

    /** Tempo-based estimate measured by the screen once the sheet is laid out; not persisted. */
    fun setAutoSpeed(pxPerSec: Float) = view.update { it.copy(autoSpeed = pxPerSec) }

    /** Sets and saves the text size (a song pref only, never a playlist item setting). */
    fun setTextScale(scale: Float) {
        settings.update { it.copy(textScale = scale.coerceIn(MIN_SCALE, MAX_SCALE)) }
        saveTextScale()
    }

    fun setPlaying(playing: Boolean) = view.update { it.copy(playing = playing) }
    fun togglePlaying() = view.update { it.copy(playing = !it.playing) }
    fun setChordsOnly(on: Boolean) = view.update { it.copy(chordsOnly = on) }
    /** Diagram instrument is an app-wide preference. */
    fun setInstrument(i: DiagramInstrument) {
        viewModelScope.launch { diagramPrefs.setInstrument(i) }
    }

    fun toggleFavourite() {
        val now = (state.value as? SongState.Ready)?.ui?.isFavourite ?: return
        viewModelScope.launch { library.setFavourite(songId, !now) }
    }

    fun addToPlaylist(id: String, name: String) {
        viewModelScope.launch {
            try {
                library.addToPlaylist(id, songId)
                _messages.trySend("Added to $name")
            } catch (_: PlaylistFullException) {
                _messages.trySend("$name is full")
            }
        }
    }

    fun createPlaylistAndAdd(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val id = library.createPlaylist(name)
            library.addToPlaylist(id, songId)
            _messages.trySend("Added to ${name.trim()}")
        }
    }

    /** Flags the song for moderators. A blank [note] is not sent. */
    fun flag(reason: FlagReason, note: String?) {
        viewModelScope.launch {
            val message = try {
                flagger(songId, reason, note?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_FLAG_NOTE))
                "Thanks, we'll take a look"
            } catch (e: ApiError) {
                when {
                    e is ApiError.Http && e.error == "own_song" -> "You added this song, so it can't be flagged"
                    e is ApiError.Http && e.error == "flag_rate_limited" -> "You've sent a lot of flags today. Try again tomorrow."
                    e is ApiError.Http && e.code == 404 -> "This song isn't available any more"
                    else -> "Couldn't send the flag. Check your connection."
                }
            }
            _messages.trySend(message)
        }
    }

    /** `https://<host>/portal/songs/<id>`, or null without an account. */
    fun portalUrl(): String? = portalHost()?.let { host ->
        (ServiceHost.baseUrl(host, BuildConfig.DEBUG) ?: return null) + "/portal/songs/" + songId
    }

    /** ChordPro of what is on screen: shapes as chords, `{key}` = sounding key and `{capo}` when one is used. */
    fun exportText(): String {
        val ui = current() ?: return ""
        val meta = LinkedHashMap(ui.doc.meta)
        ui.soundingKey?.let { meta["key"] = it }
        if (ui.capo > 0) meta["capo"] = ui.capo.toString() else meta.remove("capo")
        return ChordPro.serialize(ui.doc.copy(meta = meta))
    }

    fun exportFileName(): String {
        val title = loaded.value?.song?.title.orEmpty()
        val slug = title.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(60)
        return (slug.ifEmpty { "song" }) + ".cho"
    }

    /** The song as shown right now, built from the latest inputs (not waiting for [state] to recombine). */
    private fun current(): SongUi? = loaded.value?.let {
        val s = settings.value
        ui(shapeDoc(it, s.transpose, s.capo, keyless.value), s, view.value, isFavourite = false, instrument = DiagramInstrument.Guitar)
    }

    private fun edit(field: Field, change: (Settings) -> Settings) {
        settings.update(change)
        edited += field
        saveSettings()
    }

    /** Transpose/capo/speed: to the playlist item (only fields it had or that were edited here), else to SongPrefs. */
    private fun saveSettings() {
        if (loaded.value == null) return
        viewModelScope.launch {
            saveLock.withLock {
                val s = settings.value
                if (playlistId != null) {
                    val out = ItemSettings(
                        transpose = (savedTranspose ?: s.transpose).takeIf { itemBase.transpose != null || Field.Transpose in edited },
                        capo = s.capo.takeIf { itemBase.capo != null || Field.Capo in edited },
                        scrollSpeed = if (itemBase.scrollSpeed != null || Field.Speed in edited) s.speed?.toDouble() else null,
                    )
                    try {
                        library.setItemSettings(playlistId, songId, out)
                    } catch (_: IllegalArgumentException) { /* the item was removed meanwhile */ }
                } else {
                    val prefs = SongPrefs(songId, savedTranspose ?: s.transpose, s.capo, s.speed?.toDouble() ?: 0.0, s.textScale)
                    songs.setPrefs(prefs)
                    basePrefs = prefs
                }
            }
        }
    }

    /** Text size is always a song pref; in playlist mode the song's own transpose/capo/speed are kept. */
    private fun saveTextScale() {
        if (loaded.value == null) return
        viewModelScope.launch {
            saveLock.withLock {
                val s = settings.value
                val base = basePrefs
                val prefs = if (playlistId != null) {
                    SongPrefs(songId, base?.transpose ?: 0, base?.capo ?: 0, base?.scrollSpeed ?: 0.0, s.textScale)
                } else {
                    SongPrefs(songId, savedTranspose ?: s.transpose, s.capo, s.speed?.toDouble() ?: 0.0, s.textScale)
                }
                if (prefs != base) { songs.setPrefs(prefs); basePrefs = prefs }
            }
        }
    }

    private fun normalise(t: Int): Int {
        var d = ((t % 12) + 12) % 12
        if (d > 6) d -= 12
        return d
    }

}
