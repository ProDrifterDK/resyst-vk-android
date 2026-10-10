package com.resyst.vk.core

/** One file of a GIF in one size + format, exactly as KLIPY returned it (URL untouched, GA3). */
data class GifMedia(val url: String, val width: Int, val height: Int, val bytes: Long, val format: String) {
    val mime: String get() = KlipyParse.MIMES[format] ?: "application/octet-stream"
}

/** One result. [files] = size (hd, md, sm, xs) → format (gif, webp, jpg, mp4, webm) → media. */
data class Gif(val slug: String, val title: String, val files: Map<String, Map<String, GifMedia>>)

/**
 * One page of trending / search. [ok] = the response was a readable KLIPY page; [skipped] = items
 * we could not draw at all (not a GIF, or no allowlisted media) — counted, never silently hidden.
 */
data class GifPage(val items: List<Gif>, val page: Int, val hasNext: Boolean, val ok: Boolean, val skipped: Int = 0) {
    companion object { val BAD = GifPage(emptyList(), 0, hasNext = false, ok = false) }
}

/**
 * r11b (GP1–GP3, GF1–GF2): KLIPY responses → [GifPage], and which file to preview / insert.
 * Tolerant: anything malformed is [GifPage.BAD], never an exception. Order is the response order
 * (KLIPY rule: no reordering, inserting, removing); ads are never parsed (monetization is off,
 * an item whose type is not "gif" is skipped and counted).
 */
object KlipyParse {
    /** Byte caps: the page JSON, one preview thumbnail, the GIF handed to the app. */
    const val MAX_JSON = 1_000_000
    const val PREVIEW_CAP = 400_000L
    const val INSERT_CAP = 4_000_000L

    val MIMES = mapOf("gif" to "image/gif", "webp" to "image/webp", "jpg" to "image/jpeg", "mp4" to "video/mp4", "webm" to "video/webm")

    private val SIZES = listOf("hd", "md", "sm", "xs")
    private val FORMATS = listOf("gif", "webp", "jpg", "mp4", "webm")

    fun page(text: String?): GifPage {
        if (text.isNullOrBlank() || text.length > MAX_JSON) return GifPage.BAD
        val root = runCatching { MiniJson.parse(text) }.getOrNull() as? Map<*, *> ?: return GifPage.BAD
        if (root["result"] != true) return GifPage.BAD
        val data = root["data"] as? Map<*, *> ?: return GifPage.BAD
        val list = data["data"] as? List<*> ?: return GifPage.BAD
        val items = ArrayList<Gif>(list.size)
        var skipped = 0
        for (raw in list) {
            val g = item(raw as? Map<*, *>)
            if (g == null) skipped++ else items += g
        }
        val page = (data["current_page"] as? Number)?.toInt()?.takeIf { it >= 1 } ?: 1
        val hasNext = data["has_next"] == true && list.isNotEmpty() // GP3: never page past an empty page
        return GifPage(items, page, hasNext, ok = true, skipped = skipped)
    }

    private fun item(m: Map<*, *>?): Gif? {
        if (m == null || m["type"] != "gif") return null
        val slug = (m["slug"] as? String)?.takeIf { KlipyUrls.slugOk(it) } ?: return null
        val file = m["file"] as? Map<*, *> ?: return null
        val files = LinkedHashMap<String, Map<String, GifMedia>>()
        for (size in SIZES) {
            val fm = file[size] as? Map<*, *> ?: continue
            val out = LinkedHashMap<String, GifMedia>()
            for (f in FORMATS) media(fm[f] as? Map<*, *>, f)?.let { out[f] = it }
            if (out.isNotEmpty()) files[size] = out
        }
        if (files.isEmpty()) return null
        val title = (m["title"] as? String)?.replace(Regex("\\s+"), " ")?.trim()?.take(120) ?: ""
        val g = Gif(slug, title, files)
        return if (preview(g, animated = true) != null || preview(g, animated = false) != null) g else null
    }

    private fun media(m: Map<*, *>?, format: String): GifMedia? {
        if (m == null) return null
        val url = m["url"] as? String ?: return null
        if (!KlipyUrls.isMedia(url)) return null
        val w = (m["width"] as? Number)?.toInt() ?: 0
        val h = (m["height"] as? Number)?.toInt() ?: 0
        val size = (m["size"] as? Number)?.toLong() ?: 0L
        if (w <= 0 || h <= 0 || w > 4096 || h > 4096) return null
        return GifMedia(url, w, h, size, format)
    }

    /**
     * GF1: the thumbnail. Animated: the smallest gif / webp of `sm`, else of `xs`, by declared
     * byte size and under [cap] (a size-less file can't be bounded and is not a candidate).
     * Static (API 26–27, no animated decoder): the `sm` jpg, else the `xs` one.
     */
    fun preview(g: Gif, animated: Boolean, cap: Long = PREVIEW_CAP): GifMedia? {
        val formats = if (animated) listOf("gif", "webp") else listOf("jpg")
        for (size in listOf("sm", "xs")) {
            val pick = formats.mapNotNull { g.files[size]?.get(it) }.filter { it.bytes in 1..cap }.minByOrNull { it.bytes }
            if (pick != null) return pick
        }
        return null
    }

    /** The file the grid actually fetches for [g]: the animated preview, else the static one. */
    fun thumbnail(g: Gif, animated: Boolean): GifMedia? = preview(g, animated) ?: preview(g, animated = false)

    /** width / height of [thumbnail] (review m3: the cell matches the decoded file); 1 if none. */
    fun previewAspect(g: Gif, animated: Boolean): Float =
        thumbnail(g, animated)?.let { it.width.toFloat() / it.height } ?: 1f

    /**
     * GF2: the file handed to the app. Only animated image types the field declared (wildcards
     * honored); never jpg / video. Sizes md → sm → xs (hd gifs run to several MB), gif before
     * webp within a size (wider support in messengers), first one under [cap]. null = this field
     * takes none of them: nothing is downloaded.
     */
    fun insert(g: Gif, accept: List<String>, cap: Long = INSERT_CAP): GifMedia? {
        val formats = listOf("gif", "webp").filter { f -> accept.any { ClipRules.mimeMatches(it, MIMES.getValue(f)) } }
        if (formats.isEmpty()) return null
        for (size in listOf("md", "sm", "xs")) for (f in formats) {
            val m = g.files[size]?.get(f) ?: continue
            if (m.bytes in 1..cap) return m
        }
        return null
    }

    /** True when the field declared at least one type [insert] could hand it. */
    fun fieldTakesGifs(accept: List<String>): Boolean =
        listOf("image/gif", "image/webp").any { m -> accept.any { ClipRules.mimeMatches(it, m) } }
}
