package com.resyst.vk.core

import java.text.Normalizer

/**
 * Offline word completion over a frequency-ordered lexicon (most frequent first).
 * Ranking: accent corrections of the typed word ("esta" → "está") first, then exact-prefix
 * completions by frequency, then accent-insensitive completions ("cancio" → "canción").
 */
class Suggest(words: List<String>) {
    private val words: Array<String> = words.toTypedArray()
    private val folded: Array<String> = Array(words.size) { fold(words[it]) }

    fun suggest(typed: String, limit: Int = 3): List<String> {
        if (typed.isEmpty() || typed.length > MAX_LEN || !typed.all { it.isLetter() || it == '\'' }) return emptyList()
        val p = typed.lowercase()
        val fp = fold(p)
        val corrections = ArrayList<String>()
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
        return (corrections + exact + loose).distinct().take(limit).map { matchCase(it, typed) }
    }

    companion object {
        const val MAX_LEN = 24
        private val MARKS = Regex("\\p{Mn}+")

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
    }
}
