package dev.avijit.orbittuner.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dev.avijit.orbittuner.backing.Backing
import dev.avijit.orbittuner.metronome.Tempo
import dev.avijit.orbittuner.music.Accidental
import dev.avijit.orbittuner.songview.SongDefaults
import dev.avijit.orbittuner.tuner.Tuning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

enum class NoteNaming { Letters, Solfege }

/** Spelling for notes that are not fixed by a tuning (chromatic readings, new custom tunings). */
enum class AccidentalPref { Sharps, Flats }

/** App theme: follow the system, or force dark / light. */
enum class ThemeMode { System, Dark, Light }

/** Tuner meter: Guide talks a beginner through each string; Dial shows exact cents. */
enum class MeterStyle { Guide, Dial }

/** The Tools tab's two panes. */
enum class ToolsPane { Metronome, Backing }

/** A backing-track lesson's last key shift (semitones) and tempo, as the player left them. */
@Serializable
data class BackingSaved(val keyOffset: Int, val bpm: Int)

/** A user-made tuning; it belongs to one instrument. */
data class CustomTuning(val instrumentId: String, val tuning: Tuning)

/**
 * App preferences (DataStore). Reads are flows; every setter is a suspending write. One instance per file
 * (AppGraph owns the app's one), because DataStore forbids two live stores on the same file.
 */
class Prefs(
    context: Context,
    fileName: String = "orbit_prefs",
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) {
        context.applicationContext.preferencesDataStoreFile(fileName)
    }

    private val data: Flow<Preferences> = store.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    private fun <T> field(read: (Preferences) -> T): Flow<T> = data.map(read).distinctUntilChanged()

    val a4: Flow<Double> = field { (it[A4] ?: DEFAULT_A4).coerceIn(MIN_A4, MAX_A4) }
    val noteNaming: Flow<NoteNaming> = field { enumOr(it[NAMING], NoteNaming.Letters) }
    val accidental: Flow<AccidentalPref> = field { enumOr(it[ACCIDENTAL], AccidentalPref.Sharps) }
    val lastInstrumentId: Flow<String?> = field { it[LAST_INSTRUMENT] }
    val lastTuningId: Flow<String?> = field { it[LAST_TUNING] }
    /** Whole-tuning shift in semitones, −2..+2. */
    val tuningOffset: Flow<Int> = field { (it[OFFSET] ?: 0).coerceIn(MIN_OFFSET, MAX_OFFSET) }
    val haptics: Flow<Boolean> = field { it[HAPTICS] ?: true }
    val meterStyle: Flow<MeterStyle> = field { enumOr(it[METER_STYLE], MeterStyle.Guide) }
    val customTunings: Flow<List<CustomTuning>> = field { decode(it[CUSTOM]) }
    val theme: Flow<ThemeMode> = field { enumOr(it[THEME], ThemeMode.System) }
    /** Keep the screen on for as long as a song is open (otherwise only while auto-scrolling). */
    val keepScreenOn: Flow<Boolean> = field { it[KEEP_ON] ?: false }
    /** Debug builds only: a service host that replaces BuildConfig.SERVICE_HOST (null: the default). */
    val serviceHostOverride: Flow<String?> = field { it[SERVICE_HOST] }
    /** One-shot: a revoked account was replaced and this device's favourites/playlists were cleared; tell the user. */
    val accountResetNotice: Flow<Boolean> = field { it[RESET_NOTICE] ?: false }
    /** The Tools metronome's last tempo and bar length. */
    val metronomeBpm: Flow<Int> = field { Tempo.clamp(it[METRONOME_BPM] ?: Tempo.DEFAULT_BPM) }
    val metronomeBeats: Flow<Int> = field { (it[METRONOME_BEATS] ?: Tempo.DEFAULT_BEATS).coerceIn(Tempo.MIN_BEATS, Tempo.MAX_BEATS) }

    /** Which Tools pane was open last (Metronome until the player switches). */
    val toolsPane: Flow<ToolsPane> = field { enumOr(it[TOOLS_PANE], ToolsPane.Metronome) }
    /** Backing tracks: each lesson's last key shift and tempo, by lesson id (only lessons the player changed). */
    val backingLessons: Flow<Map<String, BackingSaved>> = field { decodeBacking(it[BACKING]) }

    /** Explore categories the user browsed, with how often (Explore "For you"; local only, never sent). */
    val browsedCategories: Flow<Map<String, Int>> = field { decodeCounts(it[BROWSED]) }

    /** Current A4 for the audio thread (no suspension, no allocation). */
    val a4State: StateFlow<Double> = a4.stateIn(scope, SharingStarted.Eagerly, DEFAULT_A4)

    suspend fun setA4(hz: Double) = store.edit { it[A4] = hz.coerceIn(MIN_A4, MAX_A4) }
    suspend fun setNoteNaming(v: NoteNaming) = store.edit { it[NAMING] = v.name }
    suspend fun setAccidental(v: AccidentalPref) = store.edit { it[ACCIDENTAL] = v.name }
    suspend fun setLastInstrumentId(id: String) = store.edit { it[LAST_INSTRUMENT] = id }
    suspend fun setLastTuningId(id: String?) = store.edit { if (id == null) it.remove(LAST_TUNING) else it[LAST_TUNING] = id }
    suspend fun setSelection(instrumentId: String, tuningId: String?) = store.edit {
        it[LAST_INSTRUMENT] = instrumentId
        if (tuningId == null) it.remove(LAST_TUNING) else it[LAST_TUNING] = tuningId
    }
    suspend fun setTuningOffset(semitones: Int) = store.edit { it[OFFSET] = semitones.coerceIn(MIN_OFFSET, MAX_OFFSET) }
    suspend fun setHaptics(on: Boolean) = store.edit { it[HAPTICS] = on }
    suspend fun setMeterStyle(s: MeterStyle) = store.edit { it[METER_STYLE] = s.name }
    suspend fun setTheme(v: ThemeMode) = store.edit { it[THEME] = v.name }
    suspend fun setKeepScreenOn(on: Boolean) = store.edit { it[KEEP_ON] = on }
    suspend fun setAccountResetNotice(show: Boolean) = store.edit { if (show) it[RESET_NOTICE] = true else it.remove(RESET_NOTICE) }
    suspend fun setServiceHostOverride(host: String?) = store.edit { if (host == null) it.remove(SERVICE_HOST) else it[SERVICE_HOST] = host }
    suspend fun setMetronomeBpm(bpm: Int) = store.edit { it[METRONOME_BPM] = Tempo.clamp(bpm) }
    suspend fun setMetronomeBeats(n: Int) = store.edit { it[METRONOME_BEATS] = n.coerceIn(Tempo.MIN_BEATS, Tempo.MAX_BEATS) }
    suspend fun setToolsPane(p: ToolsPane) = store.edit { it[TOOLS_PANE] = p.name }
    /** Saves [lessonId]'s key shift and tempo (clamped to −6…+6 and 40–220). */
    suspend fun setBackingLesson(lessonId: String, keyOffset: Int, bpm: Int) = store.edit {
        val all = decodeBacking(it[BACKING]) + (lessonId to BackingSaved(keyOffset, bpm).clamped())
        it[BACKING] = json.encodeToString(all)
    }

    private fun decodeBacking(s: String?): Map<String, BackingSaved> =
        if (s.isNullOrBlank()) emptyMap()
        else try { json.decodeFromString<Map<String, BackingSaved>>(s).mapValues { it.value.clamped() } } catch (_: Exception) { emptyMap() }

    private fun BackingSaved.clamped() = BackingSaved(
        keyOffset.coerceIn(Backing.MIN_KEY, Backing.MAX_KEY), bpm.coerceIn(Backing.MIN_BPM, Backing.MAX_BPM),
    )

    /**
     * Counts one browse of category [name] (trimmed; blank is ignored). At most [MAX_BROWSED] categories are kept:
     * past that, the least-browsed one is dropped.
     */
    suspend fun recordBrowsedCategory(name: String) {
        val key = name.trim().takeIf { it.isNotEmpty() } ?: return
        store.edit {
            val counts = decodeCounts(it[BROWSED]).toMutableMap()
            counts.merge(key, 1, Int::plus)
            while (counts.size > MAX_BROWSED) counts.remove(counts.entries.filter { e -> e.key != key }.minBy { e -> e.value }.key)
            it[BROWSED] = json.encodeToString(counts)
        }
    }

    private fun decodeCounts(s: String?): Map<String, Int> =
        if (s.isNullOrBlank()) emptyMap() else try { json.decodeFromString<Map<String, Int>>(s) } catch (_: Exception) { emptyMap() }

    suspend fun setCustomTunings(list: List<CustomTuning>) = store.edit { it[CUSTOM] = encode(list) }
    /** Adds (or replaces) [t]; with [select], also makes it the current tuning with no shift, in the same write. */
    suspend fun addCustomTuning(t: CustomTuning, select: Boolean = false) = store.edit {
        val list = decode(it[CUSTOM]).filterNot { c -> c.tuning.id == t.tuning.id } + t
        it[CUSTOM] = encode(list)
        if (select) {
            it[LAST_INSTRUMENT] = t.instrumentId
            it[LAST_TUNING] = t.tuning.id
            it[OFFSET] = 0
        }
    }

    @Serializable private data class Stored(val instrumentId: String, val id: String, val name: String, val notes: List<String>)

    private fun encode(list: List<CustomTuning>): String =
        json.encodeToString(list.map { Stored(it.instrumentId, it.tuning.id, it.tuning.name, it.tuning.notes) })

    private fun decode(s: String?): List<CustomTuning> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<List<Stored>>(s).mapNotNull { st ->
                // A corrupt entry is dropped rather than taking the whole list with it.
                runCatching { CustomTuning(st.instrumentId, Tuning(st.id, st.name, st.notes)) }.getOrNull()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        const val DEFAULT_A4 = 440.0
        const val MIN_A4 = 415.0
        const val MAX_A4 = 466.0
        const val MIN_OFFSET = -2
        const val MAX_OFFSET = 2

        private val A4 = doublePreferencesKey("a4")
        private val NAMING = stringPreferencesKey("noteNaming")
        private val ACCIDENTAL = stringPreferencesKey("accidental")
        private val LAST_INSTRUMENT = stringPreferencesKey("lastInstrumentId")
        private val LAST_TUNING = stringPreferencesKey("lastTuningId")
        private val OFFSET = intPreferencesKey("tuningOffset")
        private val HAPTICS = booleanPreferencesKey("haptics")
        private val METER_STYLE = stringPreferencesKey("meterStyle")
        private val CUSTOM = stringPreferencesKey("customTunings")
        private val THEME = stringPreferencesKey("theme")
        private val KEEP_ON = booleanPreferencesKey("keepScreenOn")
        private val SERVICE_HOST = stringPreferencesKey("serviceHostOverride")
        private val RESET_NOTICE = booleanPreferencesKey("accountResetNotice")
        private val METRONOME_BPM = intPreferencesKey("metronomeBpm")
        private val METRONOME_BEATS = intPreferencesKey("metronomeBeats")
        private val BROWSED = stringPreferencesKey("browsedCategories")
        private val TOOLS_PANE = stringPreferencesKey("toolsPane")
        private val BACKING = stringPreferencesKey("backingLessons")
        const val MAX_BROWSED = 200
        private val json = Json { ignoreUnknownKeys = true }

        private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: default
    }
}

/**
 * What the song view takes from these prefs: the sharps/flats preference, for songs without a key. (The diagram
 * instrument is its own app-wide choice, songview.DiagramPrefs, shared by Settings and the diagram panel.)
 */
fun Prefs.songDefaults(): Flow<SongDefaults> =
    accidental.map { SongDefaults(if (it == AccidentalPref.Flats) Accidental.Flat else Accidental.Sharp) }.distinctUntilChanged()
