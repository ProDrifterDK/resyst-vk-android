package com.resyst.vk.ime

import com.resyst.vk.R
import com.resyst.vk.core.Txt
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.resyst.vk.core.ClipRules
import com.resyst.vk.core.ClipboardHistory
import com.resyst.vk.core.Palette
import com.resyst.vk.core.Drawn
import com.resyst.vk.core.Ink
import com.resyst.vk.core.Surface
import kotlin.math.max
import kotlin.math.min

/**
 * The clipboard history panel, drawn by [KeyboardView] over the key area (same window, no
 * PopupWindow). Three states: the list (newest first, pinned on top, ~5 rows, scrollable), the
 * preview of one entry (long-press a row: full text + Pegar / Fijar / Borrar) and the
 * "¿Borrar todo?" confirmation in the header. Layout and hit areas are recomputed by [relayout]
 * after every state change; the view routes touches and accessibility through [hits].
 */
class ClipboardPanel(private val dp: Float, private val tx: Txt) {
    enum class Act { CLOSE, CLEAR_ASK, CLEAR_YES, CLEAR_NO, ROW, BACK, PASTE, PIN, DELETE }
    class Hit(val act: Act, val id: Long, val rect: RectF, val desc: String)

    var items: List<ClipboardHistory.Entry> = emptyList()
        private set
    var now = 0L
    /** Entry shown in the preview, or null for the list. */
    var detail: Long? = null
        private set
    var confirmClear = false
        private set
    private var scroll = 0f

    val bounds = RectF()
    val hits = ArrayList<Hit>()
    private val headerH get() = 44 * dp
    private val listTop get() = bounds.top + headerH
    private val rowH get() = max(42 * dp, (bounds.bottom - listTop - 6 * dp) / VISIBLE_ROWS)

    fun setItems(list: List<ClipboardHistory.Entry>, now: Long) {
        items = list
        this.now = now
        if (detail != null && list.none { it.id == detail }) detail = null
        if (list.isEmpty()) confirmClear = false
        scroll = scroll.coerceIn(0f, maxScroll())
        relayout()
    }

    fun reset() { detail = null; confirmClear = false; scroll = 0f; relayout() }
    fun showDetail(id: Long) { if (items.any { it.id == id }) { detail = id; confirmClear = false; relayout() } }
    fun back() { detail = null; relayout() }
    fun askClear(ask: Boolean) { confirmClear = ask && items.isNotEmpty(); relayout() }

    fun setBounds(r: RectF) { bounds.set(r); scroll = scroll.coerceIn(0f, maxScroll()); relayout() }

    /** Drags the list; true when the offset changed. */
    fun scrollBy(dy: Float): Boolean {
        val s = (scroll + dy).coerceIn(0f, maxScroll())
        if (s == scroll) return false
        scroll = s
        relayout()
        return true
    }

    private fun maxScroll(): Float = max(0f, items.size * rowH - (bounds.bottom - listTop - 6 * dp))

    fun hitAt(x: Float, y: Float): Hit? = hits.firstOrNull { it.rect.contains(x, y) }

    fun relayout() {
        hits.clear()
        if (bounds.width() <= 0f) return
        val pad = 8 * dp
        val hTop = bounds.top + 4 * dp
        val hBot = bounds.top + headerH - 4 * dp
        val d = detail?.let { id -> items.firstOrNull { it.id == id } }
        if (d != null) {
            hits += Hit(Act.BACK, 0, RectF(bounds.left + pad, hTop, bounds.left + pad + 96 * dp, hBot), tx.get(R.string.clip_back_list))
            val bw = (bounds.width() - 4 * pad) / 3
            val by = bounds.bottom - 50 * dp
            hits += Hit(Act.PASTE, d.id, RectF(bounds.left + pad, by, bounds.left + pad + bw, by + 42 * dp), tx.get(R.string.clip_paste))
            hits += Hit(Act.PIN, d.id, RectF(bounds.left + 2 * pad + bw, by, bounds.left + 2 * pad + 2 * bw, by + 42 * dp),
                tx.get(if (d.pinned) R.string.clip_unpin else R.string.clip_pin))
            hits += Hit(Act.DELETE, d.id, RectF(bounds.left + 3 * pad + 2 * bw, by, bounds.right - pad, by + 42 * dp), tx.get(R.string.clip_delete))
            return
        }
        val closeW = 64 * dp
        hits += Hit(Act.CLOSE, 0, RectF(bounds.right - pad - closeW, hTop, bounds.right - pad, hBot), tx.get(R.string.clip_close_desc))
        if (confirmClear) {
            val x = bounds.right - pad - closeW - 8 * dp
            hits += Hit(Act.CLEAR_NO, 0, RectF(x - 84 * dp, hTop, x, hBot), tx.get(R.string.clip_clear_no_desc))
            hits += Hit(Act.CLEAR_YES, 0, RectF(x - 84 * dp - 8 * dp - 104 * dp, hTop, x - 84 * dp - 8 * dp, hBot), tx.get(R.string.clip_clear_yes_desc))
        } else if (items.isNotEmpty()) {
            val x = bounds.right - pad - closeW - 8 * dp
            hits += Hit(Act.CLEAR_ASK, 0, RectF(x - 110 * dp, hTop, x, hBot), tx.get(R.string.clip_clear_ask_desc))
        }
        val bottom = bounds.bottom - 6 * dp
        items.forEachIndexed { i, e ->
            val top = listTop + i * rowH - scroll
            if (top + rowH > listTop && top < bottom) {
                val r = RectF(bounds.left + pad, max(top + 2 * dp, listTop), bounds.right - pad, min(top + rowH - 2 * dp, bottom))
                if (r.height() > 8 * dp) {
                    val pin = if (e.pinned) tx.get(R.string.clip_pinned_cap) + " " else ""
                    val times = if (e.count > 1) ", " + tx.get(R.string.clip_copied_times, e.count) else ""
                    hits += Hit(Act.ROW, e.id, r, tx.get(R.string.clip_row_desc, pin, ClipRules.label(e.text, false), ClipRules.ago(tx, now, e.at), times))
                }
            }
        }
    }

    // ── drawing ──────────────────────────────────────────────────────────
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val tmp = RectF()

    fun draw(c: Canvas, p: Palette, radius: Float, typeface: (Int) -> Typeface, pressed: Hit?) {
        val t = p.theme
        fill.shader = null
        fill.color = Drawn.surface(p, Surface.BACKGROUND)
        c.drawRect(bounds, fill)
        val d = detail?.let { id -> items.firstOrNull { it.id == id } }
        // header
        text.textAlign = Paint.Align.LEFT
        text.typeface = typeface(650)
        text.textSize = 15 * dp
        val hy = bounds.top + headerH / 2
        if (d != null) {
            drawPreview(c, p, radius, typeface, d, pressed)
            return
        }
        if (confirmClear) {
            text.color = Drawn.ink(p, Surface.BACKGROUND, Ink.BAD)
            drawLeft(c, tx.get(R.string.clip_clear_title), bounds.left + 14 * dp, hy)
        } else {
            drawClipIcon(c, bounds.left + 22 * dp, hy, 16 * dp, Drawn.ink(p, Surface.BACKGROUND, Ink.ACCENT))
            text.color = Drawn.ink(p, Surface.BACKGROUND, Ink.TEXT)
            drawLeft(c, tx.get(R.string.clip_title), bounds.left + 38 * dp, hy)
            if (items.isNotEmpty()) {
                text.color = Drawn.ink(p, Surface.BACKGROUND, Ink.MUTED)
                text.typeface = typeface(500)
                text.textSize = 12 * dp
                drawLeft(c, "${items.size}", bounds.left + 38 * dp + measure(tx.get(R.string.clip_title), 15 * dp, typeface(650)) + 8 * dp, hy)
            }
        }
        for (h in hits) when (h.act) {
            Act.CLOSE -> button(c, h, "ABC", Surface.MOD_PLAIN, Ink.TEXT_MOD, radius, typeface, h === pressed, p)
            Act.CLEAR_ASK -> button(c, h, tx.get(R.string.clip_clear_ask), Surface.PLAIN, Ink.BAD, radius, typeface, h === pressed, p)
            Act.CLEAR_YES -> button(c, h, tx.get(R.string.clip_clear_yes), Surface.BAD_FILL, Ink.BAD_INK, radius, typeface, h === pressed, p)
            Act.CLEAR_NO -> button(c, h, tx.get(R.string.clip_clear_no), Surface.PLAIN, Ink.TEXT, radius, typeface, h === pressed, p)
            else -> Unit
        }
        fill.color = t.edge
        c.drawRect(bounds.left, listTop - 1, bounds.right, listTop, fill)
        // list
        if (items.isEmpty()) {
            text.textAlign = Paint.Align.CENTER
            text.color = Drawn.ink(p, Surface.BACKGROUND, Ink.TEXT)
            text.typeface = typeface(600)
            text.textSize = 15 * dp
            val cy = (listTop + bounds.bottom) / 2
            drawCenter(c, tx.get(R.string.clip_empty), bounds.centerX(), cy - 12 * dp)
            text.color = Drawn.ink(p, Surface.BACKGROUND, Ink.MUTED)
            text.typeface = typeface(450)
            text.textSize = 12 * dp
            drawCenter(c, tx.get(R.string.clip_empty_hint), bounds.centerX(), cy + 12 * dp)
            return
        }
        c.save()
        c.clipRect(bounds.left, listTop, bounds.right, bounds.bottom)
        for (h in hits) {
            if (h.act != Act.ROW) continue
            val e = items.firstOrNull { it.id == h.id } ?: continue
            val r = h.rect
            val down = h === pressed
            fill.color = Drawn.surface(p, if (down) Surface.PRESSED else Surface.PLAIN)
            c.drawRoundRect(r, radius, radius, fill)
            if (e.pinned) {
                fill.color = if (down) Drawn.ink(p, Surface.PRESSED, Ink.ACCENT) else Drawn.ink(p, Surface.PLAIN, Ink.ACCENT)
                c.drawRoundRect(r.left, r.top + 6 * dp, r.left + 3 * dp, r.bottom - 6 * dp, dp, dp, fill)
            }
            val meta = buildString {
                if (e.pinned) append(tx.get(R.string.clip_pinned)).append(" · ")
                append(ClipRules.ago(tx, now, e.at))
                if (e.count > 1) append(" · ×").append(e.count)
            }
            text.textSize = 11 * dp
            text.typeface = typeface(500)
            val metaW = text.measureText(meta)
            text.color = when {
                e.pinned && down -> Drawn.ink(p, Surface.PRESSED, Ink.ACCENT)
                e.pinned -> Drawn.ink(p, Surface.PLAIN, Ink.ACCENT)
                down -> Drawn.ink(p, Surface.PRESSED, Ink.MUTED)
                else -> Drawn.ink(p, Surface.PLAIN, Ink.MUTED)
            }
            text.textAlign = Paint.Align.RIGHT
            drawBaseline(c, meta, r.right - 10 * dp, r.centerY())
            text.textAlign = Paint.Align.LEFT
            text.textSize = 15 * dp
            text.typeface = typeface(450)
            text.color = if (down) Drawn.ink(p, Surface.PRESSED, Ink.TEXT) else Drawn.ink(p, Surface.PLAIN, Ink.TEXT)
            val avail = r.width() - metaW - 34 * dp
            drawBaseline(c, ellipsize(oneLine(e.text), avail), r.left + 12 * dp, r.centerY())
        }
        c.restore()
        // scroll hint: a thin bar when there is more
        val ms = maxScroll()
        if (ms > 0f) {
            val listH = bounds.bottom - listTop
            val barH = max(18 * dp, listH * listH / (listH + ms))
            val y = listTop + (listH - barH) * (scroll / ms)
            fill.color = t.edgeHi
            c.drawRoundRect(bounds.right - 4 * dp, y, bounds.right - 2 * dp, y + barH, dp, dp, fill)
        }
    }

    private fun drawPreview(c: Canvas, p: Palette, radius: Float, typeface: (Int) -> Typeface, e: ClipboardHistory.Entry, pressed: Hit?) {
        val t = p.theme
        val hy = bounds.top + headerH / 2
        for (h in hits) when (h.act) {
            Act.BACK -> button(c, h, tx.get(R.string.clip_back), Surface.MOD_PLAIN, Ink.TEXT_MOD, radius, typeface, h === pressed, p)
            else -> Unit
        }
        text.textAlign = Paint.Align.RIGHT
        text.color = Drawn.ink(p, Surface.BACKGROUND, Ink.MUTED)
        text.typeface = typeface(500)
        text.textSize = 12 * dp
        val chars = e.text.length
        drawBaseline(c, tx.get(R.string.clip_chars, ClipRules.ago(tx, now, e.at), chars) + if (e.pinned) " · " + tx.get(R.string.clip_pinned) else "", bounds.right - 12 * dp, hy)
        // body: the full text in a card aligned with the buttons, wrapped, cut where they start
        val pad = 8 * dp
        tmp.set(bounds.left + pad, listTop + 2 * dp, bounds.right - pad, bounds.bottom - 58 * dp)
        fill.color = Drawn.surface(p, Surface.CARD)
        c.drawRoundRect(tmp, radius, radius, fill)
        stroke.color = t.edge
        stroke.strokeWidth = 1 * dp
        c.drawRoundRect(tmp, radius, radius, stroke)
        val bodyTop = tmp.top + 10 * dp
        val bodyBottom = tmp.bottom - 8 * dp
        text.textAlign = Paint.Align.LEFT
        text.color = Drawn.ink(p, Surface.CARD, Ink.TEXT)
        text.typeface = typeface(450)
        text.textSize = 14 * dp
        val w = (bounds.width() - 2 * pad - 24 * dp).toInt().coerceAtLeast(1)
        val shown = if (e.text.length > PREVIEW_CHARS) e.text.substring(0, PREVIEW_CHARS) + "…" else e.text
        val maxLines = ((bodyBottom - bodyTop) / (text.fontSpacing)).toInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(shown, 0, shown.length, text, w)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(maxLines)
            .setEllipsize(android.text.TextUtils.TruncateAt.END)
            .build()
        c.save()
        c.clipRect(bounds.left, bodyTop, bounds.right, bodyBottom)
        c.translate(bounds.left + pad + 12 * dp, bodyTop)
        layout.draw(c)
        c.restore()
        for (h in hits) when (h.act) {
            Act.PASTE -> button(c, h, tx.get(R.string.clip_paste), Surface.SOLID, Ink.ACCENT_INK, radius, typeface, h === pressed, p)
            Act.PIN -> button(c, h, tx.get(if (e.pinned) R.string.clip_unpin else R.string.clip_pin), Surface.PLAIN, Ink.TEXT, radius, typeface, h === pressed, p)
            Act.DELETE -> button(c, h, tx.get(R.string.clip_delete), Surface.PLAIN, Ink.BAD, radius, typeface, h === pressed, p)
            else -> Unit
        }
    }

    private fun button(c: Canvas, h: Hit, label: String, bg: Surface, ink: Ink, radius: Float, typeface: (Int) -> Typeface, pressed: Boolean, p: Palette) {
        fill.color = Drawn.surface(p, if (pressed) Surface.PRESSED else bg)
        c.drawRoundRect(h.rect, radius, radius, fill)
        if (!pressed && (bg == Surface.PLAIN || bg == Surface.MOD_PLAIN)) {
            // quiet buttons sit close to the panel background: an edge keeps their bounds visible
            stroke.color = p.theme.edgeHi
            stroke.strokeWidth = 1 * dp
            c.drawRoundRect(h.rect, radius, radius, stroke)
        }
        if (pressed) {
            stroke.color = p.accentGlow
            stroke.strokeWidth = 1.2f * dp
            c.drawRoundRect(h.rect, radius, radius, stroke)
        }
        text.textAlign = Paint.Align.CENTER
        // r14 fix (LG10): a pressed button reads in the legend ink on the pressed fill (0.13.0 kept «Pegar»'s ink on it)
        text.color = if (pressed) Drawn.ink(p, Surface.PRESSED, Ink.TEXT) else Drawn.ink(p, bg, ink)
        text.typeface = typeface(600)
        text.textSize = 13 * dp
        drawCenter(c, label, h.rect.centerX(), h.rect.centerY())
    }

    private fun oneLine(s: String): String {
        val head = if (s.length > 400) s.substring(0, 400) else s
        return head.trim().replace(Regex("\\s+"), " ")
    }

    private fun ellipsize(s: String, maxW: Float): String {
        if (maxW <= 0f) return ""
        if (text.measureText(s) <= maxW) return s
        var n = s.length
        val ell = text.measureText("…")
        while (n > 1 && text.measureText(s, 0, n) + ell > maxW) n--
        if (n > 0 && Character.isHighSurrogate(s[n - 1])) n--
        return s.substring(0, n) + "…"
    }

    private fun measure(s: String, size: Float, tf: Typeface): Float {
        val oldSize = text.textSize
        val oldTf = text.typeface
        text.textSize = size; text.typeface = tf
        val w = text.measureText(s)
        text.textSize = oldSize; text.typeface = oldTf
        return w
    }

    private fun drawBaseline(c: Canvas, s: String, x: Float, cy: Float) {
        val fm = text.fontMetrics
        c.drawText(s, x, cy - (fm.ascent + fm.descent) / 2, text)
    }

    private fun drawLeft(c: Canvas, s: String, x: Float, cy: Float) { text.textAlign = Paint.Align.LEFT; drawBaseline(c, s, x, cy) }
    private fun drawCenter(c: Canvas, s: String, x: Float, cy: Float) { text.textAlign = Paint.Align.CENTER; drawBaseline(c, s, x, cy) }

    /** A clipboard glyph (board + clip), drawn with paths: no emoji / font fallback. */
    fun drawClipIcon(c: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        val h = size / 2
        stroke.color = color
        stroke.strokeWidth = size * 0.1f
        stroke.strokeJoin = Paint.Join.ROUND
        stroke.strokeCap = Paint.Cap.ROUND
        tmp.set(cx - h * 0.78f, cy - h * 0.78f, cx + h * 0.78f, cy + h)
        c.drawRoundRect(tmp, h * 0.18f, h * 0.18f, stroke)
        fill.shader = null
        fill.color = color
        tmp.set(cx - h * 0.38f, cy - h * 1.0f, cx + h * 0.38f, cy - h * 0.6f)
        c.drawRoundRect(tmp, h * 0.12f, h * 0.12f, fill)
        path.reset()
        c.drawLine(cx - h * 0.4f, cy - h * 0.05f, cx + h * 0.4f, cy - h * 0.05f, stroke)
        c.drawLine(cx - h * 0.4f, cy + h * 0.4f, cx + h * 0.15f, cy + h * 0.4f, stroke)
    }

    companion object {
        const val VISIBLE_ROWS = 5
        const val PREVIEW_CHARS = 3000
    }
}
