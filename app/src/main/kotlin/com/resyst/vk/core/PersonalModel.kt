package com.resyst.vk.core

/**
 * What the user types, learned on the device: a bigram table (previous word → next words),
 * the words that open their sentences, and their own vocabulary with the casing they use.
 * Keys are [Tokens.key] (case-folded); the shown form is the user's own spelling, except
 * capitals that only came from a sentence start or ALL-CAPS shouting (M4).
 *
 * Bounded everywhere (M3): [NEXT_CAP] continuations per word, [STARTER_CAP] starters,
 * [PREV_CAP] previous words, [VOCAB_CAP] words per language. A full table evicts its least
 * useful entry — lowest count, oldest among equals — and never the entry being written.
 * Counts saturate at [COUNT_CAP]: crossing it halves the whole table, so an old habit decays
 * and a new one can overtake it.
 *
 * A logical clock (not wall time) orders recency, so ranking is deterministic and survives a
 * device clock change. Not thread-safe: the IME touches it from the main thread only.
 *
 * r11 (v2): a word can be [keep]-marked — the user reverted the space-correction that replaced it
 * (⌫ right after it, or the ↶ chip). A kept word is theirs at once: never corrected, offered as a
 * strong completion, evicted last (K1–K3). Completions come in two tiers ([Completion.strong]): a
 * continuation of the previous word, a word used [COMPLETE_MIN]+ times, or a kept word; and the
 * one-offs (typed once), which the bar ranks after the first lexicon candidate (N1/N2).
 */
class PersonalModel {
    private class Word(var form: String, val folded: String, var count: Int, var stamp: Long, var kept: Boolean = false)
    private class Cont(val key: String, var count: Int, var stamp: Long)
    private class Tables {
        val vocab = LinkedHashMap<String, Word>()
        val starters = ArrayList<Cont>()
        val next = LinkedHashMap<String, ArrayList<Cont>>()
    }

    private val langs = LinkedHashMap<Lang, Tables>()
    private var clock = 0L

    private fun tables(lang: Lang) = langs.getOrPut(lang) { Tables() }

    /** Records one finished [word]; [prev] is the previous word (any case) or null. */
    fun learn(lang: Lang, prev: String?, word: String, sentenceStart: Boolean) {
        if (!Tokens.learnable(word)) return
        val p = prev?.takeIf { Tokens.learnable(it) }?.let(Tokens::key)
        val k = Tokens.key(word)
        val t = tables(lang)
        val now = ++clock
        val form = formOf(word, sentenceStart)
        val w = t.vocab[k]
        if (w != null) {
            w.count++; w.stamp = now
            if (form != null) w.form = form
        } else {
            evictVocab(t)
            t.vocab[k] = Word(form ?: k, Suggest.fold(k), 1, now)
        }
        if ((t.vocab[k]?.count ?: 0) > COUNT_CAP) for (v in t.vocab.values) v.count = halve(v.count)
        if (sentenceStart) bump(t.starters, k, now, STARTER_CAP)
        if (p != null) {
            val list = t.next[p] ?: ArrayList<Cont>().also {
                if (t.next.size >= PREV_CAP) {
                    t.next.entries.minWithOrNull(compareBy({ e -> e.value.sumOf { it.count } }, { e -> e.value.maxOf { it.stamp } }))
                        ?.let { t.next.remove(it.key) }
                }
                t.next[p] = it
            }
            bump(list, k, now, NEXT_CAP)
        }
    }

    /**
     * r11 (K1): the user kept [word] as typed (reverted its space-correction): mark it as theirs.
     * Normally called right after the [learn] of that word; creates the entry if it is missing.
     * True when the model changed.
     */
    fun keep(lang: Lang, word: String): Boolean {
        if (!Tokens.learnable(word)) return false
        val k = Tokens.key(word)
        val t = tables(lang)
        val w = t.vocab[k]
        if (w != null) {
            if (w.kept) return false
            w.kept = true
            return true
        }
        evictVocab(t)
        t.vocab[k] = Word(formOf(word, false) ?: k, Suggest.fold(k), 1, ++clock, kept = true)
        return true
    }

    /** Whether the user kept [word] in [lang] (see [keep]). */
    fun kept(lang: Lang, word: String): Boolean = langs[lang]?.vocab?.get(Tokens.key(word))?.kept == true

    /**
     * r11: [word] is the user's in any of [langs] — kept, or typed at least [minCount] times. Space
     * never corrects it (B5, K1, BL8).
     */
    fun owns(within: List<Lang>, word: String, minCount: Int): Boolean =
        within.any { l -> langs[l]?.vocab?.get(Tokens.key(word))?.let { it.kept || it.count >= minCount } == true }

    /** Takes back one [learn] (the user immediately deleted the word it learned, M10). */
    fun unlearn(lang: Lang, prev: String?, word: String, sentenceStart: Boolean) {
        val t = langs[lang] ?: return
        val k = Tokens.key(word)
        // undoing the very learn that kept a word (count 1) drops it with its mark: the user is
        // editing the word again; a later use's undo leaves the mark (K3)
        t.vocab[k]?.let { if (--it.count <= 0) t.vocab.remove(k) }
        if (sentenceStart) drop(t.starters, k)
        val p = prev?.let(Tokens::key) ?: return
        val list = t.next[p] ?: return
        drop(list, k)
        if (list.isEmpty()) t.next.remove(p)
    }

    /**
     * Next words after [prev] (a key), or — with no previous word — the user's sentence
     * openers when [sentenceStart]. Most used first, most recent among equals.
     */
    fun predict(lang: Lang, prev: String?, sentenceStart: Boolean, limit: Int): List<String> {
        val t = langs[lang] ?: return emptyList()
        val list = when {
            prev != null -> t.next[Tokens.key(prev)] ?: return emptyList()
            sentenceStart -> t.starters
            else -> return emptyList()
        }
        return ranked(list).take(limit).map { formFor(t, it.key) }
    }

    /**
     * The user's strong words that complete [prefix] (accent-insensitive, never the prefix itself):
     * continuations of [prev] first, then words used at least [COMPLETE_MIN] times or kept.
     */
    fun complete(lang: Lang, prefix: String, prev: String?, limit: Int): List<String> =
        completions(listOf(lang), prefix, prev, limit).filter { it.strong }.map { it.word }

    /** One personal completion; [strong] = a continuation, a habit (≥ [COMPLETE_MIN]) or kept. */
    data class Completion(val word: String, val strong: Boolean)

    /**
     * r11: completions of [prefix] from the vocabularies of [langs] (the writing language first,
     * de-duplicated by key, BL8). Strong ones first — continuations of [prev] in the first language
     * (bigrams stay per language), then habits and kept words — at most [limit]; then the one-offs
     * (typed once), at most [limit], which the bar ranks lower (N2).
     */
    fun completions(langs: List<Lang>, prefix: String, prev: String?, limit: Int): List<Completion> {
        if (prefix.isEmpty() || langs.isEmpty() || limit <= 0) return emptyList()
        val kp = Tokens.key(prefix)
        val fp = Suggest.fold(prefix)
        val strong = LinkedHashMap<String, String>()
        val first = this.langs[langs[0]]
        if (first != null) prev?.let { first.next[Tokens.key(it)] }?.let { list ->
            for (c in ranked(list)) {
                val folded = first.vocab[c.key]?.folded ?: Suggest.fold(c.key)
                if (c.key != kp && folded.startsWith(fp)) strong.putIfAbsent(c.key, formFor(first, c.key))
            }
        }
        val weak = LinkedHashMap<String, String>()
        val order = compareByDescending<Map.Entry<String, Word>> { it.value.count }.thenByDescending { it.value.stamp }
        for (pass in 0..1) for (l in langs.distinct()) {
            val t = this.langs[l] ?: continue
            t.vocab.entries.asSequence()
                .filter { (k, w) -> k != kp && w.folded.startsWith(fp) && (w.kept || w.count >= COMPLETE_MIN) == (pass == 0) }
                .sortedWith(order)
                .forEach { (k, w) -> if (pass == 0) strong.putIfAbsent(k, w.form) else if (k !in strong) weak.putIfAbsent(k, w.form) }
        }
        return strong.values.take(limit).map { Completion(it, true) } + weak.values.take(limit).map { Completion(it, false) }
    }

    /** Whether the user has typed [word] at least [minCount] times (space never "corrects" it, B5). */
    fun knows(lang: Lang, word: String, minCount: Int = 1): Boolean =
        (langs[lang]?.vocab?.get(Tokens.key(word))?.count ?: 0) >= minCount

    /**
     * r10 ("Lo que sé de ti", V11): forgets [word] in [lang] everywhere — its vocabulary entry,
     * the sentence openers, every continuation that leads to it and the continuations it leads
     * to. True when anything was removed.
     */
    fun forget(lang: Lang, word: String): Boolean {
        val t = langs[lang] ?: return false
        val k = Tokens.key(word)
        var changed = t.vocab.remove(k) != null
        changed = t.starters.removeAll { it.key == k } || changed
        changed = (t.next.remove(k) != null) || changed
        val it = t.next.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.value.removeAll { c -> c.key == k }) changed = true
            if (e.value.isEmpty()) it.remove()
        }
        return changed
    }

    /** One learned word as "Lo que sé de ti" lists it: the user's spelling, how often, and whether they kept it (K5). */
    data class Learned(val key: String, val form: String, val count: Int, val kept: Boolean = false)

    /** The learned words of [lang], most used first (most recent among equals), at most [limit] (V13). */
    fun words(lang: Lang, limit: Int): List<Learned> {
        val t = langs[lang] ?: return emptyList()
        return t.vocab.entries.asSequence()
            .sortedWith(compareByDescending<Map.Entry<String, Word>> { it.value.count }.thenByDescending { it.value.stamp })
            .take(limit.coerceAtLeast(0))
            .map { (k, w) -> Learned(k, w.form, w.count, w.kept) }
            .toList()
    }

    fun clear() { langs.clear(); clock = 0 }

    fun isEmpty(): Boolean = langs.values.all { it.vocab.isEmpty() && it.next.isEmpty() && it.starters.isEmpty() }

    // ── introspection (tests, diagnostics) ──────────────────────────────
    fun vocabCount(lang: Lang): Int = langs[lang]?.vocab?.size ?: 0
    fun prevCount(lang: Lang): Int = langs[lang]?.next?.size ?: 0
    fun countOf(lang: Lang, prev: String, word: String): Int =
        langs[lang]?.next?.get(Tokens.key(prev))?.firstOrNull { it.key == Tokens.key(word) }?.count ?: 0

    // ── helpers ─────────────────────────────────────────────────────────
    private fun ranked(list: List<Cont>): List<Cont> =
        list.sortedWith(compareByDescending<Cont> { it.count }.thenByDescending { it.stamp })

    private fun formFor(t: Tables, key: String) = t.vocab[key]?.form ?: key

    /** A full vocabulary drops its least useful word: never a kept one while another is left (K3). */
    private fun evictVocab(t: Tables) {
        if (t.vocab.size < VOCAB_CAP) return
        t.vocab.entries.minWithOrNull(compareBy({ it.value.kept }, { it.value.count }, { it.value.stamp }))?.let { t.vocab.remove(it.key) }
    }

    private fun bump(list: ArrayList<Cont>, k: String, now: Long, cap: Int) {
        val c = list.firstOrNull { it.key == k }
        if (c != null) {
            c.count++; c.stamp = now
            if (c.count > COUNT_CAP) for (x in list) x.count = halve(x.count)
            return
        }
        if (list.size >= cap) list.minWithOrNull(compareBy({ it.count }, { it.stamp }))?.let { list.remove(it) }
        list += Cont(k, 1, now)
    }

    private fun drop(list: ArrayList<Cont>, k: String) {
        val c = list.firstOrNull { it.key == k } ?: return
        if (--c.count <= 0) list.remove(c)
    }

    // ── persistence ─────────────────────────────────────────────────────
    fun toJson(): String {
        val b = StringBuilder()
        b.append("{\"v\":").append(VERSION).append(",\"clock\":").append(clock).append(",\"langs\":{")
        var firstLang = true
        for ((lang, t) in langs) {
            if (!firstLang) b.append(','); firstLang = false
            b.append(MiniJson.quote(lang.code)).append(":{\"vocab\":[")
            t.vocab.entries.forEachIndexed { i, (k, w) ->
                if (i > 0) b.append(',')
                b.append('[').append(MiniJson.quote(k)).append(',').append(MiniJson.quote(w.form)).append(',').append(w.count).append(',').append(w.stamp).append(',').append(if (w.kept) 1 else 0).append(']')
            }
            b.append("],\"starters\":")
            conts(b, t.starters)
            b.append(",\"next\":[")
            var i = 0
            for ((p, list) in t.next) {
                if (i++ > 0) b.append(',')
                b.append('[').append(MiniJson.quote(p)).append(',')
                conts(b, list)
                b.append(']')
            }
            b.append("]}")
        }
        return b.append("}}").toString()
    }

    private fun conts(b: StringBuilder, list: List<Cont>) {
        b.append('[')
        list.forEachIndexed { i, c ->
            if (i > 0) b.append(',')
            b.append('[').append(MiniJson.quote(c.key)).append(',').append(c.count).append(',').append(c.stamp).append(']')
        }
        b.append(']')
    }

    companion object {
        /** r11: v2 adds the kept flag as a 5th vocabulary field; v1 files load unchanged (K4). */
        const val VERSION = 2
        private const val V1 = 1
        const val NEXT_CAP = 8
        const val STARTER_CAP = 16
        const val PREV_CAP = 1500
        const val VOCAB_CAP = 2000
        const val COUNT_CAP = 64
        /** A word needs this many uses to complete a prefix without context (one-offs are noise). */
        const val COMPLETE_MIN = 2

        private fun halve(c: Int) = (c + 1) / 2

        /** null = no case information (auto-capital at a sentence start, or shouting). */
        private fun formOf(word: String, sentenceStart: Boolean): String? = when {
            word.length > 1 && word.all { !it.isLetter() || it.isUpperCase() } -> null
            sentenceStart && word[0].isUpperCase() && word.drop(1).none { it.isUpperCase() } -> null
            else -> word
        }

        /**
         * Parses [toJson] output (v2, or a v1 file from r4–r10 — nothing kept yet); anything malformed
         * yields an empty model, bad entries are dropped (M9, K4).
         */
        fun fromJson(text: String): PersonalModel {
            val m = PersonalModel()
            val root = runCatching { MiniJson.parse(text) }.getOrNull() as? Map<*, *> ?: return m
            val v = root["v"]
            if (v !is Number || (v.toLong() != VERSION.toLong() && v.toLong() != V1.toLong())) return m
            val langs = root["langs"] as? Map<*, *> ?: return m
            var maxStamp = 0L
            for ((code, raw) in langs) {
                val lang = Lang.values().firstOrNull { it.code == code } ?: continue
                val o = raw as? Map<*, *> ?: continue
                val t = Tables()
                for (e in o["vocab"] as? List<*> ?: emptyList<Any>()) {
                    val a = e as? List<*> ?: continue
                    val k = a.getOrNull(0) as? String ?: continue
                    val form = a.getOrNull(1) as? String ?: continue
                    val count = int(a.getOrNull(2)) ?: continue
                    val stamp = long(a.getOrNull(3)) ?: continue
                    if (!Tokens.learnable(k) || k != Tokens.key(k) || Tokens.key(form) != k || count <= 0 || t.vocab.size >= VOCAB_CAP) continue
                    // v2 5th field: 1 = kept; anything else (absent in v1, junk) = not kept
                    val kept = (a.getOrNull(4) as? Number)?.toLong() == 1L
                    t.vocab[k] = Word(form, Suggest.fold(k), count.coerceAtMost(COUNT_CAP), stamp, kept)
                    maxStamp = maxOf(maxStamp, stamp)
                }
                readConts(o["starters"], STARTER_CAP).let { t.starters += it; it.forEach { c -> maxStamp = maxOf(maxStamp, c.stamp) } }
                for (e in o["next"] as? List<*> ?: emptyList<Any>()) {
                    val a = e as? List<*> ?: continue
                    val p = a.getOrNull(0) as? String ?: continue
                    if (!Tokens.learnable(p) || p != Tokens.key(p) || t.next.size >= PREV_CAP) continue
                    val list = readConts(a.getOrNull(1), NEXT_CAP)
                    if (list.isEmpty()) continue
                    list.forEach { maxStamp = maxOf(maxStamp, it.stamp) }
                    t.next[p] = list
                }
                m.langs[lang] = t
            }
            m.clock = maxOf(long(root["clock"]) ?: 0L, maxStamp)
            return m
        }

        private fun readConts(raw: Any?, cap: Int): ArrayList<Cont> {
            val out = ArrayList<Cont>()
            for (e in raw as? List<*> ?: emptyList<Any>()) {
                val a = e as? List<*> ?: continue
                val k = a.getOrNull(0) as? String ?: continue
                val count = int(a.getOrNull(1)) ?: continue
                val stamp = long(a.getOrNull(2)) ?: continue
                if (!Tokens.learnable(k) || k != Tokens.key(k) || count <= 0 || out.size >= cap || out.any { it.key == k }) continue
                out += Cont(k, count.coerceAtMost(COUNT_CAP), stamp)
            }
            return out
        }

        private fun long(v: Any?): Long? = (v as? Long) ?: (v as? Int)?.toLong()
        private fun int(v: Any?): Int? = long(v)?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
    }
}
