package com.resyst.vk.core

/**
 * r13: the IME's correction wiring, without Android — which language is being written ([detect],
 * r10 BiLang), which lexicon corrects it and with which other-language guard ([lexiconFor]), the
 * vocabularies the user's words come from ([vocab], BL8) and the space corrector itself
 * ([corrector]). ResystImeService runs exactly this; the session replay (Round13ReplayTest) builds
 * the same object over the shipped assets, so the replay measures the shipped corrector.
 */
class Writing(
    /** [Lang]'s lexicon, loading it if needed (null while it loads). */
    private val get: (Lang) -> Suggest?,
    /** [Lang]'s lexicon only if already loaded (never starts a load). */
    private val peek: (Lang) -> Suggest?,
) {
    /** The effective settings (phone + tema + mode). */
    var settings: KbSettings = KbSettings()
    /** The field allows suggestions / corrections (FieldPolicy). */
    var suggestField: Boolean = true
    /** The learned model for OFFERING (read gate), or null. */
    var personal: () -> PersonalModel? = { null }
    /** r13 (LR5): the rejected corrections (read gate), or null. */
    var rejections: () -> Rejections? = { null }
    /** The language being written right now (r10 bet 4): the keyboard's, or the other by [detect]. */
    var lang: Lang = Lang.ES
    /**
     * r13d (TB2): the touch record of the word being typed (the border keys of each letter's touch), or
     * null; space and the bar read it for the word they correct ([TouchTrail.forWord]).
     */
    var touch: () -> TouchTrail? = { null }

    /** r11 (BL8): the writing language first, both with bilingual suggestions on. */
    fun vocab(): List<Lang> = if (settings.bilingual && settings.suggest) listOf(lang, BiLang.other(lang)) else listOf(lang)

    /** The language the text before the cursor is being written in ([windowFull]: see [Tokens]). */
    fun detect(before: CharSequence, windowFull: Boolean): Lang {
        val st = settings
        if (!st.bilingual || !st.suggest || !suggestField) return st.lang
        val p = personal()
        return BiLang.detect(
            BiLang.recentWords(before, windowFull), st.lang,
            rank = { l, w -> peek(l)?.rank(w) },
            knows = { l, w -> p?.knows(l, w, Bar.HABIT) == true },
        )
    }

    /**
     * [l]'s lexicon with the other language as its guard (BL4, r13 RW1); without bilingual, no guard (BL6).
     * r13d (OR1): the user's own words, read through the personal READ gate, are its correction targets.
     */
    fun lexiconFor(l: Lang): Suggest? {
        val main = get(l) ?: get(settings.lang) ?: return null
        main.foreign = if (settings.bilingual) peek(BiLang.other(l)) else null
        main.mine = learned()
        return main
    }

    private data class LearnedKey(val model: PersonalModel, val version: Long, val vocab: List<Lang>)
    private var learnedKey: LearnedKey? = null
    private var learnedList: List<String> = emptyList()

    /** r13d (OR1, OR4): [PersonalModel.targets] of the readable model, rebuilt only when it or the vocabularies change. */
    private fun learned(): List<String> {
        val p = personal() ?: return emptyList()
        val key = LearnedKey(p, p.version, vocab())
        if (key != learnedKey) {
            learnedList = p.targets(key.vocab, Bar.HABIT)
            learnedKey = key
        }
        return learnedList
    }

    /** r13d (TB3): the touch record for [word] (space and the bar read the same one), or null. */
    fun near(word: String): Array<String>? = touch()?.forWord(word)

    /** The space corrector for these settings and this field, or null when space never corrects here. */
    fun corrector(): Corrector? {
        val st = settings
        if (!st.suggest || !st.spaceCorrects || !suggestField) return null
        return Corrector { word, sentenceStart ->
            Bar.correction(
                word, sentenceStart, lexiconFor(lang), personal(), lang, clean = st.profanityFilter, vocab = vocab(),
                rejected = rejections(), near = near(word),
            )
        }
    }
}
