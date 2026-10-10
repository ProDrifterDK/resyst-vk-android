package com.resyst.vk.core

/**
 * r10 (F-5, V5–V6): the "Libro de conexiones". Every request the app makes is one [Entry] here —
 * when, what, to whom ([What.host]), why (the user's tap or the keyboard opening) and how it
 * ended. Settings shows the count since install and the last [CAP] entries, so the user can SEE
 * the promise instead of reading it.
 *
 * r11b (GL1–GL4): two clients now write here — the updater (kv.resyst.cl) and, only when the
 * user turned it on, the GIF search (KLIPY). A GIF page is ONE entry; the thumbnails fetched for
 * it are summarized in its [Entry.media] count (one entry per thumbnail would flood the book).
 * Each entry has a sequence number [Entry.n] so a page logged when the API answered can be
 * amended with its media count later ([amend]) without a second entry.
 *
 * Pure and immutable; stored as a small JSON string in the updater's own prefs, on the device.
 * A corrupt stored log reads as empty (never throws); [total] and [firstAt] survive the cap.
 * v1 logs (r10, updater only) still read: their entries get n = their position.
 */
data class ConnectionLog(
    private val items: List<Entry> = emptyList(),
    val total: Int = 0,
    /** Time of the first request ever logged, or null. */
    val firstAt: Long? = null,
) {
    enum class What(val id: String, val label: String, val host: String) {
        CHECK("check", "Consulta de versión", "kv.resyst.cl"),
        DOWNLOAD("download", "Descarga de actualización", "kv.resyst.cl"),
        GIF_TRENDING("gif_trending", "GIF en tendencia", "KLIPY"),
        GIF_SEARCH("gif_search", "Búsqueda de GIF", "KLIPY"),
        GIF_FILE("gif_file", "Descarga del GIF elegido", "KLIPY"),
        GIF_SHARE("gif_share", "Aviso de GIF enviado", "KLIPY"),
        ;
        val gif: Boolean get() = host == "KLIPY"
    }
    /**
     * [STARTUP] is what 0.6–0.7.0 stored for their once-per-process check; it stays so those
     * entries still decode and render (an unknown id is dropped, OC15). New automatic checks are [OPEN].
     */
    enum class Why(val id: String, val label: String) {
        USER("user", "tú lo pediste"),
        STARTUP("startup", "al iniciar el teclado"),
        OPEN("open", "al abrir el teclado"),
    }

    /** [media] = media files fetched for this request (GIF thumbnails of a page, the chosen GIF). [n] = sequence number, 1-based. */
    data class Entry(val at: Long, val what: What, val why: Why, val outcome: String, val media: Int = 0, val n: Int = 0)

    fun add(e: Entry): ConnectionLog {
        val n = total + 1
        val clean = e.copy(outcome = clean(e.outcome), media = e.media.coerceAtLeast(0), n = n)
        return ConnectionLog((listOf(clean) + items).take(CAP), n, firstAt ?: e.at)
    }

    /** The number [add] gives the next entry. */
    val nextN: Int get() = total + 1

    /** Entry [n] gets [media] (and a new [outcome] when given); unknown / evicted n = unchanged. */
    fun amend(n: Int, media: Int, outcome: String? = null): ConnectionLog {
        if (items.none { it.n == n }) return this
        return copy(items = items.map { if (it.n == n) it.copy(media = media.coerceAtLeast(0), outcome = outcome?.let(::clean) ?: it.outcome) else it })
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
                .append(MiniJson.quote(e.why.id)).append(',').append(MiniJson.quote(e.outcome)).append(',')
                .append(e.media).append(',').append(e.n).append(']')
        }
        return b.append("]}").toString()
    }

    companion object {
        const val VERSION = 2
        const val CAP = 50
        const val OUTCOME_MAX = 80

        private fun clean(s: String) = s.replace(Regex("\\s+"), " ").trim().take(OUTCOME_MAX)

        fun decode(text: String?): ConnectionLog {
            if (text.isNullOrBlank()) return ConnectionLog()
            val root = runCatching { MiniJson.parse(text) }.getOrNull() as? Map<*, *> ?: return ConnectionLog()
            val v = (root["v"] as? Number)?.toInt()
            if (v != 1 && v != VERSION) return ConnectionLog()
            val raw = (root["items"] as? List<*> ?: emptyList<Any>()).mapNotNull { it as? List<*> }
            val listedTotal = (root["total"] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0
            val out = ArrayList<Entry>()
            for (a in raw) {
                val at = (a.getOrNull(0) as? Number)?.toLong() ?: continue
                val what = What.values().firstOrNull { it.id == a.getOrNull(1) } ?: continue
                val why = Why.values().firstOrNull { it.id == a.getOrNull(2) } ?: continue
                val outcome = (a.getOrNull(3) as? String)?.take(OUTCOME_MAX) ?: ""
                val media = if (v == 1) 0 else (a.getOrNull(4) as? Number)?.toInt()?.takeIf { it >= 0 } ?: continue
                val n = if (v == 1) 0 else (a.getOrNull(5) as? Number)?.toInt()?.takeIf { it > 0 } ?: continue
                if (out.size < CAP) out += Entry(at, what, why, outcome, media, n)
            }
            if (v == 1) {
                // v1 had no sequence numbers: newest first, so the i-th kept entry is number top - i
                // (junk entries are dropped first: they never raise the total, V6)
                val top = maxOf(listedTotal, out.size)
                for (i in out.indices) out[i] = out[i].copy(n = top - i)
            }
            val total = maxOf(listedTotal, out.size, out.maxOfOrNull { it.n } ?: 0)
            val first = (root["first"] as? Number)?.toLong() ?: out.minOfOrNull { it.at }
            return ConnectionLog(out, total, first)
        }
    }
}
