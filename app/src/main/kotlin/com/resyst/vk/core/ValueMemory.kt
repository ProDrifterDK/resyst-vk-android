package com.resyst.vk.core

/**
 * Complete values the user enters in typed fields (today: email addresses), offered back by
 * prefix so a long address is one tap. Separate from the word model on purpose: different
 * data, different lifetime, wiped together only by "Borrar lo aprendido".
 *
 * At most [CAP] values per kind; a full kind evicts its lowest-count, oldest value. Ranking:
 * most used, then most recent. Case-insensitive identity, the latest spelling is kept.
 */
class ValueMemory {
    private class Value(var text: String, var count: Int, var stamp: Long)

    private val kinds = LinkedHashMap<FieldKind, ArrayList<Value>>()
    private var clock = 0L

    /** Records a finished field value; false when it isn't a complete value of [kind] (F1). */
    fun remember(kind: FieldKind, raw: String): Boolean {
        val v = raw.trim()
        if (!valid(kind, v)) return false
        val list = kinds.getOrPut(kind) { ArrayList() }
        val now = ++clock
        val hit = list.firstOrNull { it.text.equals(v, ignoreCase = true) }
        if (hit != null) {
            hit.text = v; hit.count = (hit.count + 1).coerceAtMost(COUNT_CAP); hit.stamp = now
            return true
        }
        if (list.size >= CAP) list.minWithOrNull(compareBy({ it.count }, { it.stamp }))?.let { list.remove(it) }
        list += Value(v, 1, now)
        return true
    }

    /** Values of [kind] starting with [typed] (case-insensitive), never [typed] itself (F2). */
    fun suggest(kind: FieldKind, typed: String, limit: Int): List<String> {
        val list = kinds[kind] ?: return emptyList()
        return list.asSequence()
            .filter { it.text.startsWith(typed, ignoreCase = true) && !it.text.equals(typed, ignoreCase = true) }
            .sortedWith(compareByDescending<Value> { it.count }.thenByDescending { it.stamp })
            .take(limit).map { it.text }.toList()
    }

    fun countOf(kind: FieldKind, value: String): Int =
        kinds[kind]?.firstOrNull { it.text.equals(value, ignoreCase = true) }?.count ?: 0

    /** r10 ("Lo que sé de ti", V12): every value of [kind], most used first. */
    fun values(kind: FieldKind): List<String> = suggest(kind, "", Int.MAX_VALUE)

    /** r10 (V12): forgets one remembered value (case-insensitive). True when it existed. */
    fun forget(kind: FieldKind, value: String): Boolean {
        val list = kinds[kind] ?: return false
        val removed = list.removeAll { it.text.equals(value.trim(), ignoreCase = true) }
        if (list.isEmpty()) kinds.remove(kind)
        return removed
    }

    fun clear() { kinds.clear(); clock = 0 }

    fun isEmpty(): Boolean = kinds.values.all { it.isEmpty() }

    /**
     * One editing session of one field: the value is remembered once, however many times the
     * session ends (action key, then field exit). A different final value counts again.
     */
    class Session(private val memory: ValueMemory?, private val kind: FieldKind) {
        private var last: String? = null

        fun commit(raw: String): Boolean {
            val m = memory ?: return false
            val v = raw.trim()
            if (v.equals(last, ignoreCase = true)) return false
            if (!m.remember(kind, v)) return false
            last = v
            return true
        }
    }

    fun toJson(): String {
        val b = StringBuilder("{\"v\":").append(VERSION).append(",\"clock\":").append(clock).append(",\"kinds\":{")
        var first = true
        for ((k, list) in kinds) {
            if (!first) b.append(','); first = false
            b.append(MiniJson.quote(k.name)).append(":[")
            list.forEachIndexed { i, v ->
                if (i > 0) b.append(',')
                b.append('[').append(MiniJson.quote(v.text)).append(',').append(v.count).append(',').append(v.stamp).append(']')
            }
            b.append(']')
        }
        return b.append("}}").toString()
    }

    companion object {
        const val VERSION = 1
        const val CAP = 20
        const val COUNT_CAP = 1000
        const val MAX_LEN = 80
        /** Values shown at once: they are long, two stay readable on a phone. */
        const val BAR_LIMIT = 2

        private val EMAIL = Regex("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$")

        fun valid(kind: FieldKind, v: String): Boolean = when (kind) {
            FieldKind.EMAIL -> v.length <= MAX_LEN && EMAIL.matches(v)
            else -> false
        }

        /** The value being typed: the chunk after the last whitespace before the cursor. */
        fun typed(before: CharSequence): String = before.toString().takeLastWhile { !it.isWhitespace() }

        fun bar(before: CharSequence, after: CharSequence, memory: ValueMemory?, kind: FieldKind): List<String> {
            if (memory == null || (after.isNotEmpty() && !after[0].isWhitespace())) return emptyList()
            return memory.suggest(kind, typed(before), BAR_LIMIT)
        }

        /** Replaces the typed chunk with the whole value; no trailing space (F3). */
        fun pick(before: CharSequence, value: String): List<Out> {
            val t = typed(before)
            return if (t.isEmpty()) listOf(Out.Commit(value)) else listOf(Out.DeleteBefore(t.length), Out.Commit(value))
        }

        fun fromJson(text: String): ValueMemory {
            val m = ValueMemory()
            val root = runCatching { MiniJson.parse(text) }.getOrNull() as? Map<*, *> ?: return m
            if ((root["v"] as? Number)?.toInt() != VERSION) return m
            val kinds = root["kinds"] as? Map<*, *> ?: return m
            var maxStamp = 0L
            for ((name, raw) in kinds) {
                val kind = FieldKind.values().firstOrNull { it.name == name } ?: continue
                val list = ArrayList<Value>()
                for (e in raw as? List<*> ?: continue) {
                    val a = e as? List<*> ?: continue
                    val v = a.getOrNull(0) as? String ?: continue
                    val count = (a.getOrNull(1) as? Long)?.takeIf { it > 0 }?.coerceAtMost(COUNT_CAP.toLong())?.toInt() ?: continue
                    val stamp = a.getOrNull(2) as? Long ?: continue
                    if (!valid(kind, v) || list.size >= CAP || list.any { it.text.equals(v, ignoreCase = true) }) continue
                    list += Value(v, count, stamp)
                    maxStamp = maxOf(maxStamp, stamp)
                }
                if (list.isNotEmpty()) m.kinds[kind] = list
            }
            m.clock = maxOf((root["clock"] as? Long) ?: 0L, maxStamp)
            return m
        }
    }
}
