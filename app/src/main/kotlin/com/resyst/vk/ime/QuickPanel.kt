package com.resyst.vk.ime

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.resyst.vk.core.ColorMath
import com.resyst.vk.core.Mode
import com.resyst.vk.core.Palette
import com.resyst.vk.core.Quick
import com.resyst.vk.core.QuickAction
import kotlin.math.max
import kotlin.math.min

/**
 * r10 (UX-3): the quick panel ⚙ opens, drawn by [KeyboardView] over the strip + keys like the
 * clipboard and emoji panels. A header (‹ Teclado · title), an optional update line (the r9
 * notice stays reachable once the strip chip stepped down, QP5), and a 2 × 4 grid of tiles —
 * each a [KeyIcons] icon, its label and its current state, one TalkBack node, ≥ 48 dp (QP4).
 */
class QuickPanel(private val dp: Float) {
    enum class Act { CLOSE, UPDATE, TILE }
    class Hit(val act: Act, val action: QuickAction?, val rect: RectF, val desc: String, val enabled: Boolean = true)

    val bounds = RectF()
    val hits = ArrayList<Hit>()
    private var tiles: List<Quick.Tile> = emptyList()
    private var update: String? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private val headH get() = 44 * dp
    private val updH get() = if (update != null) 40 * dp else 0f

    fun set(tiles: List<Quick.Tile>, update: String?) {
        this.tiles = tiles
        this.update = update
        relayout()
    }

    fun setBounds(r: RectF) { bounds.set(r); relayout() }

    fun hitAt(x: Float, y: Float): Hit? = hits.firstOrNull { it.rect.contains(x, y) }

    private fun relayout() {
        hits.clear()
        if (bounds.width() <= 0f) return
        val pad = 6 * dp
        hits += Hit(Act.CLOSE, null, RectF(bounds.left, bounds.top, bounds.left + 112 * dp, bounds.top + headH), "Volver al teclado")
        var top = bounds.top + headH
        update?.let { u ->
            hits += Hit(Act.UPDATE, null, RectF(bounds.left + pad, top, bounds.right - pad, top + updH - 4 * dp), u)
            top += updH
        }
        val cols = 4
        val rows = max(1, (tiles.size + cols - 1) / cols)
        val gridH = bounds.bottom - top - pad
        val cw = (bounds.width() - 2 * pad) / cols
        val ch = max(Quick.MIN_TILE_DP * dp, gridH / rows)
        tiles.forEachIndexed { i, t ->
            val r = i / cols
            val c = i % cols
            val l = bounds.left + pad + c * cw
            val tp = top + r * ch
            hits += Hit(Act.TILE, t.action, RectF(l + 3 * dp, tp + 3 * dp, l + cw - 3 * dp, tp + ch - 3 * dp), t.desc, t.enabled)
        }
    }

    fun draw(c: Canvas, p: Palette, radius: Float, typeface: (Int) -> Typeface, pressed: Hit?, mode: Mode) {
        val t = p.theme
        fill.shader = null
        fill.color = t.bg
        c.drawRect(bounds, fill)
        // header
        val close = hits.firstOrNull { it.act == Act.CLOSE }
        if (close != null) {
            if (close === pressed) {
                fill.color = t.keyHi
                c.drawRoundRect(close.rect.left + 4 * dp, close.rect.top + 6 * dp, close.rect.right - 4 * dp, close.rect.bottom - 6 * dp, radius, radius, fill)
            }
            KeyIcons.draw(c, Icon.PREVIOUS, close.rect.left + 22 * dp, close.rect.centerY(), 14 * dp, t.textMod, stroke, fill)
            text.textAlign = Paint.Align.LEFT
            text.color = t.textMod
            text.textSize = 14 * dp
            text.typeface = typeface(600)
            baseline(c, "Teclado", close.rect.left + 36 * dp, close.rect.centerY())
        }
        text.textAlign = Paint.Align.RIGHT
        text.color = p.accent
        text.textSize = 11 * dp
        text.typeface = typeface(700)
        text.letterSpacing = 0.12f
        baseline(c, "✦ AJUSTES RÁPIDOS", bounds.right - 12 * dp, bounds.top + headH / 2)
        text.letterSpacing = 0f
        fill.color = t.edge
        c.drawRect(bounds.left, bounds.top + headH - 1, bounds.right, bounds.top + headH, fill)
        // update line (r9 notice, QP5)
        hits.firstOrNull { it.act == Act.UPDATE }?.let { h ->
            fill.color = if (h === pressed) ColorMath.withAlpha(p.accent, 0.3f) else p.accentSoft
            val rr = h.rect.height() / 2
            c.drawRoundRect(h.rect, rr, rr, fill)
            stroke.color = p.accentGlow
            stroke.strokeWidth = dp
            c.drawRoundRect(h.rect, rr, rr, stroke)
            fill.color = p.accent
            c.drawCircle(h.rect.left + 18 * dp, h.rect.centerY(), 9 * dp, fill)
            drawDownload(c, h.rect.left + 18 * dp, h.rect.centerY(), p.accentInk)
            text.textAlign = Paint.Align.LEFT
            text.color = p.accent
            text.textSize = 13.5f * dp
            text.typeface = typeface(650)
            baseline(c, ellipsize(h.desc, h.rect.width() - 44 * dp), h.rect.left + 34 * dp, h.rect.centerY())
        }
        // tiles
        for (h in hits) if (h.act == Act.TILE) {
            val a = h.action ?: continue
            val tile = tiles.firstOrNull { it.action == a } ?: continue
            val active = isActive(tile, mode)
            val bg = when {
                h === pressed && h.enabled -> t.keyHi
                active -> p.accentSoft
                else -> t.key
            }
            fill.color = bg
            c.drawRoundRect(h.rect, radius + 2 * dp, radius + 2 * dp, fill)
            stroke.color = if (active) p.accentGlow else t.edge
            stroke.strokeWidth = dp
            c.drawRoundRect(h.rect, radius + 2 * dp, radius + 2 * dp, stroke)
            val ink = when {
                !h.enabled -> ColorMath.withAlpha(t.muted, 0.55f)
                active -> p.accent
                else -> t.text
            }
            val iconSize = min(22 * dp, h.rect.height() * 0.3f)
            val iy = h.rect.top + h.rect.height() * 0.34f
            KeyIcons.draw(c, iconFor(tile), h.rect.centerX(), iy, iconSize, ink, stroke, fill)
            text.textAlign = Paint.Align.CENTER
            text.color = ink
            text.textSize = min(13 * dp, h.rect.width() * 0.16f)
            text.typeface = typeface(600)
            baseline(c, ellipsize(tile.label, h.rect.width() - 8 * dp), h.rect.centerX(), h.rect.top + h.rect.height() * 0.66f)
            text.color = if (h.enabled) t.muted else ColorMath.withAlpha(t.muted, 0.55f)
            text.textSize = min(11 * dp, h.rect.width() * 0.14f)
            text.typeface = typeface(500)
            baseline(c, ellipsize(tile.state, h.rect.width() - 8 * dp), h.rect.centerX(), h.rect.top + h.rect.height() * 0.85f)
        }
    }

    /** Tiles whose state is "on" get the accent (a mode is on, one-hand is on). */
    private fun isActive(t: Quick.Tile, mode: Mode): Boolean = when (t.action) {
        QuickAction.MODE -> mode != Mode.NONE
        QuickAction.ONE_HAND -> t.state != "Apagado"
        else -> false
    }

    private fun iconFor(t: Quick.Tile): com.resyst.vk.ime.Icon = when (t.action) {
        QuickAction.DAY_NIGHT -> if (t.state == "Oscuro") Icon.SUN else Icon.MOON
        QuickAction.TEMA -> Icon.PALETTE
        QuickAction.MODE -> Icon.MODE
        QuickAction.CLIPBOARD -> Icon.CLIPBOARD
        QuickAction.ONE_HAND -> Icon.ONE_HAND
        QuickAction.HEIGHT -> Icon.HEIGHT
        QuickAction.EDIT -> Icon.EDIT
        QuickAction.SETTINGS -> Icon.GEAR
    }

    private fun drawDownload(c: Canvas, cx: Float, cy: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = 1.6f * dp
        stroke.strokeCap = Paint.Cap.ROUND
        c.drawLine(cx, cy - 4.4f * dp, cx, cy + 2f * dp, stroke)
        c.drawLine(cx - 3.2f * dp, cy - 1f * dp, cx, cy + 2.3f * dp, stroke)
        c.drawLine(cx + 3.2f * dp, cy - 1f * dp, cx, cy + 2.3f * dp, stroke)
        c.drawLine(cx - 4f * dp, cy + 4.8f * dp, cx + 4f * dp, cy + 4.8f * dp, stroke)
    }

    private fun ellipsize(s: String, maxW: Float): String {
        if (text.measureText(s) <= maxW) return s
        var n = s.length
        while (n > 1 && text.measureText(s, 0, n) + text.measureText("…") > maxW) n--
        return s.substring(0, n) + "…"
    }

    private fun baseline(c: Canvas, s: String, x: Float, cy: Float) {
        val fm = text.fontMetrics
        c.drawText(s, x, cy - (fm.ascent + fm.descent) / 2, text)
    }
}
