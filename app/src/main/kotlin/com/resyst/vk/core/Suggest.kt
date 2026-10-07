package com.resyst.vk.core

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min

/**
 * Offline word completion + typo correction over a frequency-ordered lexicon (most frequent
 * first). Bar ranking: a confident correction ([best]) first, then accent corrections of the
 * typed word ("esta" → "está"), exact-prefix completions by frequency, accent-insensitive
 * completions ("cancio" → "canción"), and finally a non-confident close match.
 *
 * Not thread-safe (reuses DP buffers); the IME calls it from the main thread only.
 */
class Suggest(words: List<String>, lang: Lang? = null) {
    /** Key centers of the layout this lexicon is typed on (r8: per language, see [geometry]). */
    private val keyPos = geometry(lang)
    private val words: Array<String> = words.toTypedArray()
    private val folded: Array<String> = Array(words.size) { fold(words[it]) }
    private val rankOf = HashMap<String, Int>(words.size * 2)
    private val foldRank = HashMap<String, Int>(words.size * 2)

    init {
        for (i in this.words.indices) {
            rankOf.putIfAbsent(this.words[i], i)
            foldRank.putIfAbsent(folded[i], i)
        }
    }

    /** A close lexicon word for a typed one; [confident] = safe to apply without asking. */
    data class Candidate(val word: String, val confident: Boolean)

    fun suggest(typed: String, limit: Int = 3): List<String> {
        if (typed.isEmpty() || typed.length > MAX_LEN || !typed.all { it.isLetter() || it == '\'' }) return emptyList()
        val p = typed.lowercase()
        val fp = fold(p)
        val corrections = ArrayList<String>()
        val exact = ArrayList<String>()
        val loose = ArrayList<String>()
        for (i in words.indices) {
            val w = words[i]
            if (w == p) continue
            val f = folded[i]
            when {
                f == fp -> corrections += w
                w.startsWith(p) -> if (exact.size < limit) exact += w
                f.startsWith(fp) -> if (loose.size < limit) loose += w
            }
            if (exact.size >= limit && corrections.size >= limit) break
        }
        val fix = best(p)
        val head = if (fix?.confident == true) listOf(fix.word) else emptyList()
        val tail = if (fix != null && !fix.confident) listOf(fix.word) else emptyList()
        return (head + corrections + exact + loose + tail).distinct().take(limit).map { matchCase(it, typed) }
    }

    /**
     * The word space should commit instead of [typed], cased like it, or null to leave it alone.
     * Case gates (A3): a capitalized word mid-sentence is a name the user meant; ALL-CAPS
     * (acronyms) and mixed case (iPhone) are never touched.
     */
    fun correction(typed: String, sentenceStart: Boolean): String? {
        if (typed.length < MIN_LEN || typed.length > MAX_FUZZY_LEN || !typed.all { it.isLetter() }) return null
        if (typed.drop(1).any { it.isUpperCase() }) return null
        if (typed[0].isUpperCase() && !sentenceStart) return null
        val c = best(typed.lowercase()) ?: return null
        return if (c.confident) matchCase(c.word, typed) else null
    }

    private var lastQuery: String? = null
    private var lastBest: Candidate? = null

    /**
     * Closest lexicon word for a lowercase [typed] word, or null when [typed] is itself a word
     * (A2) or nothing is near enough (A5). A word in the lexicon is only re-spelled when its
     * accented twin is ≥ [ACCENT_RATIO]× more frequent ("tambien" → "también", never
     * "esta" → "está").
     */
    fun best(typed: String): Candidate? {
        if (typed == lastQuery) return lastBest
        val r = compute(typed)
        lastQuery = typed
        lastBest = r
        return r
    }

    private fun compute(typed: String): Candidate? {
        if (typed.length < MIN_LEN || typed.length > MAX_FUZZY_LEN || !typed.all { it.isLetter() }) return null
        val ft = fold(typed)
        rankOf[typed]?.let { own ->
            // r8: the 25k list carries subtitle spellings without ñ ("manana" at 17k): an ñ twin
            // that is ACCENT_RATIO× more frequent wins, exactly like an accent twin
            val twin = listOfNotNull(foldRank[ft], enyeTwin(ft)).minOrNull() ?: return null
            return if (words[twin] != typed && twin.toLong() * ACCENT_RATIO <= own) Candidate(words[twin], true) else null
        }
        val maxD = maxDistance(ft.length)
        val hits = ArrayList<Hit>()
        for (i in words.indices) {
            val f = folded[i]
            if (abs(f.length - ft.length) > maxD) continue
            if (ft.length == f.length + 1 && ft.endsWith("nt") && ft.startsWith(f)) continue // "doesnt" ↛ "doesn" (EN contraction stems)
            if (!distance(ft, f, maxD)) continue
            hits += Hit(i, dpDistance, dpWeighted, dpWeighted + FREQ_WEIGHT * ln(i + 1.0).toFloat())
        }
        val best = hits.minByOrNull { it.cost } ?: return null
        // accent twins of the winner ("tú"/"tu") are the same word, not a rival
        val rival = hits.filter { folded[it.index] != folded[best.index] }.minOfOrNull { it.cost } ?: Float.MAX_VALUE
        val confident = best.distance == 0 || (
            best.index < frequentRank(ft.length) &&
                rival - best.cost >= minGap(ft.length) &&
                best.weighted <= allowance(ft.length, best.index)
            )
        return Candidate(words[best.index], confident)
    }

    /** Best rank among the spellings of [ft] with some n → ñ (≤ 3 n's), or null. */
    private fun enyeTwin(ft: String): Int? {
        val at = ft.indices.filter { ft[it] == 'n' }
        if (at.isEmpty() || at.size > 3) return null
        var best: Int? = null
        for (mask in 1 until (1 shl at.size)) {
            val c = ft.toCharArray()
            at.forEachIndexed { b, i -> if (mask and (1 shl b) != 0) c[i] = 'ñ' }
            val r = foldRank[String(c)] ?: continue
            if (best == null || r < best) best = r
        }
        return best
    }

    private class Hit(val index: Int, val distance: Int, val weighted: Float, val cost: Float)

    // ── weighted Damerau (OSA) distance with an unweighted twin for the hard bound ──
    private var dpDistance = 0
    private var dpWeighted = 0f
    private var dBuf = IntArray(0)
    private var wBuf = FloatArray(0)

    /** Fills [dpDistance]/[dpWeighted]; false when the OSA distance exceeds [maxD]. */
    private fun distance(a: String, b: String, maxD: Int): Boolean {
        val n = a.length
        val m = b.length
        val cols = m + 1
        val size = (n + 1) * cols
        if (dBuf.size < size) { dBuf = IntArray(size * 2); wBuf = FloatArray(size * 2) }
        val d = dBuf
        val w = wBuf
        for (j in 0..m) { d[j] = j; w[j] = j.toFloat() }
        for (i in 1..n) {
            d[i * cols] = i; w[i * cols] = i.toFloat()
            var rowMin = i
            val ca = a[i - 1]
            for (j in 1..m) {
                val cb = b[j - 1]
                val at = i * cols + j
                val up = (i - 1) * cols + j
                val left = at - 1
                val diag = up - 1
                var dv = min(min(d[up] + 1, d[left] + 1), d[diag] + if (ca == cb) 0 else 1)
                val del = if (i >= 2 && ca == a[i - 2]) DOUBLED_COST else 1f
                val ins = if (j >= 2 && cb == b[j - 2]) DOUBLED_COST else 1f
                var wv = min(min(w[up] + del, w[left] + ins), w[diag] + substitution(keyPos, ca, cb, i == 1 && j == 1))
                if (i > 1 && j > 1 && ca == b[j - 2] && a[i - 2] == cb) {
                    val tr = (i - 2) * cols + (j - 2)
                    dv = min(dv, d[tr] + 1)
                    wv = min(wv, w[tr] + TRANSPOSE_COST)
                }
                d[at] = dv; w[at] = wv
                if (dv < rowMin) rowMin = dv
            }
            if (rowMin > maxD) return false
        }
        dpDistance = d[n * cols + m]
        dpWeighted = w[n * cols + m]
        return dpDistance <= maxD
    }

    companion object {
        const val MAX_LEN = 24
        const val MIN_LEN = 3
        const val MAX_FUZZY_LEN = 16
        /** In-lexicon word → accented twin only when the twin is this many times more frequent. */
        const val ACCENT_RATIO = 10
        /** Confident corrections on short words only land on words this frequent (top N). */
        const val FREQUENT_RANK = 3000
        /** r8: 5–6 letters may land deeper in the (now 20–25k) lexicon. */
        const val MID_RANK = 12000
        /** Cost margin over the best different word; below it the call is ambiguous. */
        const val MIN_GAP = 0.35f
        /** r8: short words need a clearly unique fix (its/it's class). */
        const val SHORT_GAP = 0.6f
        /** r8: a long word rarely has a near twin; a smaller margin is still unambiguous. */
        const val LONG_GAP = 0.3f
        const val FREQ_WEIGHT = 0.12f
        const val DOUBLED_COST = 0.4f
        const val TRANSPOSE_COST = 0.6f
        const val ADJACENT_COST = 0.5f
        const val ENYE_COST = 0.2f
        const val FIRST_LETTER_PENALTY = 0.6f

        private val MARKS = Regex("\\p{Mn}+")

        /** ≤ 2 edits, by length: 1–2 letters never, 3–6 one edit, 7+ two (A5). */
        fun maxDistance(len: Int): Int = when {
            len < MIN_LEN -> 0
            len < 7 -> 1
            else -> 2
        }

        /**
         * r8 thresholds by TYPED length (docs: reports/r8b-android.report.md):
         * - ≤ 4 letters: target in the top [FREQUENT_RANK], margin [SHORT_GAP], only cheap typos
         *   (adjacent key, transposition, doubled letter: weighted ≤ 0.6) — a dropped letter in a
         *   3-letter word has too many readings ("cas" → casa? caso? casi?).
         * - 5–6: target in the top [MID_RANK], margin [MIN_GAP], one full edit.
         * - ≥ 7: any lexicon word, margin [LONG_GAP], two edits (weighted ≤ 2).
         */
        fun frequentRank(len: Int): Int = when {
            len >= 7 -> Int.MAX_VALUE
            len >= 5 -> MID_RANK
            else -> FREQUENT_RANK
        }

        fun minGap(len: Int): Float = when {
            len >= 7 -> LONG_GAP
            len >= 5 -> MIN_GAP
            else -> SHORT_GAP
        }

        /**
         * Weighted cost a confident correction may spend. 5–6 letters: a full non-adjacent edit
         * only toward the top 1000 words; deeper targets need a thumb-shaped typo (≤ 0.8:
         * neighbour key, transposition, doubled letter) — "pololo" (Chilean) ↛ "pollo".
         */
        fun allowance(len: Int, rank: Int): Float = when {
            len >= 7 -> 2f
            len >= 5 -> if (rank < 1000) 1f else 0.8f
            else -> 0.6f
        }

        /** r8: straight neighbours (same row ±1, or the key right above/below) are the commonest slip. */
        const val NEIGHBOUR_COST = 0.4f

        private val KEY_ROWS = listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm")

        /**
         * Key positions in key units, rows as drawn by [KeyboardLayouts]: ES has a full 10-key
         * home row (ñ), so a sits right under q; EN centers 9 keys (offset ½); z follows the
         * 1.5-wide shift in both. null = a compromise between both (pre-r8 map).
         */
        fun geometry(lang: Lang?): Map<Char, Pair<Float, Int>> {
            val home = when (lang) { Lang.ES -> 0f; Lang.EN -> 0.5f; null -> 0.25f }
            val offsets = floatArrayOf(0f, home, 1.5f)
            return buildMap {
                KEY_ROWS.forEachIndexed { r, row -> row.forEachIndexed { i, c -> put(c, (offsets[r] + i) to r) } }
            }
        }

        private val KEY_POS = geometry(null)

        /** Squared center distance of two keys in key units, or null if either isn't a letter key. */
        private fun dist2(pos: Map<Char, Pair<Float, Int>>, a: Char, b: Char): Float? {
            val pa = pos[a] ?: return null
            val pb = pos[b] ?: return null
            val dx = pa.first - pb.first
            val dy = (pa.second - pb.second).toFloat()
            return dx * dx + dy * dy
        }

        /** Keys that touch on the drawn QWERTY (same row ±1, the touching keys above/below). */
        fun adjacent(a: Char, b: Char, lang: Lang? = null): Boolean {
            val d = dist2(if (lang == null) KEY_POS else geometry(lang), a, b) ?: return false
            return a != b && d <= 1.69f
        }

        /** Substitution cost on [pos]: straight neighbour < diagonal neighbour < any other key. */
        fun substitutionCost(pos: Map<Char, Pair<Float, Int>>, a: Char, b: Char): Float {
            if (a == b) return 0f
            if ((a == 'n' && b == 'ñ') || (a == 'ñ' && b == 'n')) return ENYE_COST
            val d = dist2(pos, a, b) ?: return 1f
            return when {
                d <= 1.0001f -> NEIGHBOUR_COST
                d <= 1.69f -> ADJACENT_COST
                else -> 1f
            }
        }

        private fun substitution(pos: Map<Char, Pair<Float, Int>>, a: Char, b: Char, first: Boolean): Float {
            val base = substitutionCost(pos, a, b)
            return if (first && base > 0f) base + FIRST_LETTER_PENALTY else base
        }

        /** Lowercase + strip diacritics, but ñ stays ñ (it is a letter in Spanish). */
        fun fold(s: String): String {
            val lower = s.lowercase().replace('ñ', '\u0001')
            return MARKS.replace(Normalizer.normalize(lower, Normalizer.Form.NFD), "").replace('\u0001', 'ñ')
        }

        fun matchCase(word: String, typed: String): String = when {
            typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
            typed.firstOrNull()?.isUpperCase() == true -> word.replaceFirstChar { it.uppercaseChar() }
            else -> word
        }

        /** The word being typed immediately before the cursor ("" if the cursor follows a separator). */
        fun currentWord(before: CharSequence): String {
            var i = before.length
            while (i > 0 && (before[i - 1].isLetter() || before[i - 1] == '\'')) i--
            val w = before.subSequence(i, before.length).toString()
            if (w.endsWith("'")) return ""
            return w.trimStart('\'')
        }

        private const val OPENERS = "¿¡([{\"«“‘—-"

        /**
         * Whether the word ending at the cursor stands alone (A4): it must start the text or
         * follow whitespace / an opening mark — never glued to a digit, `@`, `/`, `.`, `_`.
         */
        fun standalone(before: CharSequence, word: String): Boolean {
            val start = before.length - word.length
            if (start < 0 || word.isEmpty()) return false
            if (start == 0) return true
            val prev = before[start - 1]
            return prev.isWhitespace() || prev in OPENERS
        }

        /** True when the word ending at the cursor begins a sentence (text start or after . ! ? … or a newline). */
        fun sentenceStart(before: CharSequence, word: String): Boolean {
            var i = before.length - word.length - 1
            while (i >= 0 && (before[i] == ' ' || before[i] in OPENERS)) i--
            return i < 0 || before[i] in ".!?…\n"
        }
    }
}
