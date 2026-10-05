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
class Suggest(words: List<String>) {
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
            val twin = foldRank[ft] ?: return null
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
            best.index < FREQUENT_RANK &&
                rival - best.cost >= MIN_GAP &&
                best.weighted <= allowance(ft.length, best.index)
            )
        return Candidate(words[best.index], confident)
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
                var wv = min(min(w[up] + del, w[left] + ins), w[diag] + substitution(ca, cb, i == 1 && j == 1))
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
        /** Confident corrections only land on words this frequent (top N of the lexicon). */
        const val FREQUENT_RANK = 3000
        /** Cost margin over the best different word; below it the call is ambiguous. */
        const val MIN_GAP = 0.35f
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

        /** Weighted cost a confident correction may spend: cheap typos only on rarer targets. */
        private fun allowance(len: Int, rank: Int): Float = when {
            len >= 7 -> 2f
            rank < 1000 -> 1f
            else -> 0.6f
        }

        private val KEY_ROWS = listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm")
        private val ROW_OFFSET = floatArrayOf(0f, 0.25f, 1.5f)
        private val KEY_POS: Map<Char, Pair<Float, Int>> = buildMap {
            KEY_ROWS.forEachIndexed { r, row -> row.forEachIndexed { i, c -> put(c, (ROW_OFFSET[r] + i) to r) } }
        }

        /** Keys that touch on the drawn QWERTY (same row ±1, the two touching keys above/below). */
        fun adjacent(a: Char, b: Char): Boolean {
            val pa = KEY_POS[a] ?: return false
            val pb = KEY_POS[b] ?: return false
            val dx = pa.first - pb.first
            val dy = (pa.second - pb.second).toFloat()
            return a != b && dx * dx + dy * dy <= 1.69f
        }

        private fun substitution(a: Char, b: Char, first: Boolean): Float {
            if (a == b) return 0f
            if ((a == 'n' && b == 'ñ') || (a == 'ñ' && b == 'n')) return ENYE_COST
            val base = if (adjacent(a, b)) ADJACENT_COST else 1f
            return if (first) base + FIRST_LETTER_PENALTY else base
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
