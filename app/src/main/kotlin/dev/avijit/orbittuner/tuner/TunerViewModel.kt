package dev.avijit.orbittuner.tuner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.avijit.orbittuner.music.Accidental
import dev.avijit.orbittuner.music.Pitch
import dev.avijit.orbittuner.settings.AccidentalPref
import dev.avijit.orbittuner.settings.CustomTuning
import dev.avijit.orbittuner.settings.MeterStyle
import dev.avijit.orbittuner.settings.NoteNaming
import dev.avijit.orbittuner.settings.Prefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

enum class ModeChoice { Auto, Manual, Chromatic }

/** Everything the tuner screen shows apart from the live reading. */
data class TunerUi(
    val instruments: List<Instrument>,
    val instrument: Instrument,
    /** Selected tuning as stored (no shift); null for chromatic instruments. */
    val baseTuning: Tuning?,
    /** [baseTuning] moved by [offset]: what the engine tunes to and the pegs show. */
    val tuning: Tuning?,
    val customIds: Set<String>,
    val mode: ModeChoice,
    val manualString: Int,
    val offset: Int,
    val a4: Double,
    val naming: NoteNaming,
    val accidental: AccidentalPref,
    val haptics: Boolean,
    val meterStyle: MeterStyle,
) {
    val strings: List<NoteLabel> = tuning?.notes?.map { NoteLabels.label(it, naming) } ?: emptyList()
    val stringsText: String get() = strings.joinToString(" ") { it.name }
}

/**
 * The reading reduced to what text needs: whole cents, so it changes a few times a second rather than on every
 * analysis hop. The needle animates from the raw [TunerViewModel.live] stream instead.
 */
data class Readout(
    val note: NoteLabel? = null,
    val cents: Int = 0,
    val hz: Double = 0.0,
    val targetHz: Double = 0.0,
    val stringIndex: Int? = null,
    val inTune: Boolean = false,
    val error: MicError? = null,
) {
    val hasPitch: Boolean get() = note != null

    /**
     * Spoken meter state in coarse buckets ("Slightly flat", "In tune"), so TalkBack speaks when the state
     * changes in a meaningful way, not on every cent; the exact cents are in the visible text.
     */
    val stateDescription: String get() = when {
        error != null -> "Microphone unavailable"
        note == null -> "Listening"
        inTune -> "In tune"
        abs(cents) <= SLIGHT_CENTS -> if (cents < 0) "Slightly flat" else "Slightly sharp"
        else -> if (cents < 0) "Flat" else "Sharp"
    }

    companion object {
        const val SLIGHT_CENTS = 15
    }
}

class TunerViewModel(
    private val engine: PitchTracker,
    private val prefs: Prefs,
    private val builtIn: List<Instrument>,
    private val tone: TonePlayer,
) : ViewModel() {

    private data class Local(
        val instrumentId: String?,
        val tuningId: String?,
        val offset: Int,
        val mode: ModeChoice,
        val manualString: Int,
        /** A just-saved custom tuning, shown before the store echoes it back. */
        val pending: CustomTuning? = null,
    )

    private val local = MutableStateFlow<Local?>(null)

    /** Raw engine state, one emission per analysis hop: for the needle only. */
    val live: StateFlow<TunerState> get() = engine.state

    private val _tuned = MutableStateFlow<Set<Int>>(emptySet())
    /**
     * Strings shown as tuned (ticks and the haptic). Follows the engine, except that strings the engine reports
     * while a reference tone plays (and for [TONE_TAIL_MS] after) are ignored: the mic hears the speaker.
     */
    val tuned: StateFlow<Set<Int>> = _tuned.asStateFlow()
    private var engineTuned: Set<Int> = emptySet()
    @Volatile private var toneEndedAt = Long.MIN_VALUE / 2
    private var holdJob: Job? = null

    private fun toneSuppressed(): Boolean =
        _toneString.value != null || System.nanoTime() / 1_000_000 < toneEndedAt + TONE_TAIL_MS

    private val _toneString = MutableStateFlow<Int?>(null)
    /** String whose reference tone is playing (Manual mode). */
    val toneString: StateFlow<Int?> = _toneString.asStateFlow()
    private var toneJob: Job? = null

    val ui: StateFlow<TunerUi?> = combine(
        local.filterNotNull(), prefs.a4, prefs.noteNaming, prefs.accidental, prefs.customTunings, prefs.haptics,
        prefs.meterStyle,
    ) { arr ->
        @Suppress("UNCHECKED_CAST")
        resolve(
            arr[0] as Local, arr[1] as Double, arr[2] as NoteNaming, arr[3] as AccidentalPref,
            arr[4] as List<CustomTuning>, arr[5] as Boolean, arr[6] as MeterStyle,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val readout: StateFlow<Readout> = combine(engine.state, ui) { s, u -> toReadout(s, u) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, Readout())

    /** Narrow views of [readout] for parts of the screen that only care about one field. */
    val stringIndex: StateFlow<Int?> = readout.map { it.stringIndex }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val micError: StateFlow<MicError?> = readout.map { it.error }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            local.value = Local(
                instrumentId = prefs.lastInstrumentId.first(),
                tuningId = prefs.lastTuningId.first(),
                offset = prefs.tuningOffset.first(),
                mode = ModeChoice.Auto,
                manualString = 0,
            )
        }
        viewModelScope.launch {
            var prev: Selected? = null
            ui.filterNotNull()
                .map { u -> Selected(u.instrument, u.tuning, engineMode(u)) }
                .distinctUntilChanged()
                .collect { sel ->
                    val p = prev
                    // Only a new instrument, tuning or shift clears the ticks; a mode or string change keeps them.
                    if (p == null || p.instrument.id != sel.instrument.id || p.tuning != sel.tuning) {
                        engine.select(sel.instrument, sel.tuning, sel.mode)
                        _tuned.value = emptySet()
                    } else {
                        engine.setMode(sel.mode)
                    }
                    prev = sel
                }
        }
        viewModelScope.launch {
            engine.tunedStrings.collect { now ->
                val added = now - engineTuned
                engineTuned = now
                when {
                    now.isEmpty() -> _tuned.value = emptySet()
                    added.isNotEmpty() && !toneSuppressed() -> _tuned.update { it + added }
                }
            }
        }
    }

    private data class Selected(val instrument: Instrument, val tuning: Tuning?, val mode: TunerMode)

    private fun engineMode(u: TunerUi): TunerMode = when {
        u.tuning == null || u.mode == ModeChoice.Chromatic -> TunerMode.Chromatic
        u.mode == ModeChoice.Manual -> TunerMode.Manual(u.manualString.coerceIn(0, u.tuning.notes.size - 1))
        else -> TunerMode.Auto
    }

    private fun resolve(
        l: Local, a4: Double, naming: NoteNaming, acc: AccidentalPref, stored: List<CustomTuning>, haptics: Boolean,
        meterStyle: MeterStyle,
    ): TunerUi {
        val customs = if (l.pending != null && stored.none { it.tuning.id == l.pending.tuning.id }) stored + l.pending else stored
        val byInstrument = customs.groupBy { it.instrumentId }
        val instruments = builtIn.map { i ->
            val extra = byInstrument[i.id]
            if (extra.isNullOrEmpty() || i.chromatic) i else i.copy(tunings = i.tunings + extra.map { it.tuning })
        }
        val instrument = instruments.firstOrNull { it.id == l.instrumentId }
            ?: instruments.firstOrNull { it.id == DEFAULT_INSTRUMENT } ?: instruments.first()
        val base = if (instrument.chromatic) null
            else instrument.tunings.firstOrNull { it.id == l.tuningId } ?: instrument.tunings.firstOrNull()
        val mode = if (base == null) ModeChoice.Chromatic else l.mode
        return TunerUi(
            instruments = instruments,
            instrument = instrument,
            baseTuning = base,
            tuning = base?.shifted(l.offset),
            customIds = customs.map { it.tuning.id }.toSet(),
            mode = mode,
            manualString = if (base == null) 0 else l.manualString.coerceIn(0, base.notes.size - 1),
            offset = l.offset,
            a4 = a4,
            naming = naming,
            accidental = acc,
            haptics = haptics,
            meterStyle = meterStyle,
        )
    }

    private fun toReadout(s: TunerState, u: TunerUi?): Readout = when (s) {
        is TunerState.Error -> Readout(error = s.reason)
        is TunerState.Reading -> {
            val tuning = u?.tuning
            val idx = s.stringIndex?.takeIf { tuning != null && it in tuning.notes.indices }
            val midi = runCatching { NoteLabels.readingMidi(s.noteName, s.octave) }.getOrNull()
            val targetMidi = if (idx != null) tuning!!.midi[idx] else midi ?: 69
            val label = when {
                idx != null -> u!!.strings[idx]
                u != null -> NoteLabels.label(targetMidi, u.accidental, u.naming)
                else -> NoteLabel(s.noteName.replace("#", "♯"), s.octave)
            }
            val cents = s.cents.roundToInt()
            val target = Pitch.midiToHz(targetMidi.toDouble(), u?.a4 ?: Prefs.DEFAULT_A4)
            Readout(
                note = label,
                cents = cents,
                // Derived from the smoothed, rounded cents so the Hz text moves with the needle, not with noise.
                hz = target * 2.0.pow(cents / 1200.0),
                targetHz = target,
                stringIndex = idx,
                inTune = s.inTune,
            )
        }
        else -> Readout()
    }

    // ---- lifecycle ----

    /** Start listening (Tune is visible and the mic permission is granted). */
    fun start() = engine.start()

    /** Stop listening and silence any reference tone (Tune hidden or paused). */
    fun stop() {
        stopTone()
        engine.stop()
    }

    override fun onCleared() {
        stop()
        // stopTone() releases the hold from a coroutine, which dies with viewModelScope; the engine is app-wide.
        holdJob?.cancel()
        engine.holdTuned(false)
    }

    // ---- selection ----

    fun selectTuning(instrumentId: String, tuningId: String?) {
        stopTone()
        local.update { it?.copy(instrumentId = instrumentId, tuningId = tuningId, manualString = 0) }
        viewModelScope.launch { prefs.setSelection(instrumentId, tuningId) }
    }

    fun setMode(mode: ModeChoice) {
        if (mode != ModeChoice.Manual) stopTone()
        local.update { l ->
            l?.copy(mode = mode, manualString = if (mode == ModeChoice.Manual) readout.value.stringIndex ?: l.manualString else l.manualString)
        }
    }

    /** Tapping a string picks it (switching to Manual) and plays its reference tone; tapping it again silences it. */
    fun tapString(index: Int) {
        val u = ui.value ?: return
        val tuning = u.tuning ?: return
        if (index !in tuning.notes.indices) return
        if (u.mode == ModeChoice.Manual && u.manualString == index && _toneString.value == index) {
            stopTone(); return
        }
        local.update { it?.copy(mode = ModeChoice.Manual, manualString = index) }
        val hz = Pitch.midiToHz(tuning.midi[index].toDouble(), u.a4)
        holdJob?.cancel()
        engine.holdTuned(true)
        tone.play(hz)
        _toneString.value = index
        toneJob?.cancel()
        toneJob = viewModelScope.launch { delay(TONE_MS); stopTone() }
    }

    private fun stopTone() {
        toneJob?.cancel(); toneJob = null
        if (_toneString.value == null) return
        tone.stop()
        _toneString.value = null
        toneEndedAt = System.nanoTime() / 1_000_000
        // The fade-out and room echo still reach the mic for a moment.
        holdJob?.cancel()
        holdJob = viewModelScope.launch { delay(TONE_TAIL_MS); engine.holdTuned(false) }
    }

    fun setOffset(semitones: Int) {
        val v = semitones.coerceIn(Prefs.MIN_OFFSET, Prefs.MAX_OFFSET)
        stopTone()
        local.update { it?.copy(offset = v) }
        viewModelScope.launch { prefs.setTuningOffset(v) }
    }

    fun setA4(hz: Double) { viewModelScope.launch { prefs.setA4(hz) } }
    fun setHaptics(on: Boolean) { viewModelScope.launch { prefs.setHaptics(on) } }

    /** Notes for a new custom tuning: the current (shifted) tuning, or another instrument's first tuning. */
    fun customStartMidi(instrumentId: String): List<Int> {
        val u = ui.value ?: return emptyList()
        if (instrumentId == u.instrument.id) return u.tuning?.midi ?: emptyList()
        return u.instruments.firstOrNull { it.id == instrumentId }?.tunings?.firstOrNull()?.midi ?: emptyList()
    }

    fun noteFor(midi: Int): NoteLabel {
        val u = ui.value
        return NoteLabels.label(midi, u?.accidental ?: AccidentalPref.Sharps, u?.naming ?: NoteNaming.Letters)
    }

    /** Saves a custom tuning for [instrumentId] and selects it (instrument included). */
    fun saveCustomTuning(instrumentId: String, name: String, midi: List<Int>) {
        val u = ui.value ?: return
        val inst = u.instruments.firstOrNull { it.id == instrumentId } ?: return
        if (name.isBlank() || midi.isEmpty() || inst.chromatic) return
        val acc = if (u.accidental == AccidentalPref.Flats) Accidental.Flat else Accidental.Sharp
        val t = Tuning("custom-" + UUID.randomUUID(), name.trim(), midi.map { NoteLabels.midiToNote(it, acc) })
        val c = CustomTuning(inst.id, t)
        stopTone()
        // The new tuning is entered as written: the whole-tuning shift goes back to 0.
        local.update { it?.copy(instrumentId = inst.id, tuningId = t.id, pending = c, manualString = 0, offset = 0) }
        viewModelScope.launch { prefs.addCustomTuning(c, select = true) }
    }

    companion object {
        const val DEFAULT_INSTRUMENT = "guitar"
        private const val TONE_MS = 4_000L
        const val TONE_TAIL_MS = 150L
    }
}
