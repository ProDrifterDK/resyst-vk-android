package com.resyst.vk.core

/**
 * r11b (GA1–GA4): every URL the GIF search may touch, built and checked here. Requests go only
 * to [API] and media only from [MEDIA], over HTTPS, on the default port, with no userinfo,
 * fragment, dot segment, backslash or whitespace. The check is a prefix match on the full origin
 * plus a character whitelist, hand-made so no URL-parser quirk decides what the host is (same
 * approach as [UpdateChecker.resolveDownloadUrl]). Media URLs are used exactly as KLIPY returned
 * them: a URL that fails is dropped, never repaired (GA3).
 */
object KlipyUrls {
    const val API = "https://api.klipy.com/api/v1/"
    const val MEDIA = "https://static.klipy.com/"
    const val API_HOST = "api.klipy.com"
    const val MEDIA_HOST = "static.klipy.com"
    const val MAX_URL = 2048

    /** 25 results fill ~6 rows of the panel; one request per page (shared testing limit: 100/h). */
    const val PER_PAGE = 24

    private val KEY = Regex("[A-Za-z0-9_-]{16,128}")
    private val SLUG = Regex("[A-Za-z0-9_-]{1,200}")
    /** RFC 3986 unreserved + reserved + '%': anything else (space, quote, <, \, control, non-ASCII) is refused. */
    private val SAFE = Regex("[A-Za-z0-9\\-._~:/?\\[\\]@!$&'()*+,;=%]*")

    fun keyOk(key: String?): Boolean = key != null && KEY.matches(key)
    fun slugOk(slug: String?): Boolean = slug != null && SLUG.matches(slug)

    fun isApi(url: String?): Boolean = allowed(url, API)
    fun isMedia(url: String?): Boolean = allowed(url, MEDIA)

    private fun allowed(url: String?, origin: String): Boolean {
        if (url == null || url.length > MAX_URL || !url.startsWith(origin)) return false
        val rest = url.substring(origin.length)
        if (rest.isEmpty() || !SAFE.matches(rest)) return false
        val path = rest.substringBefore('?')
        if (path.split('/').any { it == ".." || it == "." }) return false
        return '#' !in rest && '@' !in path
    }

    /** What the request carries besides the key: the anonymous id, the region and the safety level. */
    data class Params(val customerId: String, val locale: String?, val contentFilter: String, val perPage: Int = PER_PAGE)

    fun trending(key: String, page: Int, p: Params): String? = page("trending", key, page, p, null)

    /** null when the query is blank: an empty search box means trending (GQ3). */
    fun search(key: String, query: String, page: Int, p: Params): String? =
        if (query.isBlank()) null else page("search", key, page, p, query)

    private fun page(kind: String, key: String, page: Int, p: Params, q: String?): String? {
        if (!keyOk(key) || page < 1) return null
        val b = StringBuilder(API).append(key).append("/gifs/").append(kind)
            .append("?page=").append(page)
            .append("&per_page=").append(p.perPage.coerceIn(8, 50))
        if (q != null) b.append("&q=").append(encode(q))
        b.append("&customer_id=").append(encode(p.customerId))
        p.locale?.let { b.append("&locale=").append(encode(it)) }
        b.append("&content_filter=").append(encode(p.contentFilter))
        return b.toString().takeIf(::isApi)
    }

    /** POST target of KLIPY's share trigger; null for a slug we would have to escape (GA4). */
    fun share(key: String, slug: String): String? {
        if (!keyOk(key) || !slugOk(slug)) return null
        return "$API$key/gifs/share/$slug".takeIf(::isApi)
    }

    /** JSON body of the share trigger: the anonymous id and the search that led to it ("" from trending). */
    fun shareBody(customerId: String, query: String?): String =
        "{\"customer_id\":${MiniJson.quote(customerId)},\"q\":${MiniJson.quote(query ?: "")}}"

    /** `high` while the offensive-word filter is on, `medium` when it is off (Alan's rule). */
    fun contentFilter(profanityFilter: Boolean): String = if (profanityFilter) "high" else "medium"

    /** ISO 3166 alpha-2 of the device locale, lower case, or null (then KLIPY picks). */
    fun locale(country: String?): String? = country?.lowercase()?.takeIf { it.length == 2 && it.all { c -> c in 'a'..'z' } }

    /** Percent-encodes everything but RFC 3986 unreserved characters, as UTF-8. */
    fun encode(s: String): String {
        val b = StringBuilder()
        for (byte in s.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xff
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '~') b.append(ch)
            else b.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
        }
        return b.toString()
    }

    /** For debug logs: the key and the id never appear (GK1). */
    fun redact(url: String, key: String, customerId: String?): String {
        var u = if (key.isNotEmpty()) url.replace(key, "{key}") else url
        if (!customerId.isNullOrEmpty()) u = u.replace(encode(customerId), "{id}")
        return u
    }
}
