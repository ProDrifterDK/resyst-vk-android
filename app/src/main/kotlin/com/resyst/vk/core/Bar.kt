package com.resyst.vk.core

/**
 * What the suggestion bar shows. Two modes, decided by the text around the cursor:
 *
 * - Prediction (cursor after a separator, or at a sentence start): the user's learned
 *   continuations of the previous word — or their usual sentence openers — then the generic
 *   [Seeds]. Cased by the shift state (an AUTO capital at a sentence start shows "Hola").
 * - Completion (a word is being typed): the confident correction first (A12: it is what space
 *   applies), then the user's words matching the prefix (continuations of the previous word
 *   first), then the static lexicon.
 *
 * [personal] is null whenever the privacy gate is closed: the bar then behaves exactly like r3
 * plus the generic seeds (X1/X3). Duplicates across sources collapse by [Tokens.key] (B3).
 */
object Bar {
    const val LIMIT = 3
    /** Uses after which a word the user types is theirs: space stops "correcting" it (B5). */
    const val HABIT = 2
    private const val OPENERS = "¿¡([{\"«“‘"

    /** The space-correction for [word]: the lexicon's, unless the user habitually types [word]. */
    fun correction(word: String, sentenceStart: Boolean, lexicon: Suggest?, personal: PersonalModel?, lang: Lang): String? {
        val fix = lexicon?.correction(word, sentenceStart) ?: return null
        return if (personal?.knows(lang, word, HABIT) == true) null else fix
    }

    fun words(
        before: CharSequence, after: CharSequence, windowFull: Boolean, lang: Lang,
        lexicon: Suggest?, personal: PersonalModel?, shift: ShiftState, limit: Int = LIMIT,
    ): List<String> {
        if (after.isNotEmpty() && (after[0].isLetter() || after[0] == '\'')) return emptyList() // B6
        val word = Suggest.currentWord(before)
        return if (word.isEmpty()) predictions(before, windowFull, lang, personal, shift, limit)
        else completions(before, word, windowFull, lang, lexicon, personal, limit)
    }

    private fun predictions(before: CharSequence, windowFull: Boolean, lang: Lang, personal: PersonalModel?, shift: ShiftState, limit: Int): List<String> {
        if (before.isNotEmpty()) {
            val c = before[before.length - 1]
            if (!c.isWhitespace() && c !in OPENERS) return emptyList() // "hola," + pick would glue
        }
        val ctx = Tokens.context(before, windowFull)
        val out = LinkedHashMap<String, String>()
        personal?.predict(lang, ctx.prev, ctx.sentenceStart, limit)?.forEach { out.putIfAbsent(Tokens.key(it), it) }
        if (ctx.prev != null) for (w in Seeds.next(lang, ctx.prev)) out.putIfAbsent(Tokens.key(w), w)
        return out.values.take(limit).map { cased(it, shift) }
    }

    private fun completions(
        before: CharSequence, word: String, windowFull: Boolean, lang: Lang,
        lexicon: Suggest?, personal: PersonalModel?, limit: Int,
    ): List<String> {
        val stem = before.subSequence(0, before.length - word.length)
        val ctx = Tokens.context(stem, windowFull)
        val static = lexicon?.suggest(word, limit) ?: emptyList()
        // suggest() leads with the confident fix when there is one
        val confident = static.isNotEmpty() && lexicon?.best(word.lowercase())?.confident == true
        val habit = confident && personal?.knows(lang, word, HABIT) == true
        val out = LinkedHashMap<String, String>()
        fun add(w: String) { out.putIfAbsent(Tokens.key(w), w) }
        if (confident) add(if (habit) word else static[0])
        personal?.complete(lang, word, ctx.prev, limit)?.forEach { add(Suggest.matchCase(it, word)) }
        if (habit) add(static[0])
        static.drop(if (confident) 1 else 0).forEach(::add)
        return out.values.take(limit)
    }

    private fun cased(w: String, shift: ShiftState): String = when (shift) {
        ShiftState.LOCKED -> w.uppercase()
        ShiftState.AUTO, ShiftState.ONCE -> w.replaceFirstChar { it.uppercaseChar() }
        ShiftState.OFF -> w
    }
}
