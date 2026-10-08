package com.resyst.vk.core

/**
 * Pure text-editing rules (r10, bet 5): how much "borrar palabra" removes, when a held ⌫
 * switches from characters to words, and the Spanish opening-mark offer (¿ … ? / ¡ … !).
 * The IME turns the answers into InputConnection calls. Failure modes W1–W5, O1–O3.
 */
/**
 * What the edit panel (UX-7) and the ⌫ swipe ask the IME to do. [SELECT] toggles selection
 * mode: while on, the arrows / Inicio / Fin extend the selection instead of moving the cursor.
 */
enum class EditOp { LEFT, RIGHT, UP, DOWN, HOME, END, SELECT, ALL, COPY, CUT, PASTE, DELETE_WORD }

object TextEdit {
    /** Bar label prefix for the "undo the space-correction" offer (UX-5); never a lexicon word. */
    const val UNDO_PREFIX = "↶ "

    /** Auto-repeats of a held ⌫ before it starts deleting whole words (W3). */
    const val WORD_AFTER_REPEATS = 8

    /**
     * Characters to delete so the word before the cursor goes away: trailing whitespace, then
     * trailing punctuation, then the word itself (W1). Never splits a surrogate pair (W2).
     * Empty text → 0.
     */
    fun wordBefore(text: CharSequence): Int {
        var i = text.length
        while (i > 0 && text[i - 1].isWhitespace()) i--
        val afterSpaces = i
        while (i > 0 && !Tokens.isWordChar(text[i - 1]) && !text[i - 1].isWhitespace()) i--
        if (i == afterSpaces) {
            while (i > 0 && Tokens.isWordChar(text[i - 1])) i--
        }
        if (i == text.length) return 0
        // keep pairs whole: never start the deletion on a low surrogate
        if (i > 0 && i < text.length && Character.isLowSurrogate(text[i]) && Character.isHighSurrogate(text[i - 1])) i--
        return text.length - i
    }

    /** Chars one ⌫ press deletes at auto-repeat [repeat] (0 = the press itself) (W3, W4). */
    fun backspaceSpan(before: CharSequence, repeat: Int, secret: Boolean): Int {
        if (before.isEmpty()) return 0
        if (!secret && repeat >= WORD_AFTER_REPEATS) return wordBefore(before).coerceAtLeast(1)
        val n = before.length
        return if (n >= 2 && Character.isSurrogatePair(before[n - 2], before[n - 1])) 2 else 1
    }

    /**
     * The opening-mark offer: [before] ends with `?` or `!` (possibly after the closing mark
     * the user just typed) closing a sentence that has no matching `¿`/`¡`. Returns where to
     * insert the opener (an index into [before]) and which, or null (O1, O3).
     */
    data class Opener(val at: Int, val mark: Char, val closing: Char)

    fun opener(before: CharSequence, windowFull: Boolean): Opener? {
        if (before.isEmpty()) return null
        val closing = before[before.length - 1]
        val mark = when (closing) { '?' -> '¿'; '!' -> '¡'; else -> return null }
        val body = before.subSequence(0, before.length - 1)
        // sentence start: after the last terminator / newline before the body
        var s = body.length
        while (s > 0) {
            val c = body[s - 1]
            if (c == '.' || c == '\n' || c == '…' || c == '?' || c == '!' ) break
            s--
        }
        if (s == 0 && windowFull) return null // O3: the start is outside what we can read
        val sentence = body.subSequence(s, body.length)
        if (sentence.any { it == mark }) return null // already opened (O1)
        if (sentence.isBlank()) return null // a lone "?"
        // "a=b?c", URLs: the closing mark must follow a letter, digit, quote or closing bracket
        val prev = body[body.length - 1]
        if (!(prev.isLetterOrDigit() || prev in "\"'»”)")) return null
        if (sentence.contains("://") || sentence.contains('=') || sentence.contains('/')) return null
        var at = s
        while (at < body.length && body[at].isWhitespace()) at++ // O2: after the leading spaces
        // need at least one word in the sentence
        if (sentence.none { it.isLetter() }) return null
        return Opener(at, mark, closing)
    }

    /**
     * The rewrite for [o] as edits on the text before the cursor: delete the tail from the
     * insertion point and commit it back with the opener in front (one batch edit, O2).
     */
    fun applyOpener(before: CharSequence, o: Opener): List<Out> {
        val tail = before.subSequence(o.at, before.length).toString()
        return listOf(Out.DeleteBefore(tail.length), Out.Commit(o.mark + tail))
    }

    /** The bar label for an opener offer: "¿…?" / "¡…!". */
    fun openerLabel(o: Opener): String = "${o.mark}…${o.closing}"
}
