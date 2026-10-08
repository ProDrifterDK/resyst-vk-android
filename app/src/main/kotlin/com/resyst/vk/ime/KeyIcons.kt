package com.resyst.vk.ime

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Vector key icons drawn with Paths: no dependence on font fallback glyphs (⏎ ⌫ ⇧ ⚙ 🌐 render
 * inconsistently across OEM fonts and the globe would be a color emoji). r10 (UI-1): one family
 * for the keys, the strip and the quick panel — a single stroke weight ([STROKE] × size), round
 * caps and joins; states are told apart by shape (outline / filled / filled + bar), not color.
 */
enum class Icon {
    ENTER, BACKSPACE, SHIFT, SHIFT_ON, SHIFT_LOCK, GLOBE, SEARCH, SEND, DONE, NEXT, PREVIOUS, GO, EMOJI,
    // r10: strip + quick panel
    GEAR, SUN, MOON, CLIPBOARD, PALETTE, MODE, ONE_HAND, HEIGHT, EDIT,
    // r10: the one-handed rail
    ARROW_LEFT, ARROW_RIGHT, EXPAND,
}

object KeyIcons {
    /** Stroke weight as a share of the icon size, for every icon. */
    const val STROKE = 0.09f

    private val path = Path()
    private val cut = Path()

    fun draw(c: Canvas, icon: Icon, cx: Float, cy: Float, size: Float, color: Int, stroke: Paint, fill: Paint) {
        val s = size
        stroke.color = color
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = s * STROKE
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
                // off = outline, once = filled, locked = filled + bar (QI2: shape, not color)
                c.drawPath(path, if (icon == Icon.SHIFT) stroke else fill)
                if (icon != Icon.SHIFT) c.drawPath(path, stroke) // same silhouette as the outline
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
            Icon.GEAR -> {
                // six square teeth on a ring + a hub: never reads as the sun's thin rays (UI-1)
                val r = h * 0.62f
                val tw = h * 0.17f
                for (i in 0 until 6) {
                    val a = Math.toRadians(i * 60.0 + 30.0)
                    val dx = cos(a).toFloat()
                    val dy = sin(a).toFloat()
                    val x0 = cx + dx * r * 0.9f; val y0 = cy + dy * r * 0.9f
                    val x1 = cx + dx * h * 0.95f; val y1 = cy + dy * h * 0.95f
                    path.reset()
                    path.moveTo(x0 - dy * tw, y0 + dx * tw)
                    path.lineTo(x1 - dy * tw, y1 + dx * tw)
                    path.lineTo(x1 + dy * tw, y1 - dx * tw)
                    path.lineTo(x0 + dy * tw, y0 - dx * tw)
                    path.close()
                    c.drawPath(path, fill)
                }
                c.drawCircle(cx, cy, r, stroke)
                c.drawCircle(cx, cy, h * 0.24f, stroke)
            }
            Icon.SUN -> {
                c.drawCircle(cx, cy, h * 0.4f, stroke)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val dx = cos(a).toFloat()
                    val dy = sin(a).toFloat()
                    c.drawLine(cx + dx * h * 0.68f, cy + dy * h * 0.68f, cx + dx * h * 0.92f, cy + dy * h * 0.92f, stroke)
                }
            }
            Icon.MOON -> {
                path.addCircle(cx, cy, h * 0.8f, Path.Direction.CW)
                cut.reset()
                cut.addCircle(cx + h * 0.42f, cy - h * 0.3f, h * 0.68f, Path.Direction.CW)
                path.op(cut, Path.Op.DIFFERENCE)
                c.drawPath(path, fill)
            }
            Icon.CLIPBOARD -> {
                c.drawRoundRect(cx - h * 0.7f, cy - h * 0.72f, cx + h * 0.7f, cy + h * 0.92f, h * 0.16f, h * 0.16f, stroke)
                c.drawRoundRect(cx - h * 0.34f, cy - h * 0.95f, cx + h * 0.34f, cy - h * 0.55f, h * 0.1f, h * 0.1f, fill)
                c.drawLine(cx - h * 0.36f, cy - h * 0.05f, cx + h * 0.36f, cy - h * 0.05f, stroke)
                c.drawLine(cx - h * 0.36f, cy + h * 0.4f, cx + h * 0.12f, cy + h * 0.4f, stroke)
            }
            Icon.PALETTE -> {
                // a palette: ring with a thumb hole and three paint dots
                c.drawCircle(cx, cy, h * 0.85f, stroke)
                c.drawCircle(cx + h * 0.38f, cy + h * 0.38f, h * 0.18f, stroke)
                c.drawCircle(cx - h * 0.4f, cy - h * 0.05f, h * 0.13f, fill)
                c.drawCircle(cx - h * 0.08f, cy - h * 0.45f, h * 0.13f, fill)
                c.drawCircle(cx + h * 0.34f, cy - h * 0.3f, h * 0.13f, fill)
            }
            Icon.MODE -> {
                // two sliders: a mode is a fixed, named set of switches
                c.drawLine(cx - h * 0.8f, cy - h * 0.4f, cx + h * 0.8f, cy - h * 0.4f, stroke)
                c.drawLine(cx - h * 0.8f, cy + h * 0.4f, cx + h * 0.8f, cy + h * 0.4f, stroke)
                c.drawCircle(cx + h * 0.3f, cy - h * 0.4f, h * 0.22f, fill)
                c.drawCircle(cx - h * 0.3f, cy + h * 0.4f, h * 0.22f, fill)
            }
            Icon.ONE_HAND -> {
                // a narrowed keyboard pushed to one side + the arrow that moves it
                c.drawRoundRect(cx - h * 0.2f, cy - h * 0.6f, cx + h * 0.9f, cy + h * 0.6f, h * 0.14f, h * 0.14f, stroke)
                c.drawLine(cx + h * 0.08f, cy, cx + h * 0.62f, cy, stroke)
                c.drawLine(cx - h * 0.85f, cy, cx - h * 0.45f, cy, stroke)
                arrowHead(c, cx - h * 0.9f, cy, -1f, 0f, h * 0.35f, stroke)
            }
            Icon.HEIGHT -> {
                c.drawLine(cx, cy - h * 0.8f, cx, cy + h * 0.8f, stroke)
                arrowHead(c, cx, cy - h * 0.85f, 0f, -1f, h * 0.4f, stroke)
                arrowHead(c, cx, cy + h * 0.85f, 0f, 1f, h * 0.4f, stroke)
                c.drawLine(cx - h * 0.8f, cy - h * 0.92f, cx + h * 0.8f, cy - h * 0.92f, stroke)
                c.drawLine(cx - h * 0.8f, cy + h * 0.92f, cx + h * 0.8f, cy + h * 0.92f, stroke)
            }
            Icon.ARROW_LEFT, Icon.ARROW_RIGHT -> {
                val d = if (icon == Icon.ARROW_LEFT) -1f else 1f
                c.drawLine(cx - d * h * 0.75f, cy, cx + d * h * 0.7f, cy, stroke)
                arrowHead(c, cx + d * h * 0.8f, cy, d, 0f, h * 0.55f, stroke)
            }
            Icon.EXPAND -> {
                // two outward arrows: back to the full width
                c.drawLine(cx - h * 0.85f, cy, cx + h * 0.85f, cy, stroke)
                arrowHead(c, cx - h * 0.9f, cy, -1f, 0f, h * 0.45f, stroke)
                arrowHead(c, cx + h * 0.9f, cy, 1f, 0f, h * 0.45f, stroke)
                c.drawLine(cx - h * 0.95f, cy - h * 0.7f, cx - h * 0.95f, cy + h * 0.7f, stroke)
                c.drawLine(cx + h * 0.95f, cy - h * 0.7f, cx + h * 0.95f, cy + h * 0.7f, stroke)
            }
            Icon.EDIT -> {
                // a text cursor (I-beam) with arrows either side
                c.drawLine(cx, cy - h * 0.7f, cx, cy + h * 0.7f, stroke)
                c.drawLine(cx - h * 0.25f, cy - h * 0.8f, cx + h * 0.25f, cy - h * 0.8f, stroke)
                c.drawLine(cx - h * 0.25f, cy + h * 0.8f, cx + h * 0.25f, cy + h * 0.8f, stroke)
                arrowHead(c, cx - h * 0.9f, cy, -1f, 0f, h * 0.4f, stroke)
                arrowHead(c, cx + h * 0.9f, cy, 1f, 0f, h * 0.4f, stroke)
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
