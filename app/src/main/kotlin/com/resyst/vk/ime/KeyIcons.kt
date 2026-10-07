package com.resyst.vk.ime

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.min

/**
 * Vector key icons drawn with Paths: no dependence on font fallback glyphs (⏎ ⌫ ⇧ 🌐 render
 * inconsistently across OEM fonts and the globe would be a color emoji).
 */
enum class Icon { ENTER, BACKSPACE, SHIFT, SHIFT_ON, SHIFT_LOCK, GLOBE, SEARCH, SEND, DONE, NEXT, PREVIOUS, GO, EMOJI }

object KeyIcons {
    private val path = Path()

    fun draw(c: Canvas, icon: Icon, cx: Float, cy: Float, size: Float, color: Int, stroke: Paint, fill: Paint) {
        val s = size
        stroke.color = color
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = s * 0.09f
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND
        fill.shader = null
        fill.color = color
        fill.style = Paint.Style.FILL
        path.reset()
        val h = s / 2
        when (icon) {
            Icon.ENTER -> {
                path.moveTo(cx + h * 0.8f, cy - h * 0.7f)
                path.lineTo(cx + h * 0.8f, cy + h * 0.05f)
                path.lineTo(cx - h * 0.75f, cy + h * 0.05f)
                c.drawPath(path, stroke)
                arrowHead(c, cx - h * 0.8f, cy + h * 0.05f, -1f, 0f, h * 0.45f, stroke)
            }
            Icon.BACKSPACE -> {
                path.moveTo(cx - h * 0.95f, cy)
                path.lineTo(cx - h * 0.45f, cy - h * 0.62f)
                path.lineTo(cx + h * 0.95f, cy - h * 0.62f)
                path.lineTo(cx + h * 0.95f, cy + h * 0.62f)
                path.lineTo(cx - h * 0.45f, cy + h * 0.62f)
                path.close()
                c.drawPath(path, stroke)
                val x0 = cx + h * 0.25f
                val d = h * 0.26f
                c.drawLine(x0 - d, cy - d, x0 + d, cy + d, stroke)
                c.drawLine(x0 - d, cy + d, x0 + d, cy - d, stroke)
            }
            Icon.SHIFT, Icon.SHIFT_ON, Icon.SHIFT_LOCK -> {
                path.moveTo(cx, cy - h * 0.9f)
                path.lineTo(cx + h * 0.85f, cy - h * 0.02f)
                path.lineTo(cx + h * 0.4f, cy - h * 0.02f)
                path.lineTo(cx + h * 0.4f, cy + h * 0.55f)
                path.lineTo(cx - h * 0.4f, cy + h * 0.55f)
                path.lineTo(cx - h * 0.4f, cy - h * 0.02f)
                path.lineTo(cx - h * 0.85f, cy - h * 0.02f)
                path.close()
                c.drawPath(path, if (icon == Icon.SHIFT) stroke else fill)
                if (icon == Icon.SHIFT_LOCK) {
                    c.drawLine(cx - h * 0.4f, cy + h * 0.88f, cx + h * 0.4f, cy + h * 0.88f, stroke)
                }
            }
            Icon.GLOBE -> {
                val r = h * 0.85f
                c.drawCircle(cx, cy, r, stroke)
                c.drawLine(cx - r, cy, cx + r, cy, stroke)
                c.drawOval(cx - r * 0.45f, cy - r, cx + r * 0.45f, cy + r, stroke)
            }
            Icon.EMOJI -> {
                val r = h * 0.85f
                c.drawCircle(cx, cy, r, stroke)
                c.drawCircle(cx - r * 0.36f, cy - r * 0.25f, r * 0.11f, fill)
                c.drawCircle(cx + r * 0.36f, cy - r * 0.25f, r * 0.11f, fill)
                c.drawArc(cx - r * 0.5f, cy - r * 0.2f, cx + r * 0.5f, cy + r * 0.55f, 20f, 140f, false, stroke)
            }
            Icon.SEARCH -> {
                val r = h * 0.5f
                c.drawCircle(cx - h * 0.15f, cy - h * 0.15f, r, stroke)
                c.drawLine(cx + h * 0.22f, cy + h * 0.22f, cx + h * 0.75f, cy + h * 0.75f, stroke)
            }
            Icon.SEND -> {
                path.moveTo(cx - h * 0.8f, cy - h * 0.75f)
                path.lineTo(cx + h * 0.85f, cy)
                path.lineTo(cx - h * 0.8f, cy + h * 0.75f)
                path.lineTo(cx - h * 0.45f, cy)
                path.close()
                c.drawPath(path, fill)
            }
            Icon.DONE -> {
                path.moveTo(cx - h * 0.75f, cy + h * 0.02f)
                path.lineTo(cx - h * 0.2f, cy + h * 0.55f)
                path.lineTo(cx + h * 0.8f, cy - h * 0.55f)
                c.drawPath(path, stroke)
            }
            Icon.NEXT, Icon.GO -> {
                c.drawLine(cx - h * 0.8f, cy, cx + h * 0.7f, cy, stroke)
                arrowHead(c, cx + h * 0.8f, cy, 1f, 0f, h * 0.5f, stroke)
                if (icon == Icon.NEXT) c.drawLine(cx + h * 0.95f, cy - h * 0.6f, cx + h * 0.95f, cy + h * 0.6f, stroke)
            }
            Icon.PREVIOUS -> {
                c.drawLine(cx + h * 0.8f, cy, cx - h * 0.7f, cy, stroke)
                arrowHead(c, cx - h * 0.8f, cy, -1f, 0f, h * 0.5f, stroke)
                c.drawLine(cx - h * 0.95f, cy - h * 0.6f, cx - h * 0.95f, cy + h * 0.6f, stroke)
            }
        }
    }

    private fun arrowHead(c: Canvas, x: Float, y: Float, dx: Float, dy: Float, len: Float, p: Paint) {
        // 45° barbs pointing back from (x, y) along -(dx, dy)
        val bx = -dx * len
        val by = -dy * len
        val px = -by * 0.9f
        val py = bx * 0.9f
        c.drawLine(x, y, x + bx * 0.7f + px * 0.7f, y + by * 0.7f + py * 0.7f, p)
        c.drawLine(x, y, x + bx * 0.7f - px * 0.7f, y + by * 0.7f - py * 0.7f, p)
    }

    fun size(rowH: Float, keyW: Float, dp: Float): Float = min(min(rowH, keyW) * 0.42f, 24 * dp)
}
