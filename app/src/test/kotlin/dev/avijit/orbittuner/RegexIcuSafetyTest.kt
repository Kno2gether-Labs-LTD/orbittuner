package dev.avijit.orbittuner

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android's ICU regex engine rejects an unescaped literal '}' (and '{' that is not a quantifier)
 * outside a character class, while the JVM accepts it. Robolectric tests run on the JVM, so this
 * lint-style test scans main sources for Regex("...") / Regex("""...""") / "...".toRegex() literals.
 */
class RegexIcuSafetyTest {
    private val quantifier = Regex("""\{\d+(,\d*)?\}""")

    private fun literals(src: String): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        // Regex( "..." / Regex(""" ... """
        Regex("""Regex\(\s*(\"\"\"([\s\S]*?)\"\"\"|"((?:[^"\\\n]|\\.)*)")""").findAll(src).forEach { m ->
            val raw = m.groups[2]?.value
            val body = raw ?: unescape(m.groups[3]!!.value)
            out += src.substring(0, m.range.first).count { it == '\n' } + 1 to body
        }
        Regex("""(\"\"\"([\s\S]*?)\"\"\"|"((?:[^"\\\n]|\\.)*)")\.toRegex\(""").findAll(src).forEach { m ->
            val raw = m.groups[2]?.value
            val body = raw ?: unescape(m.groups[3]!!.value)
            out += src.substring(0, m.range.first).count { it == '\n' } + 1 to body
        }
        return out
    }

    private fun unescape(s: String) = s.replace("\\\\", "\u0000").replace("\\\"", "\"").replace("\u0000", "\\")

    /** Returns problem descriptions for brace misuse outside character classes. */
    internal fun problems(p: String): List<String> {
        val bad = mutableListOf<String>()
        var i = 0
        var inClass = 0
        while (i < p.length) {
            val c = p[i]
            when {
                c == '\\' -> i++ // skip escaped char
                c == '[' -> inClass++
                c == ']' && inClass > 0 -> inClass--
                inClass == 0 && c == '{' -> {
                    if (quantifier.matchAt(p, i) == null) bad += "unescaped '{' at $i"
                }
                inClass == 0 && c == '}' -> {
                    // valid only as the end of a quantifier that started earlier
                    val open = p.lastIndexOf('{', i)
                    val ok = open >= 0 && quantifier.matchAt(p, open)?.range?.last == i
                    if (!ok) bad += "unescaped '}' at $i"
                }
            }
            i++
        }
        return bad
    }

    @Test
    fun detectorFlagsTheOriginalBug() {
        assertTrue(problems("""\{\s*key\s*:\s*([^}]+)}""").isNotEmpty())
        assertTrue(problems("""\{\s*key\s*:\s*([^}]+)\}""").isEmpty())
        assertTrue(problems("""[A-Z]{1,5}x{2}y{3,}""").isEmpty())
    }

    @Test
    fun mainSourcesHaveNoIcuIncompatibleBraces() {
        val root = listOf(File("src/main/kotlin"), File("app/src/main/kotlin")).first { it.isDirectory }
        val failures = mutableListOf<String>()
        var scanned = 0
        root.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            literals(f.readText()).forEach { (line, pat) ->
                scanned++
                problems(pat).forEach { failures += "${f.name}:$line  $it in /$pat/" }
            }
        }
        assertTrue("expected to scan regex literals", scanned > 0)
        assertTrue("ICU-incompatible regex:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
