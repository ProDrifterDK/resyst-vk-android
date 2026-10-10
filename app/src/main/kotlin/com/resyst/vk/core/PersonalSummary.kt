package com.resyst.vk.core

/**
 * r11a-fix (F1): what "Borrar lo aprendido" says it will erase — everything the keyboard keeps in
 * files/personal/ (learned words, remembered emails, emoji recents, chosen skin tones). The row is
 * enabled when any of them is there; "Nada aprendido todavía" only when all are empty.
 */
object PersonalSummary {
    const val LOCAL = "nada sale del teléfono"

    /**
     * r12 (SA2): [tones] = null while settings still loads the emoji catalog the tones are decoded
     * against and the tones file has content: some may be kept, so the row is not empty and the
     * count reads "…" (never 0).
     */
    data class Counts(val words: Int, val emails: Int, val emojiRecents: Int, val tones: Int?) {
        val empty: Boolean get() = words <= 0 && emails <= 0 && emojiRecents <= 0 && tones != null && tones <= 0
    }

    /** The tones count while loading (SA2). */
    const val LOADING = "…"

    /** "12 palabras · 1 correo · 8 emojis recientes · 2 tonos · nada sale del teléfono" (zero parts left out). */
    fun text(c: Counts): String {
        if (c.empty) return "Nada aprendido todavía · $LOCAL"
        val parts = listOfNotNull(
            part(c.words, "palabra", "palabras"),
            part(c.emails, "correo", "correos"),
            part(c.emojiRecents, "emoji reciente", "emojis recientes"),
            if (c.tones == null) "$LOADING tonos" else part(c.tones, "tono", "tonos"),
        )
        return (parts + LOCAL).joinToString(" · ")
    }

    private fun part(n: Int, one: String, many: String): String? = when {
        n <= 0 -> null
        n == 1 -> "1 $one"
        else -> "$n $many"
    }
}
