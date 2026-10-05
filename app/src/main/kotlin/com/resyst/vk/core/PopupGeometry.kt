package com.resyst.vk.core

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Long-press popup placement. The default variant (index 0) opens in the column nearest the
 * pressed key; following variants fill columns to its right, then wrap to its left.
 */
class VariantPopup(val left: Float, val cell: Float, val count: Int, private val defaultCol: Int) {
    val width: Float get() = cell * count

    /** Column (0 = leftmost) for variant [index]. */
    fun columnOf(index: Int): Int {
        val rightSlots = count - defaultCol
        return if (index < rightSlots) defaultCol + index else defaultCol - (index - rightSlots) - 1
    }

    fun indexOfColumn(col: Int): Int {
        val rightSlots = count - defaultCol
        return if (col >= defaultCol) col - defaultCol else rightSlots + (defaultCol - 1 - col)
    }

    fun cellLeft(index: Int): Float = left + columnOf(index) * cell
    fun cellCenter(index: Int): Float = cellLeft(index) + cell / 2f

    fun indexAt(x: Float): Int {
        val col = floor((x - left) / cell).toInt().coerceIn(0, count - 1)
        return indexOfColumn(col)
    }
}

object PopupGeometry {
    fun variants(keyLeft: Float, keyWidth: Float, count: Int, cellWidth: Float, viewWidth: Float, margin: Float): VariantPopup {
        val n = count.coerceAtLeast(1)
        val cell = min(cellWidth, (viewWidth - 2 * margin) / n)
        val total = cell * n
        val center = keyLeft + keyWidth / 2f
        val left = (center - cell / 2f).coerceAtMost(viewWidth - margin - total).coerceAtLeast(margin)
        val col = ((center - left - cell / 2f) / cell).roundToInt().coerceIn(0, n - 1)
        return VariantPopup(left, cell, n, col)
    }

    /** Key preview bubble top, never above the view. */
    fun previewTop(keyTop: Float, previewHeight: Float): Float = (keyTop - previewHeight).coerceAtLeast(0f)
}
