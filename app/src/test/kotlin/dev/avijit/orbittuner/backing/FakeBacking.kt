package dev.avijit.orbittuner.backing

import kotlinx.coroutines.flow.MutableStateFlow

/** Records what the player asked for; tests drive [state] to stand in for the playback head. */
class FakeBacking : Backing {
    data class Play(val lessonId: String, val keyOffset: Int, val bpm: Int, val chords: Boolean)
    data class Update(val keyOffset: Int, val bpm: Int, val chords: Boolean)

    override val state = MutableStateFlow(Backing.IDLE)
    val plays = mutableListOf<Play>()
    val updates = mutableListOf<Update>()
    var stops = 0

    override fun play(lesson: Lesson, keyOffset: Int, bpm: Int, chords: Boolean) {
        plays += Play(lesson.id, keyOffset, bpm, chords)
        val (chord, next) = BackingViewModel.startChords(lesson, keyOffset)
        state.value = BackingState(true, lesson.id, -1, -1, chord, next, countIn = true)
    }

    override fun stop() {
        stops++
        state.value = state.value.copy(playing = false, beat = -1)
    }

    override fun update(keyOffset: Int, bpm: Int, chords: Boolean) {
        if (state.value.playing) updates += Update(keyOffset, bpm, chords)
    }
}

/** The chord the real sequencer announces first for [lesson] at [keyOffset] (the count-in's Position). */
fun sequencerFirstChord(lesson: Lesson, keyOffset: Int): String {
    val s = Sequencer(48_000)
    s.configure(lesson, keyOffset, lesson.bpm)
    return s.next(48_000).filterIsInstance<Event.Position>().first().chord
}
