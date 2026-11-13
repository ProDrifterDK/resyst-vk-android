package com.resyst.vk.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.text.Normalizer

/**
 * r13a: the full-form valid-word set of one language (docs/failure-modes.md VW1–VW12), read from
 * `assets/lexicon/valid-<code>.dawg` (scripts/gen_valid_words.py: pinned Hunspell + the Spanish
 * enclitic layer). A minimized DAWG used in place inside the buffer it was read from — memory-mapped
 * on the device — so the set costs no per-word objects and nothing is built after [read] (VW9/VW10).
 * Immutable, safe to share between threads.
 *
 * Case rules are Hunspell's (VW4): lowercase input matches lowercase entries only; an Initial
 * capital (sentence start) also matches its lowercase entry; ALL CAPS matches any casing of the
 * same letters; mixed case (`iPhone`) matches exactly. Input may be NFD and may use ’ for '.
 */
class ValidWords private constructor(
    /** Number of words in the set. */
    val size: Int,
    /** License notice embedded by the generator (MPL-1.1+ for es, SCOWL for en). */
    val notice: String,
    private val alphabet: CharArray,
    /** Each label with its accents / diaeresis removed (case and ñ kept). */
    private val labelKey: CharArray,
    private val root: Int,
    private val arcs: IntBuffer,
) {
    /** Exact membership under the case rules. */
    fun contains(word: CharSequence): Boolean {
        val w = clean(word) ?: return false
        if (has(w)) return true
        return when (caseOf(w)) {
            CASE_INITIAL -> has(w.lowercase())
            CASE_ALL -> w.lowercase().let { has(it) || has(capitalize(it)) }
            else -> false
        }
    }

    /**
     * Every member equal to [word] once acute / grave / circumflex accents and the diaeresis are
     * removed from both (ñ stays a letter of its own), under the same case rules as [contains]:
     * `caido` → [caído], `que` → [que, qué]. Members are returned as stored (lowercase for a
     * sentence-initial `Caido`), in label order, without duplicates; empty if none.
     */
    fun accentVariants(word: CharSequence): List<String> {
        val w = clean(word) ?: return emptyList()
        val out = LinkedHashSet<String>()
        collect(w, out)
        when (caseOf(w)) {
            CASE_INITIAL -> collect(w.lowercase(), out)
            CASE_ALL -> w.lowercase().let { collect(it, out); collect(capitalize(it), out) }
        }
        return out.toList()
    }

    private fun collect(w: String, out: MutableSet<String>) {
        val key = CharArray(w.length) { stripMarks(w[it]) }
        walk(root, key, 0, StringBuilder(w.length), out)
    }

    private fun walk(node: Int, key: CharArray, i: Int, sb: StringBuilder, out: MutableSet<String>) {
        var a = node
        while (true) {
            val arc = arcs.get(a)
            val label = arc and LABEL
            if (labelKey[label] == key[i]) {
                sb.append(alphabet[label])
                if (i == key.size - 1) {
                    if (arc and FINAL != 0) out.add(sb.toString())
                } else {
                    val t = arc ushr TARGET_SHIFT
                    if (t != 0) walk(t, key, i + 1, sb, out)
                }
                sb.setLength(sb.length - 1)
            }
            if (arc and LAST != 0) return
            a++
        }
    }

    /** Exact walk (labels are sorted inside a node: stop at the first larger one). */
    private fun has(w: String): Boolean {
        var node = root
        var final = false
        for (c in w) {
            if (node == 0) return false
            val label = alphabet.binarySearch(c)
            if (label < 0) return false
            var a = node
            while (true) {
                val arc = arcs.get(a)
                val l = arc and LABEL
                if (l == label) {
                    final = arc and FINAL != 0
                    node = arc ushr TARGET_SHIFT
                    break
                }
                if (l > label || arc and LAST != 0) return false
                a++
            }
        }
        return final
    }

    companion object {
        const val VERSION = 1
        private const val LABEL = 0xFF
        private const val FINAL = 0x100
        private const val LAST = 0x200
        private const val TARGET_SHIFT = 10
        private const val CASE_LOWER = 0
        private const val CASE_INITIAL = 1
        private const val CASE_ALL = 2
        private const val CASE_MIXED = 3

        /** Where the IME finds a language's set (stored uncompressed in the APK: it is mapped). */
        fun assetPath(lang: Lang): String = "lexicon/valid-${lang.code}.dawg"

        /**
         * The set in [buf] (from its position to its limit), used in place — a view, no copy — or
         * null when it is not a valid r13a asset: wrong magic / version, truncated, or any arc
         * pointing outside the arc table (VW11). Checks every arc once, so lookups can never run
         * out of bounds afterwards.
         */
        fun read(buf: ByteBuffer): ValidWords? = runCatching { parse(buf.slice().order(ByteOrder.BIG_ENDIAN)) }.getOrNull()

        private fun parse(b: ByteBuffer): ValidWords? {
            if (b.remaining() < 24) return null
            if (b.get(0) != 'V'.code.toByte() || b.get(1) != 'K'.code.toByte() || b.get(2) != 'V'.code.toByte() || b.get(3) != 'W'.code.toByte()) return null
            if (b.getInt(4) != VERSION) return null
            val words = b.getInt(8)
            val root = b.getInt(12)
            val nArcs = b.getInt(16)
            val nLabels = b.getShort(20).toInt() and 0xFFFF
            if (words <= 0 || nArcs < 2 || root !in 1 until nArcs || nLabels !in 1..256) return null
            var p = 22
            if (b.limit() < p + 2 * nLabels + 2) return null
            val alphabet = CharArray(nLabels) { b.getChar(p + 2 * it) }
            p += 2 * nLabels
            if ((1 until nLabels).any { alphabet[it - 1] >= alphabet[it] }) return null
            val noticeLen = b.getShort(p).toInt() and 0xFFFF
            p += 2
            if (b.limit() < p + noticeLen) return null
            val noticeBytes = ByteArray(noticeLen).also { for (i in it.indices) it[i] = b.get(p + i) }
            p += noticeLen
            p += (4 - p % 4) % 4
            if ((b.limit() - p).toLong() != nArcs * 4L) return null
            b.position(p)
            val arcs = b.slice().order(ByteOrder.BIG_ENDIAN).asIntBuffer()
            for (i in 1 until nArcs) {
                val a = arcs.get(i)
                if (a and LABEL >= nLabels || a ushr TARGET_SHIFT >= nArcs) return null
            }
            if (arcs.get(nArcs - 1) and LAST == 0) return null
            val keys = CharArray(nLabels) { stripMarks(alphabet[it]) }
            return ValidWords(words, String(noticeBytes, Charsets.UTF_8), alphabet, keys, root, arcs)
        }

        /** NFC, ’ → ' (Hunspell en ICONV); null for empty input. */
        private fun clean(word: CharSequence): String? {
            if (word.isEmpty()) return null
            var s = word.toString()
            if (s.any { it in '\u0300'..'\u036F' }) s = Normalizer.normalize(s, Normalizer.Form.NFC)
            return s.replace('\u2019', '\'')
        }

        private fun caseOf(w: String): Int {
            var letters = 0
            var upper = 0
            for (c in w) if (c.isLetter()) { letters++; if (c.isUpperCase()) upper++ }
            return when {
                upper == 0 -> CASE_LOWER
                upper == 1 && w[0].isUpperCase() -> CASE_INITIAL
                upper == letters -> CASE_ALL
                else -> CASE_MIXED
            }
        }

        private fun capitalize(lower: String) = lower.replaceFirstChar { it.uppercaseChar() }

        /** One char without its accent / diaeresis; ñ / Ñ and anything without marks unchanged. */
        private fun stripMarks(c: Char): Char {
            if (c < '\u00C0' || c == 'ñ' || c == 'Ñ') return c
            val d = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
            return if (d.length > 1 && d.substring(1).all { it in '\u0300'..'\u036F' }) d[0] else c
        }
    }
}
