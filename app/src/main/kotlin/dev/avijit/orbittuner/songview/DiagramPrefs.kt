package dev.avijit.orbittuner.songview

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * App-wide choice of chord diagram instrument (Guitar / Ukulele / Piano), shared by every song; set from the diagram
 * panel. [instrument] is null until the player has picked one (then a default applies).
 */
interface DiagramPrefs {
    val instrument: Flow<DiagramInstrument?>
    suspend fun setInstrument(i: DiagramInstrument)
}

class InMemoryDiagramPrefs(initial: DiagramInstrument? = null) : DiagramPrefs {
    private val state = MutableStateFlow(initial)
    override val instrument: Flow<DiagramInstrument?> = state
    override suspend fun setInstrument(i: DiagramInstrument) { state.value = i }
}

/** DataStore-backed ("songview" file). One store per process, as DataStore requires: use [get]. */
class DataStoreDiagramPrefs private constructor(private val store: DataStore<Preferences>) : DiagramPrefs {
    override val instrument: Flow<DiagramInstrument?> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p -> DiagramInstrument.entries.firstOrNull { it.name == p[KEY] } }

    override suspend fun setInstrument(i: DiagramInstrument) {
        store.edit { it[KEY] = i.name }
    }

    companion object {
        private val KEY = stringPreferencesKey("diagram_instrument")
        @Volatile private var instance: DataStoreDiagramPrefs? = null

        fun get(context: Context): DataStoreDiagramPrefs = instance ?: synchronized(this) {
            instance ?: DataStoreDiagramPrefs(
                PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
                    context.applicationContext.preferencesDataStoreFile("songview")
                },
            ).also { instance = it }
        }
    }
}
