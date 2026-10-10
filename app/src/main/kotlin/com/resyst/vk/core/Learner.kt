package com.resyst.vk.core

/** Replays engine [Out]s on a plain string: what the text before the cursor becomes. */
object Edits {
    fun apply(before: String, outs: List<Out>): String {
        var t = before
        for (o in outs) t = when (o) {
            is Out.Commit -> t + o.text
            is Out.DeleteBefore -> t.dropLast(o.chars.coerceIn(0, t.length))
            Out.Backspace -> when {
                t.isEmpty() -> t
                t.length >= 2 && Character.isSurrogatePair(t[t.length - 2], t[t.length - 1]) -> t.dropLast(2)
                else -> t.dropLast(1)
            }
            is Out.Action, Out.EnterKey -> t
        }
        return t
    }
}

/**
 * The learning hook: after every edit the IME applies, decides whether a word was just
 * finished (space, punctuation, enter/send, a picked suggestion, a space-correction) and feeds
 * it to the personal model. [model] returns null whenever learning is not allowed for the
 * current field / settings — the gate is re-evaluated on every edit (X1–X3).
 *
 * A word is learned once: an edit that leaves the same finished word in place (a second
 * space, the double-space period) learns nothing. A backspace right after a learning edit
 * takes it back (M10): the user is fixing it — reverting a correction, or deleting the space
 * to keep typing the word.
 */
class Learner(private val model: () -> PersonalModel?) {
    enum class Edit { KEY, PICK, BACKSPACE }

    private class Learned(val lang: Lang, val event: Tokens.Finished, val after: String)
    private var last: Learned? = null
    /** r11: a kept word restored without its space (⌫ revert): marked when that word finishes. */
    private var keepNext: String? = null

    /**
     * [windowFull] = [before] was read with a full window (may start mid-word).
     * Returns true when the model changed (the caller persists it).
     */
    fun afterEdit(lang: Lang, before: String, outs: List<Out>, windowFull: Boolean, kind: Edit): Boolean {
        val m = model()
        val pending = last
        last = null
        if (m == null || outs.isEmpty()) return false
        if (kind == Edit.BACKSPACE && pending != null && pending.lang == lang && sameTail(pending.after, before)) {
            m.unlearn(lang, pending.event.prev, pending.event.word, pending.event.sentenceStart)
            return true
        }
        if (kind == Edit.BACKSPACE) return false // deleting never finishes a new word
        val after = Edits.apply(before, outs)
        val ends = outs.any { it is Out.Action || it == Out.EnterKey }
        val now = Tokens.finished(if (ends) "$after\n" else after, windowFull) ?: return false
        val was = Tokens.finished(before, windowFull)
        if (was != null && was.at == now.at && was.word == now.word) return false
        m.learn(lang, now.prev, now.word, now.sentenceStart)
        last = Learned(lang, now, after)
        val k = keepNext
        keepNext = null
        if (k != null && Tokens.key(now.word) == k) m.keep(lang, now.word)
        return true
    }

    /**
     * r11 (K1/K2): the user reverted a space-correction and kept [word] as typed — by ⌫ (the
     * word comes back without its space) or by the ↶ chip (word + space). The correction's learn is
     * taken back in [undoLang] (M10, for both paths), and [word] becomes the user's in [lang]: at
     * once if [outs] finished it, else when the next edit finishes that same word.
     */
    fun kept(undoLang: Lang, lang: Lang, before: String, outs: List<Out>, windowFull: Boolean, word: String): Boolean {
        val m = model()
        val pending = last
        last = null
        keepNext = null
        if (m == null) return false
        var changed = false
        if (pending != null && pending.lang == undoLang && sameTail(pending.after, before)) {
            m.unlearn(undoLang, pending.event.prev, pending.event.word, pending.event.sentenceStart)
            changed = true
        }
        val after = Edits.apply(before, outs)
        val now = Tokens.finished(after, windowFull)
        if (now != null && Tokens.key(now.word) == Tokens.key(word)) {
            m.learn(lang, now.prev, now.word, now.sentenceStart)
            m.keep(lang, now.word)
            last = Learned(lang, now, after)
            changed = true
        } else if (Tokens.learnable(word)) keepNext = Tokens.key(word)
        return changed
    }

    /** Forget the pending undo (the cursor moved, the field changed). */
    fun reset() { last = null; keepNext = null }

    private fun sameTail(a: String, b: String): Boolean {
        val n = minOf(a.length, b.length, 32)
        return n > 0 && a.regionMatches(a.length - n, b, b.length - n, n) || (a.isEmpty() && b.isEmpty())
    }
}
