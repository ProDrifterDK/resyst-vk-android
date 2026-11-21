package com.resyst.vk.ime

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import com.resyst.vk.core.ColorMath
import com.resyst.vk.core.Drawn
import com.resyst.vk.core.KeyCap
import com.resyst.vk.core.KeyKind
import com.resyst.vk.core.KeyShape
import com.resyst.vk.core.Palette
import com.resyst.vk.core.Surface
import kotlin.math.max
import kotlin.math.min

/**
 * r14: draws key bodies for every shape × cap, always inside the key's 0.13.0 rect (VO1), and reports the face the
 * legend centres on. No allocation per frame (PE1): one Path, one RectF, and the vertical gradients cached per
 * (row, surface kind) for the palette in use — 0.13.0 built a LinearGradient for every key on every frame.
 *
 * [press] is 0…1: 1 while held, fading to 0 after the release (motion), so the press reads as a key that sinks and
 * comes back up, not a flash.
 */
class KeyPainter(private val dp: Float) {
    var shape: KeyShape = KeyShape.SOFT
    var cap: KeyCap = KeyCap.RAISED

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private val skirt = RectF()

    /** Gradients per (row, kind, cap face): built once per palette / layout, [reset] drops them. */
    private val shaders = arrayOfNulls<Shader>(MAX_ROWS * STRIDE)
    private val shaderTop = FloatArray(MAX_ROWS * STRIDE)
    private val shaderBottom = FloatArray(MAX_ROWS * STRIDE)

    fun reset() { shaders.fill(null) }

    /** Corner radius for [r]: the shape's, never more than half the shorter side (PILL = exactly half). */
    fun radius(r: RectF): Float {
        val half = min(r.width(), r.height()) / 2
        return if (shape == KeyShape.PILL) half else min(shape.radiusDp * dp, half)
    }

    /** Fills (or strokes, with a stroke [p]) the key shape over [r]. */
    fun shapeOn(c: Canvas, r: RectF, p: Paint, rad: Float = radius(r)) {
        if (shape == KeyShape.CHAMFER) {
            val k = min(max(rad, 3 * dp) * 1.1f, min(r.width(), r.height()) / 3)
            path.rewind()
            path.moveTo(r.left + k, r.top); path.lineTo(r.right - k, r.top); path.lineTo(r.right, r.top + k)
            path.lineTo(r.right, r.bottom - k); path.lineTo(r.right - k, r.bottom); path.lineTo(r.left + k, r.bottom)
            path.lineTo(r.left, r.bottom - k); path.lineTo(r.left, r.top + k); path.close()
            c.drawPath(path, p)
        } else c.drawRoundRect(r, rad, rad, p)
    }

    private fun gradient(slot: Int, top: Float, bottom: Float, c0: Int, c1: Int): Shader {
        val cached = shaders[slot]
        if (cached != null && shaderTop[slot] == top && shaderBottom[slot] == bottom) return cached
        return LinearGradient(0f, top, 0f, bottom, c0, c1, Shader.TileMode.CLAMP).also {
            shaders[slot] = it; shaderTop[slot] = top; shaderBottom[slot] = bottom
        }
    }

    /**
     * Draws the body of a key over [r] (row [row], surface [kind]) with palette [p]; [press] 0…1; [face] receives the
     * rect the legend centres on. Every colour comes from [Drawn] (r14 fix LG6): the face ends and the press are the
     * key surfaces the legibility gate corrects and checks; the skirt, the hairline and the rings carry no ink.
     */
    fun body(c: Canvas, r: RectF, row: Int, kind: KeyKind, p: Palette, press: Float, face: RectF) {
        val solid = kind == KeyKind.ACCENT_SOLID
        val top = Drawn.surface(p, Surface.KEY_TOP, cap, kind)
        val bottom = Drawn.surface(p, Surface.KEY_BOTTOM, cap, kind)
        val pressedFill = Drawn.surface(p, Surface.KEY_PRESSED, cap, kind)
        fill.shader = null
        face.set(r)
        when (cap) {
            KeyCap.RAISED -> {
                fill.color = Drawn.skirt(p, cap, kind)
                shapeOn(c, r, fill)
                face.set(r.left, r.top, r.right, r.bottom - 2 * dp)
                face.offset(0f, 1.5f * dp * press)
                if (solid) fill.color = top
                else fill.shader = gradient(slot(row, kind, 0), face.top, face.bottom, top, bottom)
                shapeOn(c, face, fill)
                fill.shader = null
                overlay(c, face, pressedFill, press)
            }
            KeyCap.KEYCAP -> {
                // a mechanical keycap: a skirt, and a lighter top face set back from it (more skirt at the bottom,
                // as seen from the typing position); pressing sinks the face into the skirt
                fill.color = Drawn.skirt(p, cap, kind)
                shapeOn(c, r, fill)
                val sink = 2.5f * dp * press
                face.set(r.left + 2.5f * dp, r.top + 1.5f * dp + sink, r.right - 2.5f * dp, r.bottom - 4.5f * dp + sink * 0.6f)
                // dark palettes: the face lifts toward the pressed colour (text is gated against it), the skirt sinks
                // toward black, so the keycap reads at a glance even on near-black keys
                fill.shader = gradient(slot(row, kind, 1), face.top, face.bottom, top, bottom)
                val rad = max(radius(r) - 2 * dp, 1.5f * dp)
                shapeOn(c, face, fill, rad)
                fill.shader = null
                // a hairline of light along the face's top edge
                if (!solid) {
                    stroke.shader = null
                    stroke.strokeWidth = dp
                    stroke.color = Drawn.keycapHairline(p, cap, kind)
                    c.drawLine(face.left + rad, face.top + dp * 0.5f, face.right - rad, face.top + dp * 0.5f, stroke)
                }
                overlay(c, face, pressedFill, press, rad)
            }
            KeyCap.FLAT -> {
                fill.color = top
                shapeOn(c, r, fill)
                overlay(c, r, pressedFill, press)
            }
            KeyCap.OUTLINE -> {
                if (solid) { fill.color = top; shapeOn(c, r, fill) }
                overlay(c, r, pressedFill, press)
                stroke.shader = null
                stroke.strokeWidth = dp
                stroke.color = if (solid) top else Drawn.pressEdge(p, press)
                skirt.set(r); skirt.inset(0.5f * dp, 0.5f * dp)
                shapeOn(c, skirt, stroke)
            }
            KeyCap.BORDERLESS -> {
                when {
                    solid -> { fill.color = top; shapeOn(c, r, fill) }
                    kind == KeyKind.SPACE -> {
                        // the space bar keeps a quiet outline so the thumb finds it; the label sits on the background
                        skirt.set(r.left + 2 * dp, r.top + r.height() * 0.16f, r.right - 2 * dp, r.bottom - r.height() * 0.16f)
                        stroke.shader = null
                        stroke.strokeWidth = dp
                        stroke.color = Drawn.pressEdge(p, press)
                        shapeOn(c, skirt, stroke)
                    }
                }
                if (!solid && press > 0f) {
                    skirt.set(r); skirt.inset(1.5f * dp, 1.5f * dp)
                    overlay(c, skirt, pressedFill, press)
                }
            }
        }
        if (press > 0f && !solid && cap != KeyCap.OUTLINE) {
            stroke.shader = null
            stroke.strokeWidth = 1.2f * dp
            stroke.color = Drawn.pressRing(p, press)
            shapeOn(c, if (cap == KeyCap.BORDERLESS) skirt else face, stroke, if (cap == KeyCap.KEYCAP) max(radius(r) - 2 * dp, 1.5f * dp) else radius(face))
        }
        if (p.highContrast && !solid) {
            // r14 (LG5): every key outlined at ≥ 3:1 against the keyboard
            stroke.shader = null
            stroke.strokeWidth = 1.5f * dp
            stroke.color = p.outline
            skirt.set(r); skirt.inset(0.75f * dp, 0.75f * dp)
            shapeOn(c, skirt, stroke)
        }
    }

    /** The press: [color] (a KEY_PRESSED surface) over the face at alpha [press]; at 1 the legend sits on it alone. */
    private fun overlay(c: Canvas, r: RectF, color: Int, press: Float, rad: Float = radius(r)) {
        if (press <= 0f) return
        fill.shader = null
        fill.color = ColorMath.withAlpha(color, press)
        shapeOn(c, r, fill, rad)
    }

    private fun slot(row: Int, kind: KeyKind, face: Int): Int = row.coerceIn(0, MAX_ROWS - 1) * STRIDE + kind.ordinal * 2 + face

    companion object {
        private const val MAX_ROWS = 6
        /** Kinds × the two faces (RAISED's, KEYCAP's) per row. */
        private val STRIDE = KeyKind.values().size * 2
    }
}
