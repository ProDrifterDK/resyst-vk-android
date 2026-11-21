package com.resyst.vk.core

/**
 * r15 (docs/failure-modes.md QP6–QP14): the quick-settings panel's geometry, pure and JVM-swept. The view
 * (ime/QuickPanel) measures its text with the real font and draws; this decides where everything goes.
 *
 * One rhythm (QP8): side margins and the gap above the tiles are [MARGIN] dp, the bottom margin too (the grid ends one
 * margin above the panel's bottom: no dead band), tiles are [GAP] dp apart in both directions. Tema chips are as wide as
 * their whole name (QP6); «Personalizar» is pinned at the row's right end, with its label when everything fits and as
 * its palette button when the temas need the room, and the chips scroll sideways in what is left when they still do
 * not fit. The layout takes the richest arrangement that fits the height (QP7):
 *
 *   1. chips on their own row, FULL tiles (icon disc, label, state)
 *   2. chips on their own row, COMPACT tiles (plain icon, label, state)
 *   3. chips in the header beside «← Teclado» (the title yields), COMPACT tiles
 *   4. chips on their own row, MINI tiles (icon, label; the state stays in the TalkBack name)
 *   5. chips in the header, MINI tiles
 *
 * and never a tile under [Quick.MIN_TILE_DP] (QP4). Units: px in, px out; [dp] converts.
 */
object QuickLayout {
    const val MARGIN = 12f
    const val GAP = 8f
    const val HEADER = 48f
    const val CHIP_ROW = 44f
    const val CHIP_H = 40f
    const val CHIP_GAP = 8f
    const val UPDATE = 36f
    /** Space between the header and an update line or the chip row (the header's own text already breathes). */
    const val AFTER_HEADER = 2f

    enum class Place { ROW, HEADER, NONE }
    enum class Tier { FULL, COMPACT, MINI }

    /** A rectangle in px (core stays free of android.graphics). */
    data class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
        val w get() = r - l
        val h get() = b - t
        fun overlaps(o: Box) = l < o.r - 0.01f && o.l < r - 0.01f && t < o.b - 0.01f && o.t < b - 0.01f
    }

    /**
     * What the view measured: [chipW] = each tema chip's full width (its whole name, never cut), [personalizeW] the
     * labelled «Personalizar» chip and [personalizeIconW] its palette button, [backW] «← Teclado», [titleW] the title;
     * [need] = the content height of a tile per tier.
     */
    class Input(
        val left: Float, val top: Float, val width: Float, val height: Float, val dp: Float,
        val tiles: Int, val update: Boolean,
        val chipW: List<Float>, val personalizeW: Float, val personalizeIconW: Float, val backW: Float, val titleW: Float,
        val need: Map<Tier, Float>,
        val activeChip: Int = -1,
    )

    class Result(
        val place: Place, val tier: Tier,
        val back: Box, val titleShown: Boolean, val update: Box?,
        /** The chip row (null = no chips) and the part of it the tema chips scroll in (left of «Personalizar»). */
        val row: Box?, val viewport: Box?,
        /** Each tema chip at scroll 0 (the view subtracts its scroll); «Personalizar» pinned at the row's end. */
        val chips: List<Box>, val personalize: Box?, val personalizeIcon: Boolean,
        /** How far the row scrolls (0 = everything fits). */
        val maxScroll: Float,
        val tiles: List<Box>, val tileH: Float,
        /** The scroll that shows the tema in use (0 when it is already visible or there is none). */
        val scrollToActive: Float,
    )

    fun layout(i: Input): Result {
        val dp = i.dp
        val order = if (i.chipW.isEmpty()) listOf(Place.NONE to Tier.FULL, Place.NONE to Tier.COMPACT, Place.NONE to Tier.MINI)
        else listOf(Place.ROW to Tier.FULL, Place.ROW to Tier.COMPACT, Place.HEADER to Tier.COMPACT, Place.ROW to Tier.MINI, Place.HEADER to Tier.MINI)
        for ((place, tier) in order) {
            val r = attempt(i, place, tier)
            if (r.tileH >= maxOf(i.need[tier] ?: 0f, Quick.MIN_TILE_DP * dp) - 0.01f) return r
        }
        // nothing fits (a keyboard smaller than the app allows): the smallest arrangement, tiles at their floor
        return attempt(i, order.last().first, order.last().second)
    }

    private fun attempt(i: Input, place: Place, tier: Tier): Result {
        val dp = i.dp
        val m = MARGIN * dp
        val right = i.left + i.width
        val back = Box(i.left, i.top, i.left + i.backW, i.top + HEADER * dp)
        var y = i.top + HEADER * dp
        var update: Box? = null
        if (i.update) {
            update = Box(i.left + m, y + AFTER_HEADER * dp, right - m, y + AFTER_HEADER * dp + UPDATE * dp)
            y = update.b
        }
        val row: Box? = when (place) {
            Place.ROW -> Box(i.left + m, y + AFTER_HEADER * dp, right - m, y + AFTER_HEADER * dp + CHIP_ROW * dp).also { y = it.b }
            Place.HEADER -> Box(back.r + 4 * dp, i.top, right - m, i.top + HEADER * dp)
            Place.NONE -> null
        }
        // chips (QP6): whole widths, packed left; «Personalizar» pinned right (labelled when everything fits, else its
        // palette button), the chips scroll in what is left
        val chips = ArrayList<Box>()
        var personalize: Box? = null
        var icon = false
        var viewport: Box? = null
        var maxScroll = 0f
        var toActive = 0f
        if (row != null) {
            val cg = CHIP_GAP * dp
            val ct = row.t + (row.h - CHIP_H * dp) / 2
            val cb = ct + CHIP_H * dp
            val chipsW = i.chipW.sum() + cg * maxOf(0, i.chipW.size - 1)
            icon = chipsW + cg + i.personalizeW > row.w
            val pw = if (icon) i.personalizeIconW else i.personalizeW
            personalize = Box(row.r - pw, ct, row.r, cb)
            val vp = Box(row.l, row.t, personalize.l - cg, row.b)
            viewport = vp
            var x = vp.l
            for (w in i.chipW) { chips += Box(x, ct, x + w, cb); x += w + cg }
            maxScroll = maxOf(0f, chipsW - vp.w)
            chips.getOrNull(i.activeChip)?.let { a -> if (a.r > vp.r) toActive = minOf(maxScroll, a.r - vp.r + cg) }
        }
        // tiles (QP8): the rest of the height, one margin above (after the chip row) and below
        val cols = 4
        val rows = maxOf(1, (i.tiles + cols - 1) / cols)
        val gridTop = if (place == Place.ROW || update != null) y + m else y + AFTER_HEADER * dp + m * 0.5f
        val gridBottom = i.top + i.height - m
        val g = GAP * dp
        val tw = (i.width - 2 * m - (cols - 1) * g) / cols
        val th = (gridBottom - gridTop - (rows - 1) * g) / rows
        val tileH = maxOf(th, Quick.MIN_TILE_DP * dp)
        val tiles = (0 until i.tiles).map { k ->
            val c = k % cols
            val r = k / cols
            val l = i.left + m + c * (tw + g)
            val t = gridTop + r * (tileH + g)
            Box(l, t, l + tw, t + tileH)
        }
        return Result(place, tier, back, titleShown = place != Place.HEADER, update = update, row = row, viewport = viewport, chips = chips,
            personalize = personalize, personalizeIcon = icon, maxScroll = maxScroll, tiles = tiles, tileH = th, scrollToActive = toActive)
    }
}
