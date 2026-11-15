package com.resyst.vk.core

import java.text.Normalizer
import java.util.Locale

/**
 * r13f (offers): the n-gram tokenizer, the runtime twin of tools/ngram-build/NgramBuild.java (r13e spike, ce8792c,
 * unchanged). Both must give the same tokens for the same text, or the model is queried with tokens it never
 * counted; r13f/offers/tokenizer-cases.tsv pins them (AS3).
 *
 * NFC, lowercase; a word = letters with inner apostrophes (’ → '); any letter/digit run with a digit = [NUM];
 * `, ; : ( ) [ ] — –` and a spaced hyphen = [COMMA] (collapsed, never first or last); `. ? ! …` followed by
 * (closing quotes and) whitespace or the end, and a new line, end a sentence, except a single period after a
 * one-letter word or a known abbreviation ([ContextSegments.ABBREVIATIONS]); ¿ ¡ quotes, symbols, emoji are dropped.
 */
object ContextTokens {
    const val BOS = "<s>"
    const val EOS = "</s>"
    const val COMMA = ","
    const val NUM = "<n>"
    const val UNK = "<unk>"

    private const val MARKS = ".?!…"
    private const val CLOSERS = "\"')]}»”’"
    private const val COMMAS = ",;:()[]—–"

    /** One token: [start, end) in the source text (a comma: its mark), the sentence it belongs to. */
    data class Tok(val text: String, val start: Int, val end: Int)

    /** A sentence's tokens; [closed] = a boundary (mark or new line) ended it, not the end of the text. */
    data class Sentence(val toks: List<Tok>, val closed: Boolean)

    /** The sentences of [text] as token lists (no [BOS]/[EOS]); the corpus-side view. */
    fun sentences(text: String): List<List<String>> = tokenize(text).map { s -> s.toks.map { it.text } }

    /**
     * Tokenizes [text]. Lowercasing never changes the length of the letters VK handles (Latin), and NFC of typed text
     * is the identity in practice; positions are into the normalised text, which the caller should pass already NFC.
     */
    fun tokenize(text: String): List<Sentence> {
        val t = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        val out = ArrayList<Sentence>()
        var cur = ArrayList<Tok>()
        val n = t.length
        var i = 0
        var lastWordEnd = -1
        var lastWord: String? = null
        fun end(closed: Boolean) {
            while (cur.isNotEmpty() && cur.last().text == COMMA) cur.removeAt(cur.size - 1)
            if (cur.any { it.text != COMMA }) out += Sentence(cur, closed)
            cur = ArrayList()
        }
        fun comma(at: Int) {
            if (cur.isNotEmpty() && cur.last().text != COMMA) cur += Tok(COMMA, at, at + 1)
        }
        while (i < n) {
            val c = t[i]
            if (c.isLetterOrDigit()) {
                val s = i
                val sb = StringBuilder()
                var digit = false
                while (i < n) {
                    val d = t[i]
                    if (d.isLetter()) { sb.append(d); i++ }
                    else if (d.isDigit()) { digit = true; sb.append(d); i++ }
                    else if ((d == '\'' || d == '’') && i + 1 < n && t[i + 1].isLetter() && sb.isNotEmpty() && sb[sb.length - 1].isLetter()) { sb.append('\''); i++ }
                    else if ((d == '.' || d == ',' || d == ':') && digit && i + 1 < n && t[i + 1].isDigit() && sb[sb.length - 1].isDigit()) { sb.append(d); i++ }
                    else break
                }
                val w = if (digit) NUM else sb.toString()
                cur += Tok(w, s, i)
                lastWord = w
                lastWordEnd = i
                continue
            }
            if (c == '\n') { end(true); i++; continue }
            if (c in MARKS) {
                var j = i
                while (j < n && t[j] in MARKS) j++
                var k = j
                while (k < n && t[k] in CLOSERS) k++
                var boundary = k >= n || t[k].isWhitespace()
                val lw = lastWord
                if (boundary && j == i + 1 && c == '.' && lastWordEnd == i && lw != null &&
                    ((lw.length == 1 && lw[0].isLetter()) || lw in ContextSegments.ABBREVIATIONS)) boundary = false
                if (boundary) { end(true); i = k } else i = j
                continue
            }
            if (c in COMMAS) { comma(i); i++; continue }
            if (c == '-') {
                val inWord = i > 0 && i + 1 < n && t[i - 1].isLetter() && t[i + 1].isLetter()
                if (!inWord) comma(i)
                i++
                continue
            }
            i++
        }
        end(false)
        return out
    }

    /** Where a word of the text sits among the tokens: sentence index and token index, or null (not a token). */
    fun locate(sents: List<Sentence>, wordStart: Int): Pair<Int, Int>? {
        for ((si, s) in sents.withIndex()) {
            if (s.toks.isEmpty() || s.toks.first().start > wordStart) continue
            for ((ti, tk) in s.toks.withIndex()) if (tk.start == wordStart) return si to ti
        }
        return null
    }

    /** The [n] tokens before token [ti] of [s], with [BOS] when the sentence start is within reach. */
    fun leftOf(s: Sentence, ti: Int, n: Int = 2): List<String> {
        val prev = s.toks.subList(0, ti).map { it.text }
        return (if (prev.size < n) listOf(BOS) + prev else prev).takeLast(n)
    }

    /**
     * Up to [k] words after token [ti] of [s] (commas included, not counted), then [EOS] if the sentence's end mark is
     * reached within them. The caller passes the text as it stands (live: no future text), so an end mark that was
     * not typed yet is not there.
     */
    fun rightOf(s: Sentence, ti: Int, k: Int): List<String> {
        if (k <= 0) return emptyList()
        val out = ArrayList<String>()
        var words = 0
        var j = ti + 1
        while (j < s.toks.size && words < k) {
            val tx = s.toks[j].text
            out += tx
            if (tx != COMMA) words++
            j++
        }
        if (j >= s.toks.size && s.closed) out += EOS
        return out
    }
}
