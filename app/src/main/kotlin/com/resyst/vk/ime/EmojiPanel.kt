package com.resyst.vk.ime

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.Log
import com.resyst.vk.core.EmojiCatalog
import com.resyst.vk.core.EmojiTones
import com.resyst.vk.core.Palette
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The emoji panel (r8), drawn by [KeyboardView] over the strip + keys like [ClipboardPanel]:
 * a tab row (recents + the Unicode groups of [EmojiCatalog]), a scrollable grid of the selected
 * tab, and a bottom bar (ABC · space · ⌫). Glyphs the device font can't draw are dropped
 * (Paint.hasGlyph), so an old Android never shows tofu boxes.
 *
 * r11: the full Unicode 18.0 catalog. Each tab's drawable cells are measured once, the first
 * time it is shown, and cached (EC5): opening the panel or switching tabs never re-measures ~1900
 * emoji, and scrolling only re-lays out the visible rows. A cell with skin tones shows the user's
 * chosen tone ([tones]); a long-press opens its tones ([showTones]) — tap one, or slide onto it
 * and lift.
 */
class EmojiPanel(private val dp: Float) {
    enum class Act { TAB, EMOJI, ABC, SPACE, DELETE, TONE, CLOSE_TONES }
    /** [cell] = the catalog cell behind an EMOJI / TONE hit (null in recents for unknown emoji). */
    class Hit(val act: Act, val text: String, val index: Int, val rect: RectF, val desc: String, val cell: EmojiCatalog.Cell? = null)

    val bounds = RectF()
    val hits = ArrayList<Hit>()
    var catalog: EmojiCatalog = EmojiCatalog.EMPTY
        set(v) { if (v !== field) { field = v; cache.clear() } }
    var tones: EmojiTones = EmojiTones()
    /** 0 = recents, 1.. = [EmojiCatalog.groups]. */
    var tab = 1
        private set
    private var recents: List<String> = emptyList()
    private var scroll = 0f
    /** The cell whose tones are open (null = closed). */
    var tonesOf: EmojiCatalog.Cell? = null
        private set
    private var tonesAnchor = RectF()
    /** The grid laid out under an open tones popup (drawn dimmed, not hittable). */
    private val under = ArrayList<Hit>()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val glyphOk = HashMap<String, Boolean>()
    /** Drawable cells per catalog tab, measured once (EC5). */
    private val cache = HashMap<Int, List<EmojiCatalog.Cell>>()

    private val tabH get() = 44 * dp
    private val barH get() = 46 * dp
    private val gridTop get() = bounds.top + tabH
    private val gridBottom get() = bounds.bottom - barH
    private val cols get() = max(6, (bounds.width() / (46 * dp)).toInt())
    private val cell get() = bounds.width() / cols

    private fun drawable(e: String) = glyphOk.getOrPut(e) { text.hasGlyph(e) }

    private fun tabCount() = catalog.groups.size + 1

    /** The drawable cells of catalog tab [t] (1-based), measured on first use and logged. */
    private fun cellsOf(t: Int): List<EmojiCatalog.Cell> {
        // r11c: the panels are laid out together (⚙ before the emoji key ever ran = EMPTY catalog);
        // nothing to show yet, and nothing cached so the real catalog is measured once it is set
        val g = catalog.groups.getOrNull(t - 1) ?: return emptyList()
        return cellsOf(t, g)
    }

    private fun cellsOf(t: Int, g: EmojiCatalog.Group): List<EmojiCatalog.Cell> = cache.getOrPut(t) {
        val t0 = SystemClock.uptimeMillis()
        val ok = g.cells.filter { drawable(it.base) }
        Log.i(TAG, "emoji: tab ${g.id} shown=${ok.size}/${g.cells.size} ms=${SystemClock.uptimeMillis() - t0}")
        ok
    }

    /** What the grid of the current tab shows: (emoji to draw/commit, its catalog cell). */
    private fun items(): List<Pair<String, EmojiCatalog.Cell?>> =
        if (tab == 0) recents.filter(::drawable).map { it to catalog.cellOf(it) }
        else cellsOf(tab).map { c -> tones.shown(c.base).takeIf(::drawable).let { (it ?: c.base) to c } }

    /** Opens on recents when there are any, else on the first category. */
    fun open(recents: List<String>) {
        this.recents = recents
        tonesOf = null
        tab = if (recents.any(::drawable)) 0 else 1
        scroll = 0f
        relayout()
    }

    fun setRecents(r: List<String>) { recents = r; if (tab == 0) relayout() }

    fun selectTab(i: Int) {
        val t = i.coerceIn(0, catalog.groups.size)
        tonesOf = null
        if (t != tab) { tab = t; scroll = 0f }
        relayout()
    }

    fun setBounds(r: RectF) { bounds.set(r); scroll = scroll.coerceIn(0f, maxScroll()); relayout() }

    fun scrollBy(dy: Float): Boolean {
        if (tonesOf != null) return false
        val s = (scroll + dy).coerceIn(0f, maxScroll())
        if (s == scroll) return false
        scroll = s
        relayout()
        return true
    }

    /** Opens the skin tones of [h]'s cell (an EMOJI hit whose cell has tones). False when it has none. */
    fun showTones(h: Hit): Boolean {
        val c = h.cell ?: return false
        if (c.tones.isEmpty()) return false
        tonesOf = c
        tonesAnchor = RectF(h.rect)
        relayout()
        return true
    }

    fun hideTones() { if (tonesOf != null) { tonesOf = null; relayout() } }

    private fun rowH() = min(cell, 52 * dp)
    private fun maxScroll(): Float {
        if (bounds.width() <= 0f) return 0f
        val rows = ceil(items().size / cols.toFloat())
        return max(0f, rows * rowH() - (gridBottom - gridTop))
    }

    fun hitAt(x: Float, y: Float): Hit? = hits.firstOrNull { it.rect.contains(x, y) }

    fun relayout() {
        hits.clear()
        under.clear()
        if (bounds.width() <= 0f) return
        val open = tonesOf
        if (open != null) {
            tonesOf = null
            relayout() // the grid as it is, kept for drawing under the popup
            tonesOf = open
            under.addAll(hits)
            hits.clear()
            layoutTones(open)
            return
        }
        // tabs
        val n = tabCount()
        val tw = bounds.width() / n
        for (i in 0 until n) {
            val label = if (i == 0) "Recientes" else catalog.groups[i - 1].label
            hits += Hit(Act.TAB, if (i == 0) "" else catalog.groups[i - 1].icon, i,
                RectF(bounds.left + i * tw, bounds.top, bounds.left + (i + 1) * tw, bounds.top + tabH),
                label + if (i == tab) ", seleccionada" else "")
        }
        // grid: only the visible rows become hits
        val list = items()
        val c = cols
        val w = cell
        val rh = rowH()
        val firstRow = max(0, (scroll / rh).toInt())
        val lastRow = ((scroll + (gridBottom - gridTop)) / rh).toInt()
        for (row in firstRow..lastRow) for (col in 0 until c) {
            val i = row * c + col
            if (i >= list.size) break
            val (e, cl) = list[i]
            val top = gridTop + row * rh - scroll
            if (top + rh > gridTop && top < gridBottom) {
                val l = bounds.left + col * w
                hits += Hit(Act.EMOJI, e, i, RectF(l, max(top, gridTop), l + w, min(top + rh, gridBottom)), e, cl)
            }
        }
        // bottom bar
        val pad = 6 * dp
        val by = gridBottom + 4 * dp
        val bb = bounds.bottom - 4 * dp
        val side = 76 * dp
        hits += Hit(Act.ABC, "ABC", 0, RectF(bounds.left + pad, by, bounds.left + pad + side, bb), "Volver al teclado")
        hits += Hit(Act.SPACE, "", 0, RectF(bounds.left + 2 * pad + side, by, bounds.right - 2 * pad - side, bb), "Espacio")
        hits += Hit(Act.DELETE, "⌫", 0, RectF(bounds.right - pad - side, by, bounds.right - pad, bb), "Borrar")
    }

    /** The tones popup: base + its variants, rows of up to [cols] cells, above the pressed cell when it fits. */
    private fun layoutTones(c: EmojiCatalog.Cell) {
        val items = (listOf(c.base) + c.tones).filter(::drawable)
        val perRow = min(cols, max(1, items.size))
        val w = min(cell, 52 * dp)
        val h = w
        val rows = ceil(items.size / perRow.toFloat()).toInt()
        val pw = perRow * w + 8 * dp
        val ph = rows * h + 8 * dp
        var left = (tonesAnchor.centerX() - pw / 2).coerceIn(bounds.left + 4 * dp, bounds.right - 4 * dp - pw)
        if (pw > bounds.width() - 8 * dp) left = bounds.left + 4 * dp
        var top = tonesAnchor.top - ph - 4 * dp
        if (top < bounds.top + 4 * dp) top = tonesAnchor.bottom + 4 * dp
        if (top + ph > bounds.bottom - 4 * dp) top = (bounds.bottom - 4 * dp - ph).coerceAtLeast(bounds.top + 4 * dp)
        items.forEachIndexed { i, e ->
            val l = left + 4 * dp + (i % perRow) * w
            val t = top + 4 * dp + (i / perRow) * h
            val sel = e == tones.shown(c.base)
            hits += Hit(Act.TONE, e, i, RectF(l, t, l + w, t + h), if (sel) "$e, elegido" else e, c)
        }
        // anything else closes the tones without picking (TalkBack: one last node)
        hits += Hit(Act.CLOSE_TONES, "", 0, RectF(bounds), "Cerrar tonos de piel")
    }

    fun draw(c: Canvas, p: Palette, radius: Float, typeface: (Int) -> Typeface, pressed: Hit?) {
        val t = p.theme
        fill.shader = null
        fill.color = t.bg
        c.drawRect(bounds, fill)
        val open = tonesOf
        val layer = if (open != null) under else hits
        val dim = if (open != null) 90 else 255
        // tab row
        for (h in layer) if (h.act == Act.TAB) {
            val sel = h.index == tab
            if (sel || h === pressed) {
                fill.color = if (sel) p.accentSoft else t.keyHi
                c.drawRoundRect(h.rect.left + 2 * dp, h.rect.top + 5 * dp, h.rect.right - 2 * dp, h.rect.bottom - 5 * dp, radius, radius, fill)
            }
            if (h.index == 0) {
                drawClock(c, h.rect.centerX(), h.rect.centerY(), min(h.rect.width(), h.rect.height()) * 0.42f, if (sel) p.accent else t.muted)
            } else {
                text.textSize = min(h.rect.width() * 0.55f, 20 * dp)
                text.color = t.text
                text.alpha = (if (sel) 255 else 150) * dim / 255
                drawCentered(c, h.text, h.rect.centerX(), h.rect.centerY())
                text.alpha = 255
            }
            if (sel) {
                fill.color = p.accent
                c.drawRoundRect(h.rect.centerX() - 9 * dp, h.rect.bottom - 4 * dp, h.rect.centerX() + 9 * dp, h.rect.bottom - 2 * dp, dp, dp, fill)
            }
        }
        fill.color = t.edge
        c.drawRect(bounds.left, gridTop - 1, bounds.right, gridTop, fill)
        // grid
        c.save()
        c.clipRect(bounds.left, gridTop, bounds.right, gridBottom)
        var any = false
        for (h in layer) if (h.act == Act.EMOJI) {
            any = true
            if (h === pressed) {
                fill.color = t.keyHi
                c.drawRoundRect(h.rect.left + 2 * dp, h.rect.top + 2 * dp, h.rect.right - 2 * dp, h.rect.bottom - 2 * dp, radius, radius, fill)
            }
            text.color = t.text
            text.alpha = dim
            text.textSize = min(cell * 0.56f, 28 * dp)
            text.typeface = Typeface.DEFAULT
            // the rect may be clipped at the grid edge: center on the full cell
            val full = rowH()
            val cy = if (h.rect.top <= gridTop + 0.5f) h.rect.bottom - full / 2 else h.rect.top + full / 2
            drawCentered(c, h.text, h.rect.centerX(), cy)
            // a cell with tones gets the platform's small corner mark (long-press affordance)
            if (h.cell?.tones?.isNotEmpty() == true && h.rect.height() >= full - 0.5f) {
                fill.color = t.muted
                val r = 2.2f * dp
                c.drawCircle(h.rect.right - 6 * dp, h.rect.bottom - 6 * dp, r, fill)
            }
        }
        c.restore()
        text.alpha = 255
        if (open != null) { drawTones(c, p, radius, pressed); return }
        if (!any) {
            text.color = t.muted
            text.textSize = 13 * dp
            text.typeface = typeface(500)
            drawCentered(c, if (tab == 0) "Aquí aparecen los emojis que uses" else "Sin emojis disponibles", bounds.centerX(), (gridTop + gridBottom) / 2)
        }
        val ms = maxScroll()
        if (ms > 0f) {
            val listH = gridBottom - gridTop
            val barLen = max(18 * dp, listH * listH / (listH + ms))
            val y = gridTop + (listH - barLen) * (scroll / ms)
            fill.color = t.edgeHi
            c.drawRoundRect(bounds.right - 4 * dp, y, bounds.right - 2 * dp, y + barLen, dp, dp, fill)
        }
        // bottom bar
        for (h in hits) when (h.act) {
            Act.ABC -> button(c, h, "ABC", t.keyMod, t.textMod, radius, typeface, h === pressed, p)
            Act.SPACE -> button(c, h, "", t.key, t.muted, radius, typeface, h === pressed, p)
            Act.DELETE -> button(c, h, "⌫", t.keyMod, t.textMod, radius, typeface, h === pressed, p)
            else -> Unit
        }
    }

    private fun drawTones(c: Canvas, p: Palette, radius: Float, pressed: Hit?) {
        val t = p.theme
        val toneHits = hits.filter { it.act == Act.TONE }
        if (toneHits.isEmpty()) return
        val box = RectF(toneHits.minOf { it.rect.left } - 4 * dp, toneHits.minOf { it.rect.top } - 4 * dp,
            toneHits.maxOf { it.rect.right } + 4 * dp, toneHits.maxOf { it.rect.bottom } + 4 * dp)
        fill.color = t.key
        c.drawRoundRect(box, radius, radius, fill)
        stroke.color = t.edgeHi
        stroke.strokeWidth = dp
        c.drawRoundRect(box, radius, radius, stroke)
        val chosen = tonesOf?.let { tones.shown(it.base) }
        for (h in toneHits) {
            if (h === pressed || h.text == chosen) {
                fill.color = if (h === pressed) p.accent else p.accentSoft
                c.drawRoundRect(h.rect.left + 2 * dp, h.rect.top + 2 * dp, h.rect.right - 2 * dp, h.rect.bottom - 2 * dp, radius, radius, fill)
            }
            text.color = t.text
            text.textSize = min(h.rect.width() * 0.56f, 28 * dp)
            text.typeface = Typeface.DEFAULT
            drawCentered(c, h.text, h.rect.centerX(), h.rect.centerY())
        }
    }

    private fun button(c: Canvas, h: Hit, label: String, bg: Int, ink: Int, radius: Float, typeface: (Int) -> Typeface, pressed: Boolean, p: Palette) {
        fill.color = if (pressed) p.theme.keyHi else bg
        c.drawRoundRect(h.rect, radius, radius, fill)
        if (pressed) {
            stroke.color = p.accentGlow
            stroke.strokeWidth = 1.2f * dp
            c.drawRoundRect(h.rect, radius, radius, stroke)
        }
        if (label.isEmpty()) return
        if (label == "⌫") { // r10 (UI-1): the same vector ⌫ as the keyboard
            KeyIcons.draw(c, Icon.BACKSPACE, h.rect.centerX(), h.rect.centerY(), min(22 * dp, h.rect.height() * 0.45f), ink, stroke, fill)
            return
        }
        text.color = ink
        text.typeface = typeface(600)
        text.textSize = 14 * dp
        drawCentered(c, label, h.rect.centerX(), h.rect.centerY())
    }

    private fun drawClock(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = r * 0.16f
        stroke.strokeCap = Paint.Cap.ROUND
        c.drawCircle(cx, cy, r * 0.8f, stroke)
        c.drawLine(cx, cy, cx, cy - r * 0.48f, stroke)
        c.drawLine(cx, cy, cx + r * 0.36f, cy, stroke)
    }

    private fun drawCentered(c: Canvas, s: String, cx: Float, cy: Float) {
        val fm = text.fontMetrics
        c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2, text)
    }

    private companion object { const val TAG = "ResystVK" }
}
