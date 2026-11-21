package com.resyst.vk.core

import com.resyst.vk.R

/**
 * r11a-fix (F1): what "Borrar lo aprendido" says it will erase — everything the keyboard keeps in
 * files/personal/ (learned words, remembered emails, emoji recents, chosen skin tones, and since r13
 * the corrections the user rejected, rejections.json). The row is enabled when any of them is
 * there; "Nada aprendido todavía" only when all are empty.
 */
object PersonalSummary {
    /**
     * r12 (SA2): [tones] = null while settings still loads the emoji catalog the tones are decoded
     * against and the tones file has content: some may be kept, so the row is not empty and the
     * count reads "…" (never 0).
     */
    data class Counts(val words: Int, val emails: Int, val emojiRecents: Int, val tones: Int?, val rejections: Int = 0, val pairs: Int = 0) {
        val empty: Boolean get() = words <= 0 && emails <= 0 && emojiRecents <= 0 && tones != null && tones <= 0 && rejections <= 0 && pairs <= 0
    }

    /** The tones count while loading (SA2). */
    const val LOADING = "…"

    /** "12 words · 1 email · 8 recent emoji · 2 skin tones · nothing leaves the phone" (zero parts left out). */
    fun text(t: Txt, c: Counts): String {
        val local = t.get(R.string.sum_local)
        if (c.empty) return t.get(R.string.sum_none) + " · " + local
        val parts = listOfNotNull(
            part(t, R.plurals.sum_words, c.words),
            part(t, R.plurals.sum_emails, c.emails),
            part(t, R.plurals.sum_emoji, c.emojiRecents),
            if (c.tones == null) t.get(R.string.sum_tones_loading, LOADING) else part(t, R.plurals.sum_tones, c.tones),
            part(t, R.plurals.sum_rejections, c.rejections),
            part(t, R.plurals.sum_pairs, c.pairs), // r13i (PM5): files/personal/pairs.bin
        )
        return (parts + local).joinToString(" · ")
    }

    private fun part(t: Txt, plural: Int, n: Int): String? = if (n <= 0) null else t.plural(plural, n)
}
