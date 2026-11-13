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
class Suggest(words: List<String>, private val lang: Lang? = null, regional: Collection<String> = emptyList()) {
    /** Key centers of the layout this lexicon is typed on (r8: per language, see [geometry]). */
    private val keyPos = geometry(lang)

    /**
     * r13 (RW2): the words the region's rule files promote or insert (cachai, fome, bacán …):
     * never replaced by space, whatever Hunspell says.
     */
    private val regional: Set<String> = regional.mapTo(HashSet()) { it.lowercase() }

    /**
     * r13: this language's full-form valid-word set (phase A), attached by the loader once mapped.
     * null = the r12 correction path, unchanged (RW3). Read from the main thread; the io thread
     * writes it once.
     */
    @Volatile var valid: ValidWords? = null

    /**
     * r13 (NW9): builds the folded-order index the correction scan walks — call on the loading
     * thread so the first word typed never pays the sort.
     */
    fun prepare() { byFold.size }

    /** r13 diagnostics (JVM replay only): the best spelling families of each R3 search. */
    var trace: ((String) -> Unit)? = null

    /** r13: the edit-cost rows of the space-correction search (allocated on first use). */
    private val rows by lazy(LazyThreadSafetyMode.NONE) { TypoModel.EditRows(lang) }
    private val words: Array<String> = words.toTypedArray()
    private val folded: Array<String> = Array(words.size) { fold(words[it]) }
    /** r13 (NW9): word indices in folded alphabetical order, for the trie-like correction scan. */
    private val byFold: IntArray by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        folded.indices.sortedWith { a, b -> folded[a].compareTo(folded[b]) }.toIntArray()
    }
    private val rankOf = HashMap<String, Int>(words.size * 2)
    private val foldRank = HashMap<String, Int>(words.size * 2)

    init {
        for (i in this.words.indices) {
            rankOf.putIfAbsent(this.words[i], i)
            foldRank.putIfAbsent(folded[i], i)
        }
    }

    /** Frequency rank (0 = most frequent) of the exact lowercase [word], or null if not in the list. */
    fun rank(word: String): Int? = rankOf[word]

    val size: Int get() = words.size

    /**
     * r10 bilingual guard (BL4): the other language's lexicon. A typed word that is NOT in this
     * lexicon but is a common word over there ([FOREIGN_RANK]) is never re-spelled into this
     * language — "meeting" typed in Spanish stays "meeting". null = r9 behavior (BL6).
     */
    var foreign: Suggest? = null
        set(v) {
            if (v === field) return
            field = v
            lastQuery = null; lastBest = null
        }

    private fun isForeign(typed: String): Boolean {
        val f = foreign ?: return false
        return rankOf[typed] == null && (f.rank(typed) ?: Int.MAX_VALUE) < FOREIGN_RANK
    }

    /** A close lexicon word for a typed one; [confident] = safe to apply without asking. */
    data class Candidate(val word: String, val confident: Boolean)

    fun suggest(typed: String, limit: Int = 3): List<String> {
        if (typed.isEmpty() || typed.length > MAX_LEN || !typed.all { it.isLetter() || it == '\'' }) return emptyList()
        val p = typed.lowercase()
        val fp = fold(p)
        val corrections = ArrayList<String>()
        // r13 (AC2/AC3/RW4): the valid accent variants of the typed word lead the accent corrections
        // (bastion → bastión first; que → qué), whether or not space applies one
        valid?.let { v -> if (p.length >= 2) corrections += accentForms(p, v) }
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
        val v = valid
        if (typed == lastQuery && v === lastValid) return lastBest
        val r = if (v == null) compute(typed) else compute13(typed, v)
        lastQuery = typed
        lastValid = v
        lastBest = r
        return r
    }

    /** The set [lastBest] was computed with (attached later by the io thread, RW3). */
    private var lastValid: ValidWords? = null

    // ── r13: R1 real words, R2 accent completion, R3 non-word correction ─────────

    /** Lowercase [w] is a word of this language: a lowercase entry, or a name typed lowercase (RW1). */
    private fun ValidWords.word(w: String): Boolean = contains(w) || contains(w.replaceFirstChar { it.uppercaseChar() })

    /**
     * RW1/RW2: never replaced by space — valid here or in the other enabled language, a regional
     * word, or (while the other language's set is not mapped yet) a common word there (BL4).
     */
    private fun protectedWord(t: String, v: ValidWords): Boolean {
        if (v.word(t) || t in regional) return true
        val f = foreign ?: return false
        val fv = f.valid
        if (fv != null && fv.word(t)) return true
        return rankOf[t] == null && (f.rank(t) ?: Int.MAX_VALUE) < FOREIGN_RANK
    }

    /**
     * R2: the valid spellings of lowercase [t] that differ only by accents / diaeresis (ñ is a
     * letter), lowercase, without [t] itself: lowercase entries, and lowercased names (peru → perú).
     */
    fun accentForms(t: String): List<String> = valid?.let { accentForms(t, it) } ?: emptyList()

    private fun accentForms(t: String, v: ValidWords): List<String> {
        val out = LinkedHashSet<String>()
        for (f in v.accentVariants(t)) if (f != t) out += f
        if (t.isNotEmpty()) for (f in v.accentVariants(t.replaceFirstChar { it.uppercaseChar() })) {
            if (f[0].isUpperCase() && f.drop(1).none { it.isUpperCase() }) f.lowercase().let { if (it != t) out += it }
        }
        return out.toList()
    }

    private fun compute13(t: String, v: ValidWords): Candidate? {
        if (t.length < MIN_LEN || t.length > MAX_LEN || !t.all { it.isLetter() }) return null
        if (protectedWord(t, v)) return null // R1
        val forms = accentForms(t, v)
        if (forms.size == 1) return Candidate(forms[0], true) // R2 (AC1)
        if (forms.size > 1) return null // AC2: the bar offers them
        // r8 A2: a frequency-list word is never replaced by another word; its ñ twin may still win
        rankOf[t]?.let { own ->
            val twin = enyeTwin(fold(t)) ?: return null
            return if (words[twin] != t && twin.toLong() * ACCENT_RATIO <= own && v.word(words[twin])) Candidate(words[twin], true) else null
        }
        if (t.length > MAX_FUZZY_LEN) return null
        return nonWord(t, v)
    }

    /** One spelling family (a fold) found near the typed word, with its best evidence. */
    private class Group(
        val fold: String, var cost: Float, var total: Float, var dist: Int, var rank: Int,
        var rare: Boolean, var other: Boolean, var spelling: String,
    )

    /** R3 (NW1–NW8): the likeliest word for the non-word [t], and whether it is safe to apply. */
    private fun nonWord(t: String, v: ValidWords): Candidate? {
        val ft = fold(t)
        val len = ft.length
        val maxD = maxDistance(len)
        val r = rows
        r.reset(ft, maxD, maxD * SEARCH_COST)
        val groups = HashMap<String, Group>()
        fun offer(fold: String, spelling: String, cost: Float, dist: Int, rank: Int, rare: Boolean, other: Boolean) {
            val prior = if (rare) RARE_PRIOR else FREQ_WEIGHT * ln(rank + 1.0).toFloat()
            val total = cost + prior + if (other) OTHER_LANG else 0f
            val g = groups[fold]
            if (g == null) groups[fold] = Group(fold, cost, total, dist, rank, rare, other, spelling)
            else if (total < g.total) { g.cost = cost; g.total = total; g.dist = dist; g.rank = rank; g.rare = rare; g.other = other; g.spelling = spelling }
        }
        fun scan(s: Suggest, other: Boolean) {
            // NW9: the list in folded order is walked like a trie — rows are kept for the prefix the
            // next word shares, and a prefix that can no longer match skips every word under it
            val folded = s.folded
            r.rewind()
            var dead: String? = null
            for (i in s.byFold) {
                val f = folded[i]
                if (dead != null && f.startsWith(dead)) continue
                dead = null
                if (abs(f.length - len) > maxD) continue
                if (len == f.length + 1 && ft.endsWith("nt") && ft.startsWith(f)) continue // "doesnt" ↛ "doesn"
                r.popTo(r.shared(f))
                var ok = true
                while (r.depth < f.length) {
                    if (!r.push(f[r.depth])) { dead = f.substring(0, r.depth + 1); ok = false; break }
                }
                if (ok && r.within) offer(f, s.words[i], r.cost, r.distance, i, rare = false, other = other)
            }
        }
        scan(this, other = false)
        v.near(r) { form, cost, d -> val f = fold(form); if (f !in groups) offer(f, form, cost, d, Int.MAX_VALUE, rare = true, other = false) }
        // NW8: the other language's common words compete (vuild → build); its rare forms do not
        foreign?.let { scan(it, other = true) }
        if (groups.isEmpty()) return null
        val sorted = groups.values.sortedBy { it.total }
        val best = sorted[0]
        val rival = sorted.getOrNull(1)?.total ?: Float.MAX_VALUE
        val word = committedForm(best, v)
        val confident = keepsTypedAccents(t, word) && (best.cost == 0f || sure(best, rival - best.total, sorted.size == 1, len))
        trace?.invoke(sorted.take(4).joinToString(" ") { g ->
            "%s(%s d=%d c=%.2f t=%.2f%s)".format(java.util.Locale.ROOT, g.spelling, if (g.rare) "rare" else "#${g.rank}", g.dist, g.cost, g.total, if (g.other) " other" else "")
        })
        return Candidate(word, confident)
    }

    /**
     * NW5: an accent typed by long-press is deliberate — a fix that drops it (chascón → chasco,
     * cachái → cachai) is never applied by space.
     */
    private fun keepsTypedAccents(typed: String, fix: String): Boolean {
        if (typed.none { it in ACCENTED }) return true
        val left = fix.toMutableList()
        for (c in typed) if (c in ACCENTED && !left.remove(c)) return false
        return true
    }

    /**
     * R3 confidence by TYPED length (NW4–NW6). A list word: the r8 bands (a top-[SHORT_RANK13] word
     * and a cheap typo for ≤ 4 letters, top [MID_RANK] for 5–6, any length 7+), each with a margin
     * over the next spelling family. A form outside the list: one cheap edit on 5+ letters, or —
     * on a long word — two neighbour slips with nothing else within two edits (clnfkgurarlo).
     */
    private fun sure(best: Group, margin: Float, sole: Boolean, len: Int): Boolean {
        if (margin < gap13(len)) return false
        if (best.rare) return margin >= RARE_GAP && when (best.dist) {
            1 -> len >= RARE_MIN_LEN && best.cost <= RARE_ONE
            else -> len >= RARE_TWO_LEN && sole && best.cost <= RARE_TWO
        }
        val rankOk = when {
            len >= 7 -> true
            len >= 5 -> best.rank < MID_RANK
            else -> best.rank < SHORT_RANK13
        }
        return rankOk && best.cost <= allowance(len, best.rank)
    }

    /**
     * NW7: R3 fixes letters, R2 decides accents. The bare spelling when it is a word of an enabled
     * language (pkxel → pixel, not píxel), else its single accented form here, else the list's.
     */
    private fun committedForm(g: Group, v: ValidWords): String {
        val fv = foreign?.valid
        if (v.contains(g.fold) || fv?.contains(g.fold) == true) return g.fold
        val here = v.accentVariants(g.fold)
        if (here.size == 1) return here[0]
        if (here.size > 1) foldRank[g.fold]?.let { return words[it] }
        return g.spelling
    }

    private fun compute(typed: String): Candidate? {
        if (typed.length < MIN_LEN || typed.length > MAX_FUZZY_LEN || !typed.all { it.isLetter() }) return null
        if (isForeign(typed)) return null // BL4: a word of the other language is meant as typed
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
        /** r10: an other-language word this frequent (top N there) is never corrected (BL4). */
        const val FOREIGN_RANK = 8000

        /** r13 search bound per allowed edit (the unweighted distance is the real bound). */
        const val SEARCH_COST = 1.7f
        /** r13 (NW4): prior of a valid form outside the frequency list (≈ rank 250 000: most of the 2.2M forms are enclitic / rare inflections). */
        val RARE_PRIOR = FREQ_WEIGHT * ln(250_000.0).toFloat()
        /** r13 (NW8): a word of the other enabled language is one notch behind the writing language's. */
        const val OTHER_LANG = 0.3f
        /** r13 (NW4): a form outside the list is applied for one cheap edit on this many letters … */
        const val RARE_MIN_LEN = 5
        /** … costing at most one omission / one neighbour slip; */
        const val RARE_ONE = 0.8f
        /** two edits only from this length, both neighbour slips, nothing else within two edits (NW5). */
        const val RARE_TWO_LEN = 10
        const val RARE_TWO = 1.1f
        /** Two rare forms are told apart by their edit cost alone (no frequency): a wider margin. */
        const val RARE_GAP = 0.4f
        /** r13 (NW6): ≤ 4 letters land on the top N (r8: 3000; regional everyday words sit just past it). */
        const val SHORT_RANK13 = 4000

        /** r13 margin over the next spelling family, by typed length. */
        fun gap13(len: Int): Float = when {
            len >= 7 -> 0.25f
            len >= 5 -> 0.35f
            else -> 0.45f
        }

        /**
         * r13: a language's lexicon exactly as the IME loads it ([com.resyst.vk.ime.Lexicon]): Spanish
         * re-ranked by [region]'s rule files ([readRule] reads `regional/<name>.txt`) with their words
         * protected (RW2); English as listed. The valid set is attached separately ([valid]).
         */
        fun build(lang: Lang, words: List<String>, region: Region, readRule: (String) -> String?): Suggest =
            if (lang == Lang.ES) Suggest(Regional.forRegion(words, region, readRule), lang, Regional.protectedWords(region, readRule))
            else Suggest(words, lang)

        private val MARKS = Regex("\\p{Mn}+")
        /** Letters that only a long-press types (ñ is a key of its own, not an accent). */
        private const val ACCENTED = "áéíóúüàèìòùâêîôûäëïö"

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
