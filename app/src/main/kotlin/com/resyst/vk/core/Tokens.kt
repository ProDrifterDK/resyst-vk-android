package com.resyst.vk.core

/**
 * Word boundaries for the personal model: which word just finished, and what context (the
 * previous word, or a sentence start) the next word will have. A "chunk" is a whitespace-
 * delimited run; only chunks that are plain words ([learnable]) count — pieces of emails,
 * URLs, numbers and codes are neither learned nor used as context (M5).
 *
 * [windowFull] = the text came from a read window that was filled completely, so its first
 * chunk may be the tail of a longer word (M8): it never counts.
 */
object Tokens {
    data class Context(val prev: String?, val sentenceStart: Boolean)

    /** [word] as typed; [prev] already a [key]. */
    data class Finished(val word: String, val prev: String?, val sentenceStart: Boolean)

    const val MIN_LEN = 2
    const val MAX_LEN = 24

    private const val TERMINATORS = ".!?…"
    private const val OPENERS = "¿¡([{\"«“‘"
    private const val SOFT = ",;:\"»”’)]}-—"

    fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '\''

    fun key(word: String): String = word.lowercase()

    /** A plain word: 2–24 chars, letters with inner apostrophes only (don't), ≥ 2 letters. */
    fun learnable(w: String): Boolean {
        if (w.length < MIN_LEN || w.length > MAX_LEN) return false
        if (!w.first().isLetter() || !w.last().isLetter()) return false
        return w.all { it.isLetter() || it == '\'' } && w.count { it.isLetter() } >= MIN_LEN
    }

    /** Context for a word that would start at the end of [text]. */
    fun context(text: CharSequence, windowFull: Boolean): Context {
        var i = text.length
        while (i > 0) {
            val c = text[i - 1]
            if (c in TERMINATORS || c == '\n') return Context(null, true)
            if (c.isWhitespace() || c in OPENERS || c in SOFT) i-- else break
        }
        if (i == 0) return Context(null, !windowFull)
        val end = i
        while (i > 0 && !text[i - 1].isWhitespace()) i--
        if (i == 0 && windowFull) return Context(null, false)
        val chunk = text.subSequence(i, end).toString().trimStart { it in OPENERS }
        return if (learnable(chunk)) Context(key(chunk), false) else Context(null, false)
    }

    /**
     * The word that the last separator of [text] finished, or null when the text ends inside a
     * word or the finished chunk isn't a plain word.
     */
    fun finished(text: CharSequence, windowFull: Boolean): Finished? {
        if (text.isEmpty() || isWordChar(text[text.length - 1])) return null
        var i = text.length
        while (i > 0) {
            val c = text[i - 1]
            if (c.isWhitespace() || c in TERMINATORS || c in SOFT) i-- else break
        }
        if (i == 0) return null
        val end = i
        while (i > 0 && !text[i - 1].isWhitespace()) i--
        if (i == 0 && windowFull) return null
        val chunk = text.subSequence(i, end).toString().trimStart { it in OPENERS }
        if (!learnable(chunk)) return null
        val ctx = context(text.subSequence(0, i), windowFull)
        return Finished(chunk, ctx.prev, ctx.sentenceStart)
    }
}
