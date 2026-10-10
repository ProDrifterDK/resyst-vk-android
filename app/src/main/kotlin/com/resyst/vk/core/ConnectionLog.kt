package com.resyst.vk.core

/**
 * r10 (F-5, V5–V6): the "Libro de conexiones". Resyst VK has ONE network path (Updater.kt); every
 * request it makes is one [Entry] here — when, what (version check or APK download), why (the
 * user's tap or the keyboard opening) and how it ended. Settings shows the count since install and
 * the last [CAP] entries, so the user can SEE the promise instead of reading it.
 *
 * Pure and immutable; stored as a small JSON string in the updater's own prefs, on the device.
 * A corrupt stored log reads as empty (never throws); [total] and [firstAt] survive the cap.
 */
data class ConnectionLog(
    private val items: List<Entry> = emptyList(),
    val total: Int = 0,
    /** Time of the first request ever logged, or null. */
    val firstAt: Long? = null,
) {
    enum class What(val id: String, val label: String) { CHECK("check", "Consulta de versión"), DOWNLOAD("download", "Descarga de actualización") }
    /**
     * [STARTUP] is what 0.6–0.7.0 stored for their once-per-process check; it stays so those
     * entries still decode and render (an unknown id is dropped, OC15). New automatic checks are [OPEN].
     */
    enum class Why(val id: String, val label: String) {
        USER("user", "tú lo pediste"),
        STARTUP("startup", "al iniciar el teclado"),
        OPEN("open", "al abrir el teclado"),
    }

    data class Entry(val at: Long, val what: What, val why: Why, val outcome: String)

    fun add(e: Entry): ConnectionLog {
        val clean = e.copy(outcome = e.outcome.replace(Regex("\\s+"), " ").trim().take(OUTCOME_MAX))
        return ConnectionLog((listOf(clean) + items).take(CAP), total + 1, firstAt ?: e.at)
    }

    /** Newest first, at most [CAP]. */
    fun recent(): List<Entry> = items

    fun count(why: Why): Int = items.count { it.why == why }

    fun encode(): String {
        val b = StringBuilder("{\"v\":").append(VERSION).append(",\"total\":").append(total)
        if (firstAt != null) b.append(",\"first\":").append(firstAt)
        b.append(",\"items\":[")
        items.forEachIndexed { i, e ->
            if (i > 0) b.append(',')
            b.append('[').append(e.at).append(',').append(MiniJson.quote(e.what.id)).append(',')
                .append(MiniJson.quote(e.why.id)).append(',').append(MiniJson.quote(e.outcome)).append(']')
        }
        return b.append("]}").toString()
    }

    companion object {
        const val VERSION = 1
        const val CAP = 50
        const val OUTCOME_MAX = 80

        fun decode(text: String?): ConnectionLog {
            if (text.isNullOrBlank()) return ConnectionLog()
            val root = runCatching { MiniJson.parse(text) }.getOrNull() as? Map<*, *> ?: return ConnectionLog()
            if ((root["v"] as? Number)?.toInt() != VERSION) return ConnectionLog()
            val out = ArrayList<Entry>()
            for (raw in root["items"] as? List<*> ?: emptyList<Any>()) {
                val a = raw as? List<*> ?: continue
                val at = (a.getOrNull(0) as? Number)?.toLong() ?: continue
                val what = What.values().firstOrNull { it.id == a.getOrNull(1) } ?: continue
                val why = Why.values().firstOrNull { it.id == a.getOrNull(2) } ?: continue
                val outcome = (a.getOrNull(3) as? String)?.take(OUTCOME_MAX) ?: ""
                if (out.size < CAP) out += Entry(at, what, why, outcome)
            }
            val total = maxOf((root["total"] as? Number)?.toInt() ?: 0, out.size)
            val first = (root["first"] as? Number)?.toLong() ?: out.minOfOrNull { it.at }
            return ConnectionLog(out, total, first)
        }
    }
}
