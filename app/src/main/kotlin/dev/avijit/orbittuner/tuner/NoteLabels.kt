package dev.avijit.orbittuner.tuner

import dev.avijit.orbittuner.music.Accidental
import dev.avijit.orbittuner.music.Note
import dev.avijit.orbittuner.settings.AccidentalPref
import dev.avijit.orbittuner.settings.NoteNaming

/** A note as shown on screen: [name] ("E♭", "Sol♯") and [octave] in scientific pitch notation. */
data class NoteLabel(val name: String, val octave: Int) {
    val full: String get() = "$name$octave"
}

object NoteLabels {
    private val SOLFEGE = mapOf('C' to "Do", 'D' to "Re", 'E' to "Mi", 'F' to "Fa", 'G' to "Sol", 'A' to "La", 'B' to "Si")
    private val NOTE = Regex("([A-G])([#b]?)(-?[0-9])")

    fun midiToNote(midi: Int, acc: Accidental): String = Note(midi).name(acc) + (Math.floorDiv(midi, 12) - 1)

    /** Display label for a scientific-pitch string such as "Eb2"; the spelling of the source is kept. */
    fun label(note: String, naming: NoteNaming): NoteLabel {
        val m = NOTE.matchEntire(note) ?: return NoteLabel(note, 0)
        return NoteLabel(letter(m.groupValues[1][0], m.groupValues[2], naming), m.groupValues[3].toInt())
    }

    fun label(midi: Int, acc: AccidentalPref, naming: NoteNaming): NoteLabel =
        label(midiToNote(midi, if (acc == AccidentalPref.Flats) Accidental.Flat else Accidental.Sharp), naming)

    private fun letter(l: Char, acc: String, naming: NoteNaming): String {
        val base = if (naming == NoteNaming.Solfege) SOLFEGE.getValue(l) else l.toString()
        return base + when (acc) { "#" -> "♯"; "b" -> "♭"; else -> "" }
    }

    /** MIDI number of an engine reading (sharp-spelled name + octave). */
    fun readingMidi(noteName: String, octave: Int): Int = Tuning.parseNote(noteName + octave)
}

/**
 * This tuning moved by [semitones] (the user's whole-tuning shift). Shifted notes are spelled in the direction
 * of travel (down: flats, up: sharps), so a half step down reads E♭ A♭ D♭ G♭ B♭ E♭.
 */
fun Tuning.shifted(semitones: Int): Tuning {
    if (semitones == 0) return this
    val acc = if (semitones < 0) Accidental.Flat else Accidental.Sharp
    return Tuning(id, name, midi.map { NoteLabels.midiToNote(it + semitones, acc) })
}

/** Picker search: names, and notes as letters or Solfège, with ♭/♯ glyphs or b/# typed. */
object TuningSearch {
    private fun norm(s: String) = s.lowercase(java.util.Locale.ROOT).replace('♭', 'b').replace('♯', '#')

    private fun haystack(t: Tuning): String = buildString {
        append(t.name).append(' ')
        val pitch = t.notes.map { it.filterNot(Char::isDigit).trimEnd('-') }
        append(pitch.joinToString(" ")).append(' ')
        append(t.notes.joinToString(" ") { NoteLabels.label(it, NoteNaming.Solfege).name })
    }.let(::norm)

    /** True when [query] (already trimmed) is found in the instrument's name or the tuning's name and notes. */
    fun matches(instrument: Instrument?, tuning: Tuning?, query: String): Boolean {
        val q = norm(query.trim())
        if (q.isEmpty()) return true
        if (instrument != null && norm(instrument.name).contains(q)) return true
        return tuning != null && haystack(tuning).contains(q)
    }
}
