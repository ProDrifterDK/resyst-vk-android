package com.resyst.vk.core

import kotlin.math.ln

/**
 * r13f (offers): the bar's context offer as a small state machine. ResystImeService drives it; the JVM tests drive
 * the same object. Mode O@1: when a word end completes the NEXT word of a sentence, the word before it is checked
 * ([job] on the main thread, [decide] off it); an offer is shown while the live text still holds its word ([arrive]),
 * stays until the sentence moves on ([refresh], [sent]) and edits only when tapped ([take], CO2). Memory only:
 * nothing here is stored (PV2).
 *
 * Where an offer sits is re-read from the field every time, never remembered as an offset (ST2): the words of its
 * sentence up to and including the target ([Offer.prefix]) must still open the last sentence before the cursor.
 */
class ContextOffers {
    /**
     * A decision to make off the main thread: the live text from the start of the sentence before the target's to the
     * end of the next word, and the target in it.
     */
    class Job(val text: String, val start: Int, val end: Int, val typed: String, val prefix: List<String>)

    /** [typed] → [alt] (lowercase) shown as [label] (the typed word's case), its Δ, and the words of its sentence up to it. */
    data class Offer(val typed: String, val alt: String, val label: String, val delta: Double, val prefix: List<String>)

    /** The offer on the bar, or null. */
    var current: Offer? = null
        private set

    /** ST5: offers of a pair (typed → alt) the user left until their sentence moved on, in this field; bounded. */
    private val kept = object : LinkedHashMap<String, Int>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean = size > MAX_PAIRS
    }

    /**
     * A word end happened; [before] = the text before the cursor ([whole] = it is the field's whole text before the
     * cursor, not a window that may start mid-sentence). Returns the decision to make, or null: no completed next
     * word in the same open sentence, the sentence ended by a new line, or its start is out of the window.
     */
    fun job(before: String, whole: Boolean): Job? {
        val segs = ContextSegments.segments(before)
        if (segs.isEmpty()) return null
        val words = ContextSegments.words(before)
        if (words.size < 2) return null
        val next = words[words.size - 1]
        val target = words[words.size - 2]
        if (next.end >= before.length) return null // the next word is still being typed
        val si = ContextSegments.segmentAt(segs, target.start)
        if (si < 0 || si != segs.size - 1 || ContextSegments.segmentAt(segs, next.start) != si) return null
        if (!whole && si == 0) return null // its start may lie before the window
        val seg = segs[si]
        if (seg.closed && '\n' in before.substring(seg.end)) return null // a new line ended it: moved on
        val inSeg = ContextSegments.words(before, seg.start, seg.end)
        val ti = inSeg.indexOfFirst { it.start == target.start }
        if (ti < 0) return null
        // HP5: the decision reads the target's sentence and the one before it (the oracle's piece), nothing earlier
        val from = if (si > 0) segs[si - 1].start else seg.start
        return Job(before.substring(from, next.end), target.start - from, target.end - from, target.text, inSeg.subList(0, ti + 1).map { it.text })
    }

    /** The decision for [job] (any thread; touches no state): the offer if Δ ≥ τ_offer, else null. */
    fun decide(job: Job, check: ContextCheck, keep: ContextKeep.Keep?, p: ContextCheck.Params = ContextCheck.Params()): Offer? {
        val c = check.decide(job.text, job.start, job.end, p, keep) ?: return null
        if (c.delta < p.tauOffer) return null
        return Offer(job.typed, c.alt.word, Suggest.matchCase(c.alt.word, job.typed), c.delta, job.prefix)
    }

    /** A decision arrived (main thread): shown if the live text still holds its word in an open sentence (ST3). */
    fun arrive(offer: Offer, before: String, whole: Boolean): Boolean {
        if (suppressed(offer.typed, offer.alt)) return false
        val w = locate(offer, before, whole)
        if (w.where != Where.LIVE || w.contrast) return false
        current = offer // a newer offer replaces an older one (one chip at a time)
        return true
    }

    /**
     * After any edit or cursor move (main thread). [collapsed] = no selection. Returns true when the offer went away:
     * the sentence moved on (counted as a kept original, ST1/ST5), the cursor or the text moved under it (ST2), or its
     * alternative was typed later in the sentence (CO5).
     */
    fun refresh(before: String, whole: Boolean, collapsed: Boolean): Boolean {
        val o = current ?: return false
        if (!collapsed) { current = null; return true }
        val w = locate(o, before, whole)
        when {
            w.where == Where.LIVE && !w.contrast -> return false
            w.where == Where.MOVED_ON -> keptOriginal(o)
        }
        current = null
        return true
    }

    /** VK's own Enter sent the field (a chat's send): the sentence moved on with the original word (ST1/ST5). */
    fun sent(): Boolean {
        val o = current ?: return false
        keptOriginal(o)
        current = null
        return true
    }

    /**
     * The chip was tapped (main thread): the edit — the target replaced and the text typed after it kept, the cursor
     * after the same text (ST4) — or null when the field no longer holds it (nothing to do). The offer is gone either
     * way.
     */
    fun take(before: String, whole: Boolean): List<Out>? {
        val o = current ?: return null
        current = null
        val w = locate(o, before, whole)
        val t = w.word ?: return null
        if (w.where != Where.LIVE) return null
        return listOf(Out.DeleteBefore(before.length - t.start), Out.Commit(o.label + before.substring(t.end)))
    }

    /** The keyboard was hidden or restarted in the same field: the offer goes, the kept pairs stay. */
    fun dropOffer(): Boolean {
        val had = current != null
        current = null
        return had
    }

    /** Another field: the offer and the field's kept pairs go (ST5). */
    fun reset() {
        current = null
        kept.clear()
    }

    /** ST5: the pair was kept twice in this field. */
    fun suppressed(typed: String, alt: String): Boolean = (kept[key(typed, alt)] ?: 0) >= KEEP_LIMIT

    private fun keptOriginal(o: Offer) {
        val k = key(o.typed, o.alt)
        kept[k] = (kept[k] ?: 0) + 1
    }

    private fun key(typed: String, alt: String) = typed.lowercase() + "\u0000" + alt.lowercase()

    private enum class Where { LIVE, MOVED_ON, LOST }

    private class Found(val where: Where, val word: ContextSegments.Word? = null, val contrast: Boolean = false)

    /** Where [o]'s word is in [before]: in the live sentence, in a sentence that already ended, or nowhere. */
    private fun locate(o: Offer, before: String, whole: Boolean): Found {
        val segs = ContextSegments.segments(before)
        if (segs.isEmpty()) return Found(Where.LOST)
        fun holds(i: Int): List<ContextSegments.Word>? {
            if (!whole && i == 0) return null // may start before the window
            val s = segs[i]
            val ws = ContextSegments.words(before, s.start, s.end)
            if (ws.size < o.prefix.size) return null
            for (j in o.prefix.indices) if (ws[j].text != o.prefix[j]) return null
            return ws
        }
        val li = segs.size - 1
        val last = segs[li]
        val ws = holds(li)
        if (ws != null) {
            // closed by its mark and nothing of the next sentence typed yet: still live; a new line: moved on
            if (last.closed && '\n' in before.substring(last.end)) return Found(Where.MOVED_ON)
            val t = ws[o.prefix.size - 1]
            val later = ws.subList(o.prefix.size, ws.size).any { it.text.lowercase() == o.alt }
            return Found(Where.LIVE, t, contrast = later)
        }
        for (i in li - 1 downTo 0) if (segs[i].closed && holds(i) != null) return Found(Where.MOVED_ON)
        return Found(Where.LOST)
    }

    companion object {
        /** ST5: kept this many times in a field = not offered again there. */
        const val KEEP_LIMIT = 2
        const val MAX_PAIRS = 64
        /** Chars the IME reads before the cursor for the offers (a sentence longer than this gets no offer). */
        const val WINDOW = 480
    }
}

/**
 * r13f (offers): the r13e oracle's lexicon over the IME's own objects, without copies (HP1): the Spanish valid-word
 * set ([valid]), the frequency list as the IME ranks it for the region ([list], with its rule words), English for
 * the Spanish-sentence rule ([english], null when not loaded). Read-only: safe off the main thread.
 *
 * Same definitions as the oracle (AS4-tested): a word = valid, listed or a regional rule word; its accent variants =
 * the valid lowercase spellings with the same letters plus the listed / rule words with the same letters; log
 * frequency = −ln(rank + 2), or −ln 250,000 outside the list.
 */
class ContextLexicon(private val list: Suggest, private val valid: ValidWords, private val english: ValidWords?) : ContextCheck.Lex {
    private val regionalByFold: Map<String, List<String>> =
        list.regionalWords.filter { plain(it) }.groupBy { Suggest.fold(it) }

    override fun isWord(w: String): Boolean = valid.contains(w) || (plain(w) && (list.rank(w) != null || w in list.regionalWords))

    override fun accentVariants(w: String): List<String> {
        val out = LinkedHashSet<String>()
        for (v in valid.accentVariants(w)) if (v == v.lowercase()) out += v
        val f = ContextCandidates.bare(w) // = Suggest.fold, with its plain-text fast path (HP5)
        for (m in list.foldMates(w, f)) { val l = m.lowercase(); if (plain(l)) out += l }
        regionalByFold[f]?.let { out += it }
        return out.toList()
    }

    override fun logFreq(w: String): Double = list.rank(w)?.let { -ln(it + 2.0) } ?: RARE

    override fun isEn(w: String): Boolean = english?.contains(w) == true

    /** Built over exactly these objects (the IME rebuilds it when a lexicon reloads or English gets mapped). */
    fun sameAs(list: Suggest, valid: ValidWords, english: ValidWords?): Boolean =
        this.list === list && this.valid === valid && this.english === english

    private fun plain(w: String) = w.isNotEmpty() && w.all { it.isLetter() || it == '\'' }

    companion object {
        val RARE = -ln(250_000.0)
    }
}
