package com.resyst.vk.core

import java.text.Normalizer
import java.util.Locale

/**
 * r15 (docs/failure-modes.md ES1–ES14): find an emoji by its name or a keyword, in Spanish and in English whatever the
 * interface language («águila», «aguila», «eagle», «hawk» all reach 🦅). The data is assets/emoji/search.txt, generated
 * by scripts/gen_emoji_search.py from Unicode CLDR 49 (es + es_419, en) plus a few Resyst keywords, over the catalog's
 * base cells ([EmojiCatalog]); a toned variant is found through its base.
 *
 * Pure JVM and private by construction: it is handed the asset text and the catalog, answers ids, and keeps nothing of a
 * query (no field, no log, no store). Ranking (ES6): the exact name > an exact keyword > an exact word > a prefix; within
 * a tier the user's recents first, then the catalog's order. A query of several words needs every word (each exact or a
 * prefix); the whole phrase equal to a name or keyword ranks as that name or keyword.
 *
 * Memory (ES8): the tokens live in one String with offsets, the postings in a ShortArray (id × 4 + kind), the names of
 * one language in one String; the caller drops the index when the keyboard hides. Not thread-safe: [search] reuses two
 * arrays (UI thread only).
 */
class EmojiSearch private constructor(
    private val cells: List<EmojiCatalog.Cell>,
    private val names: String,
    private val nameAt: IntArray,
    private val tok: String,
    private val tokAt: IntArray,
    private val post: ShortArray,
    private val postAt: IntArray,
    /** The language of [name]: "es" or "en". */
    val lang: String,
) {
    val size: Int get() = cells.size
    val tokenCount: Int get() = tokAt.size - 1
    val postingCount: Int get() = post.size

    private val best = IntArray(cells.size)
    private val cur = IntArray(cells.size)

    fun cell(id: Int): EmojiCatalog.Cell = cells[id]

    /** The emoji's CLDR name in [lang] (TalkBack, ES12). */
    fun name(id: Int): String = names.substring(nameAt[id], nameAt[id + 1])

    /** A token of the index (tests: the fold parity, ES3). */
    fun token(t: Int): String = tok.substring(tokAt[t], tokAt[t + 1])

    /**
     * Rank of each id among [recents] (most recent = 0), [NOT_RECENT] otherwise. Computed once when the search opens:
     * a toned recent counts for its base cell.
     */
    fun recentRanks(recents: List<String>, catalog: EmojiCatalog): IntArray {
        val rank = IntArray(cells.size) { NOT_RECENT }
        if (recents.isEmpty()) return rank
        val idOf = HashMap<String, Int>(cells.size * 2)
        cells.forEachIndexed { i, c -> idOf[c.base] = i }
        recents.forEachIndexed { r, e ->
            val base = catalog.cellOf(e)?.base ?: e
            val id = idOf[base] ?: return@forEachIndexed
            if (r < rank[id]) rank[id] = minOf(r, NOT_RECENT - 1)
        }
        return rank
    }

    /** The ids matching [query], best first, at most [limit]. Empty for a query with no letter or digit. */
    fun search(query: String, ranks: IntArray? = null, limit: Int = LIMIT): IntArray {
        val ws = words(query)
        if (ws.isEmpty()) return IntArray(0)
        val n = cells.size
        for ((wi, w) in ws.withIndex()) {
            val into = if (wi == 0) best else cur
            into.fill(MISS)
            var t = lowerBound(w)
            while (t < tokenCount && startsWith(t, w)) {
                val exact = tokAt[t + 1] - tokAt[t] == w.length
                for (k in postAt[t] until postAt[t + 1]) {
                    val v = post[k].toInt()
                    val tier = if (exact) TIER_OF_KIND[v and 3] else PREFIX
                    val id = v shr 2
                    if (tier < into[id]) into[id] = tier
                }
                t++
            }
            if (wi > 0) for (i in 0 until n) best[i] = if (best[i] == MISS || cur[i] == MISS) MISS else maxOf(best[i], cur[i])
        }
        if (ws.size > 1) {
            // the whole phrase equal to a multi-word name or keyword ranks as that name or keyword
            val t = exactToken(ws.joinToString(" "))
            if (t >= 0) for (k in postAt[t] until postAt[t + 1]) {
                val v = post[k].toInt()
                val id = v shr 2
                val tier = TIER_OF_KIND[v and 3]
                if (tier < best[id]) best[id] = tier
            }
        }
        var count = 0
        for (i in 0 until n) if (best[i] != MISS) count++
        val keys = IntArray(count)
        var j = 0
        for (i in 0 until n) if (best[i] != MISS) {
            val r = ranks?.get(i) ?: NOT_RECENT
            keys[j++] = (best[i] shl 20) or (r shl 13) or i
        }
        keys.sort()
        val out = IntArray(minOf(limit, count))
        for (i in out.indices) out[i] = keys[i] and ID_MASK
        return out
    }

    private fun lowerBound(w: String): Int {
        var lo = 0
        var hi = tokenCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compare(mid, w) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun exactToken(w: String): Int {
        val t = lowerBound(w)
        return if (t < tokenCount && compare(t, w) == 0) t else -1
    }

    /** Token [t] against [w], by UTF-16 units (the generator sorts the same way). */
    private fun compare(t: Int, w: String): Int {
        val a = tokAt[t]
        val len = tokAt[t + 1] - a
        val m = minOf(len, w.length)
        for (i in 0 until m) {
            val d = tok[a + i] - w[i]
            if (d != 0) return d
        }
        return len - w.length
    }

    private fun startsWith(t: Int, w: String): Boolean =
        tokAt[t + 1] - tokAt[t] >= w.length && tok.regionMatches(tokAt[t], w, 0, w.length)

    companion object {
        const val LIMIT = 120
        /** [recentRanks] for an emoji that is not recent (6 bits in the sort key). */
        const val NOT_RECENT = 63
        const val TIER_NAME = 0
        const val TIER_KEYWORD = 1
        const val TIER_WORD = 2
        const val PREFIX = 3
        private const val MISS = Int.MAX_VALUE
        private const val ID_MASK = 0x1FFF
        /** Posting kinds in the asset: n (whole name), k (whole keyword), w (word of the name), p (word of a keyword). */
        private const val KINDS = "nkwp"
        private val TIER_OF_KIND = intArrayOf(TIER_NAME, TIER_KEYWORD, TIER_WORD, TIER_WORD)

        /** ES3: the fold the generator applies (NFD, combining marks dropped, lower case). */
        fun fold(s: String): String {
            val d = Normalizer.normalize(s, Normalizer.Form.NFD)
            val b = StringBuilder(d.length)
            for (c in d) if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) b.append(c)
            return b.toString().lowercase(Locale.ROOT)
        }

        /** The words of [s] after [fold]: runs of letters and decimal digits. */
        fun words(s: String): List<String> {
            val f = fold(s)
            val out = ArrayList<String>(4)
            var start = -1
            for (i in f.indices) {
                if (f[i].isLetterOrDigit()) { if (start < 0) start = i }
                else if (start >= 0) { out += f.substring(start, i); start = -1 }
            }
            if (start >= 0) out += f.substring(start)
            return out
        }

        /** The catalog's base cells in the order the generator numbered them. */
        fun cellsOf(catalog: EmojiCatalog): List<EmojiCatalog.Cell> = catalog.groups.flatMap { it.cells }

        /**
         * Reads the asset for [catalog] with the names of [lang]; null when the text is malformed or was generated for
         * another catalog (an emoji at an id that is not the catalog's): no search rather than a wrong answer.
         */
        fun parse(text: String, catalog: EmojiCatalog, lang: String): EmojiSearch? {
            val cells = cellsOf(catalog)
            if (cells.isEmpty() || cells.size * 4 > Short.MAX_VALUE) return null
            val col = if (lang == "en") 2 else 1
            val lines = text.lineSequence().filter { it.isNotEmpty() && !it.startsWith("# ") }.iterator()
            fun header(tag: String): Int? = lines.takeIf { it.hasNext() }?.next()?.takeIf { it.startsWith("$tag ") }?.substring(tag.length + 1)?.toIntOrNull()
            val n = header("=E") ?: return null
            if (n != cells.size) return null
            val names = StringBuilder(n * 16)
            val nameAt = IntArray(n + 1)
            for (i in 0 until n) {
                if (!lines.hasNext()) return null
                val parts = lines.next().split('\t')
                if (parts.size != 3 || parts[0] != cells[i].base) return null
                nameAt[i] = names.length
                names.append(parts[col])
            }
            nameAt[n] = names.length
            val m = header("=T") ?: return null
            val tok = StringBuilder(m * 9)
            val tokAt = IntArray(m + 1)
            val postAt = IntArray(m + 1)
            var post = ShortArray(m * 3)
            var np = 0
            for (t in 0 until m) {
                if (!lines.hasNext()) return null
                val line = lines.next()
                val tab = line.indexOf('\t')
                if (tab <= 0) return null
                tokAt[t] = tok.length
                tok.append(line, 0, tab)
                postAt[t] = np
                var i = tab + 1
                while (i < line.length) {
                    var id = 0
                    var digits = 0
                    while (i < line.length && line[i] in '0'..'9') { id = id * 10 + (line[i] - '0'); i++; digits++ }
                    if (digits == 0 || i >= line.length || id >= n) return null
                    val kind = KINDS.indexOf(line[i])
                    if (kind < 0) return null
                    i++
                    if (np == post.size) post = post.copyOf(post.size * 2)
                    post[np++] = (id * 4 + kind).toShort()
                    if (i < line.length && line[i] == ' ') i++
                }
            }
            tokAt[m] = tok.length
            postAt[m] = np
            return EmojiSearch(cells, names.toString(), nameAt, tok.toString(), tokAt, post.copyOf(np), postAt, if (col == 2) "en" else "es")
        }
    }
}
