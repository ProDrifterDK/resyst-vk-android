package com.resyst.vk.core

import kotlin.math.ln

/**
 * r10 bilingual suggestions (F-1, bet 4): which language the user is writing in right now,
 * read from the last [WINDOW] finished words before the cursor. The keyboard language is the
 * default; the other one takes over only on clear evidence (BL2/BL3): at least [MIN_VOTES] of
 * the window's words lean to it and their summed lean beats [MARGIN].
 *
 * A word's lean is the log ratio of its frequency ranks in both lexicons (capped at [CAP]), so
 * words both languages use ("no", "me", "a") weigh almost nothing and "the"/"think" vs
 * "que"/"tengo" weigh a lot. A word the user habitually types in one language only counts for
 * it too. Pure and JVM-testable; the lexicons come in as lookups.
 */
object BiLang {
    const val WINDOW = 3
    const val MIN_VOTES = 2
    const val MARGIN = 2.0
    const val CAP = 3.0
    /** A word clearly leans to a language when its log rank ratio exceeds this. */
    const val LEAN = 0.7
    /** Rank given to a word missing from a lexicon (just past the longest list). */
    const val ABSENT = 30_000
    private const val SMOOTH = 50.0

    /** The last [WINDOW] whole words before [before] (the word being typed is excluded), lowercase. */
    fun recentWords(before: CharSequence, windowFull: Boolean): List<String> {
        val text = before.toString()
        var end = text.length
        // drop the word under the cursor: it is being typed (and may be a typo)
        while (end > 0 && Tokens.isWordChar(text[end - 1])) end--
        val head = text.substring(0, end)
        val chunks = head.split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }
        // a full read window may start mid-word: its first chunk doesn't count (M8)
        val usable = if (windowFull && chunks.isNotEmpty() && head.isNotEmpty() && Tokens.isWordChar(head[0])) chunks.drop(1) else chunks
        return usable.map { it.trim('\'').lowercase() }.filter { Tokens.learnable(it) }.takeLast(WINDOW)
    }

    /**
     * Lean of [word] toward [lang] versus [other]: > 0 favors [lang]. [rank] answers a lexicon
     * rank (null = absent); [knows] = the user typed it in that language at least twice.
     */
    fun lean(word: String, lang: Lang, other: Lang, rank: (Lang, String) -> Int?, knows: (Lang, String) -> Boolean): Double {
        val r1 = rank(lang, word) ?: ABSENT
        val r2 = rank(other, word) ?: ABSENT
        var v = ln((r2 + SMOOTH) / (r1 + SMOOTH))
        val k1 = knows(lang, word)
        val k2 = knows(other, word)
        if (k1 && !k2) v += 1.0 else if (k2 && !k1) v -= 1.0
        return v.coerceIn(-CAP, CAP)
    }

    /** The language to suggest / correct in, given the keyboard's [keyboard] language. */
    fun detect(
        words: List<String>, keyboard: Lang,
        rank: (Lang, String) -> Int?, knows: (Lang, String) -> Boolean = { _, _ -> false },
    ): Lang {
        val other = other(keyboard)
        var sum = 0.0
        var votes = 0
        for (w in words.takeLast(WINDOW)) {
            val l = lean(w, other, keyboard, rank, knows)
            sum += l
            if (l >= LEAN) votes++
        }
        return if (votes >= MIN_VOTES && sum >= MARGIN) other else keyboard
    }

    fun other(l: Lang): Lang = if (l == Lang.ES) Lang.EN else Lang.ES
}
