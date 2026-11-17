package dev.avijit.orbittuner.metronome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.avijit.orbittuner.settings.Prefs
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The metronome controls over the shared [Metronome] engine: the engine's state is the single source of truth
 * (bpm, beats, accent, playing, the beat just heard), so two screens on the same engine always agree.
 *
 * On creation the Tools tab's last tempo and bar length are read from [Prefs]; a [load] (the song sheet) or any
 * change made before they arrive wins. Changes the player makes here are saved; a song's [load] is not.
 */
class MetronomeViewModel(
    private val engine: Metronome,
    private val prefs: Prefs,
    clock: () -> Long,
    /** False for the song sheet: it neither reads the saved Tools values nor writes anything to [Prefs]. */
    private val persist: Boolean = true,
) : ViewModel() {
    val state: StateFlow<MetronomeState> get() = engine.state

    private val tapper = TapTempo(clock)

    /** Something has set the values already: the saved ones must not override it. */
    private var touched = false

    /** What the Tools tab last set (or read from Prefs); [restore] puts it back on the shared engine. */
    private var toolsBpm: Int? = null
    private var toolsBeats: Int? = null
    private var toolsAccent: Boolean? = null

    /** True once the saved values have been read (applied or not). */
    @Volatile var loaded = false
        private set

    init {
        if (!persist) {
            loaded = true
        } else viewModelScope.launch {
            val bpm = prefs.metronomeBpm.first()
            val beats = prefs.metronomeBeats.first()
            // A playing engine belongs to whoever started it; don't change its tempo under them.
            if (!touched && !engine.state.value.playing) {
                engine.set(bpm, beats, engine.state.value.accent)
            }
            if (toolsBpm == null) toolsBpm = Tempo.clamp(bpm)
            if (toolsBeats == null) toolsBeats = beats.coerceIn(Tempo.MIN_BEATS, Tempo.MAX_BEATS)
            loaded = true
        }
    }

    /**
     * Puts this tab's own tempo, bar length and accent back on the shared engine (the song sheet may have loaded
     * a song's values since). Does nothing while the engine is playing, or for a view model that doesn't persist.
     */
    fun restore() {
        if (!persist || engine.state.value.playing) return
        val s = engine.state.value
        val bpm = toolsBpm ?: return
        val beats = toolsBeats ?: return
        engine.set(bpm, beats, toolsAccent ?: s.accent)
    }

    fun toggle() {
        if (engine.state.value.playing) engine.stop() else engine.start()
    }

    fun start() = engine.start()
    fun stop() = engine.stop()

    fun nudge(delta: Int) = setBpm(engine.state.value.bpm + delta)

    fun setBpm(bpm: Int) {
        val s = engine.state.value
        val b = Tempo.clamp(bpm)
        touched = true
        if (persist) toolsBpm = b
        if (b == s.bpm) return
        engine.set(b, s.beatsPerBar, s.accent)
        if (persist) viewModelScope.launch { prefs.setMetronomeBpm(b) }
    }

    fun setBeats(n: Int) {
        val s = engine.state.value
        val beats = n.coerceIn(Tempo.MIN_BEATS, Tempo.MAX_BEATS)
        touched = true
        if (persist) toolsBeats = beats
        engine.set(s.bpm, beats, s.accent)
        if (persist) viewModelScope.launch { prefs.setMetronomeBeats(beats) }
    }

    fun setAccent(on: Boolean) {
        val s = engine.state.value
        if (persist) toolsAccent = on
        engine.set(s.bpm, s.beatsPerBar, on)
    }

    fun tap() {
        tapper.tap()?.let(::setBpm)
    }

    /** Preloads a song's tempo and beats; 80 BPM and 4 beats when the song has none. Not saved. */
    fun load(bpm: Int?, beats: Int?) {
        touched = true
        val s = engine.state.value
        engine.set(
            Tempo.clamp(bpm ?: Tempo.DEFAULT_BPM),
            (beats ?: Tempo.DEFAULT_BEATS).coerceIn(Tempo.MIN_BEATS, Tempo.MAX_BEATS),
            s.accent,
        )
    }
}
