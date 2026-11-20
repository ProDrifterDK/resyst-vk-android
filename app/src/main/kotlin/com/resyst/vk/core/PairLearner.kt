package com.resyst.vk.core

/**
 * r13i (PM1-PM3): when and what the pair memory learns. ResystImeService drives it; the JVM tests and the private
 * eval drive the same object (S4Chat's composer mirrors the service's calls).
 *
 * A sentence is learned only when the user is done with it, read from the field as it stands then (PM3):
 * - (a) [edited] with a word end: it is closed (mark or new line) and a word of the next sentence was just finished;
 * - (b) [sent]: VK's own Enter sent the field;
 * - (c) [emptiedFromOutside]: the app emptied the field (its send button), from the text VK saw after its own last edit.
 * Never: a field left with a draft, a deletion VK made ([forgetSeen]), a field VK pasted into ([pasted]) until it is
 * empty again, a sentence already learned since the field was last empty. Only Spanish sentences (CO3's rule) of ≥ [MIN_WORDS] words.
 *
 * What (PM1, PM2): [ContextTokens] tokens; a word a dictionary knows or the user owns ([Words]) is kept as text, any
 * other as `<unk>`; numbers are `<n>`; the words of the space corrections the engine applied in this field ([edited]'s
 * `fixes`: unconfirmed edits of the keyboard) are masked (no count, no pair across them).
 *
 * The write gate is [memory]: null whenever this field / these settings may not learn (secret, incognito, opted out,
 * a switch off) — re-read on every call, like [Learner]. Main thread only.
 */
class PairLearner(private val memory: () -> PairMemory?) {
    /** The lexicon hooks (lowercase words). */
    interface Words {
        /** A Spanish word (valid, listed or a regional rule word): [ContextLexicon.isWord]. */
        fun isEs(w: String): Boolean
        /** An English word (valid): [ContextLexicon.isEn]. */
        fun isEn(w: String): Boolean
        /** The user's own word: typed ≥ 2 times or kept ([PersonalModel.owns]). */
        fun owned(w: String): Boolean
    }

    /** Sentences learned since the field was last empty (their text), most recent last; bounded. */
    private val learned = ArrayDeque<String>()
    /** The text before the cursor after VK's last edit, and whether it was the field's whole text before the cursor. */
    private var seen: String? = null
    private var seenWhole = false
    /** VK pasted into this field: nothing is learned in it until it is empty again (pasted text is not the user's). */
    private var hold = false

    /**
     * After every VK edit, without reading the field again: [before] = the text before the cursor the edit was computed
     * on (the key path's short read; [windowFull] = it may start mid-text), [outs] = the edit. The text VK last saw is
     * carried forward from the last longer read while it still ends with [before] (else it restarts from [before]): it
     * is what (c) learns if the app empties the field.
     */
    fun track(before: String, outs: List<Out>, windowFull: Boolean) {
        if (memory() == null) { seen = null; return }
        if (before.isEmpty() && !windowFull) { learned.clear(); hold = false } // the edit started in an empty field
        val s = seen
        val base: String
        if (s != null && s.endsWith(before)) base = s else { base = before; seenWhole = !windowFull }
        var t = Edits.apply(base, outs)
        if (t.length > MAX_SEEN) { t = t.takeLast(MAX_SEEN); seenWhole = false }
        seen = t
        if (t.isBlank()) { learned.clear(); hold = false }
    }

    /**
     * After an edit with a longer read of the field: [after] = the text before the cursor now ([whole] = not a window
     * that may start mid-sentence). [wordEnd] = the edit ended a word. [words] = null while the lexicon is not loaded
     * (nothing is learned then). [fixes] = the words of this field's unconfirmed space corrections. True = learned.
     */
    fun edited(after: String, whole: Boolean, wordEnd: Boolean, words: Words?, fixes: Collection<String>, now: Long): Boolean {
        val m = memory()
        if (m == null) { seen = null; return false }
        seen = after
        seenWhole = whole
        if (after.isBlank()) { learned.clear(); hold = false; return false }
        if (!wordEnd || words == null || hold) return false
        val segs = ContextSegments.segments(after)
        if (segs.size < 2) return false
        val pi = segs.size - 2
        val prev = segs[pi]
        val last = segs[pi + 1]
        if (!prev.closed || (!whole && pi == 0)) return false
        // a word of the next sentence was finished (a separator after it)
        if (ContextSegments.words(after, last.start, last.end).none { it.end < after.length }) return false
        return learnSegment(m, after.substring(prev.start, prev.end), words, fixes, now)
    }

    /** (b) VK's Enter sent the field; [after] = the text before the cursor once the edits before the send applied. */
    fun sent(after: String, whole: Boolean, words: Words?, fixes: Collection<String>, now: Long): Boolean {
        val m = memory()
        seen = null
        if (m == null || words == null || hold) return false
        return learnTail(m, after, whole, words, fixes, now)
    }

    /** (c) The field was emptied, not by VK (the app sent the message and cleared it). */
    fun emptiedFromOutside(words: Words?, fixes: Collection<String>, now: Long): Boolean {
        val t = seen
        val whole = seenWhole
        seen = null
        val m = memory()
        val done = if (m == null || words == null || t == null || hold) false else learnTail(m, t, whole, words, fixes, now)
        learned.clear()
        hold = false
        return done
    }

    /** VK deleted text it cannot follow (⌫ over a selection, the edit panel's cut / delete-word): nothing to send. */
    fun forgetSeen() { seen = null }

    /** VK pasted text (the clipboard chip or panel, the edit panel): this field learns nothing until it is empty. */
    fun pasted() { hold = true }

    /** Another field. */
    fun reset() {
        seen = null
        learned.clear()
        hold = false
    }

    /** The last sentence of [text] (and the closed one before it, if not learned yet). */
    private fun learnTail(m: PairMemory, text: String, whole: Boolean, words: Words, fixes: Collection<String>, now: Long): Boolean {
        val segs = ContextSegments.segments(text)
        if (segs.isEmpty()) return false
        var any = false
        val li = segs.size - 1
        if (li >= 1 && segs[li - 1].closed && (whole || li - 1 > 0)) {
            any = learnSegment(m, text.substring(segs[li - 1].start, segs[li - 1].end), words, fixes, now) || any
        }
        if (whole || li > 0) any = learnSegment(m, text.substring(segs[li].start, segs[li].end), words, fixes, now) || any
        return any
    }

    private fun learnSegment(m: PairMemory, seg: String, words: Words, fixes: Collection<String>, now: Long): Boolean {
        val key = seg.trim()
        if (key in learned) return false
        val ws = ContextSegments.words(key).map { it.text }
        if (ws.size < MIN_WORDS || !ContextCheck.spanishSegment(ws, words::isEs, words::isEn)) return false
        val masked = fixes.mapTo(HashSet()) { it.lowercase() }
        var any = false
        for (s in ContextTokens.tokenize(key)) {
            val toks = s.toks.map { t ->
                val x = t.text
                when {
                    x == ContextTokens.COMMA || x == ContextTokens.NUM -> x
                    x in masked -> null
                    words.isEs(x) || words.isEn(x) || words.owned(x) -> x
                    else -> ContextTokens.UNK
                }
            }
            any = m.learn(toks, bos = true, eos = true, now = now) || any
        }
        learned.addLast(key)
        while (learned.size > LEARNED_MEMORY) learned.removeFirst()
        return any
    }

    companion object {
        /** A sentence needs this many words to be learned. */
        const val MIN_WORDS = 2
        const val LEARNED_MEMORY = 16
        /** The text VK carries forward for (c) (twice the offers' read). */
        const val MAX_SEEN = 2 * ContextOffers.WINDOW
    }
}
