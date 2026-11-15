package com.resyst.vk.core

/**
 * r13f (offers, CO4): words the context offers never touch. r13b's RetroKeep (spike/r13e-ngram, ce8792c),
 * unchanged but for the name.
 *
 * Three layers, all checked on the TYPED word (accent-insensitive, so `bacan` is kept like `bacán`):
 * - [SEED]: the Chilean keep-list the owner curates (fome → come and cachai → chacha are measured failures);
 * - the region's protected words (r13a RW2, [Regional.protectedWords]: cachai, fome, bacán, po, ya, dale …);
 * - the user's own words, passed by the caller (the IME: the words he kept against a correction).
 * [PHRASES] keep both of their words when they appear together (`al tiro`).
 */
object ContextKeep {
    /** The seed Chilean keep-list (owner-extended later). Folded on use. */
    val SEED: Set<String> = linkedSetOf(
        "fome", "fomes", "cachai", "cachái", "cachay", "cacha", "bacán", "bacanes", "pega", "pegas", "harto", "harta",
        "hartos", "hartas", "po", "pos", "altiro", "pololo", "polola", "pololos", "pololas", "pololear", "weón", "weona",
        "weones", "weá", "wea", "wn", "wena", "wenas", "culiao", "culiá", "culiaos", "sipo", "nopo", "ya", "ctm",
        "pucha", "chuta", "cuático", "filo", "flaite", "carrete", "pituto", "luca", "lucas", "guagua", "cabros", "cabro",
    )

    /** Multi-word keep entries: each word is kept when the whole phrase is present around it. */
    val PHRASES: List<List<String>> = listOf(listOf("al", "tiro"), listOf("la", "raja"))

    class Keep(words: Collection<String>, private val phrases: List<List<String>> = PHRASES) {
        private val folded: Set<String> = words.mapTo(HashSet()) { fold(it) }

        /** The typed word itself is on a keep-list. */
        fun word(typed: String): Boolean = fold(typed) in folded

        /**
         * [words] = the folded words of the text, [i] = the target's index: kept if the word is listed or it is part
         * of a listed phrase at this position.
         */
        fun keeps(words: List<String>, i: Int): Boolean {
            if (word(words[i])) return true
            val w = fold(words[i])
            for (p in phrases) for ((j, pw) in p.withIndex()) {
                if (pw != w) continue
                val s = i - j
                if (s < 0 || s + p.size > words.size) continue
                if (p.indices.all { fold(words[s + it]) == p[it] }) return true
            }
            return false
        }

        val size: Int get() = folded.size

        /** [Suggest.fold] (accents off, ñ kept, lowercase) through [ContextCandidates.bare]: the same, without regex (HP5). */
        private fun fold(w: String) = ContextCandidates.bare(w)
    }

    /** The keep set for a region's protected words plus the user's own words. */
    fun of(regionalProtected: Collection<String>, personal: Collection<String> = emptyList()): Keep =
        Keep(SEED + regionalProtected + personal)
}
