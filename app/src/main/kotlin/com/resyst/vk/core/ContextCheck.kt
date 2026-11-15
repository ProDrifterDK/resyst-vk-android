package com.resyst.vk.core

import kotlin.math.ln

/**
 * r13f (offers): the context checker of r13e (spike/r13e-ngram, ce8792c) at its frozen OFFER point, without the
 * personal notebook and the automatic modes. A typed REAL word is compared with its confusion set
 * ([ContextCandidates]) by an n-gram window score ([ContextNgram]):
 * ```
 * Δ(alt) = [W(alt) − W(typed)] + α·channel(typed | alt)
 * W(x)   = Σ ln P(token | two before) over x and the words typed after it (and </s> when the sentence's end mark is
 *          among them), within the sentence; the left context is the sentence so far
 * ```
 * Stage 0 (which alternatives are scored) is r13b's Retro, unchanged: the I6 shape rule, the keep-lists, the
 * contrast guard, θ_flag and K. Frozen point (reports/r13e.md, r13e-s4): O@1 — decided once ONE more word of the same
 * sentence is typed —, τ_offer 6, K 2, θ_flag −8, guard on, keep-lists on, α 1, λf 0 (CO1: never retuned here).
 * Nothing here writes text (CO2): [decide] returns a proposal.
 */
class ContextCheck(val model: ContextNgram, val lex: Lex) {
    /** The lexicon hooks: the writing language's words ([ContextCandidates.Lexicon]) and the English guard (CO3). */
    interface Lex : ContextCandidates.Lexicon {
        /** Lowercase [w] is an English word (r13b's Spanish-sentence rule); false when English is not loaded. */
        fun isEn(w: String): Boolean
    }

    /** The frozen operating point. */
    data class Params(
        val tauOffer: Double = TAU_OFFER,
        /** Words after the target the decision waits for (O@1). */
        val rightWords: Int = 1,
        /** Alternatives scored per word (best [ContextCandidates.selection] first). */
        val k: Int = 2,
        /** Stage 0: an alternative is scored only if α·channel + λsel·Δlogfreq ≥ θ_flag. */
        val thetaFlag: Double = -8.0,
        val contrastGuard: Boolean = true,
        val keepLists: Boolean = true,
        /** Channel scale α. */
        val alpha: Double = 1.0,
    )

    private val cands = ContextCandidates(lex)
    private val lnUnk = ln(model.unkTypes.toDouble().coerceAtLeast(1.0))

    /**
     * HP5/HP3: the confusion sets of the last words checked (chat repeats its words); bounded, tied to this checker's
     * lexicon (the IME builds a new checker when a lexicon object changes).
     */
    private val candCache = object : LinkedHashMap<String, List<ContextCandidates.Alt>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ContextCandidates.Alt>>?): Boolean = size > CAND_CACHE
    }

    private fun candidates(lw: String): List<ContextCandidates.Alt> {
        synchronized(candCache) { candCache[lw] }?.let { return it }
        val t0 = System.nanoTime()
        val c = cands.of(lw)
        lastCandidatesNs = System.nanoTime() - t0
        synchronized(candCache) { candCache[lw] = c }
        return c
    }

    /**
     * HP5: computes [word]'s confusion set ahead of its decision (the IME calls it at the word end that completes
     * [word], one word before the decision needs it), when [word] could be checked at all. Returns the ns spent.
     */
    fun prefetch(word: String): Long {
        if (!eligibleShape(word)) return 0L
        val lw = word.lowercase()
        if (!lex.isWord(lw)) return 0L
        val t0 = System.nanoTime()
        candidates(lw)
        return System.nanoTime() - t0
    }

    /** Diagnostics (the debug build's time log): the last confusion set computed, ns (0 = from the cache). */
    @Volatile var lastCandidatesNs = 0L
        private set

    /** Σ ln P of [seq] from token [from] on; an out-of-vocabulary word at [from] (the candidate) pays ln(unkTypes). */
    fun windowScore(seq: List<String>, from: Int): Double {
        val ids = IntArray(seq.size) { model.id(seq[it]) }
        var s = 0.0
        for (j in from until seq.size) {
            if (seq[j] == ContextTokens.BOS) continue
            val b = if (j >= 1) ids[j - 1] else -1
            val a = if (j >= 2 && ids[j - 1] != ContextNgram.BOS_ID) ids[j - 2] else -1
            var lp = model.logP(ids[j], a, b)
            if (j == from && ids[j] == ContextNgram.UNK) lp -= lnUnk
            s += lp
        }
        return s
    }

    /**
     * W(alt) − W(typed) for each of [alts] between [left] and [right] (tokens; [typed] and [alts] lowercase): the sums
     * [windowScore] makes, term for term, with the context's ids looked up once (HP5).
     */
    fun lmDiffs(left: List<String>, typed: String, alts: List<String>, right: List<String>): DoubleArray {
        val l = IntArray(left.size) { model.id(left[it]) }
        val r = IntArray(right.size) { model.id(right[it]) }
        val base = score(left, l, typed, right, r)
        return DoubleArray(alts.size) { score(left, l, alts[it], right, r) - base }
    }

    /** [windowScore] of left + [mid] + right from [mid] on, over ids already looked up. */
    private fun score(left: List<String>, l: IntArray, mid: String, right: List<String>, r: IntArray): Double {
        val m = model.id(mid)
        val from = left.size
        val n = from + 1 + right.size
        fun id(j: Int) = if (j < from) l[j] else if (j == from) m else r[j - from - 1]
        fun tok(j: Int) = if (j < from) left[j] else if (j == from) mid else right[j - from - 1]
        var s = 0.0
        for (j in from until n) {
            if (tok(j) == ContextTokens.BOS) continue
            val b = if (j >= 1) id(j - 1) else -1
            val a = if (j >= 2 && id(j - 1) != ContextNgram.BOS_ID) id(j - 2) else -1
            var lp = model.logP(id(j), a, b)
            if (j == from && m == ContextNgram.UNK) lp -= lnUnk
            s += lp
        }
        return s
    }

    /** The r13e oracle's whole-sentence view (tests only): the guard's words and the sentence's words. */
    class OracleView(val guard: Collection<String>, val sentence: List<String>)

    /** One scored alternative and its Δ. */
    data class Choice(val alt: ContextCandidates.Alt, val delta: Double)

    /**
     * The decision for the word at [wordStart, wordEnd) of [text], the LIVE text as the IME sees it (nothing after
     * the cursor; for O@1 cut at the end of the next word): shape, a word of the writing language, a Spanish
     * sentence, not kept, Stage 0, the window scores. Returns the best alternative and its Δ (the caller compares it
     * with [Params.tauOffer]), or null. [keep] = the keep-lists (null = none). [oracle]: the r13e oracle's view of the
     * sentence — the WHOLE sentence, words typed after the decision included — for the contrast guard and the
     * Spanish-sentence rule; the offline tests use it to show that this is the one difference (CO5). The IME never
     * passes it: live, only what is typed exists.
     */
    fun decide(text: String, wordStart: Int, wordEnd: Int, p: Params, keep: ContextKeep.Keep?, oracle: OracleView? = null): Choice? {
        val typed = text.substring(wordStart, wordEnd)
        if (!eligibleShape(typed)) return null
        val lw = typed.lowercase()
        if (!lex.isWord(lw)) return null
        val segs = ContextSegments.segments(text)
        val si = ContextSegments.segmentAt(segs, wordStart)
        if (si < 0) return null
        val seg = segs[si]
        val words = ContextSegments.words(text)
        val wi = words.indexOfFirst { it.start == wordStart }
        if (wi < 0 || words[wi].end != wordEnd) return null
        val sentence = oracle?.sentence ?: ContextSegments.words(text, seg.start, seg.end).map { it.text }
        if (!spanishSegment(sentence, lex::isWord, lex::isEn)) return null
        // folded on demand: the keep-lists read the word and its phrase neighbours only (HP5)
        if (p.keepLists && keep != null && keep.keeps(object : AbstractList<String>() {
                override val size get() = words.size
                override fun get(index: Int) = ContextCandidates.bare(words[index].text)
            }, wi)) return null
        val from = if (si > 0) segs[si - 1].start else seg.start
        val guard = oracle?.guard ?: ContextSegments.words(text, from, seg.end).map { it.text.lowercase() }
        val tlf = lex.logFreq(lw)
        lastCandidatesNs = 0L
        val alts = flag(candidates(lw), tlf, guard, p)
        if (alts.isEmpty()) return null
        // the context exactly as the r13e oracle cut it: tokens from the previous sentence's start
        val piece = text.substring(from)
        val sents = ContextTokens.tokenize(piece)
        val (ts, ti) = ContextTokens.locate(sents, wordStart - from) ?: return null
        val s = sents[ts]
        val left = ContextTokens.leftOf(s, ti)
        val right = ContextTokens.rightOf(s, ti, p.rightWords)
        val d = lmDiffs(left, lw, alts.map { it.word }, right)
        var best: Choice? = null
        for ((i, a) in alts.withIndex()) {
            val c = Choice(a, d[i] + p.alpha * a.channel)
            if (best == null || c.delta > best.delta) best = c // ties: the earlier one in selection order
        }
        return best
    }

    companion object {
        const val TAU_OFFER = 6.0
        /** Confusion sets kept per checker (HP3). */
        const val CAND_CACHE = 256

        /** I6 shape rule: letters only; never ALL CAPS (> 1 letter) or a capital after the first letter (iPhone, trAe). */
        fun eligibleShape(typed: String): Boolean {
            if (typed.isEmpty() || !typed.all { it.isLetter() }) return false
            if (typed.length > 1 && typed.all { it.isUpperCase() }) return false
            return typed.drop(1).none { it.isUpperCase() }
        }

        /**
         * Contrast guard (CO5): never switch to [alt] when it already occurs (any case, same accents) among
         * [windowWords] — the words of the target's sentence and of the previous one ("no es lo mismo casa que caza").
         */
        fun contrastHit(alt: String, windowWords: Collection<String>): Boolean {
            val a = alt.lowercase()
            return windowWords.any { it.lowercase() == a }
        }

        /** Stage 0 prior of [alt] relative to the typed word: α·channel + λsel·Δlogfreq. */
        fun flagPrior(alt: ContextCandidates.Alt, typedLogFreq: Double, p: Params): Double =
            p.alpha * alt.channel + ContextCandidates.LAMBDA_SEL * (alt.logFreq - typedLogFreq)

        /**
         * Stage 0: the alternatives that get scored, in selection order: not blocked by the contrast guard, prior ≥
         * θ_flag, at most K. Empty = the word is not flagged. [alts] must already be in selection order.
         */
        fun flag(alts: List<ContextCandidates.Alt>, typedLogFreq: Double, windowWords: Collection<String>, p: Params): List<ContextCandidates.Alt> {
            val out = ArrayList<ContextCandidates.Alt>()
            for (a in alts) {
                if (out.size >= p.k) break
                if (p.contrastGuard && contrastHit(a.word, windowWords)) continue
                if (flagPrior(a, typedLogFreq, p) < p.thetaFlag) continue
                out += a
            }
            return out
        }

        /**
         * CO3: a sentence is scored only when its words are not mostly English: words valid only in English must not
         * outnumber words valid only in Spanish (the bilingual owner writes whole English sentences).
         */
        fun spanishSegment(words: List<String>, isEs: (String) -> Boolean, isEn: (String) -> Boolean): Boolean {
            var es = 0
            var en = 0
            for (w in words) {
                val l = w.lowercase()
                val a = isEs(l)
                val b = isEn(l)
                if (a && !b) es++ else if (b && !a) en++
            }
            return en <= es
        }
    }
}
