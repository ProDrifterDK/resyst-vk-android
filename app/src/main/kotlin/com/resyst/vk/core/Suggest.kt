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

    /** r13f (offers): the region's rule words ([regional]), read-only, for the context offers' lexicon and keep-list. */
    val regionalWords: Set<String> get() = regional

    /**
     * r13f (offers): the list's words spelled with the same letters as [w] once accents are removed ([fold]; [key] =
     * that folded form when the caller has it), in the folded index's order. Read-only over arrays built at load
     * ([prepare]): safe off the main thread.
     */
    fun foldMates(w: String, key: String = fold(w)): List<String> {
        val f = key
        if (foldRank[f] == null) return emptyList() // no list word has these letters (HP5: most look-ups end here)
        val idx = byFold
        var lo = 0
        var hi = idx.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (folded[idx[mid]] < f) lo = mid + 1 else hi = mid
        }
        var out: ArrayList<String>? = null
        var i = lo
        while (i < idx.size && folded[idx[i]] == f) {
            (out ?: ArrayList<String>(2).also { out = it }).add(words[idx[i]])
            i++
        }
        return out ?: emptyList()
    }

    /**
     * r10 bilingual guard (BL4): the other language's lexicon. A typed word that is NOT in this
     * lexicon but is a common word over there ([FOREIGN_RANK]) is never re-spelled into this
     * language — "meeting" typed in Spanish stays "meeting". null = r9 behavior (BL6).
     */
    var foreign: Suggest? = null
        set(v) {
            if (v === field) return
            field = v
            lastQuery = null; lastBest = null; mineHere = null; wordMemo.clear()
        }

    /**
     * r13d (OR1): the user's own words as correction targets, spelled as space would commit them
     * (`GrapheneOS`), set by [Writing] through the personal READ gate (empty = none: incognito, secret,
     * personal suggestions off). Only those that are no word of this lexicon compete ([mineTargets]).
     */
    var mine: List<String> = emptyList()
        set(v) {
            if (v === field) return
            field = v
            lastQuery = null; lastBest = null; mineHere = null
        }

    /** [mine] without this lexicon's words, folded → spelling; rebuilt when [mine], [valid] or [foreign] change. */
    private var mineHere: List<Pair<String, String>>? = null
    private var mineValid: ValidWords? = null
    /**
     * r13d (OR4): [isWordHere] per folded target, kept across rebuilds of [mine] (the model changes with every
     * word learned; dictionary membership does not); cleared with [valid] / [foreign], at most [WORD_MEMO].
     */
    private val wordMemo = HashMap<String, Boolean>()

    private fun mineTargets(v: ValidWords): List<Pair<String, String>> {
        val cached = mineHere
        if (cached != null && mineValid === v) return cached
        if (mineValid !== v || wordMemo.size > WORD_MEMO) wordMemo.clear()
        val out = ArrayList<Pair<String, String>>()
        for (m in mine) {
            val f = fold(m)
            if (f.length < MIN_LEN || f.length > MAX_LEN || !f.all { it.isLetter() }) continue
            if (wordMemo.getOrPut(f) { isWordHere(f, v) }) continue
            out += f to m
        }
        mineHere = out
        mineValid = v
        return out
    }

    /** A word of an enabled language for folded [f]: in a list, valid, regional, or one of its accented spellings is valid. */
    private fun isWordHere(f: String, v: ValidWords): Boolean =
        rankOf[f] != null || foldRank[f] != null || protectedWord(f, v) || accentForms(f, v).isNotEmpty()

    private fun isForeign(typed: String): Boolean {
        val f = foreign ?: return false
        return rankOf[typed] == null && (f.rank(typed) ?: Int.MAX_VALUE) < FOREIGN_RANK
    }

    /**
     * A close lexicon word for a typed one; [confident] = safe to apply without asking. r13d (WC3): [alt] =
     * the next reading when it is close to [word] and nothing is applied — the bar offers both.
     */
    data class Candidate(val word: String, val confident: Boolean, val alt: String? = null)

    /** [near]: the touch record of [typed] ([TouchTrail.forWord]), or null. */
    fun suggest(typed: String, limit: Int = 3, near: Array<String>? = null): List<String> {
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
        val fix = best(p, near)
        val head = if (fix?.confident == true) listOf(fix.word) else emptyList()
        val tail = if (fix != null && !fix.confident) listOfNotNull(fix.word, fix.alt) else emptyList()
        return (head + corrections + exact + loose + tail).distinct().take(limit).map { matchCase(it, typed) }
    }

    /**
     * The word space should commit instead of [typed], cased like it, or null to leave it alone.
     * Case gates (A3): a capitalized word mid-sentence is a name the user meant; ALL-CAPS
     * (acronyms) and mixed case (iPhone) are never touched.
     */
    fun correction(typed: String, sentenceStart: Boolean, near: Array<String>? = null): String? {
        // r13c (SW1, TW5): with the valid-word set two-letter words and words up to MAX_LEN are looked at
        val v = valid
        val min = if (v != null) SHORT_LEN else MIN_LEN
        val max = if (v != null) MAX_LEN else MAX_FUZZY_LEN
        if (typed.length < min || typed.length > max || !typed.all { it.isLetter() }) return null
        if (typed.drop(1).any { it.isUpperCase() }) return null
        if (typed[0].isUpperCase() && !sentenceStart) return null
        val c = best(typed.lowercase(), near) ?: return null
        return if (c.confident) matchCase(c.word, typed) else null
    }

    private var lastQuery: String? = null
    private var lastBest: Candidate? = null
    private var lastNear: Array<String>? = null

    /**
     * Closest lexicon word for a lowercase [typed] word, or null when [typed] is itself a word
     * (A2) or nothing is near enough (A5). A word in the lexicon is only re-spelled when its
     * accented twin is ≥ [ACCENT_RATIO]× more frequent ("tambien" → "también", never
     * "esta" → "está"). r13d (TB1): [near] = the touch record of [typed] (per letter, the keys its
     * touch was on the border of), or null; the r12 path ignores it.
     */
    fun best(typed: String, near: Array<String>? = null): Candidate? {
        val v = valid
        val n = near?.takeIf { it.size == typed.length && it.any { s -> s.isNotEmpty() } }
        if (typed == lastQuery && v === lastValid && n.contentEquals(lastNear)) return lastBest
        val r = if (v == null) compute(typed) else compute13(typed, v, n)
        lastQuery = typed
        lastValid = v
        lastNear = n
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
     * r13d (FW1): the other language's NAMES (capitalized-only entries: the surname `Auer`, one slip from
     * `ayer`) protect a lowercase typing only when that lowercase form is in its frequency list (any
     * rank: `google`, `english`); this language's own names keep r13a's rule (`chile`, `maria`).
     */
    private fun protectedWord(t: String, v: ValidWords): Boolean {
        if (v.word(t) || t in regional) return true
        val f = foreign ?: return false
        val fv = f.valid
        if (fv != null && (fv.contains(t) || (f.rank(t) != null && fv.word(t)))) return true
        return rankOf[t] == null && (f.rank(t) ?: Int.MAX_VALUE) < FOREIGN_RANK
    }

    /**
     * r13c (MC2): whether a learned casing [cased] may be applied. One with a capital after the first
     * letter (GrapheneOS, trAe) only when its lowercase is no word of an enabled language (this list
     * or valid set, the other language's) — GrapheneOS — or that casing is itself a valid spelling
     * (iPhone): `trae` is a word, so a learned `trAe` (a stray capital) is never applied.
     */
    fun casingFits(cased: String): Boolean {
        if (cased.length < 2 || cased.drop(1).none { it.isUpperCase() }) return true
        val lower = cased.lowercase()
        val v = valid
        val f = foreign
        val fv = f?.valid
        if (v?.contains(cased) == true || fv?.contains(cased) == true) return true
        val word = rankOf[lower] != null || v?.word(lower) == true ||
            (f != null && (f.rank(lower) != null || fv?.word(lower) == true))
        return !word
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

    private fun compute13(t: String, v: ValidWords, near: Array<String>?): Candidate? {
        if (t.length == SHORT_LEN) return shortWord(t, v, near) // r13c (SW1–SW5)
        if (t.length < MIN_LEN || t.length > MAX_LEN || !t.all { it.isLetter() }) return null
        if (protectedWord(t, v)) return null // R1
        val forms = accentForms(t, v)
        if (forms.size == 1) return Candidate(forms[0], true) // R2 (AC1)
        if (forms.size > 1) return null // AC2: the bar offers them
        // RW1 / AC5 (r13a): a frequency-list word is never replaced by another word; its ñ twin may still win
        rankOf[t]?.let { own ->
            val twin = enyeTwin(fold(t)) ?: return null
            return if (words[twin] != t && twin.toLong() * ACCENT_RATIO <= own && v.word(words[twin])) Candidate(words[twin], true) else null
        }
        return nonWord(t, v, near)
    }

    /**
     * r13c (SW1–SW5): a two-letter non-word one touching key away from a very frequent word of the
     * writing language ([SHORT_TOP]): nl → no, dd → de, sd → se, jn → un, ys → ya, ek → el. Real words
     * stay (SW2): valid here, in this list (any rank), regional, or common in the other enabled language
     * (BL4) — not merely valid in the other language's Hunspell set, which accepts 42% of all
     * two-letter strings. Ties stay: every list word one touching key away (any rank, and the other
     * language's common words one notch behind) competes, and the best must lead by [SHORT_GAP2].
     */
    private fun shortWord(t: String, v: ValidWords, near: Array<String>?): Candidate? {
        if (!t.all { it.isLetter() }) return null
        if (v.word(t) || t in regional || rankOf[t] != null) return null
        val f = foreign
        if (f != null && (f.rank(t) ?: Int.MAX_VALUE) < FOREIGN_RANK) return null
        var best: String? = null
        var bestScore = Float.MAX_VALUE
        var second = Float.MAX_VALUE
        fun consider(w: String, score: Float, target: Boolean) {
            if (score < bestScore) {
                second = bestScore
                bestScore = score; best = if (target) w else null
            } else if (score < second) second = score
        }
        for (i in 0..1) for (c in centres.keys) {
            val typedC = t[i]
            // r13d (TB1): a key the touch was on the border of is a neighbour too (a corner: i ↔ j)
            val border = near != null && near[i].indexOf(c) >= 0
            if (c == typedC || !(border || TypoModel.near(centres, typedC, c))) continue
            val w = if (i == 0) "$c${t[1]}" else "${t[0]}$c"
            val slip = if (border) TypoModel.BORDER
            else TypoModel.sub(centres, c, typedC, spanish).let { s -> if (i == 0 && s != TypoModel.PHON) s + TypoModel.FIRST else s }
            rankOf[w]?.let { r -> consider(w, slip + FREQ_WEIGHT * ln(r + 1.0).toFloat(), r < SHORT_TOP) }
            f?.rank(w)?.takeIf { it < FOREIGN_RANK }?.let { r -> consider(w, slip + FREQ_WEIGHT * ln(r + 1.0).toFloat() + OTHER_LANG, false) }
        }
        trace?.invoke("short $t: best=$best ${"%.2f".format(java.util.Locale.ROOT, bestScore)} next=${"%.2f".format(java.util.Locale.ROOT, second)}")
        val w = best ?: return null
        return Candidate(w, second - bestScore >= SHORT_GAP2)
    }

    /** r13c (SW1): key centres of this lexicon's drawn layout, for the two-letter rule. */
    private val centres = TypoModel.centres(lang)
    private val spanish = lang != Lang.EN

    /** One spelling family (a fold) found near the typed word, with its best evidence. */
    private class Group(
        val fold: String, var cost: Float, var total: Float, var dist: Int, var rank: Int,
        var rare: Boolean, var other: Boolean, var spelling: String,
    ) {
        /** r13c (TW2): the fold's best rank in any enabled language's list (the rank gate reads it). */
        var known: Int = if (rare) Int.MAX_VALUE else rank
    }

    /** R3 (NW1–NW8): the likeliest word for the non-word [t], and whether it is safe to apply. */
    private fun nonWord(t: String, v: ValidWords, touch: Array<String>?): Candidate? {
        val ft = fold(t)
        val len = ft.length
        val near = touch?.takeIf { it.size == len }
        val maxD = maxDistance13(len)
        val groups = HashMap<String, Group>()
        collect(ft, v, maxD, maxD * SEARCH_COST, groups, near)
        // r13d (W5-1): two edits on five letters are two SLIPS (keys mis-hit or swapped, same length); an
        // extra / missing key plus another edit leaves too many readings (cahai: casi, pkxel: piel)
        if (len == 5) groups.values.removeAll { it.dist >= 2 && it.fold.length != len }
        var answer = decide(t, ft, groups, v, near)
        if (len >= THREE_LEN && answer?.confident != true) {
            // r13c (TW7): a long word with three cheap slips (impoersnte → importante, dkcionafio →
            // diccionario): a second pass within three edits, bounded by cost, added to the first
            val far = HashMap<String, Group>()
            collect(ft, v, 3, THREE_SLIP + THREE_SEEN, far, near)
            for ((f, g) in far) if (g.dist == 3 && f !in groups) groups[f] = g
            answer = decide(t, ft, groups, v, near)
        }
        return answer
    }

    /** Every spelling family within [maxD] edits / [maxCost] of folded [ft], into [groups] (NW9 walk). */
    private fun collect(ft: String, v: ValidWords, maxD: Int, maxCost: Float, groups: HashMap<String, Group>, near: Array<String>?) {
        val len = ft.length
        val r = rows
        r.reset(ft, maxD, maxCost, near)
        fun offer(fold: String, spelling: String, cost: Float, dist: Int, rank: Int, rare: Boolean, other: Boolean) {
            val prior = if (rare) RARE_PRIOR else FREQ_WEIGHT * ln(rank + 1.0).toFloat()
            val total = cost + prior + if (other) OTHER_LANG else 0f
            val g = groups[fold]
            if (g == null) groups[fold] = Group(fold, cost, total, dist, rank, rare, other, spelling)
            else {
                if (!rare) g.known = minOf(g.known, rank)
                if (total < g.total) { g.cost = cost; g.total = total; g.dist = dist; g.rank = rank; g.rare = rare; g.other = other; g.spelling = spelling }
            }
        }
        fun scan(s: Suggest, other: Boolean) {
            // NW9: the list in folded order is walked like a trie — rows are kept for the prefix the
            // next word shares, and a prefix that can no longer match skips every word under it
            val folded = s.folded
            r.sounds(s.lang != Lang.EN) // r13c (NW3): Spanish sound spellings only for Spanish words
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
        r.sounds(lang != Lang.EN)
        v.near(r) { form, cost, d -> val f = fold(form); if (f !in groups) offer(f, form, cost, d, Int.MAX_VALUE, rare = true, other = false) }
        // NW8: the other language's common words compete (vuild → build); its rare forms do not
        foreign?.let { scan(it, other = true) }
        r.sounds(lang != Lang.EN)
        // r13d (OR1): the user's own words no dictionary knows (GrapheneOS) compete like a list word
        // (never the typed word itself: whether the user's own typing stays is B5's call, with TS3b's strikes)
        if (mine.isNotEmpty()) for ((f, spelling) in mineTargets(v)) {
            if (f == ft || abs(f.length - len) > maxD || f in groups) continue
            if (r.score(f)) offer(f, spelling, r.cost, r.distance, USER_RANK, rare = false, other = false)
        }
    }

    /** The answer over [groups] for typed [t] (folded [ft]): the best family and whether it is safe. */
    private fun decide(t: String, ft: String, groups: Map<String, Group>, v: ValidWords, near: Array<String>?): Candidate? {
        if (groups.isEmpty()) return null
        val len = ft.length
        val sorted = groups.values.sortedBy { it.total }
        val best = sorted[0]
        // r13c (TA1): a one-slip valid form outside the list is not held back by a rival that needs
        // another edit and costs at least one more slip (pusiesemls: pusiésemos 1 slip, pudiésemos 2)
        val oneSlip = best.rare && best.dist == 1 && best.cost <= ONE_SLIP && len >= RARE_SLIP_LEN
        val rival = sorted.asSequence().drop(1).firstOrNull { g -> !(oneSlip && g.dist > best.dist && g.cost - best.cost >= SLIP - EPS) }?.total ?: Float.MAX_VALUE
        val word = committedForm(best, v)
        val margin = rival - best.total
        // r13d (W5-1): the spelling families two cheap slips (or less) away
        val plausible = sorted.count { it.cost <= TWO_SLIP + EPS }
        // r13d (WC2): the lead over the next everyday word (a list word of either language)
        val listMargin = (sorted.asSequence().drop(1).firstOrNull { !it.rare }?.total ?: Float.MAX_VALUE) - best.total
        val confident = keepsTypedAccents(t, word) && when {
            best.cost == 0f -> true
            best.dist >= 3 -> !best.rare && best.cost <= THREE_SLIP && margin >= RARE_GAP // TW7
            else -> sure(best, margin, listMargin, sorted.size == 1, len, plausible) || threeLetter(ft, best, margin, near)
        }
        // r13d (WC3): nothing applied and the next reading is close: the bar offers both
        val alt = if (confident) null else sorted.getOrNull(1)?.takeIf { it.total - best.total < RARE_GAP }?.let { committedForm(it, v) }?.takeIf { it != word }
        trace?.invoke(sorted.take(4).joinToString(" ") { g ->
            "%s(%s d=%d c=%.2f t=%.2f%s)".format(java.util.Locale.ROOT, g.spelling, if (g.rare) "rare" else "#${g.rank}", g.dist, g.cost, g.total, if (g.other) " other" else "")
        } + if (confident) " ✓" else "")
        return Candidate(word, confident, alt)
    }

    /**
     * r13d (T3-1): a three-letter non-word one touching key from a very frequent three-letter word of this
     * language (`pof` → `por`): the best family is one of this list's [THREE_TOP] three-letter words,
     * reached by ONE substitution of a touching key — or of a key the touch was on the border of (TB1) —
     * costing at most one slip ([ONE_SLIP], first-letter penalty included), and it leads every other
     * family (an extra / missing key, the other language one notch behind) by [THREE_GAP].
     */
    private fun threeLetter(ft: String, best: Group, margin: Float, near: Array<String>?): Boolean {
        if (ft.length != 3 || best.fold.length != 3 || best.rare || best.other || best.dist != 1 || best.rank >= THREE_TOP) return false
        if (best.cost > ONE_SLIP + EPS || margin < THREE_GAP - EPS) return false
        var at = -1
        for (i in 0 until 3) if (ft[i] != best.fold[i]) { if (at >= 0) return false; at = i }
        if (at < 0) return false
        val c = best.fold[at]
        return TypoModel.near(centres, ft[at], c) || (near != null && near[at].indexOf(c) >= 0)
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
     * r13d: a fix needing two edits leads the next LIST word (either language) by [RARE_GAP] at any
     * length ([listMargin], WC2: two edits leave more readings than one — juneros: juntos 1.05 /
     * números 1.19, both everyday words; a rare form behind keeps the length's margin: hoohle → google
     * over hollé), and on five letters it is the only family within two cheap slips ([plausible], W5-1).
     */
    private fun sure(best: Group, margin: Float, listMargin: Float, sole: Boolean, len: Int, plausible: Int): Boolean {
        if (margin < gap13(len)) return false
        if (best.dist >= 2 && listMargin < RARE_GAP) return false // r13d (WC2)
        if (best.rare) return margin >= RARE_GAP && when (best.dist) {
            1 -> len >= RARE_MIN_LEN && best.cost <= RARE_ONE
            else -> len >= RARE_TWO_LEN && sole && best.cost <= RARE_TWO
        }
        val rank = best.known // r13c (TW2): its best rank among the enabled languages' lists
        val rankOk = when {
            len >= 7 -> true
            len >= 5 -> rank < MID_RANK
            else -> rank < SHORT_RANK13
        }
        // r13c (TW1), r13d (W5-1): two edits on 5–6 letters only as two cheap slips
        val costOk = if (len < 7 && best.dist >= 2) best.cost <= TWO_SLIP else best.cost <= allowance(len, rank)
        val alone = len != 5 || best.dist < 2 || plausible == 1
        return rankOk && costOk && alone
    }

    /**
     * NW7: R3 fixes letters, R2 decides accents. The bare spelling when it is a word of an enabled
     * language (pkxel → pixel, not píxel), else its single accented form here, else the list's.
     */
    private fun committedForm(g: Group, v: ValidWords): String {
        val fv = foreign?.valid
        if (v.contains(g.fold)) return g.fold
        val here = v.accentVariants(g.fold)
        // r13c (TA4): a typo found among this language's words whose bare spelling is only the other
        // language's (baation → bastión here, bastion in English): this language's single spelling
        if (!g.other && here.size == 1 && fv?.contains(g.fold) == true) return here[0]
        if (fv?.contains(g.fold) == true) return g.fold
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

        /** r13c (SW1): two-letter words are looked at (the r12 path keeps [MIN_LEN]). */
        const val SHORT_LEN = 2
        /** r13c (SW1): a two-letter fix lands on a two-letter word among this many most frequent list words. */
        const val SHORT_TOP = 64
        /** r13c (SW1): a two-letter fix must lead the next candidate by this much (a tie stays). */
        const val SHORT_GAP2 = 0.2f
        /** r13c (TW1): the cost of two cheap slips (two touching keys; a slip + a swap / double / near extra key). */
        const val TWO_SLIP = 1.1f
        /** r13c (TA1): one touching-key slip (a side or a vertical neighbour: 0.40 / 0.53) … */
        const val ONE_SLIP = 0.6f
        /** … a rival is "another slip away" when it needs one more edit costing at least a side slip more … */
        const val SLIP = 0.4f
        /** Float slack for cost comparisons (0.93 − 0.53 is a whole side slip). */
        private const val EPS = 1e-3f
        /** … for a valid form outside the list of this many letters (an inflection, not a short word). */
        const val RARE_SLIP_LEN = 7
        /** r13c (TW7): three slips are searched from this many letters … */
        const val THREE_LEN = 10
        /** … each a cheap slip (three touching keys ≈ 1.2–1.6) … */
        const val THREE_SLIP = 1.6f
        /** … and the pass sees rivals this much dearer too (the margin needs them). */
        const val THREE_SEEN = 1.0f

        /** r13d (T3-1): a three-letter fix lands on one of this list's this-many most frequent words … */
        const val THREE_TOP = 200
        /** … and leads every other reading by this much (0.45 stays for the regular ≤ 4-letter fixes). */
        const val THREE_GAP = 0.3f
        /**
         * r13d (OR1): the frequency prior of the user's own word as a correction target, like a list word of
         * this rank (an everyday word: inside r13a's ≤ 4-letter band; for 5–6 letters at r8's 1000 line, so
         * only thumb-shaped typos — ≤ 0.8 — reach it).
         */
        const val USER_RANK = 1000
        /** r13d (OR4): dictionary answers remembered for learned targets (2 × PersonalModel.VOCAB_CAP). */
        private const val WORD_MEMO = 4000

        /**
         * r13c (TW1), r13d (W5-1): the r13 search budget: one edit under 5 letters, two from 5 (r12 path:
         * [maxDistance]).
         */
        fun maxDistance13(len: Int): Int = when {
            len < MIN_LEN -> 0
            len < 5 -> 1
            else -> 2
        }

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
