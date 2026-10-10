package com.resyst.vk.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.resyst.vk.R
import com.resyst.vk.core.Gif
import com.resyst.vk.core.ColorMath
import com.resyst.vk.core.GifCopy
import com.resyst.vk.core.GifLayout
import com.resyst.vk.core.Palette
import kotlin.math.max
import kotlin.math.min

/**
 * r11b: the GIF tab of the emoji panel, drawn into the area under the tab row (like the emoji
 * grid). Four faces: the OFF explainer (no request until the user turns it on), the disclosure
 * (what is sent, to whom; «Activar» is the only way on), the search row + KLIPY's grid in
 * KLIPY's order, and one calm status line (loading, empty, an error with «Reintentar»).
 * The official "Powered by KLIPY" mark is always visible in this tab (GT1).
 */
class GifPanel(context: Context, private val dp: Float) {
    enum class Face { OFF, DISCLOSURE, ON }
    enum class Act { ENABLE, ACCEPT, CANCEL, SEARCH, RETRY, ITEM, ATTRIBUTION }
    class Target(val act: Act, val index: Int = 0)
    class Hit(val target: Target, val rect: RectF, val desc: String)

    var face = Face.OFF
    /** The query the grid shows results for (null = trending). */
    var query: String? = null
    var items: List<Gif> = emptyList()
        set(v) { if (v !== field) { field = v; cells = emptyList() } }
    /** Status under / instead of the grid: null = none. [retry] adds the button. */
    var status: String? = null
    var retry = false
    var loadingMore = false
    /** The decoded preview for item i, null = still loading / failed (a placeholder keeps its place, GM2). */
    var thumb: (Int) -> Drawable? = { null }

    val hits = ArrayList<Hit>()
    val area = RectF()
    private var scroll = 0f
    private var cells: List<GifLayout.Cell> = emptyList()
    private var cellsWidth = 0f

    private val logo: Drawable? = runCatching { context.getDrawable(R.drawable.klipy_powered)?.mutate() }.getOrNull()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()

    private val rowH get() = 44 * dp
    private val pad get() = 8 * dp
    private val gap get() = 4 * dp
    private val gridTop get() = area.top + rowH
    private val targetH get() = min(112 * dp, max(72 * dp, (area.height() - rowH) / 2.1f))

    fun reset() { scroll = 0f; cells = emptyList() }

    /** Lays out into [r] (the emoji grid area; the disclosure also takes the bottom bar). */
    fun layout(r: RectF) {
        area.set(r)
        hits.clear()
        when (face) {
            Face.OFF -> layoutOff()
            Face.DISCLOSURE -> layoutDisclosure()
            Face.ON -> layoutOn()
        }
    }

    // ── OFF: a short explainer, one button to the disclosure ────────────
    private var offButton = RectF()

    private fun layoutOff() {
        val bw = min(area.width() - 2 * pad, 300 * dp)
        val cy = area.top + area.height() * 0.62f
        offButton = RectF(area.centerX() - bw / 2, cy, area.centerX() + bw / 2, cy + 44 * dp)
        hits += Hit(Target(Act.ENABLE), offButton, GifCopy.OFF_BUTTON)
        attributionHit(RectF(area.centerX() - 70 * dp, area.bottom - 22 * dp, area.centerX() + 70 * dp, area.bottom - 4 * dp))
    }

    // ── DISCLOSURE: scrollable text, «Cancelar» / «Activar» pinned at the bottom ──
    private var discLayout: StaticLayout? = null
    private var discWidth = 0
    private val discButtonsH get() = 56 * dp

    private fun layoutDisclosure() {
        val bw = (area.width() - 3 * pad) / 2
        val by = area.bottom - discButtonsH + 6 * dp
        val bb = area.bottom - 8 * dp
        hits += Hit(Target(Act.CANCEL), RectF(area.left + pad, by, area.left + pad + bw, bb), GifCopy.CANCEL + ": se queda apagado")
        hits += Hit(Target(Act.ACCEPT), RectF(area.right - pad - bw, by, area.right - pad, bb), GifCopy.ACCEPT + " la búsqueda de GIF con KLIPY")
        // the text itself is one node, so TalkBack reads the whole disclosure (GO2)
        hits += Hit(Target(Act.ATTRIBUTION, 1), RectF(area.left, area.top, area.right, by - 2 * dp),
            GifCopy.TITLE + ". " + GifCopy.DISCLOSURE.joinToString(" "))
    }

    private fun disclosureText(width: Int): StaticLayout {
        discLayout?.let { if (discWidth == width) return it }
        val body = GifCopy.DISCLOSURE.joinToString("\n\n") { "•  $it" }
        val tp = TextPaint(text).apply { textSize = 12.5f * dp; typeface = Typeface.DEFAULT }
        val l = staticLayout(body, tp, width, 1.12f)
        discLayout = l
        discWidth = width
        return l
    }

    // ── ON: search row + grid ───────────────────────────────────────────
    private val logoW get() = 74 * dp
    private var searchBox = RectF()

    private fun layoutOn() {
        searchBox = RectF(area.left + pad, area.top + 6 * dp, area.right - pad - logoW - 6 * dp, area.top + rowH - 4 * dp)
        hits += Hit(Target(Act.SEARCH), searchBox, if (query != null) "Buscar en KLIPY: $query. Toca para cambiar la búsqueda" else GifCopy.PLACEHOLDER)
        attributionHit(RectF(area.right - pad - logoW, area.top + 6 * dp, area.right - pad, area.top + rowH - 4 * dp))
        if (items.isEmpty()) {
            if (retry) hits += Hit(Target(Act.RETRY), retryRect(), "Reintentar")
            return
        }
        val w = area.width() - 2 * pad
        if (cells.isEmpty() || cellsWidth != w) {
            cells = GifLayout.justify(items.map { previewAspect(it) }, w, targetH, gap)
            cellsWidth = w
        }
        scroll = scroll.coerceIn(0f, maxScroll())
        val top = gridTop
        val bottom = area.bottom
        for (c in cells) {
            val y = top + c.top - scroll
            if (y + c.height <= top || y >= bottom) continue
            val g = items.getOrNull(c.index) ?: continue
            hits += Hit(Target(Act.ITEM, c.index), RectF(area.left + pad + c.left, max(y, top), area.left + pad + c.right, min(y + c.height, bottom)),
                "GIF: " + g.title.ifEmpty { g.slug })
        }
        if (retry) hits += Hit(Target(Act.RETRY), retryRect(), "Reintentar")
    }

    private fun retryRect(): RectF {
        val y = if (items.isEmpty()) gridTop + (area.bottom - gridTop) * 0.55f else area.bottom - 40 * dp
        return RectF(area.centerX() - 70 * dp, y, area.centerX() + 70 * dp, y + 36 * dp)
    }

    private fun attributionHit(r: RectF) { hits += Hit(Target(Act.ATTRIBUTION), r, GifCopy.ATTRIBUTION) }

    private fun previewAspect(g: Gif): Float {
        val m = g.files["sm"]?.values?.firstOrNull() ?: g.files.values.first().values.first()
        return m.width.toFloat() / m.height
    }

    private fun contentHeight(): Float = (cells.maxOfOrNull { it.bottom } ?: 0f) + if (loadingMore || (retry && items.isNotEmpty())) 44 * dp else 8 * dp

    private fun maxScroll(): Float = when (face) {
        Face.ON -> max(0f, contentHeight() - (area.bottom - gridTop))
        Face.DISCLOSURE -> {
            val l = discLayout ?: return 0f
            max(0f, l.height + 34 * dp - (area.height() - discButtonsH))
        }
        Face.OFF -> 0f
    }

    fun scrollBy(dy: Float): Boolean {
        val s = (scroll + dy).coerceIn(0f, maxScroll())
        if (s == scroll) return false
        scroll = s
        layout(RectF(area))
        return true
    }

    /** Within ~1.5 rows of the end: time to ask for the next page (the caller checks has_next). */
    fun nearEnd(): Boolean = face == Face.ON && items.isNotEmpty() && scroll >= maxScroll() - targetH * 1.5f

    /** Items whose cells are within [margin] screens of the viewport (thumbnail window, GM1). */
    fun window(margin: Float): IntRange? {
        if (face != Face.ON || cells.isEmpty()) return null
        val h = area.bottom - gridTop
        val lo = scroll - h * margin
        val hi = scroll + h * (1 + margin)
        val inside = cells.filter { it.bottom >= lo && it.top <= hi }
        if (inside.isEmpty()) return null
        return inside.minOf { it.index }..inside.maxOf { it.index }
    }

    /** Items whose cells are on screen right now (animations run only for these). */
    fun visible(): IntRange? = window(0f)

    // ── drawing ─────────────────────────────────────────────────────────
    fun draw(c: Canvas, p: Palette, radius: Float, typeface: (Int) -> Typeface, pressed: Target?) {
        when (face) {
            Face.OFF -> drawOff(c, p, typeface, pressed)
            Face.DISCLOSURE -> drawDisclosure(c, p, radius, typeface, pressed)
            Face.ON -> drawOn(c, p, radius, typeface, pressed)
        }
    }

    private fun drawOff(c: Canvas, p: Palette, typeface: (Int) -> Typeface, pressed: Target?) {
        val t = p.theme
        // a quiet "GIF" badge
        val bx = area.centerX()
        val by = area.top + area.height() * 0.17f
        fill.color = p.accentSoft
        c.drawRoundRect(bx - 26 * dp, by - 15 * dp, bx + 26 * dp, by + 15 * dp, 9 * dp, 9 * dp, fill)
        text.color = p.accent
        text.textSize = 15 * dp
        text.typeface = typeface(700)
        text.textAlign = Paint.Align.CENTER
        centered(c, "GIF", bx, by)
        val w = (area.width() - 4 * pad).toInt().coerceAtLeast(1)
        val tf600 = typeface(600)
        val tf450 = typeface(450)
        val l1 = staticLayout(GifCopy.OFF_LINE1, TextPaint(text).apply { color = t.text; textSize = 15 * dp; this.typeface = tf600; textAlign = Paint.Align.LEFT }, w, 1.05f, center = true)
        val l2 = staticLayout(GifCopy.OFF_LINE2, TextPaint(text).apply { color = t.muted; textSize = 12.5f * dp; this.typeface = tf450; textAlign = Paint.Align.LEFT }, w, 1.1f, center = true)
        var y = by + 26 * dp
        c.save(); c.translate(area.left + 2 * pad, y); l1.draw(c); c.restore()
        y += l1.height + 6 * dp
        c.save(); c.translate(area.left + 2 * pad, y); l2.draw(c); c.restore()
        pill(c, offButton, GifCopy.OFF_BUTTON, p.accent, p.accentInk, typeface, pressed?.act == Act.ENABLE, p)
        drawLogo(c, area.centerX(), area.bottom - 13 * dp, 12 * dp, t.muted, center = true)
    }

    private fun drawDisclosure(c: Canvas, p: Palette, radius: Float, typeface: (Int) -> Typeface, pressed: Target?) {
        val t = p.theme
        val textBottom = area.bottom - discButtonsH
        c.save()
        c.clipRect(area.left, area.top, area.right, textBottom)
        c.translate(0f, -scroll)
        text.color = p.accent
        text.textSize = 15 * dp
        text.typeface = typeface(650)
        text.textAlign = Paint.Align.LEFT
        c.drawText(GifCopy.TITLE, area.left + 2 * pad, area.top + 24 * dp, text)
        val w = (area.width() - 4 * pad).toInt().coerceAtLeast(1)
        text.color = t.textMod
        val l = disclosureText(w)
        c.save(); c.translate(area.left + 2 * pad, area.top + 34 * dp); l.draw(c); c.restore()
        c.restore()
        // a fade + hairline where the text continues under the buttons
        fill.color = t.edge
        c.drawRect(area.left, textBottom, area.right, textBottom + 1, fill)
        val ms = maxScroll()
        if (ms > 0f) {
            val h = textBottom - area.top
            val len = max(18 * dp, h * h / (h + ms))
            val y = area.top + (h - len) * (scroll / ms)
            fill.color = t.edgeHi
            c.drawRoundRect(area.right - 4 * dp, y, area.right - 2 * dp, y + len, dp, dp, fill)
        }
        for (h in hits) when (h.target.act) {
            Act.CANCEL -> pill(c, h.rect, GifCopy.CANCEL, t.keyMod, t.textMod, typeface, pressed?.act == Act.CANCEL, p)
            Act.ACCEPT -> pill(c, h.rect, GifCopy.ACCEPT, p.accent, p.accentInk, typeface, pressed?.act == Act.ACCEPT, p)
            else -> Unit
        }
    }

    private fun drawOn(c: Canvas, p: Palette, radius: Float, typeface: (Int) -> Typeface, pressed: Target?) {
        val t = p.theme
        // search row: a box that opens the keyboard on its own buffer, and KLIPY's mark
        fill.color = if (pressed?.act == Act.SEARCH) t.keyHi else t.key
        val r = searchBox.height() / 2
        c.drawRoundRect(searchBox, r, r, fill)
        stroke.color = t.edge
        stroke.strokeWidth = dp
        c.drawRoundRect(searchBox, r, r, stroke)
        KeyIcons.draw(c, Icon.SEARCH, searchBox.left + 17 * dp, searchBox.centerY(), 15 * dp, t.muted, stroke, fill)
        text.textAlign = Paint.Align.LEFT
        text.textSize = 14 * dp
        val q = query
        text.color = if (q != null) t.text else t.muted
        text.typeface = typeface(if (q != null) 550 else 450)
        val label = ellipsize(q ?: GifCopy.PLACEHOLDER, searchBox.width() - 40 * dp)
        val fm = text.fontMetrics
        c.drawText(label, searchBox.left + 32 * dp, searchBox.centerY() - (fm.ascent + fm.descent) / 2, text)
        drawLogo(c, area.right - pad, area.top + rowH / 2 + 1 * dp, 11 * dp, t.muted, center = false)
        fill.color = t.edge
        c.drawRect(area.left, gridTop - 1, area.right, gridTop, fill)
        // grid
        c.save()
        c.clipRect(area.left, gridTop, area.right, area.bottom)
        val gr = 8 * dp
        for (cell in cells) {
            val y = gridTop + cell.top - scroll
            if (y + cell.height <= gridTop || y >= area.bottom) continue
            val rect = RectF(area.left + pad + cell.left, y, area.left + pad + cell.right, y + cell.height)
            c.save()
            clip.reset()
            clip.addRoundRect(rect, gr, gr, Path.Direction.CW)
            c.clipPath(clip)
            fill.color = t.key
            c.drawRect(rect, fill)
            val d = thumb(cell.index)
            if (d != null) {
                // center-crop into the cell
                val iw = d.intrinsicWidth.coerceAtLeast(1).toFloat()
                val ih = d.intrinsicHeight.coerceAtLeast(1).toFloat()
                val s = max(rect.width() / iw, rect.height() / ih)
                val w = iw * s
                val h = ih * s
                d.setBounds((rect.centerX() - w / 2).toInt(), (rect.centerY() - h / 2).toInt(), (rect.centerX() + w / 2).toInt(), (rect.centerY() + h / 2).toInt())
                d.draw(c)
            }
            if (pressed?.act == Act.ITEM && pressed.index == cell.index) {
                fill.color = ColorMath.withAlpha(p.accent, 0.28f)
                c.drawRect(rect, fill)
            }
            c.restore()
        }
        c.restore()
        // status: centered when there is no grid, at the foot of the grid otherwise
        val st = status
        if (items.isEmpty()) {
            if (st != null) {
                text.textAlign = Paint.Align.CENTER
                text.color = t.muted
                text.textSize = 13.5f * dp
                text.typeface = typeface(500)
                centered(c, st, area.centerX(), gridTop + (area.bottom - gridTop) * (if (retry) 0.36f else 0.5f))
            }
        } else if (st != null || loadingMore) {
            val y = gridTop + contentHeight() - 24 * dp - scroll
            if (y < area.bottom && !retry) {
                text.textAlign = Paint.Align.CENTER
                text.color = t.muted
                text.textSize = 12.5f * dp
                text.typeface = typeface(500)
                centered(c, st ?: GifCopy.LOADING, area.centerX(), y)
            }
        }
        for (h in hits) if (h.target.act == Act.RETRY) {
            if (items.isNotEmpty() && st != null) {
                fill.color = t.bg
                c.drawRect(area.left, h.rect.top - 30 * dp, area.right, area.bottom, fill)
                text.textAlign = Paint.Align.CENTER
                text.color = t.muted
                text.textSize = 12.5f * dp
                text.typeface = typeface(500)
                centered(c, st, area.centerX(), h.rect.top - 14 * dp)
            }
            pill(c, h.rect, "Reintentar", t.keyMod, t.text, typeface, pressed?.act == Act.RETRY, p)
        }
        // scroll indicator
        val ms = maxScroll()
        if (ms > 0f) {
            val h = area.bottom - gridTop
            val len = max(18 * dp, h * h / (h + ms))
            val y = gridTop + (h - len) * (scroll / ms)
            fill.color = t.edgeHi
            c.drawRoundRect(area.right - 4 * dp, y, area.right - 2 * dp, y + len, dp, dp, fill)
        }
        text.textAlign = Paint.Align.CENTER
    }

    private fun drawLogo(c: Canvas, x: Float, cy: Float, h: Float, color: Int, center: Boolean) {
        val d = logo
        if (d == null) { // the text attribution when the vector can't load
            text.textAlign = if (center) Paint.Align.CENTER else Paint.Align.RIGHT
            text.color = color
            text.textSize = h
            text.typeface = Typeface.DEFAULT_BOLD
            centered(c, GifCopy.ATTRIBUTION, x, cy)
            text.textAlign = Paint.Align.CENTER
            return
        }
        val w = h * d.intrinsicWidth / d.intrinsicHeight.coerceAtLeast(1)
        val left = if (center) x - w / 2 else x - w
        d.setTint(color)
        d.setBounds(left.toInt(), (cy - h / 2).toInt(), (left + w).toInt(), (cy + h / 2).toInt())
        d.draw(c)
    }

    private fun pill(c: Canvas, r: RectF, label: String, bg: Int, ink: Int, typeface: (Int) -> Typeface, pressed: Boolean, p: Palette) {
        fill.color = if (pressed) p.theme.keyHi else bg
        val rr = r.height() / 2
        c.drawRoundRect(r, rr, rr, fill)
        if (pressed) {
            stroke.color = p.accentGlow
            stroke.strokeWidth = 1.2f * dp
            c.drawRoundRect(r, rr, rr, stroke)
        }
        text.textAlign = Paint.Align.CENTER
        text.color = if (pressed) p.theme.text else ink
        text.textSize = 14 * dp
        text.typeface = typeface(650)
        centered(c, ellipsize(label, r.width() - 20 * dp), r.centerX(), r.centerY())
    }

    private fun centered(c: Canvas, s: String, cx: Float, cy: Float) {
        val fm = text.fontMetrics
        c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2, text)
    }

    private fun ellipsize(s: String, w: Float): String {
        if (text.measureText(s) <= w) return s
        var e = s
        while (e.isNotEmpty() && text.measureText("$e…") > w) e = e.dropLast(1)
        return "$e…"
    }

    private fun staticLayout(s: String, tp: TextPaint, width: Int, spacing: Float, center: Boolean = false): StaticLayout {
        val align = if (center) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL
        return if (Build.VERSION.SDK_INT >= 23) {
            StaticLayout.Builder.obtain(s, 0, s.length, tp, width).setAlignment(align).setLineSpacing(0f, spacing).setIncludePad(false).build()
        } else @Suppress("DEPRECATION") StaticLayout(s, tp, width, align, spacing, 0f, false)
    }
}
