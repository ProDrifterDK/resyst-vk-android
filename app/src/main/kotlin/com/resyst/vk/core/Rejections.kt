package com.resyst.vk.core

/**
 * r13 (LR5): corrections the user rejected — the word they typed → the fix space applied — by ⌫
 * right after it, the ↶ chip, or deleting the fix later and typing the word again. Space never
 * applies that same fix to that same word again (the bar may still offer it).
 *
 * Bounded ([CAP]: the oldest rejection goes first), persisted as files/personal/rejections.json,
 * listed one by one in "Lo que sé de ti" and wiped by "Borrar lo aprendido". Keys are lowercase
 * ([Tokens.key]); only plain words ([Tokens.learnable]) are kept. Not thread-safe: main thread.
 */
class Rejections {
    /** One rejected correction, lowercase. */
    data class Entry(val typed: String, val fix: String)

    private val list = ArrayList<Entry>() // oldest first

    val size: Int get() = list.size

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

    /** Space must not turn [typed] into [fix]. */
    fun rejects(typed: String, fix: String): Boolean {
        val e = entry(typed, fix) ?: return false
        return list.contains(e)
    }

    fun forget(typed: String, fix: String): Boolean = entry(typed, fix)?.let { list.remove(it) } == true

    /** Newest first, as "Lo que sé de ti" lists them. */
    fun items(): List<Entry> = list.asReversed().toList()

    fun clear() = list.clear()

    fun isEmpty(): Boolean = list.isEmpty()

    fun toJson(): String {
        val b = StringBuilder("{\"v\":").append(VERSION).append(",\"rejected\":[")
        list.forEachIndexed { i, e ->
            if (i > 0) b.append(',')
            b.append('[').append(MiniJson.quote(e.typed)).append(',').append(MiniJson.quote(e.fix)).append(']')
        }
        return b.append("]}").toString()
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
            return r
        }
    }
}
