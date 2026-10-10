package com.resyst.vk.core

/**
 * r11b (GP2): the GIF grid as justified rows. KLIPY's order is kept exactly: items fill rows
 * left to right, top to bottom, in response order; each full row is scaled to span the width,
 * the last row keeps the target height. Nothing is reordered to pack better.
 */
object GifLayout {
    data class Cell(val index: Int, val left: Float, val top: Float, val width: Float, val height: Float) {
        val right get() = left + width
        val bottom get() = top + height
    }

    /** [aspects] = width / height per item (clamped to 0.5..2.2 so a strip never eats a row). */
    fun justify(aspects: List<Float>, width: Float, targetH: Float, gap: Float, maxPerRow: Int = 4): List<Cell> {
        if (width <= 0f || targetH <= 0f) return emptyList()
        val a = aspects.map { if (it.isNaN() || it <= 0f) 1f else it.coerceIn(0.5f, 2.2f) }
        val out = ArrayList<Cell>(a.size)
        var top = 0f
        var start = 0
        while (start < a.size) {
            var end = start
            var sum = 0f
            while (end < a.size && end - start < maxPerRow) {
                sum += a[end]
                end++
                if (sum * targetH + gap * (end - start - 1) >= width) break
            }
            val n = end - start
            val full = sum * targetH + gap * (n - 1) >= width || end < a.size
            val h = if (full) ((width - gap * (n - 1)) / sum).coerceIn(targetH * 0.6f, targetH * 1.6f) else targetH
            var x = 0f
            for (i in start until end) {
                val w = a[i] * h
                out += Cell(i, x, top, w, h)
                x += w + gap
            }
            top += h + gap
            start = end
        }
        return out
    }
}
