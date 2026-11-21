package com.resyst.vk.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.resyst.vk.core.KeyCap
import com.resyst.vk.core.KeyShape
import com.resyst.vk.core.Palette
import com.resyst.vk.ime.KeyPainter
import kotlin.math.min

/**
 * r14: a keyboard in miniature — three letter rows and the bottom row — drawn with the keyboard's own [KeyPainter],
 * so a palette, a style or a tema card shows the real look (shape, cap, colours, the accent's Enter), not a name.
 * Decorative for TalkBack: the card around it carries the name.
 */
@SuppressLint("ViewConstructor")
class MiniKeyboardView(context: Context, private var palette: Palette, shape: KeyShape, cap: KeyCap) : View(context) {
    private val dp = resources.displayMetrics.density
    /** Radii and insets scaled to the miniature (a key is ~1/4 of a real one). */
    private val painter = KeyPainter(dp * 0.42f).apply { this.shape = shape; this.cap = cap }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val key = RectF()
    private val face = RectF()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun set(p: Palette, shape: KeyShape, cap: KeyCap) {
        palette = p; painter.shape = shape; painter.cap = cap; painter.reset(); invalidate()
    }

    override fun onDraw(c: Canvas) {
        val t = palette.theme
        val w = width.toFloat()
        val h = height.toFloat()
        val r = min(10 * dp, h / 6)
        fill.color = t.bg
        c.drawRoundRect(0f, 0f, w, h, r, r, fill)
        val pad = 4 * dp
        val rows = 4
        val rowH = (h - 2 * pad) / rows
        val unit = (w - 2 * pad) / 10f
        val g = 1.2f * dp
        fun k(x: Float, row: Int, units: Float, kind: KeyPainter.Kind) {
            key.set(pad + x * unit + g / 2, pad + row * rowH + g / 2, pad + (x + units) * unit - g / 2, pad + (row + 1) * rowH - g / 2)
            painter.body(c, key, row, kind, palette, 0f, face)
        }
        for (i in 0 until 10) k(i.toFloat(), 0, 1f, KeyPainter.Kind.NORMAL)
        for (i in 0 until 9) k(0.5f + i, 1, 1f, KeyPainter.Kind.NORMAL)
        k(0f, 2, 1.5f, KeyPainter.Kind.MOD)
        for (i in 0 until 7) k(1.5f + i, 2, 1f, KeyPainter.Kind.NORMAL)
        k(8.5f, 2, 1.5f, KeyPainter.Kind.MOD)
        k(0f, 3, 1.5f, KeyPainter.Kind.MOD)
        k(1.5f, 3, 1f, KeyPainter.Kind.NORMAL)
        k(2.5f, 3, 5f, KeyPainter.Kind.SPACE)
        k(7.5f, 3, 1f, KeyPainter.Kind.NORMAL)
        k(8.5f, 3, 1.5f, KeyPainter.Kind.ACCENT_SOLID)
    }
}

/**
 * r14: one key with a legend, as the keyboard draws it, for the shape / cap / font / weight pickers (a choice is
 * seen, not read). Decorative for TalkBack: the tile carries the option's name.
 */
@SuppressLint("ViewConstructor")
class SampleKeyView(
    context: Context,
    private val palette: Palette,
    shape: KeyShape,
    cap: KeyCap,
    private val legend: String,
    private val typeface: Typeface,
    private val legendDp: Float = 20f,
) : View(context) {
    private val dp = resources.displayMetrics.density
    private val painter = KeyPainter(dp).apply { this.shape = shape; this.cap = cap }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val key = RectF()
    private val face = RectF()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        key.set(w * 0.16f, h * 0.1f, w * 0.84f, h * 0.9f)
        painter.body(c, key, 0, KeyPainter.Kind.NORMAL, palette, 0f, face)
        text.color = palette.theme.text
        text.typeface = typeface
        text.textSize = min(legendDp * dp, face.height() * 0.55f)
        val fm = text.fontMetrics
        c.drawText(legend, face.centerX(), face.centerY() - (fm.ascent + fm.descent) / 2, text)
    }
}
