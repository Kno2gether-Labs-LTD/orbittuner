package dev.avijit.orbittuner.backing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.avijit.orbittuner.settings.BackingSaved
import dev.avijit.orbittuner.settings.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The backing-tracks pane over the one [Backing] engine: which lesson is open (null: the list), and the key shift,
 * tempo and chords toggle the player set for it. The engine's state stays the source of truth for what is heard;
 * [display] merges the two for the screen.
 *
 * Each lesson's key and tempo are saved in [Prefs] when changed and come back when it is opened again. The chords
 * toggle is for this session only.
 *
 * Threading: call everything here (and so the engine's play, stop and update) from the main thread only. The engine
 * locks its own state, but two threads starting it at once could leave the wrong worker running.
 */
class BackingViewModel(private val engine: Backing, private val prefs: Prefs) : ViewModel() {

    /** What the player chose; [lesson] null shows the lesson list. */
    data class Selection(val lesson: Lesson?, val keyOffset: Int, val bpm: Int, val chords: Boolean)

    /** What the player screen shows, from the selection and the engine. */
    data class Display(
        val playing: Boolean,
        val countIn: Boolean,
        /** 0-based beat just heard (−1 before the first). */
        val beat: Int,
        /** 0-based bar in the progression (−1 in the count-in or when stopped). */
        val bar: Int,
        val chord: String,
        val nextChord: String,
    )

    private val _selection = MutableStateFlow(Selection(null, 0, 0, chords = true))
    val selection: StateFlow<Selection> = _selection.asStateFlow()
    val engineState: StateFlow<BackingState> get() = engine.state

    private var saved: Map<String, BackingSaved> = emptyMap()

    /** Lessons changed before the saved values arrived: those keep the player's change. */
    private val touched = mutableSetOf<String>()

    /** True once the saved values have been read. */
    @Volatile var loaded = false
        private set

    init {
        viewModelScope.launch {
            saved = saved + prefs.backingLessons.first().filterKeys { it !in touched }
            loaded = true
            val open = _selection.value.lesson ?: return@launch
            if (open.id !in touched) saved[open.id]?.let { s -> _selection.update { it.copy(keyOffset = s.keyOffset, bpm = s.bpm) } }
        }
    }

    /** Opens [lessonId] at its last key and tempo (the lesson's own when never changed). Stops another lesson. */
    fun open(lessonId: String) {
        val lesson = Lessons.byId(lessonId) ?: return
        val current = _selection.value
        if (current.lesson?.id == lessonId) return
        if (engine.state.value.playing) engine.stop()
        val s = saved[lessonId]
        _selection.value = Selection(lesson, s?.keyOffset ?: 0, s?.bpm ?: lesson.bpm, chords = current.chords)
    }

    /** Back to the list; the track stops. */
    fun close() {
        stop()
        _selection.update { it.copy(lesson = null) }
    }

    fun toggle() {
        val s = _selection.value
        val lesson = s.lesson ?: return
        if (isPlaying(s, engine.state.value)) engine.stop() else engine.play(lesson, s.keyOffset, s.bpm, s.chords)
    }

    fun stop() {
        if (engine.state.value.playing) engine.stop()
    }

    /** Transposes the track by [delta] semitones (−6…+6 from the lesson's key); heard from the next bar line. */
    fun shiftKey(delta: Int) = change { it.copy(keyOffset = (it.keyOffset + delta).coerceIn(Backing.MIN_KEY, Backing.MAX_KEY)) }

    /** Tempo by [delta] BPM (40–220); heard from the next bar line. */
    fun nudgeTempo(delta: Int) = change { it.copy(bpm = (it.bpm + delta).coerceIn(Backing.MIN_BPM, Backing.MAX_BPM)) }

    /** Off: drums and bass only, so the player strums the chords. Heard at once. */
    fun setChords(on: Boolean) {
        val s = _selection.value
        if (s.chords == on) return
        _selection.value = s.copy(chords = on)
        if (isPlaying(s, engine.state.value)) engine.update(s.keyOffset, s.bpm, on)
    }

    private fun change(f: (Selection) -> Selection) {
        val old = _selection.value
        val lesson = old.lesson ?: return
        val new = f(old)
        if (new == old) return
        _selection.value = new
        if (isPlaying(new, engine.state.value)) engine.update(new.keyOffset, new.bpm, new.chords)
        touched += lesson.id
        val entry = BackingSaved(new.keyOffset, new.bpm)
        saved = saved + (lesson.id to entry)
        viewModelScope.launch { prefs.setBackingLesson(lesson.id, entry.keyOffset, entry.bpm) }
    }

    companion object {
        /** True when [engine] is playing the selected lesson. */
        fun isPlaying(s: Selection, engine: BackingState): Boolean =
            engine.playing && s.lesson != null && engine.lessonId == s.lesson.id

        /** While playing, what is heard; otherwise the opening chord and the one after it, in the chosen key. */
        fun display(s: Selection, engine: BackingState): Display {
            val lesson = s.lesson ?: return Display(false, false, -1, -1, "", "")
            if (isPlaying(s, engine)) {
                return Display(true, engine.countIn, engine.beat, engine.bar, engine.chord, engine.nextChord)
            }
            val (chord, next) = startChords(lesson, s.keyOffset)
            return Display(false, false, -1, -1, chord, next)
        }

        /** The first chord of [lesson] and the one after it, as the engine announces them at the count-in. */
        fun startChords(lesson: Lesson, keyOffset: Int): Pair<String, String> {
            val first = tokens(lesson.bars[0])
            val next = first.getOrNull(1) ?: tokens(lesson.bars[1 % lesson.bars.size])[0]
            return lesson.chordIn(first[0], keyOffset) to lesson.chordIn(next, keyOffset)
        }

        private val SPACE = Regex("\\s+")
        private fun tokens(bar: String) = bar.trim().split(SPACE)
    }
}

/** True for x/8 meters (6/8, 7/8), where the tempo counts eighth notes. Swing and shuffle stay in BPM. */
val Lesson.countsEighths: Boolean get() = timeSig.unit == 8

/** "90 BPM", or "♪ = 90" when the lesson counts eighth notes. */
fun tempoLabel(lesson: Lesson, bpm: Int): String = if (lesson.countsEighths) "♪ = $bpm" else "$bpm BPM"

/** [tempoLabel] for screen readers. */
fun tempoDescription(lesson: Lesson, bpm: Int): String =
    if (lesson.countsEighths) "$bpm eighth notes per minute" else "$bpm beats per minute"
