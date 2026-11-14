package com.resyst.vk.core

/**
 * r13 (LR5): corrections the user rejected — the word they typed → the fix space applied — by ⌫
 * right after it, the ↶ chip, or deleting the fix later and typing the word again. Space never
 * applies that same fix to that same word again (the bar may still offer it).
 *
 * r13c (TS1–TS7): a rejection counts twice before it is for good. The first one of a pair is a
 * [strike] (stored, so a later field or session can count the second; the engine keeps the word as
 * typed in that field only); the second makes it a rejection as above, and the caller keep-marks the
 * word. Pairs rejected by 0.9.0 (one rejection) stay rejections.
 *
 * Bounded ([CAP]: the oldest rejection goes first), persisted as files/personal/rejections.json,
 * listed one by one in "Lo que sé de ti" and wiped by "Borrar lo aprendido". Keys are lowercase
 * ([Tokens.key]); only plain words ([Tokens.learnable]) are kept. Not thread-safe: main thread.
 */
class Rejections {
    /** One rejected correction, lowercase. */
    data class Entry(val typed: String, val fix: String)

    private val list = ArrayList<Entry>() // oldest first
    /** r13c (TS2): pairs rejected once, oldest first, at most [CAP]. */
    private val strikes = ArrayList<Entry>()

    /** Everything stored: rejections and first strikes (the "Borrar lo aprendido" count). */
    val size: Int get() = list.size + strikes.size

    /** Records [typed] → [fix]; true when the store changed. */
    fun add(typed: String, fix: String): Boolean {
        val e = entry(typed, fix) ?: return false
        val at = list.indexOf(e)
        if (at >= 0 && at == list.size - 1) return false
        if (at >= 0) list.removeAt(at) // seen again: becomes the newest
        list += e
        while (list.size > CAP) list.removeAt(0)
        return true
    }

    /**
     * r13c (TS1/TS2): the user rejected [typed] → [fix] once more. The pair's first rejection is a
     * strike; a pair that already had one (or a stored rejection) becomes / stays a rejection.
     * True = it is a rejection now (the caller keep-marks [typed]); the store always changes.
     */
    fun strike(typed: String, fix: String): Boolean {
        val e = entry(typed, fix) ?: return false
        if (strikes.remove(e)) { add(e.typed, e.fix); return true }
        if (list.contains(e)) { add(e.typed, e.fix); return true }
        strikes += e
        while (strikes.size > CAP) strikes.removeAt(0)
        return false
    }

    /** r13c (TS3b): [typed] → [fix] was rejected once (a strike waiting for its second). */
    fun struck(typed: String, fix: String): Boolean {
        val e = entry(typed, fix) ?: return false
        return strikes.contains(e)
    }

    /** Space must not turn [typed] into [fix]. */
    fun rejects(typed: String, fix: String): Boolean {
        val e = entry(typed, fix) ?: return false
        return list.contains(e)
    }

    /** Forgets [typed] → [fix], a rejection or a first strike ("Lo que sé de ti" ✕). */
    fun forget(typed: String, fix: String): Boolean = entry(typed, fix)?.let { e -> list.remove(e) or strikes.remove(e) } == true

    /** The rejections (space never applies them), newest first, as "Lo que sé de ti" lists them. */
    fun items(): List<Entry> = list.asReversed().toList()

    /** r13c (TS5): the pairs rejected once (corrected again in other fields), newest first. */
    fun strikes(): List<Entry> = strikes.asReversed().toList()

    fun clear() { list.clear(); strikes.clear() }

    fun isEmpty(): Boolean = list.isEmpty() && strikes.isEmpty()

    /** v1 + r13c's `strikes` array (0.9.0 reads `rejected` only and ignores it, TS4). */
    fun toJson(): String {
        val b = StringBuilder("{\"v\":").append(VERSION).append(",\"rejected\":")
        pairs(b, list)
        if (strikes.isNotEmpty()) { b.append(",\"strikes\":"); pairs(b, strikes) }
        return b.append('}').toString()
    }

    private fun pairs(b: StringBuilder, entries: List<Entry>) {
        b.append('[')
        entries.forEachIndexed { i, e ->
            if (i > 0) b.append(',')
            b.append('[').append(MiniJson.quote(e.typed)).append(',').append(MiniJson.quote(e.fix)).append(']')
        }
        b.append(']')
    }

    companion object {
        const val VERSION = 1
        const val CAP = 200

        private fun entry(typed: String, fix: String): Entry? {
            val t = Tokens.key(typed)
            val f = Tokens.key(fix)
            if (t == f || !Tokens.learnable(t) || !Tokens.learnable(f)) return null
            return Entry(t, f)
        }

        /** Parses [toJson] output; anything malformed is an empty store, a bad pair is dropped. */
        fun fromJson(text: String): Rejections {
            val r = Rejections()
            val root = runCatching { MiniJson.parse(text) }.getOrNull() as? Map<*, *> ?: return r
            if ((root["v"] as? Number)?.toLong() != VERSION.toLong()) return r
            for (e in root["rejected"] as? List<*> ?: emptyList<Any>()) {
                val a = e as? List<*> ?: continue
                val t = a.getOrNull(0) as? String ?: continue
                val f = a.getOrNull(1) as? String ?: continue
                r.add(t, f)
            }
            // r13c (TS4): first strikes (absent in a 0.9.0 file); a pair that is a rejection already stays one
            for (e in root["strikes"] as? List<*> ?: emptyList<Any>()) {
                val a = e as? List<*> ?: continue
                val t = a.getOrNull(0) as? String ?: continue
                val f = a.getOrNull(1) as? String ?: continue
                val x = entry(t, f) ?: continue
                if (x in r.list || x in r.strikes) continue
                r.strikes += x
                while (r.strikes.size > CAP) r.strikes.removeAt(0)
            }
            return r
        }
    }
}
