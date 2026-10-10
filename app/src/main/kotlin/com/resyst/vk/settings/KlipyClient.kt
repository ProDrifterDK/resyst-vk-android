package com.resyst.vk.settings

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.resyst.vk.BuildConfig
import com.resyst.vk.core.ConnectionLog
import com.resyst.vk.core.GifCopy
import com.resyst.vk.core.GifPage
import com.resyst.vk.core.KlipyParse
import com.resyst.vk.core.KlipyUrls
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

/**
 * r11b: the GIF search's network code, the app's second (and last) client next to [Updater].
 * Same style: HttpsURLConnection, timeouts, byte caps, a generic User-Agent, no cookies, no
 * cache, redirects never followed (GA2). Every URL goes through [KlipyUrls] first (GA1): a URL
 * that fails is never opened. Every API request is one [ConnectionBook] entry (GL1); thumbnails
 * are counted onto their page's entry by the caller ([ConnectionBook.amend]).
 *
 * Nothing here runs on its own: each call comes from a tap / scroll in the GIF tab (GO4), and
 * [cancelAll] (tab change, panel hidden, field finished, feature off) disconnects whatever is in
 * flight and drops its callbacks.
 */
object KlipyClient {
    private const val TAG = "ResystVK"
    const val USER_AGENT = "ResystVK"
    private const val CONNECT_MS = 8_000
    private const val READ_MS = 10_000

    /** The key built in from keystore/klipy.key; empty in public clones (GK2: feature hidden). */
    val key: String get() = BuildConfig.KLIPY_KEY
    val available: Boolean get() = KlipyUrls.keyOk(key)

    /** API requests: one at a time (GE2). Media: a few in parallel. */
    private val api = Executors.newSingleThreadExecutor()
    private val media = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val open: MutableSet<HttpsURLConnection> = Collections.synchronizedSet(HashSet())

    /** Bumped by [cancelAll]: a callback whose generation is stale is dropped. */
    @Volatile var generation = 0
        private set

    sealed class Result {
        /** [entry] = this request's number in the book (thumbnails are amended onto it). */
        data class Page(val page: GifPage, val entry: Int) : Result()
        data class Failed(val fail: GifCopy.Fail) : Result()
    }

    fun cancelAll() {
        generation++
        val list = synchronized(open) { open.toList().also { open.clear() } }
        if (list.isNotEmpty()) media.execute { for (c in list) runCatching { c.disconnect() } }
    }

    /** One trending (query null) or search page. [cb] on main, unless cancelled meanwhile. */
    fun page(context: Context, query: String?, page: Int, params: KlipyUrls.Params, cb: (Result) -> Unit) {
        val app = context.applicationContext
        val url = (if (query == null) KlipyUrls.trending(key, page, params) else KlipyUrls.search(key, query, page, params))
        val gen = generation
        if (url == null) { main.post { if (gen == generation) cb(Result.Failed(GifCopy.Fail.BAD_RESPONSE)) }; return }
        val what = if (query == null) ConnectionLog.What.GIF_TRENDING else ConnectionLog.What.GIF_SEARCH
        debug("GET ${KlipyUrls.redact(url, key, params.customerId)}")
        api.execute {
            if (gen != generation) return@execute // cancelled before it left: no request, no entry
            var status = 0
            val result: Result = try {
                val body = String(get(url, KlipyParse.MAX_JSON.toLong(), KlipyUrls::isApi) { status = it }, Charsets.UTF_8)
                val p = KlipyParse.page(body)
                if (!p.ok) Result.Failed(GifCopy.Fail.BAD_RESPONSE) else Result.Page(p, 0)
            } catch (e: Exception) {
                Result.Failed(if (gen != generation) GifCopy.Fail.CANCELLED else fail(e))
            }
            val n = ConnectionBook.add(app, what, ConnectionLog.Why.USER,
                GifCopy.pageOutcome(query, page, (result as? Result.Page)?.page?.items?.size ?: 0, (result as? Result.Failed)?.fail, status))
            val out = if (result is Result.Page) result.copy(entry = n) else result
            main.post { if (gen == generation) cb(out) }
        }
    }

    /**
     * One thumbnail (≤ [cap] bytes), turned into [T] by [decode] on the media thread (bounded
     * decode, GM1); null on any failure. [fetched] = it reached the server (counted on the page's
     * entry). A cancelled load calls back with (null, fetched) and is never decoded.
     */
    fun <T> thumb(url: String, cap: Long, decode: (ByteArray) -> T?, cb: (T?, fetched: Boolean) -> Unit) {
        val gen = generation
        if (!KlipyUrls.isMedia(url)) { cb(null, false); return }
        media.execute {
            if (gen != generation) { main.post { cb(null, false) }; return@execute }
            var reached = false
            val bytes = runCatching { get(url, cap, KlipyUrls::isMedia) { reached = true } }.getOrNull()
            val value = if (bytes != null && gen == generation) runCatching { decode(bytes) }.getOrNull() else null
            main.post { cb(if (gen == generation) value else null, reached) }
        }
    }

    /**
     * The chosen GIF, into [dest] (one file, replaced on every pick, GC2). Logged as its own entry
     * with 1 media file. [cb] gets the file or the failure, on main.
     */
    fun file(context: Context, url: String, cap: Long, dest: File, cb: (File?, GifCopy.Fail?) -> Unit) {
        val app = context.applicationContext
        val gen = generation
        if (!KlipyUrls.isMedia(url)) { cb(null, GifCopy.Fail.BAD_RESPONSE); return }
        debug("GET media (chosen GIF)")
        api.execute {
            var status = 0
            val fail: GifCopy.Fail? = try {
                val bytes = get(url, cap, KlipyUrls::isMedia) { status = it }
                dest.parentFile?.mkdirs()
                dest.outputStream().use { it.write(bytes) }
                null
            } catch (e: Exception) {
                dest.delete()
                if (gen != generation) GifCopy.Fail.CANCELLED else fail(e)
            }
            ConnectionBook.add(app, ConnectionLog.What.GIF_FILE, ConnectionLog.Why.USER,
                if (fail == null) "${dest.length() / 1024} KB" else fail.log + if (fail == GifCopy.Fail.HTTP && status > 0) " $status" else "",
                media = if (status > 0) 1 else 0)
            main.post {
                if (gen != generation) { dest.delete(); return@post } // cancelled: the field is gone, nothing to commit
                if (fail == null) cb(dest, null) else cb(null, fail)
            }
        }
    }

    /** KLIPY's share trigger for a committed GIF (their measurement contract). Logged; the result is not shown. */
    fun share(context: Context, slug: String, customerId: String, query: String?) {
        val app = context.applicationContext
        val url = KlipyUrls.share(key, slug) ?: return
        val body = KlipyUrls.shareBody(customerId, query).toByteArray(Charsets.UTF_8)
        debug("POST ${KlipyUrls.redact(url, key, customerId)}")
        api.execute {
            var status = 0
            val outcome = try {
                post(url, body) { status = it }
                "Enviado · $slug"
            } catch (e: Exception) {
                fail(e).log + if (status > 0) " ($status)" else ""
            }
            ConnectionBook.add(app, ConnectionLog.What.GIF_SHARE, ConnectionLog.Why.USER, outcome.take(ConnectionLog.OUTCOME_MAX))
        }
    }

    // ── transport ───────────────────────────────────────────────────────

    private class HttpStatus(val code: Int) : IOException("HTTP $code")
    private class TooLarge : IOException("too large")

    private fun connect(url: String, allowed: (String) -> Boolean): HttpsURLConnection {
        require(allowed(url)) { "url not allowed" } // GA1: never opened (message carries no URL: GK1)
        val c = URL(url).openConnection() as HttpsURLConnection
        c.instanceFollowRedirects = false // GA2
        c.connectTimeout = CONNECT_MS
        c.readTimeout = READ_MS
        c.useCaches = false
        c.setRequestProperty("User-Agent", USER_AGENT) // no device model / OS build (Dalvik's default carries both)
        return c
    }

    private fun get(url: String, cap: Long, allowed: (String) -> Boolean, onStatus: (Int) -> Unit): ByteArray {
        val c = connect(url, allowed)
        open += c
        try {
            val code = c.responseCode
            onStatus(code)
            if (code != 200) throw HttpStatus(code)
            if (c.contentLengthLong > cap) throw TooLarge()
            return c.inputStream.use { capped(it, cap) }
        } finally {
            open -= c
            c.disconnect()
        }
    }

    private fun post(url: String, body: ByteArray, onStatus: (Int) -> Unit) {
        val c = connect(url, KlipyUrls::isApi)
        open += c
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { o: OutputStream -> o.write(body) }
            val code = c.responseCode
            onStatus(code)
            if (code !in 200..299) throw HttpStatus(code)
            c.inputStream.use { capped(it, 64_000) }
        } finally {
            open -= c
            c.disconnect()
        }
    }

    private fun capped(input: InputStream, cap: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > cap) throw TooLarge()
        }
        return out.toByteArray()
    }

    private fun fail(e: Exception): GifCopy.Fail = when (e) {
        is HttpStatus -> GifCopy.Fail.ofStatus(e.code) ?: GifCopy.Fail.HTTP
        is TooLarge -> GifCopy.Fail.TOO_LARGE
        is UnknownHostException -> GifCopy.Fail.OFFLINE
        is java.net.ConnectException, is java.net.NoRouteToHostException -> GifCopy.Fail.OFFLINE
        is SocketTimeoutException -> GifCopy.Fail.TIMEOUT
        is SSLException -> GifCopy.Fail.TLS
        else -> GifCopy.Fail.OFFLINE
    }

    /** Debuggable builds only, and never the key or the id (GK1): the E2E counts these lines. */
    private fun debug(msg: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, "klipy: $msg")
    }
}
