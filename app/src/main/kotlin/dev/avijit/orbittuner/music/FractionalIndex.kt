/*
 * Kotlin port of generateKeyBetween from "fractional-indexing"
 * https://github.com/rocicorp/fractional-indexing
 *
 * MIT License
 * Copyright (c) 2019 rocicorp
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package dev.avijit.orbittuner.music

object FractionalIndex {
    private const val DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private const val ZERO = '0'
    private val SMALLEST_INTEGER = "A" + "0".repeat(26)

    fun between(a: String?, b: String?): String {
        if (a != null) validateKey(a)
        if (b != null) validateKey(b)
        require(a == null || b == null || a < b) { "$a >= $b" }
        if (a == null && b == null) return "a$ZERO"
        if (a == null) {
            val ib = integerPart(b!!)
            val fb = b.substring(ib.length)
            if (ib == SMALLEST_INTEGER) return ib + midpoint("", fb)
            if (ib < b) return ib
            return decrement(ib) ?: error("cannot decrement any more")
        }
        if (b == null) {
            val ia = integerPart(a)
            val fa = a.substring(ia.length)
            return increment(ia) ?: (ia + midpoint(fa, null))
        }
        val ia = integerPart(a); val fa = a.substring(ia.length)
        val ib = integerPart(b); val fb = b.substring(ib.length)
        if (ia == ib) return ia + midpoint(fa, fb)
        val i = increment(ia) ?: error("cannot increment any more")
        return if (i < b) i else ia + midpoint(fa, null)
    }

    private fun midpoint(a: String, b: String?): String {
        require(b == null || a < b) { "$a >= $b" }
        require(!a.endsWith(ZERO) && b?.endsWith(ZERO) != true) { "trailing zero" }
        if (b != null) {
            var n = 0
            while ((if (n < a.length) a[n] else ZERO) == b.getOrNull(n)) n++
            if (n > 0) return b.substring(0, n) + midpoint(a.drop(n), b.substring(n))
        }
        val digitA = if (a.isNotEmpty()) DIGITS.indexOf(a[0]) else 0
        val digitB = if (b != null) DIGITS.indexOf(b[0]) else DIGITS.length
        if (digitB - digitA > 1) {
            return DIGITS[(digitA + digitB + 1) / 2].toString() // Math.round(0.5 * sum)
        }
        return if (b != null && b.length > 1) b.substring(0, 1)
        else DIGITS[digitA] + midpoint(a.drop(1), null)
    }

    private fun integerLength(head: Char): Int = when (head) {
        in 'a'..'z' -> head - 'a' + 2
        in 'A'..'Z' -> 'Z' - head + 2
        else -> throw IllegalArgumentException("invalid order key head: $head")
    }

    private fun integerPart(key: String): String {
        require(key.isNotEmpty()) { "invalid order key: empty" }
        val len = integerLength(key[0])
        require(len <= key.length) { "invalid order key: $key" }
        return key.substring(0, len)
    }

    private fun validateKey(key: String) {
        val i = integerPart(key)
        require(i != SMALLEST_INTEGER || key.length > i.length) { "invalid order key: $key" }
        require(!key.endsWith(ZERO) || key.length == i.length) { "invalid order key: $key" }
    }

    private fun increment(x: String): String? {
        val head = x[0]
        val digs = x.substring(1).toCharArray()
        var carry = true
        var i = digs.size - 1
        while (carry && i >= 0) {
            val d = DIGITS.indexOf(digs[i]) + 1
            if (d == DIGITS.length) digs[i] = ZERO else { digs[i] = DIGITS[d]; carry = false }
            i--
        }
        if (!carry) return head + String(digs)
        if (head == 'Z') return "a$ZERO"
        if (head == 'z') return null
        val h = head + 1
        val out = if (h > 'a') String(digs) + ZERO else String(digs).dropLast(1)
        return h + out
    }

    private fun decrement(x: String): String? {
        val head = x[0]
        val digs = x.substring(1).toCharArray()
        var borrow = true
        var i = digs.size - 1
        while (borrow && i >= 0) {
            val d = DIGITS.indexOf(digs[i]) - 1
            if (d == -1) digs[i] = DIGITS.last() else { digs[i] = DIGITS[d]; borrow = false }
            i--
        }
        if (!borrow) return head + String(digs)
        if (head == 'a') return "Z" + DIGITS.last()
        if (head == 'A') return null
        val h = head - 1
        val out = if (h < 'Z') String(digs) + DIGITS.last() else String(digs).dropLast(1)
        return h + out
    }
}
