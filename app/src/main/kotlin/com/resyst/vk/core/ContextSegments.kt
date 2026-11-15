package com.resyst.vk.core

/**
 * r13f (offers): words and sentences of the text before the cursor, for the context offers. r13b's RetroSegments
 * (spike/r13e-ngram, ce8792c), unchanged but for the name and without the settled rule (no automatic edits here).
 *
 * Boundaries: `. ? ! …` followed (after optional closing quotes / brackets) by whitespace or the end of the text,
 * and a new line itself. Not a boundary: a decimal or anything glued to the next character (`3.5`, `kv.resyst.cl`,
 * `a@b.cl`), a single-letter abbreviation (`J. Pérez`, `a. m.`), a known abbreviation (`Sr.`, `Dra.`, `pág.`), and
 * `etc.` unless the next word starts with a capital. A wrong boundary only shortens the context.
 */
object ContextSegments {
    /** [start, end) in the text; [text] as written. */
    data class Word(val start: Int, val end: Int, val text: String)

    /**
     * A sentence: [start, end) covers its words and its closing mark (if any; a new line is not included).
     * [closed] = a boundary ends it (mark or new line); the last segment of a text without one is open (the live one
     * when the cursor is at the end). [paragraph] = index of the paragraph (new-line separated block) it belongs to.
     */
    data class Segment(val start: Int, val end: Int, val closed: Boolean, val paragraph: Int)

    /** Abbreviations whose period never ends a sentence (lowercase, without the period). */
    val ABBREVIATIONS: Set<String> = setOf(
        "sr", "sra", "srta", "sres", "dr", "dra", "drs", "lic", "ing", "prof", "arq", "abog", "ud", "uds", "vd", "vds",
        "pág", "pag", "págs", "núm", "num", "nro", "art", "cap", "vol", "fig", "ej", "aprox", "av", "avda", "depto",
        "dpto", "tel", "cel", "cía", "ltda", "admin", "máx", "mín", "mr", "mrs", "ms", "st", "vs", "approx", "dept", "jr",
    )

    private const val MARKS = ".?!…"
    private const val CLOSERS = "\"')]}»”’"

    fun isWordChar(c: Char): Boolean = c.isLetter() || c == '\'' || c == '’'

    /** Every word (letters and apostrophes; digits are not words) of [text] in [from, to). */
    fun words(text: CharSequence, from: Int = 0, to: Int = text.length): List<Word> {
        val out = ArrayList<Word>()
        var i = from
        while (i < to) {
            if (text[i].isLetter()) {
                val s = i
                while (i < to && isWordChar(text[i])) i++
                var e = i
                while (e > s && !text[e - 1].isLetter()) e-- // a trailing apostrophe is not part of the word
                // a word glued to digits (`3er`, `abc123`) is not a word the user typed as one
                val glued = (s > 0 && text[s - 1].isDigit()) || (i < text.length && text[i].isDigit())
                if (!glued) out += Word(s, e, text.substring(s, e))
            } else i++
        }
        return out
    }

    /** Whether the mark at [i] ends a sentence (see the class notes). */
    fun isBoundary(text: CharSequence, i: Int): Boolean {
        val c = text[i]
        if (c == '\n') return true
        if (c !in MARKS) return false
        var j = i + 1
        while (j < text.length && text[j] in MARKS) j++ // "...", "?!": the last one decides
        if (j > i + 1) return isBoundary(text, j - 1)
        while (j < text.length && text[j] in CLOSERS) j++
        if (j < text.length && !text[j].isWhitespace()) return false // glued: decimal, URL, e-mail, "a.m."
        if (c != '.') return true
        if (i > 0 && text[i - 1] == '.') return true // the end of an ellipsis
        // the word right before the period
        var s = i
        while (s > 0 && isWordChar(text[s - 1])) s--
        val w = text.substring(s, i).lowercase()
        if (w.length == 1 && w[0].isLetter()) return false // J. Pérez, a. m.
        if (w in ABBREVIATIONS) return false
        if (w == "etc") {
            var k = j
            while (k < text.length && text[k].isWhitespace() && text[k] != '\n') k++
            return k >= text.length || text[k] == '\n' || text[k].isUpperCase() || text[k] in "¿¡"
        }
        return true
    }

    /** The text's segments, in order. Blank lines and whitespace-only stretches belong to no segment. */
    fun segments(text: CharSequence): List<Segment> {
        val out = ArrayList<Segment>()
        var paragraph = 0
        var start = -1
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n') {
                if (start >= 0) out += Segment(start, trimEnd(text, start, i), true, paragraph)
                start = -1
                // a new paragraph after a new line
                paragraph++
                i++
                continue
            }
            if (start < 0 && !c.isWhitespace()) start = i
            if (start >= 0 && c in MARKS && isBoundary(text, i)) {
                var e = i + 1
                while (e < text.length && (text[e] in MARKS || text[e] in CLOSERS)) e++
                out += Segment(start, e, true, paragraph)
                start = -1
                i = e
                continue
            }
            i++
        }
        if (start >= 0) {
            val e = trimEnd(text, start, text.length)
            if (e > start) out += Segment(start, e, false, paragraph)
        }
        return renumber(out)
    }

    /** Paragraph numbers counted over non-empty lines only (blank lines do not create empty paragraphs). */
    private fun renumber(segs: List<Segment>): List<Segment> {
        val map = HashMap<Int, Int>()
        return segs.map { s -> s.copy(paragraph = map.getOrPut(s.paragraph) { map.size }) }
    }

    private fun trimEnd(text: CharSequence, s: Int, e: Int): Int {
        var x = e
        while (x > s && text[x - 1].isWhitespace()) x--
        return x
    }

    /** Index of the segment holding the char at [pos], or -1. */
    fun segmentAt(segs: List<Segment>, pos: Int): Int = segs.indexOfFirst { pos >= it.start && pos < it.end }
}
