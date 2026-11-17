package dev.avijit.orbittuner.tuner

import android.content.Context
import org.json.JSONObject

data class Tuning(val id: String, val name: String, val notes: List<String>) {
    /** MIDI numbers of [notes], in the same (ascending pitch) order. */
    val midi: List<Int> = notes.map { parseNote(it) }

    companion object {
        private val PC = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)
        private val NOTE = Regex("([A-G])([#b]?)(-?[0-9])")

        /** Scientific pitch notation: C4 = 60, accidentals '#' or 'b'. */
        fun parseNote(s: String): Int {
            val m = NOTE.matchEntire(s) ?: throw IllegalArgumentException("Bad note: $s")
            val acc = when (m.groupValues[2]) { "#" -> 1; "b" -> -1; else -> 0 }
            return 12 * (m.groupValues[3].toInt() + 1) + PC.getValue(m.groupValues[1][0]) + acc
        }
    }
}

data class Instrument(
    val id: String,
    val name: String,
    val category: String,
    val chromatic: Boolean,
    val tunings: List<Tuning>,
)

object Tunings {
    const val ASSET = "tunings.json"

    fun load(ctx: Context): List<Instrument> =
        parse(ctx.assets.open(ASSET).bufferedReader().use { it.readText() })

    fun parse(json: String): List<Instrument> {
        val arr = JSONObject(json).getJSONArray("instruments")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val ts = o.getJSONArray("tunings")
            Instrument(
                id = o.getString("id"),
                name = o.getString("name"),
                category = o.getString("category"),
                chromatic = o.optBoolean("chromatic", false),
                tunings = (0 until ts.length()).map { j ->
                    val t = ts.getJSONObject(j)
                    val n = t.getJSONArray("notes")
                    Tuning(t.getString("id"), t.getString("name"), (0 until n.length()).map { n.getString(it) })
                },
            )
        }
    }
}
