package com.resyst.vk.core

/**
 * What the user copied, newest first, on this phone only (round 6). Bounded on every axis:
 *
 * - [MAX_ITEMS] entries; a full history evicts its oldest unpinned entry (C5).
 * - [MAX_BYTES] per entry (UTF-8); a larger clip is skipped silently — the paste chip still
 *   pastes it straight from the system clipboard, only the history doesn't keep a copy (C2).
 * - [MAX_PINS] pinned entries, so most slots always roll (C6).
 *
 * Dedup: copying text the history already holds moves that entry to the top and counts it (C4).
 * The IME re-reads the clipboard every time a field gains focus; the system's clip timestamp
 * ([capture]'s `stamp`) tells a re-read from a real copy, and the last stamp seen survives
 * deletes and "Borrar todo", so a deleted entry never comes back from a re-read (C3).
 *
 * Times are wall-clock millis (the optional 1-hour purge must survive process death).
 */
class ClipboardHistory {
    data class Entry(val id: Long, val text: String, val at: Long, val count: Int, val pinned: Boolean)

    enum class Capture { ADDED, BUMPED, UNCHANGED, EMPTY, TOO_LARGE }

    /** Newest first (by last copy). */
    private val list = ArrayList<Entry>()
    private var nextId = 1L
    /** System timestamp of the last clip captured or recognised; 0 = none. */
    private var lastStamp = 0L

    val size: Int get() = list.size

    fun isEmpty(): Boolean = list.isEmpty()

    /**
     * Records a clip. [stamp] is the system's clip timestamp (ClipDescription.getTimestamp);
     * 0 when unknown, then only "same text as the newest entry" counts as a re-read.
     */
    fun capture(text: String, now: Long, stamp: Long): Capture {
        if (text.isBlank()) return Capture.EMPTY
        if (!fits(text)) return Capture.TOO_LARGE
        if (stamp != 0L && stamp == lastStamp) return Capture.UNCHANGED
        if (stamp == 0L && list.firstOrNull()?.text == text) return Capture.UNCHANGED
        lastStamp = stamp
        val i = list.indexOfFirst { it.text == text }
        if (i >= 0) {
            val e = list.removeAt(i)
            list.add(0, e.copy(at = now, count = (e.count + 1).coerceAtMost(COUNT_CAP)))
            return Capture.BUMPED
        }
        list.add(0, Entry(nextId++, text, now, 1, pinned = false))
        while (list.size > MAX_ITEMS) {
            val victim = list.indexOfLast { !it.pinned }.takeIf { it >= 0 } ?: break
            list.removeAt(victim)
        }
        return Capture.ADDED
    }

    /** Panel order: pinned first, then newest first within each group (C10). */
    fun items(): List<Entry> = list.filter { it.pinned } + list.filter { !it.pinned }

    fun get(id: Long): Entry? = list.firstOrNull { it.id == id }

    fun delete(id: Long): Boolean = list.removeAll { it.id == id }

    /** False when [id] is unknown or the pin ceiling is reached (C6, C9). */
    fun setPinned(id: Long, pinned: Boolean): Boolean {
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return false
        if (list[i].pinned == pinned) return true
        if (pinned && list.count { it.pinned } >= MAX_PINS) return false
        list[i] = list[i].copy(pinned = pinned)
        return true
    }

    val pinCount: Int get() = list.count { it.pinned }

    /** Drops unpinned entries last copied more than [maxAgeMs] ago (C7). True if any went. */
    fun purge(now: Long, maxAgeMs: Long): Boolean = list.removeAll { !it.pinned && now - it.at > maxAgeMs }

    /** "Borrar todo": every entry goes, pinned too. The last stamp stays (C3). */
    fun clear() { list.clear() }

    fun toJson(): String {
        val b = StringBuilder("{\"v\":").append(VERSION)
            .append(",\"next\":").append(nextId)
            .append(",\"last\":").append(lastStamp)
            .append(",\"items\":[")
        list.forEachIndexed { i, e ->
            if (i > 0) b.append(',')
            b.append('[').append(e.id).append(',').append(MiniJson.quote(e.text)).append(',')
                .append(e.at).append(',').append(e.count).append(',').append(if (e.pinned) 1 else 0).append(']')
        }
        return b.append("]}").toString()
    }

    companion object {
        const val VERSION = 1
        const val MAX_ITEMS = 25
        const val MAX_BYTES = 100 * 1024
        const val MAX_PINS = 10
        const val COUNT_CAP = 1000
        /** "Purgar tras 1 hora". */
        const val PURGE_MS = 60 * 60 * 1000L

        /** UTF-8 size ≤ [MAX_BYTES], counted without allocating a byte copy of a huge clip. */
        fun fits(s: String): Boolean {
            if (s.length > MAX_BYTES) return false // every char is ≥ 1 byte
            var bytes = 0
            var i = 0
            while (i < s.length) {
                val c = s[i]
                bytes += when {
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> { i++; 4 }
                    else -> 3
                }
                if (bytes > MAX_BYTES) return false
                i++
            }
            return true
        }

        /** Corrupt / foreign / tampered storage → an empty or sanitized history, never a crash (C8). */
        fun fromJson(text: String): ClipboardHistory {
            val h = ClipboardHistory()
            val root = runCatching { MiniJson.parse(text) }.getOrNull() as? Map<*, *> ?: return h
            if ((root["v"] as? Number)?.toLong() != VERSION.toLong()) return h
            var maxId = 0L
            var pins = 0
            for (raw in root["items"] as? List<*> ?: emptyList<Any?>()) {
                val a = raw as? List<*> ?: continue
                val id = (a.getOrNull(0) as? Long)?.takeIf { it > 0 } ?: continue
                val t = a.getOrNull(1) as? String ?: continue
                val at = a.getOrNull(2) as? Long ?: continue
                val count = (a.getOrNull(3) as? Long)?.takeIf { it > 0 }?.coerceAtMost(COUNT_CAP.toLong())?.toInt() ?: continue
                var pinned = (a.getOrNull(4) as? Long) == 1L
                if (t.isBlank() || !fits(t) || h.list.any { it.id == id || it.text == t }) continue
                if (h.list.size >= MAX_ITEMS) break
                if (pinned && pins >= MAX_PINS) pinned = false
                if (pinned) pins++
                h.list += Entry(id, t, at, count, pinned)
                maxId = maxOf(maxId, id)
            }
            h.nextId = maxOf((root["next"] as? Long) ?: 1L, maxId + 1)
            h.lastStamp = (root["last"] as? Long) ?: 0L
            return h
        }
    }
}
