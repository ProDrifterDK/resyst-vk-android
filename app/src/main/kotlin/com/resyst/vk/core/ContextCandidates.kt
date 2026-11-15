package com.resyst.vk.core

import java.text.Normalizer
import kotlin.math.max
import kotlin.math.sqrt

/**
 * r13f (offers): the confusion set of a typed REAL word — the real words it may have been meant as. r13b Stage 0's
 * ContextCandidates (spike/r13e-ngram, ce8792c), unchanged but for what the offers never call (plain edits of
 * non-words and the wide 315-set recall generator are left out). The model never writes text: it only ranks this.
 *
 * For a VALID typed word the generator is closed and small:
 * - accent variants (que → qué, como → cómo; ñ is a letter, not an accent);
 * - the Latin-American Spanish sound table of lm-exp `stage1_candidates.py` (seseo s/c/z, yeísmo y/ll, silent h,
 *   b/v, soft c/g, c/k/qu): ≤ 2 rewrites, kept only if both spellings share the sound key [phon];
 * - one touching-key substitution (two for words of ≤ 3 letters: aje → que), on the drawn Spanish QWERTY.
 *
 * [channel] is lm-exp's keyboard error model log P(typed | intended): a weighted OSA distance where a substitution
 * costs −1.5 − 2·d² (d = key-centre distance in key widths), an accent −0.3, an insertion/deletion −4 (−3 for an
 * extra key next to the intended one), a swap −3.5, and a same-sound spelling at most −3.0.
 */
class ContextCandidates(private val lex: Lexicon) {
    interface Lexicon {
        /** Lowercase [w] is a word of the writing language. */
        fun isWord(w: String): Boolean
        /** The valid lowercase spellings equal to [w] once accents are removed (may include [w]). */
        fun accentVariants(w: String): List<String>
        /** log relative frequency of [w] (a floor for valid words outside the frequency list). */
        fun logFreq(w: String): Double
    }

    enum class Kind { ACCENT, PHONETIC, ADJACENT }

    data class Alt(val word: String, val channel: Double, val kind: Kind, val logFreq: Double)

    /**
     * Candidates for [typed] (any case; candidates are lowercase), best first by [selection] order, without the
     * typed word itself.
     */
    fun of(typed: String): List<Alt> {
        val t = typed.lowercase()
        if (t.isEmpty() || !t.all { it.isLetter() }) return emptyList()
        val found = LinkedHashMap<String, Kind>()
        fun add(w: String, k: Kind) {
            if (w == t || w.isEmpty()) return
            val prev = found[w]
            if (prev == null || k.ordinal < prev.ordinal) found[w] = k
        }
        for (v in lex.accentVariants(t)) add(v, Kind.ACCENT)
        val tb = bare(t)
        val key = phon(t)
        // r13f (HP5): a spelling's accent variants share its letters, hence its sound key — a rewrite with another key
        // can have no variant that passes, so it is not looked up (same result, a fraction of the lookups)
        for (s in soundRewrites(tb)) if (phon(s) == key) for (v in lex.accentVariants(s)) if (phon(v) == key && bare(v) != tb) add(v, Kind.PHONETIC)
        for (s in adjacentSubs(tb, if (tb.length <= 3) 2 else 1)) for (v in lex.accentVariants(s)) add(v, Kind.ADJACENT)
        val out = found.map { (w, k) -> Alt(w, channel(w, t, key, tb), k, lex.logFreq(w)) }
        return out.sortedWith(compareByDescending<Alt> { selection(it) }.thenBy { it.word })
    }

    companion object {
        /** Selection prior: channel + λsel · log frequency (lm-exp's stage-1 ranking: err + logu). */
        const val LAMBDA_SEL = 1.0
        fun selection(a: Alt): Double = a.channel + LAMBDA_SEL * a.logFreq

        const val INS = -4.0
        const val DEL = -4.0
        const val TRANS = -3.5
        const val INS_NEAR = -3.0
        const val ACCENT = -0.3
        const val FAR = -8.0
        const val PHON_LP = -3.0

        private val ROWS = listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm")
        private val X0 = doubleArrayOf(0.0, 0.0, 1.5)
        /** Row pitch in key widths, measured on the owner's layout (sessions/s1/layout.json: 124 px / 99.5 px). */
        private const val ROW_PITCH = 1.245
        private val CENTRES: Map<Char, Pair<Double, Double>> = buildMap {
            ROWS.forEachIndexed { r, row -> row.forEachIndexed { i, c -> put(c, (X0[r] + i) to r * ROW_PITCH) } }
        }

        /**
         * Accents off, ñ kept, lowercase. r13f (HP5): plain ASCII / ñ text — almost every candidate — skips the
         * normalizer and the regex (same result: NFD changes nothing there and there is no mark to remove).
         */
        fun bare(s: String): String {
            if (s.all { it.code < 0x80 || it == 'ñ' || it == 'Ñ' }) return s.lowercase()
            val lower = s.lowercase().replace('ñ', '\u0001')
            // the spike's MARKS regex (\p{Mn}+ → ""), as a scan: Android's regex goes through ICU, far slower (HP5)
            val d = Normalizer.normalize(lower, Normalizer.Form.NFD)
            val sb = StringBuilder(d.length)
            for (c in d) if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) sb.append(if (c == '\u0001') 'ñ' else c)
            return sb.toString()
        }

        /** [bare] of one character (r13f HP5: memoized for the Latin range the error model reads per cell). */
        private val BARE_CHAR = CharArray(0x250) { bare(it.toChar().toString()).firstOrNull() ?: it.toChar() }
        private fun bareChar(c: Char): Char = if (c.code < BARE_CHAR.size) BARE_CHAR[c.code] else bare(c.toString())[0]

        fun keyDistance(a: Char, b: Char): Double? {
            val ca = CENTRES[a] ?: return null
            val cb = CENTRES[b] ?: return null
            val dx = ca.first - cb.first
            val dy = ca.second - cb.second
            return sqrt(dx * dx + dy * dy)
        }

        /** Touching keys: same row ±1, or a diagonal/vertical neighbour (≤ 1.5 key widths apart). */
        fun touching(a: Char, b: Char): Boolean = a != b && (keyDistance(a, b) ?: 99.0) <= 1.5

        // r13f (HP5): the same distances and neighbour lists, read by index instead of a boxed-Char map per DP cell
        private const val TABLE = 0x250
        private val KEYS: CharArray = CENTRES.keys.toCharArray()
        private val KEY_INDEX = IntArray(TABLE) { c -> KEYS.indexOf(c.toChar()) }
        private val DIST = DoubleArray(KEYS.size * KEYS.size) { keyDistance(KEYS[it / KEYS.size], KEYS[it % KEYS.size])!! }

        /** [keyDistance], NaN for a character off the layout. */
        private fun dist(a: Char, b: Char): Double {
            val i = if (a.code < TABLE) KEY_INDEX[a.code] else -1
            val j = if (b.code < TABLE) KEY_INDEX[b.code] else -1
            return if (i < 0 || j < 0) Double.NaN else DIST[i * KEYS.size + j]
        }

        private val NEIGHBOURS: Array<CharArray> = Array(TABLE) { c ->
            if (CENTRES.containsKey(c.toChar())) CENTRES.keys.filter { touching(c.toChar(), it) }.sorted().toCharArray() else CharArray(0)
        }

        private fun neighbours(c: Char): CharArray = if (c.code < TABLE) NEIGHBOURS[c.code] else CharArray(0)

        private fun subLp(a: Char, b: Char): Double {
            if (a == b) return 0.0
            val ba = bareChar(a)
            val bb = bareChar(b)
            if (ba == bb) return ACCENT
            val d = dist(ba, bb)
            if (d.isNaN()) return FAR
            return -1.5 - 2.0 * d * d
        }

        /** log P(typed | intended): lm-exp `err_lp` (weighted OSA with the geometry substitution model). */
        fun errLp(intended: String, typed: String): Double {
            val a = intended.lowercase()
            val b = typed.lowercase()
            val n = a.length
            val m = b.length
            val neg = -1e9
            val d = Array(n + 1) { DoubleArray(m + 1) { neg } }
            d[0][0] = 0.0
            for (i in 0..n) for (j in 0..m) {
                if (i > 0 && d[i - 1][j] > -1e8) d[i][j] = max(d[i][j], d[i - 1][j] + DEL)
                if (j > 0 && d[i][j - 1] > -1e8) {
                    var ins = INS
                    if (i > 0) {
                        val k = dist(bareChar(b[j - 1]), bareChar(a[i - 1]))
                        if (!k.isNaN() && k < 1.5) ins = INS_NEAR
                    }
                    d[i][j] = max(d[i][j], d[i][j - 1] + ins)
                }
                if (i > 0 && j > 0) d[i][j] = max(d[i][j], d[i - 1][j - 1] + subLp(a[i - 1], b[j - 1]))
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = max(d[i][j], d[i - 2][j - 2] + TRANS)
            }
            return d[n][m]
        }

        /** The channel term of Δ: [errLp], floored at [PHON_LP] for a same-sound spelling. */
        fun channel(intended: String, typed: String): Double = channel(intended, typed, phon(typed), bare(typed))

        /** [channel] with the typed word's sound key and bare form computed once per confusion set (HP5). */
        private fun channel(intended: String, typed: String, typedPhon: String, typedBare: String): Double {
            val e = errLp(intended, typed)
            return if (phon(intended) == typedPhon && bare(intended) != typedBare) max(e, PHON_LP) else e
        }

        /**
         * Latin-American Spanish sound key (lm-exp `phon`): seseo, yeísmo, silent h, b = v, soft c/g. r13f (HP5): the
         * spike's regexes `qu(?=[ei])` → k, `gu(?=[ei])` → g, `c(?=[ei])` → s, `g(?=[ei])` → j and `y$` → i as
         * left-to-right scans (same matches: the lookahead consumes nothing); Android's regex goes through ICU.
         */
        fun phon(w: String): String {
            var s = bare(w)
            s = s.replace("ch", "C").replace("ll", "y").replace("gü", "gw")
            s = beforeEi(s, "qu", 'k')
            s = beforeEi(s, "gu", 'g')
            s = beforeEi(s, "c", 's')
            s = beforeEi(s, "g", 'j')
            s = s.replace("c", "k").replace("z", "s").replace("v", "b").replace("h", "").replace("w", "u")
            if (s.endsWith('y')) s = s.substring(0, s.length - 1) + "i"
            return s.replace("C", "ch")
        }

        /** Every [pat] directly followed by e or i becomes [to] (the e / i stays), left to right, non-overlapping. */
        private fun beforeEi(s: String, pat: String, to: Char): String {
            if (s.indexOf(pat) < 0) return s
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val j = i + pat.length
                if (s.startsWith(pat, i) && j < s.length && (s[j] == 'e' || s[j] == 'i')) { sb.append(to); i = j }
                else { sb.append(s[i]); i++ }
            }
            return sb.toString()
        }

        private val REWRITES: List<Pair<String, String>> = listOf(
            "b" to "v", "v" to "b", "s" to "z", "z" to "s", "s" to "c", "c" to "s", "z" to "c", "c" to "z",
            "c" to "k", "k" to "c", "c" to "qu", "qu" to "c", "k" to "qu", "qu" to "k", "y" to "ll", "ll" to "y",
            "g" to "j", "j" to "g", "h" to "", "i" to "y", "y" to "i", "gu" to "g", "g" to "gu", "x" to "s",
        )
        private const val VOWELS = "aeiou"

        /** Spellings ≤ 2 sound rewrites away from bare [w] (the caller keeps those with the same [phon]). */
        fun soundRewrites(w: String): Set<String> {
            val out = LinkedHashSet<String>()
            var front = setOf(w)
            repeat(2) {
                val next = LinkedHashSet<String>()
                for (s in front) {
                    for ((from, to) in REWRITES) {
                        var i = s.indexOf(from)
                        while (i >= 0) {
                            next += s.substring(0, i) + to + s.substring(i + from.length)
                            i = s.indexOf(from, i + 1)
                        }
                    }
                    // a silent h: at the start, or between a vowel and a vowel
                    next += "h$s"
                    for (i in 1 until s.length) if (s[i - 1] in VOWELS && s[i] in VOWELS) next += s.substring(0, i) + "h" + s.substring(i)
                }
                next.removeAll(out); next.remove(w)
                out += next
                front = next
            }
            return out
        }

        /** Spellings with up to [n] letters of bare [w] replaced by touching keys. */
        fun adjacentSubs(w: String, n: Int): Set<String> {
            val out = LinkedHashSet<String>()
            var front = setOf(w)
            repeat(n) {
                val next = LinkedHashSet<String>()
                for (s in front) for (i in s.indices) for (c in neighbours(s[i])) {
                    if (c == w.getOrNull(i) && s[i] != w[i]) continue
                    next += s.substring(0, i) + c + s.substring(i + 1)
                }
                next.remove(w)
                out += next
                front = next
            }
            return out
        }
    }
}
