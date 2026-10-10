package com.resyst.vk.ime

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import com.resyst.vk.core.Gif
import com.resyst.vk.core.GifCopy
import com.resyst.vk.core.KlipyParse
import com.resyst.vk.core.KlipyUrls
import com.resyst.vk.settings.ConnectionBook
import com.resyst.vk.settings.KlipyClient
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * r11b: what the GIF tab shows for the current field — KLIPY's pages in their order, the
 * thumbnails decoded for the cells near the viewport, and the request state. Lives only while a
 * field is being edited: [clear] on field change / finish / feature off drops everything and
 * cancels whatever is in flight (GO4, GM1).
 *
 * Memory bounds (GM1): at most [MAX_ITEMS] results (pagination stops there), thumbnails only for
 * cells within one screen of the viewport (the rest are released), each ≤ [KlipyParse.PREVIEW_CAP]
 * bytes and decoded at most [MAX_PX] on its long side. Animations run only for visible cells and
 * stop when the tab is hidden. Thumbnails never touch the disk.
 *
 * Each page is one entry in the Libro de conexiones; the thumbnails fetched for that page are
 * counted onto it ([ConnectionBook.amend], debounced) instead of one entry each (GL1).
 */
class GifFeed(private val context: Context, private val view: () -> View?, private val changed: () -> Unit) {
    val items = ArrayList<Gif>()
    var query: String? = null
        private set
    var page = 0
        private set
    var hasNext = false
        private set
    var loading = false
        private set
    var fail: GifCopy.Fail? = null
        private set
    /** A page was answered at least once (an empty first page shows "Sin resultados"). */
    var answered = false
        private set

    private val animated = Build.VERSION.SDK_INT >= 28
    private val thumbs = HashMap<Int, Drawable>()
    private val pending = HashSet<Int>()
    private val failed = HashSet<Int>()
    /** (first item index, book entry) per page, in order. */
    private val pages = ArrayList<Pair<Int, Int>>()
    private val fetched = HashMap<Int, Int>()
    private val dirty = HashSet<Int>()
    private val main = Handler(Looper.getMainLooper())
    /** Bumped by [clear] / [load]: callbacks of an older feed are dropped. */
    private var token = 0

    fun thumb(i: Int): Drawable? = thumbs[i]

    val empty get() = items.isEmpty()

    /** First page of trending ([q] null) or of a search. One request. */
    fun load(q: String?, params: KlipyUrls.Params) {
        clear()
        query = q
        request(1, params)
    }

    /** Next page, when KLIPY said there is one and nothing is loading or failed (GE2, GP3). */
    fun more(params: KlipyUrls.Params): Boolean {
        if (!hasNext || loading || fail != null || items.size >= MAX_ITEMS) return false
        request(page + 1, params)
        return true
    }

    /** «Reintentar»: the user's tap, never automatic (GE1). */
    fun retry(params: KlipyUrls.Params) {
        if (loading) return
        fail = null
        if (items.isEmpty()) load(query, params) else request(page + 1, params)
    }

    private fun request(n: Int, params: KlipyUrls.Params) {
        loading = true
        fail = null
        val t = token
        changed()
        KlipyClient.page(context, query, n, params) { r ->
            if (t != token) return@page
            loading = false
            when (r) {
                is KlipyClient.Result.Page -> {
                    answered = true
                    pages += items.size to r.entry
                    items.addAll(r.page.items)
                    page = n
                    hasNext = r.page.hasNext && items.size < MAX_ITEMS
                    Log.i(TAG, "gif: page $n → ${r.page.items.size} items (skipped ${r.page.skipped}) has_next=${r.page.hasNext}")
                }
                is KlipyClient.Result.Failed -> {
                    fail = r.fail
                    Log.i(TAG, "gif: page $n failed → ${r.fail.name}")
                }
            }
            changed()
        }
    }

    /** Loads the thumbnails of [want] and releases those outside [keep] (GM1). */
    fun window(want: IntRange?, keep: IntRange?) {
        if (keep != null) {
            val drop = thumbs.keys.filter { it !in keep }
            for (i in drop) release(i)
        }
        if (want == null) return
        val t = token
        for (i in want) {
            if (i !in items.indices || i in thumbs || i in pending || i in failed) continue
            val g = items[i]
            val m = KlipyParse.preview(g, animated) ?: KlipyParse.preview(g, animated = false)
            if (m == null) { failed += i; continue }
            pending += i
            KlipyClient.thumb(m.url, KlipyParse.PREVIEW_CAP, { bytes -> decode(bytes, animated && m.format != "jpg") }) { d, reached ->
                if (t != token) { (d as? Animatable)?.stop(); return@thumb }
                pending -= i
                if (reached) count(i)
                if (d == null) { failed += i; changed(); return@thumb }
                d.callback = callback
                thumbs[i] = d
                changed()
            }
        }
    }

    /** Only the cells on screen animate; everything else is stopped (GM1). */
    fun animate(visible: IntRange?) {
        for ((i, d) in thumbs) {
            val a = d as? Animatable ?: continue
            if (visible != null && i in visible) { if (!a.isRunning) a.start() } else if (a.isRunning) a.stop()
        }
    }

    /** The tab is hidden: stop every animation and every load in flight; decoded thumbs stay (bounded). */
    fun pause() {
        animate(null)
        if (pending.isNotEmpty() || loading) {
            KlipyClient.cancelAll()
            pending.clear()
            if (loading) { loading = false; if (!answered) fail = null }
        }
        flushCounts()
    }

    /** Field change / finish / feature off: nothing survives (GO4). */
    fun clear() {
        token++
        KlipyClient.cancelAll()
        flushCounts()
        for (i in thumbs.keys.toList()) release(i)
        pending.clear(); failed.clear(); items.clear(); pages.clear(); fetched.clear()
        query = null; page = 0; hasNext = false; loading = false; fail = null; answered = false
    }

    private fun release(i: Int) {
        val d = thumbs.remove(i) ?: return
        (d as? Animatable)?.stop()
        d.callback = null
    }

    // ── the media count on each page's book entry (GL1/GL3) ─────────────
    private fun count(i: Int) {
        val entry = pages.lastOrNull { it.first <= i }?.second ?: return
        if (entry <= 0) return
        fetched[entry] = (fetched[entry] ?: 0) + 1
        dirty += entry
        main.removeCallbacks(flush)
        main.postDelayed(flush, 600)
    }

    private val flush = Runnable { flushCounts() }

    private fun flushCounts() {
        main.removeCallbacks(flush)
        if (dirty.isEmpty()) return
        val todo = dirty.toList()
        dirty.clear()
        val app = context.applicationContext
        val counts = todo.associateWith { fetched[it] ?: 0 }
        Thread { for ((e, n) in counts) ConnectionBook.amend(app, e, n) }.start()
    }

    private val callback = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) { view()?.postInvalidateOnAnimation() }
        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) { main.postAtTime(what, who, `when`) }
        override fun unscheduleDrawable(who: Drawable, what: Runnable) { main.removeCallbacks(what, who) }
    }

    private fun decode(bytes: ByteArray, wantAnimated: Boolean): Drawable? {
        val t0 = SystemClock.uptimeMillis()
        val d: Drawable? = if (Build.VERSION.SDK_INT >= 28 && wantAnimated) {
            val src = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            ImageDecoder.decodeDrawable(src) { dec, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val s = min(1f, MAX_PX.toFloat() / max(w, h).coerceAtLeast(1))
                if (s < 1f) dec.setTargetSize(max(1, (w * s).toInt()), max(1, (h * s).toInt()))
                dec.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
            }
        } else {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var sample = 1
            while (max(o.outWidth, o.outHeight) / (sample * 2) >= MAX_PX) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            bmp?.let { BitmapDrawable(context.resources, it) }
        }
        if (d is AnimatedImageDrawable) d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        val kind = if (d is AnimatedImageDrawable) "animated" else "static"
        if (com.resyst.vk.BuildConfig.DEBUG) Log.i(TAG, "gif: thumb decoded $kind ${d?.intrinsicWidth}x${d?.intrinsicHeight} ${bytes.size}B ${SystemClock.uptimeMillis() - t0}ms")
        return d
    }

    companion object {
        private const val TAG = "ResystVK"
        const val MAX_ITEMS = 96
        const val MAX_PX = 320
    }
}
