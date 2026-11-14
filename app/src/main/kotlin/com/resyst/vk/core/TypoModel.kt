package com.resyst.vk.core

/**
 * r13 (NW2/NW3): what a thumb on the drawn keyboard turns an intended word into, as a cost (lower =
 * likelier), for space-correction ranking. Built from the real key geometry (evidence/layout.json,
 * Pixel 8 Pro letters layer: key pitch 99.7 px, row pitch 123.5 px, so rows sit [ROW_PITCH] key
 * widths apart and a key above / below is farther than a side neighbour) plus Spanish same-sound
 * spellings (seseo, yeísmo, silent h, b/v, soft c/g, k/qu).
 *
 * Words are compared FOLDED ([Suggest.fold]: lowercase, accents off, ñ kept): an accent the user
 * left out is not an error here; which accented form to commit is R2's call.
 *
 * [EditRows] is the dynamic programme, one row per candidate letter, so the same code scores a
 * frequency-list word and walks the valid-word DAWG ([ValidWords.near]) with pruning.
 */
object TypoModel {
    /** Row pitch / key pitch on the drawn letters layer (123.5 / 99.7). */
    const val ROW_PITCH = 1.24f
    /** sub cost = BASE + SLOPE·d² (d = key-centre distance in key widths), capped at [FAR]. */
    const val SUB_BASE = 0.16f
    const val SUB_SLOPE = 0.24f
    const val FAR = 1.3f
    /** n typed for ñ (or back): the same key family on the ES layout. */
    const val ENYE = 0.2f
    /** A Spanish same-sound spelling (s/z/c, y/ll, h, b/v, g/j, k/c/qu). */
    const val PHON = 0.45f
    /**
     * A letter typed twice / omitted from a double letter. Not for Spanish ll / rr: those are
     * letters of their own (zapallo is not a slip for zápalo, chupalla not for chúpala).
     */
    const val DOUBLED = 0.4f
    /** An extra key next to the letter before or after it (the thumb brushed two keys); above the ≤ 4-letter allowance. */
    const val EXTRA_NEAR = 0.65f
    /**
     * An extra bottom-row key at the end of a word of 5+ letters: the thumb aiming at space (tuvom,
     * seriom; evidence/heatmap.png: his space taps land right under b / n, next to m). Short words
     * keep the r8 rule (cheap typos are substitutions, swaps and doubles only).
     */
    const val EXTRA_SPACE = 0.5f
    const val EXTRA = 1f
    const val OMIT = 0.8f
    const val TRANSPOSE = 0.6f
    /** Added to a substitution of the first letter that is not a same-sound spelling. */
    const val FIRST = 0.2f
    /** Keys whose lower edge touches the space bar (both layouts). */
    private const val ABOVE_SPACE = "cvbnm"

    private val ROWS = listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm")

    /** Key centres (x in key widths, y in key widths) of [lang]'s letters layer; null = between ES and EN. */
    fun centres(lang: Lang?): Map<Char, FloatArray> {
        val home = when (lang) { Lang.ES -> 0f; Lang.EN -> 0.5f; null -> 0.25f }
        val off = floatArrayOf(0f, home, 1.5f)
        return buildMap {
            ROWS.forEachIndexed { r, row ->
                row.forEachIndexed { i, c -> if (lang != Lang.EN || c != 'ñ') put(c, floatArrayOf(off[r] + i, r * ROW_PITCH)) }
            }
        }
    }

    /** Squared distance in key widths, or null when either is not a letter key of the layout. */
    fun dist2(pos: Map<Char, FloatArray>, a: Char, b: Char): Float? {
        val pa = pos[a] ?: return null
        val pb = pos[b] ?: return null
        val dx = pa[0] - pb[0]
        val dy = pa[1] - pb[1]
        return dx * dx + dy * dy
    }

    /** Typing [b] for an intended [a] on [pos] (no position context). */
    fun sub(pos: Map<Char, FloatArray>, a: Char, b: Char, spanish: Boolean): Float {
        if (a == b) return 0f
        if ((a == 'n' && b == 'ñ') || (a == 'ñ' && b == 'n')) return ENYE
        val geo = dist2(pos, a, b)?.let { minOf(FAR, SUB_BASE + SUB_SLOPE * it) } ?: FAR
        return if (spanish && sameSound(a, b)) minOf(geo, PHON) else geo
    }

    /** Context-free Spanish same-sound letters: b/v, s/z, c/k (soft c and g need the next letter, see [EditRows]). */
    private fun sameSound(a: Char, b: Char): Boolean = pair(a, b, 'b', 'v') || pair(a, b, 's', 'z') || pair(a, b, 'c', 'k')

    private fun pair(a: Char, b: Char, x: Char, y: Char) = (a == x && b == y) || (a == y && b == x)

    /** True for keys within touching distance (side, diagonal or straight above / below). */
    fun near(pos: Map<Char, FloatArray>, a: Char, b: Char): Boolean = a != b && (dist2(pos, a, b) ?: 99f) <= 1.85f

    /**
     * Weighted Damerau (OSA) edit cost between a typed word and candidate words, one candidate letter
     * per row ([push] / [pop]), with an unweighted twin for the hard bound. Not thread-safe.
     */
    class EditRows(lang: Lang?) {
        private val pos = centres(lang)
        /** The layout's language spells by Spanish sound rules; [sounds] turns them off for another language's words. */
        private val layoutSpanish = lang != Lang.EN
        private var spanish = layoutSpanish
        /** Substitution costs of every letter pair, precomputed (no map lookups in the inner loop): with and without Spanish sounds. */
        private val subSpanish = table(true)
        private val subPlain = table(false)
        private var subTable = if (spanish) subSpanish else subPlain

        private fun table(sounds: Boolean) = FloatArray(ALPHA * ALPHA).also { t ->
            for (a in 0 until ALPHA) for (b in 0 until ALPHA) {
                t[a * ALPHA + b] = if (a == ALPHA - 1 || b == ALPHA - 1) FAR else sub(pos, letter(a), letter(b), sounds)
            }
        }

        /**
         * r13c (NW3): Spanish same-sound spellings (silent h, b/v, s/z, ll/y, qu/k, soft c/g, ll / rr as
         * letters) apply only while the candidates are Spanish words: an English word in the other
         * list is scored by the keys alone (hoohle is two slips from google, not a silent h from hole).
         * Keeps the typed word and bounds; the next push recomputes from the rows kept.
         */
        fun sounds(on: Boolean) {
            val s = on && layoutSpanish
            if (s == spanish) return
            spanish = s
            subTable = if (s) subSpanish else subPlain
            for (j in 1..n) { extraCost[j] = extra(j - 1); w[0][j] = w[0][j - 1] + extraCost[j] }
            depth = 0
        }

        private fun subCost(c: Char, t: Char): Float = if (c == t) 0f else subTable[index(c) * ALPHA + index(t)]
        private var typed = CharArray(0)
        private var n = 0
        private var maxD = 0
        private var maxCost = 0f
        private val w = Array(MAX + 1) { FloatArray(MAX + 1) }
        private val d = Array(MAX + 1) { IntArray(MAX + 1) }
        private val rowMinW = FloatArray(MAX + 1)
        private val rowMinD = IntArray(MAX + 1)
        private val cand = CharArray(MAX + 1)
        /** Candidate letters pushed so far. */
        var depth = 0
            private set
        /** Extra-key cost of each typed position (depends on the typed word only). */
        private val extraCost = FloatArray(MAX + 1)

        /** Starts a search for folded [t]; a word longer than [MAX] − [maxD] matches nothing. */
        fun reset(t: String, maxD: Int, maxCost: Float) {
            typed = if (t.length + maxD > MAX) CharArray(0) else t.toCharArray()
            n = typed.size
            this.maxD = maxD
            this.maxCost = maxCost
            depth = 0
            for (j in 0..n) { d[0][j] = j; w[0][j] = 0f }
            for (j in 1..n) { extraCost[j] = extra(j - 1); w[0][j] = w[0][j - 1] + extraCost[j] }
            rowMinW[0] = 0f
            rowMinD[0] = 0
        }

        /** Cost of typed[k] being an extra key. */
        private fun extra(k: Int): Float {
            val c = typed[k]
            if (k > 0 && typed[k - 1] == c && !digraph(c)) return DOUBLED
            if (spanish && c == 'h' && (k == 0 || typed[k - 1] != 'c')) return PHON // silent h written
            if (k == n - 1 && c in ABOVE_SPACE && n >= 5) return EXTRA_SPACE
            if ((k > 0 && near(pos, typed[k - 1], c)) || (k < n - 1 && near(pos, typed[k + 1], c))) return EXTRA_NEAR
            return EXTRA
        }

        /**
         * Appends candidate letter [c] (folded). False — and nothing pushed — when no extension of
         * this prefix can end within the bounds.
         */
        fun push(c: Char): Boolean {
            val i = depth + 1
            if (i > MAX || n == 0) return false
            cand[i - 1] = c
            val wi = w[i]; val di = d[i]; val wp = w[i - 1]; val dp = d[i - 1]
            val prevC = if (i >= 2) cand[i - 2] else '\u0000'
            val omit = when {
                prevC == c && !digraph(c) -> DOUBLED
                spanish && c == 'h' && prevC != 'c' -> PHON // silent h left out
                else -> OMIT
            }
            di[0] = i; wi[0] = wp[0] + omit
            var minD = di[0]; var minW = wi[0]
            // Ukkonen band: a cell more than maxD off the diagonal is already over the bound
            val lo = maxOf(1, i - maxD)
            val hi = minOf(n, i + maxD)
            for (j in 1 until lo) { di[j] = FAR_D; wi[j] = FAR_W }
            for (j in hi + 1..n) { di[j] = FAR_D; wi[j] = FAR_W }
            for (j in lo..hi) {
                val t = typed[j - 1]
                var dv = minOf(dp[j] + 1, di[j - 1] + 1)
                var wv = minOf(wp[j] + omit, wi[j - 1] + extraCost[j])
                val s = subCost(c, t).let { if (i == 1 && j == 1 && it > 0f && it != PHON) it + FIRST else it }
                dv = minOf(dv, dp[j - 1] + if (c == t) 0 else 1)
                wv = minOf(wv, wp[j - 1] + s)
                if (i >= 2 && j >= 2) {
                    val c0 = cand[i - 2]; val t0 = typed[j - 2]
                    if (c == t0 && c0 == t && c != c0) { dv = minOf(dv, d[i - 2][j - 2] + 1); wv = minOf(wv, w[i - 2][j - 2] + TRANSPOSE) }
                    if (spanish && softPair(c0, c, t0, t)) { dv = minOf(dv, d[i - 2][j - 2] + 1); wv = minOf(wv, w[i - 2][j - 2] + PHON) }
                }
                if (spanish) {
                    // two candidate letters for one typed: ll → y, qu → k
                    if (i >= 2 && ((cand[i - 2] == 'l' && c == 'l' && t == 'y') || (cand[i - 2] == 'q' && c == 'u' && t == 'k'))) {
                        dv = minOf(dv, d[i - 2][j - 1] + 1); wv = minOf(wv, w[i - 2][j - 1] + PHON)
                    }
                    // one candidate letter for two typed: y → ll, k → qu
                    if (j >= 2 && ((c == 'y' && typed[j - 2] == 'l' && t == 'l') || (c == 'k' && typed[j - 2] == 'q' && t == 'u'))) {
                        dv = minOf(dv, dp[j - 2] + 1); wv = minOf(wv, wp[j - 2] + PHON)
                    }
                }
                di[j] = dv; wi[j] = wv
                if (dv < minD) minD = dv
                if (wv < minW) minW = wv
            }
            rowMinD[i] = minD; rowMinW[i] = minW
            // sources of later rows are this row and the one before (two-letter transitions)
            val dead = (minD > maxD || minW > maxCost) && (rowMinD[i - 1] > maxD || rowMinW[i - 1] > maxCost)
            if (dead) return false
            depth = i
            return true
        }

        fun pop() { if (depth > 0) depth-- }

        /** Back to an empty candidate (the typed word and bounds stay). */
        fun rewind() { depth = 0 }

        /** Keeps only the first [k] pushed letters. */
        fun popTo(k: Int) { if (k < depth) depth = maxOf(0, k) }

        /** Length of the common prefix of the pushed letters and [f]. */
        fun shared(f: String): Int {
            val m = minOf(depth, f.length)
            var k = 0
            while (k < m && cand[k] == f[k]) k++
            return k
        }

        /** Weighted cost of the whole typed word against the pushed candidate. */
        val cost: Float get() = w[depth][n]
        /** Unweighted (OSA) distance of the same alignment bound. */
        val distance: Int get() = d[depth][n]
        /** The pushed candidate is complete enough to be an answer within both bounds. */
        val within: Boolean get() = d[depth][n] <= maxD && w[depth][n] <= maxCost

        /** Scores a whole folded candidate: (cost, distance) or null when out of bounds. */
        fun score(candidate: String): Boolean {
            depth = 0
            for (ch in candidate) if (!push(ch)) return false
            return within
        }

        /** Spanish ll / rr: doubling them is spelling, not a slip. */
        private fun digraph(c: Char) = spanish && (c == 'l' || c == 'r')

        /** Soft c / g before e or i written with their sound twin (ce ↔ se/ze, ge ↔ je), either way. */
        private fun softPair(c0: Char, c1: Char, t0: Char, t1: Char): Boolean {
            if (c1 != t1 || (c1 != 'e' && c1 != 'i')) return false
            return (c0 == 'c' && (t0 == 's' || t0 == 'z')) || (t0 == 'c' && (c0 == 's' || c0 == 'z')) ||
                (c0 == 'g' && t0 == 'j') || (c0 == 'j' && t0 == 'g')
        }

        companion object {
            /** Longest typed / candidate word searched (MAX_FUZZY_LEN + 2 edits). */
            const val MAX = 26
            private const val FAR_D = 1000
            private const val FAR_W = 1000f
            /** a–z, ñ, and one slot for anything else (apostrophe, ç …: a far key). */
            private const val ALPHA = 28

            private fun index(c: Char): Int = when (c) {
                in 'a'..'z' -> c - 'a'
                'ñ' -> 26
                else -> 27
            }

            private fun letter(i: Int): Char = if (i == 26) 'ñ' else 'a' + i
        }
    }
}
