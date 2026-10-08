package com.resyst.vk.ime

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.resyst.vk.core.Emoji
import com.resyst.vk.core.Palette
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The emoji panel (r8), drawn by [KeyboardView] over the strip + keys like [ClipboardPanel]:
 * a tab row (recents + categories), a scrollable grid of the selected tab, and a bottom bar
 * (ABC · space · ⌫). Glyphs the device font can't draw are dropped (Paint.hasGlyph), so an old
 * Android never shows tofu boxes.
 */
class EmojiPanel(private val dp: Float) {
    enum class Act { TAB, EMOJI, ABC, SPACE, DELETE }
    class Hit(val act: Act, val text: String, val index: Int, val rect: RectF, val desc: String)

    val bounds = RectF()
    val hits = ArrayList<Hit>()
    /** 0 = recents, 1.. = [Emoji.CATEGORIES]. */
    var tab = 1
        private set
    private var recents: List<String> = emptyList()
    private var scroll = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val glyphOk = HashMap<String, Boolean>()

    private val tabH get() = 44 * dp
    private val barH get() = 46 * dp
    private val gridTop get() = bounds.top + tabH
    private val gridBottom get() = bounds.bottom - barH
    private val cols get() = max(6, (bounds.width() / (46 * dp)).toInt())
    private val cell get() = bounds.width() / cols

    private fun drawable(e: String) = glyphOk.getOrPut(e) { text.hasGlyph(e) }

    private fun items(): List<String> =
        (if (tab == 0) recents else Emoji.CATEGORIES[tab - 1].items).filter(::drawable)

    /** Opens on recents when there are any, else on the first category. */
    fun open(recents: List<String>) {
        this.recents = recents
        tab = if (recents.any(::drawable)) 0 else 1
        scroll = 0f
        relayout()
    }

    fun setRecents(r: List<String>) { recents = r; if (tab == 0) relayout() }

    fun selectTab(i: Int) {
        val t = i.coerceIn(0, Emoji.CATEGORIES.size)
        if (t != tab) { tab = t; scroll = 0f; relayout() }
    }

    fun setBounds(r: RectF) { bounds.set(r); scroll = scroll.coerceIn(0f, maxScroll()); relayout() }

    fun scrollBy(dy: Float): Boolean {
        val s = (scroll + dy).coerceIn(0f, maxScroll())
        if (s == scroll) return false
        scroll = s
        relayout()
        return true
    }

    private fun rowH() = min(cell, 52 * dp)
    private fun maxScroll(): Float {
        if (bounds.width() <= 0f) return 0f
        val rows = ceil(items().size / cols.toFloat())
        return max(0f, rows * rowH() - (gridBottom - gridTop))
    }

    fun hitAt(x: Float, y: Float): Hit? = hits.firstOrNull { it.rect.contains(x, y) }

    fun relayout() {
        hits.clear()
        if (bounds.width() <= 0f) return
        // tabs
        val n = Emoji.CATEGORIES.size + 1
        val tw = bounds.width() / n
        for (i in 0 until n) {
            val label = if (i == 0) "Recientes" else Emoji.CATEGORIES[i - 1].label
            hits += Hit(Act.TAB, if (i == 0) "" else Emoji.CATEGORIES[i - 1].icon, i,
                RectF(bounds.left + i * tw, bounds.top, bounds.left + (i + 1) * tw, bounds.top + tabH),
                label + if (i == tab) ", seleccionada" else "")
        }
        // grid
        val list = items()
        val c = cols
        val w = cell
        val rh = rowH()
        list.forEachIndexed { i, e ->
            val top = gridTop + (i / c) * rh - scroll
            if (top + rh > gridTop && top < gridBottom) {
                val l = bounds.left + (i % c) * w
                hits += Hit(Act.EMOJI, e, i, RectF(l, max(top, gridTop), l + w, min(top + rh, gridBottom)), e)
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

    fun draw(c: Canvas, p: Palette, radius: Float, typeface: (Int) -> Typeface, pressed: Hit?) {
        val t = p.theme
        fill.shader = null
        fill.color = t.bg
        c.drawRect(bounds, fill)
        // tab row
        for (h in hits) if (h.act == Act.TAB) {
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
                text.alpha = if (sel) 255 else 150
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
        for (h in hits) if (h.act == Act.EMOJI) {
            any = true
            if (h === pressed) {
                fill.color = t.keyHi
                c.drawRoundRect(h.rect.left + 2 * dp, h.rect.top + 2 * dp, h.rect.right - 2 * dp, h.rect.bottom - 2 * dp, radius, radius, fill)
            }
            text.color = t.text
            text.textSize = min(cell * 0.56f, 28 * dp)
            text.typeface = Typeface.DEFAULT
            // the rect may be clipped at the grid edge: center on the full cell
            val full = rowH()
            val cy = if (h.rect.top <= gridTop + 0.5f) h.rect.bottom - full / 2 else h.rect.top + full / 2
            drawCentered(c, h.text, h.rect.centerX(), cy)
        }
        c.restore()
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
}
