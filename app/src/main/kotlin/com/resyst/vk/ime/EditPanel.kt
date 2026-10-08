package com.resyst.vk.ime

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.resyst.vk.core.ColorMath
import com.resyst.vk.core.EditOp
import com.resyst.vk.core.EditPad
import com.resyst.vk.core.Palette
import kotlin.math.min

/**
 * r10 (bet 5, UX-7): the edit panel the quick panel's «Edición» opens, drawn by [KeyboardView]
 * over the strip + keys like the other panels. A header (‹ Teclado · title + the selection
 * state), then the [EditPad] grid: a d-pad around Seleccionar, Inicio / Fin, Todo, Copiar /
 * Cortar / Pegar and Borrar palabra. Seleccionar is drawn latched while on (QE4); Copiar / Cortar
 * are drawn disabled in secret fields (QE2).
 */
class EditPanel(private val dp: Float) {
    enum class Act { CLOSE, OP }
    class Hit(val act: Act, val op: EditOp?, val rect: RectF, val desc: String, val enabled: Boolean = true)

    val bounds = RectF()
    val hits = ArrayList<Hit>()
    var selecting = false
        private set
    private var secret = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private val headH get() = EditPad.HEADER_DP * dp

    fun set(selecting: Boolean, secret: Boolean) {
        this.selecting = selecting
        this.secret = secret
        relayout()
    }

    fun setBounds(r: RectF) { bounds.set(r); relayout() }

    fun hitAt(x: Float, y: Float): Hit? = hits.firstOrNull { it.rect.contains(x, y) }

    private fun relayout() {
        hits.clear()
        if (bounds.width() <= 0f) return
        val pad = 6 * dp
        hits += Hit(Act.CLOSE, null, RectF(bounds.left, bounds.top, bounds.left + 112 * dp, bounds.top + headH), "Volver al teclado")
        val top = bounds.top + headH
        val gridH = bounds.bottom - top - pad
        val rows = EditPad.rowsFor(gridH / dp)
        val grid = EditPad.grid(rows)
        val cols = grid.maxOf { it.col } + 1
        val cw = (bounds.width() - 2 * pad) / cols
        val ch = gridH / rows
        for (b in grid) {
            val l = bounds.left + pad + b.col * cw
            val t = top + b.row * ch
            hits += Hit(Act.OP, b.op, RectF(l + 3 * dp, t + 3 * dp, l + cw - 3 * dp, t + ch - 3 * dp),
                EditPad.desc(b.op, selecting, secret), EditPad.enabled(b.op, secret))
        }
    }

    private fun label(op: EditOp) = EditPad.grid(3).first { it.op == op }.label

    fun draw(c: Canvas, p: Palette, radius: Float, typeface: (Int) -> Typeface, pressed: Hit?) {
        val t = p.theme
        fill.shader = null
        fill.color = t.bg
        c.drawRect(bounds, fill)
        // header: ‹ Teclado · the selection state on the right
        hits.firstOrNull { it.act == Act.CLOSE }?.let { close ->
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
        baseline(c, if (selecting) "✦ SELECCIONANDO" else "✦ EDICIÓN", bounds.right - 12 * dp, bounds.top + headH / 2)
        text.letterSpacing = 0f
        fill.color = t.edge
        c.drawRect(bounds.left, bounds.top + headH - 1, bounds.right, bounds.top + headH, fill)
        // buttons
        for (h in hits) if (h.act == Act.OP) {
            val op = h.op ?: continue
            val latched = op == EditOp.SELECT && selecting
            val isArrow = op == EditOp.LEFT || op == EditOp.RIGHT || op == EditOp.UP || op == EditOp.DOWN
            val bg = when {
                latched -> p.accent
                h === pressed && h.enabled -> t.keyHi
                isArrow -> t.key
                else -> t.keyMod
            }
            val rad = radius + 2 * dp
            fill.color = bg
            c.drawRoundRect(h.rect, rad, rad, fill)
            stroke.color = when {
                latched -> p.accent
                h === pressed && h.enabled -> p.accentGlow
                else -> t.edge
            }
            stroke.strokeWidth = dp
            c.drawRoundRect(h.rect, rad, rad, stroke)
            val ink = when {
                !h.enabled -> ColorMath.withAlpha(t.muted, 0.5f)
                latched -> p.accentInk
                // while selecting, the moves extend the selection: tint them with the accent
                selecting && isArrow -> p.accent
                isArrow -> t.text
                else -> t.textMod
            }
            if (isArrow) {
                val sz = min(26 * dp, min(h.rect.width(), h.rect.height()) * 0.42f)
                drawArrow(c, op, h.rect.centerX(), h.rect.centerY(), sz, ink)
            } else {
                text.textAlign = Paint.Align.CENTER
                text.color = ink
                text.typeface = typeface(600)
                text.textSize = min(14 * dp, h.rect.height() * 0.3f)
                val l = label(op)
                // two-word labels wrap rather than shrink to unreadable
                if (text.measureText(l) > h.rect.width() - 10 * dp && ' ' in l) {
                    val (a, b) = l.split(' ', limit = 2)
                    baseline(c, a, h.rect.centerX(), h.rect.centerY() - text.textSize * 0.62f)
                    baseline(c, b, h.rect.centerX(), h.rect.centerY() + text.textSize * 0.62f)
                } else baseline(c, l, h.rect.centerX(), h.rect.centerY())
            }
        }
    }

    private fun drawArrow(c: Canvas, op: EditOp, cx: Float, cy: Float, size: Float, color: Int) {
        val icon = when (op) {
            EditOp.LEFT -> Icon.ARROW_LEFT
            EditOp.RIGHT -> Icon.ARROW_RIGHT
            EditOp.UP -> Icon.ARROW_UP
            else -> Icon.ARROW_DOWN
        }
        KeyIcons.draw(c, icon, cx, cy, size, color, stroke, fill)
    }

    private fun baseline(c: Canvas, s: String, x: Float, cy: Float) {
        val fm = text.fontMetrics
        c.drawText(s, x, cy - (fm.ascent + fm.descent) / 2, text)
    }
}
