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
 */
class PersonalModel {
    private class Word(var form: String, val folded: String, var count: Int, var stamp: Long)
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
            if (t.vocab.size >= VOCAB_CAP) t.vocab.entries.minWithOrNull(compareBy({ it.value.count }, { it.value.stamp }))?.let { t.vocab.remove(it.key) }
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

    /** Takes back one [learn] (the user immediately deleted the word it learned, M10). */
    fun unlearn(lang: Lang, prev: String?, word: String, sentenceStart: Boolean) {
        val t = langs[lang] ?: return
        val k = Tokens.key(word)
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
     * The user's words that complete [prefix] (accent-insensitive, never the prefix itself):
     * continuations of [prev] first, then words used at least [COMPLETE_MIN] times.
     */
    fun complete(lang: Lang, prefix: String, prev: String?, limit: Int): List<String> {
        val t = langs[lang] ?: return emptyList()
        if (prefix.isEmpty()) return emptyList()
        val kp = Tokens.key(prefix)
        val fp = Suggest.fold(prefix)
        val out = LinkedHashSet<String>()
        prev?.let { t.next[Tokens.key(it)] }?.let { list ->
            for (c in ranked(list)) {
                val w = t.vocab[c.key]
                val folded = w?.folded ?: Suggest.fold(c.key)
                if (c.key != kp && folded.startsWith(fp)) out += c.key
            }
        }
        t.vocab.entries.asSequence()
            .filter { (k, w) -> w.count >= COMPLETE_MIN && k != kp && w.folded.startsWith(fp) }
            .sortedWith(compareByDescending<Map.Entry<String, Word>> { it.value.count }.thenByDescending { it.value.stamp })
            .forEach { out += it.key }
        return out.take(limit).map { formFor(t, it) }
    }

    /** Whether the user has typed [word] at least [minCount] times (space never "corrects" it, B5). */
    fun knows(lang: Lang, word: String, minCount: Int = 1): Boolean =
        (langs[lang]?.vocab?.get(Tokens.key(word))?.count ?: 0) >= minCount

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
                b.append('[').append(MiniJson.quote(k)).append(',').append(MiniJson.quote(w.form)).append(',').append(w.count).append(',').append(w.stamp).append(']')
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
        const val VERSION = 1
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

        /** Parses [toJson] output; anything malformed yields an empty model, bad entries are dropped (M9). */
        fun fromJson(text: String): PersonalModel {
            val m = PersonalModel()
            val root = runCatching { MiniJson.parse(text) }.getOrNull() as? Map<*, *> ?: return m
            if ((root["v"] as? Number)?.toInt() != VERSION) return m
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
                    t.vocab[k] = Word(form, Suggest.fold(k), count.coerceAtMost(COUNT_CAP), stamp)
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
