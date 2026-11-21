package com.resyst.vk.ime

import com.resyst.vk.R
import com.resyst.vk.core.Txt
import android.util.Log
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.resyst.vk.core.Legend
import com.resyst.vk.core.Mode
import com.resyst.vk.core.Palette
import com.resyst.vk.core.Drawn
import com.resyst.vk.core.Ink
import com.resyst.vk.core.Surface
import com.resyst.vk.core.Quick
import com.resyst.vk.core.QuickAction
import com.resyst.vk.core.QuickLayout
import kotlin.math.max
import kotlin.math.min

/**
 * r10 (UX-3): the quick panel ⚙ opens, drawn by [KeyboardView] over the strip + keys like the
 * clipboard and emoji panels. A header (‹ Teclado · title), an optional update line (the r9
 * notice stays reachable once the strip chip stepped down, QP5), and a 2 × 4 grid of tiles —
 * each a [KeyIcons] icon, its label and its current state, one TalkBack node, ≥ 48 dp (QP4).
 *
 * r14 (PS4): a row of the user's temas, each drawn in its own colours (a miniature: key, modifier and accent), the
 * one in use ringed; a tap switches tema and the keyboard's colours cross-fade. «Personalizar» at its end opens
 * Settings › Apariencia.
 *
 * r15 (QP6–QP14, redesign after the owner's 0.14.0 screenshot: names cut to «No…», the row and the tiles fighting for
 * the height, uneven gaps): the geometry is [QuickLayout] (pure, JVM-swept). A tema chip is as wide as its whole name and
 * the row scrolls sideways when the temas do not fit (edge fades say there is more); the tiles take the rest of the
 * height with one rhythm of gaps, and their content steps down (disc → plain icon → icon + label) before the chips move
 * into the header; the text follows the font scale (×1 to ×1.3) and a label shrinks before it shortens.
 */
class QuickPanel(private val dp: Float, private val tx: Txt) {
    enum class Act { CLOSE, UPDATE, TILE, TEMA, PERSONALIZE }
    class Hit(val act: Act, val action: QuickAction?, val rect: RectF, val desc: String, val enabled: Boolean = true, val tema: String? = null)

    /** A tema as the row shows it: [palette] as it would be drawn now, [desc] = its TalkBack name. */
    class TemaChip(val id: String, val name: String, val icon: String, val palette: Palette, val active: Boolean, val desc: String)

    val bounds = RectF()
    val hits = ArrayList<Hit>()
    private var tiles: List<Quick.Tile> = emptyList()
    private var update: String? = null
    private var temas: List<TemaChip> = emptyList()
    private var personalize = tx.get(R.string.quick_personalize)
    private val title get() = tx.get(R.string.quick_title)
    private val back get() = tx.get(R.string.quick_back)

    /** The system font scale (QP9): the panel's text follows it from ×1 to ×1.3. */
    var fontScale = 1f
        set(v) { if (v != field) { field = v; relayout() } }
    /** The keyboard's face by weight (set by [KeyboardView]); measuring with it keeps a name whole on the real font. */
    var face: ((Int) -> Typeface)? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private companion object {
        /** r14 fix (PS8): the smallest a tile's state line shrinks to before it shortens (dp). */
        const val STATE_MIN = 9.5f
        /** QP9: the smallest a tile's label shrinks to before it shortens (dp). */
        const val LABEL_MIN = 10.5f
        /** Past this the touch on the chip row scrolls it instead of tapping (QP11, dp). */
        const val SLOP = 10f
        /** A tema chip: its leading padding, its swatch (the key colour with the accent on it), the gap to the name, the end padding (dp). */
        const val CHIP_PAD = 8f
        const val SWATCH = 20f
        const val SWATCH_GAP = 6f
        const val CHIP_END = 10f
        /** «Personalizar» as its palette button when the temas need the row (a 48 dp target). */
        const val PERSONALIZE_ICON = 48f
    }
    private val tmp = RectF()
    /** A miniature key of a tema chip (no allocation while drawing, PE1). */
    private val mini = RectF()

    /** The last layout (null before the panel has bounds). */
    private var geo: QuickLayout.Result? = null
    /** How far the tema row is scrolled (px, 0 … [QuickLayout.Result.maxScroll]). */
    private var scroll = 0f
    /** The panel was laid out since it was last opened (the first layout scrolls the tema in use into view, QP12). */
    private var placed = false

    private fun ff() = Legend.fontFactor(fontScale).coerceAtMost(1.3f)
    private fun labelSize() = 13 * dp * ff()
    private fun stateSize() = 11 * dp * ff()
    private fun chipText() = 13 * dp * ff()
    private fun tf(w: Int): Typeface = face?.invoke(w) ?: Typeface.DEFAULT

    fun set(tiles: List<Quick.Tile>, update: String?, temas: List<TemaChip> = emptyList(), personalize: String = this.personalize) {
        this.tiles = tiles
        this.update = update
        this.temas = temas
        this.personalize = personalize
        relayout()
    }

    /** The panel opens (not a refresh after a tap): the next layout scrolls the tema in use into view (QP12). */
    fun opened() { placed = false; scroll = 0f }

    fun setBounds(r: RectF) { bounds.set(r); relayout() }

    fun hitAt(x: Float, y: Float): Hit? = hits.firstOrNull { it.rect.contains(x, y) }

    /** True when ([x], [y]) is on a tema row that can scroll (a sideways drag there scrolls it, QP11). */
    fun scrollsAt(x: Float, y: Float): Boolean {
        val g = geo ?: return false
        val r = g.viewport ?: return false
        return g.maxScroll > 0f && x >= r.l && x <= r.r && y >= r.t - 4 * dp && y <= r.b + 4 * dp
    }

    /** Scrolls the tema row by [dx] px; true when it moved. */
    fun scrollBy(dx: Float): Boolean {
        val g = geo ?: return false
        val s = (scroll + dx).coerceIn(0f, g.maxScroll)
        if (s == scroll) return false
        scroll = s
        place(g)
        return true
    }

    val slop get() = SLOP * dp

    private fun lineH(size: Float, weight: Int): Float {
        text.textSize = size
        text.typeface = tf(weight)
        val fm = text.fontMetrics
        return fm.descent - fm.ascent
    }

    /** Each tier's content height on the real font: what a tile needs (QP7). */
    private fun needs(): Map<QuickLayout.Tier, Float> {
        val l = lineH(labelSize(), 600)
        val s = lineH(stateSize(), 500)
        return mapOf(
            QuickLayout.Tier.FULL to (5 + 30 + 4 + 1 + 5) * dp + l + s,
            QuickLayout.Tier.COMPACT to (5 + 20 + 4 + 1 + 5) * dp + l + s,
            QuickLayout.Tier.MINI to (4 + 16 + 2 + 4) * dp + l,
        )
    }

    /** A tema chip's whole width: padding, its swatch, the full name (never cut, QP6). */
    private fun chipWidth(c: TemaChip): Float {
        text.textSize = chipText()
        text.typeface = tf(if (c.active) 700 else 550)
        return (CHIP_PAD + SWATCH + SWATCH_GAP + CHIP_END) * dp + text.measureText(c.name)
    }

    private fun personalizeWidth(): Float {
        text.textSize = chipText()
        text.typeface = tf(600)
        return (12 + 16 + 8 + 14) * dp + text.measureText(personalize)
    }

    private fun backWidth(): Float {
        text.textSize = 14 * dp * ff()
        text.typeface = tf(600)
        return (QuickLayout.MARGIN + 21 + 14) * dp + text.measureText(back)
    }

    private fun titleWidth(): Float {
        text.textSize = 11 * dp * ff()
        text.typeface = tf(700)
        text.letterSpacing = 0.12f
        val w = text.measureText(title)
        text.letterSpacing = 0f
        return w
    }

    private fun relayout() {
        hits.clear()
        if (bounds.width() <= 0f) { geo = null; return }
        val g = QuickLayout.layout(QuickLayout.Input(
            left = bounds.left, top = bounds.top, width = bounds.width(), height = bounds.height(), dp = dp,
            tiles = tiles.size, update = update != null,
            chipW = temas.map(::chipWidth), personalizeW = personalizeWidth(), personalizeIconW = PERSONALIZE_ICON * dp, backW = backWidth(), titleW = titleWidth(),
            need = needs(), activeChip = temas.indexOfFirst { it.active },
        ))
        geo = g
        if (!placed) { scroll = g.scrollToActive; placed = true }
        scroll = scroll.coerceIn(0f, g.maxScroll)
        place(g)
    }

    /** The hit targets for [g] at the current scroll. */
    private fun place(g: QuickLayout.Result) {
        hits.clear()
        hits += Hit(Act.CLOSE, null, RectF(g.back.l, g.back.t, g.back.r, g.back.b), tx.get(R.string.quick_back_desc))
        update?.let { u -> g.update?.let { b -> hits += Hit(Act.UPDATE, null, RectF(b.l, b.t, b.r, b.b), u) } }
        val vp = g.viewport
        if (vp != null) {
            // a chip's target is its visible part, 4 dp taller than the chip on each side (48 dp, the row's gaps)
            temas.forEachIndexed { i, t ->
                val b = g.chips.getOrNull(i) ?: return@forEachIndexed
                val l = max(vp.l, b.l - scroll)
                val r = min(vp.r, b.r - scroll)
                if (r - l >= 12 * dp) hits += Hit(Act.TEMA, null, RectF(l, b.t - 4 * dp, r, b.b + 4 * dp), t.desc, tema = t.id)
            }
            g.personalize?.let { b -> hits += Hit(Act.PERSONALIZE, null, RectF(b.l, b.t - 4 * dp, b.r, b.b + 4 * dp), personalize) }
        }
        tiles.forEachIndexed { i, t ->
            val b = g.tiles.getOrNull(i) ?: return@forEachIndexed
            hits += Hit(Act.TILE, t.action, RectF(b.l, b.t, b.r, b.b), t.desc(tx), t.enabled)
        }
    }

    /**
     * Debuggable builds log this when the panel opens (the E2E reads it): the arrangement chosen and what the real
     * font would have cut — names are never cut by construction (QP6); a label or state that still had to shorten
     * after shrinking is counted.
     */
    fun logReport(tag: String) {
        val g = geo ?: return
        var labelsCut = 0
        var statesCut = 0
        for ((i, t) in tiles.withIndex()) {
            val b = g.tiles.getOrNull(i) ?: continue
            val w = b.w - 8 * dp
            if (shrinkTo(t.label, labelSize(), LABEL_MIN * dp, w, 600) < 0f) labelsCut++
            if (g.tier != QuickLayout.Tier.MINI && shrinkTo(t.state, stateSize(), STATE_MIN * dp, w, 500) < 0f) statesCut++
        }
        // a name is cut when its chip is drawn narrower than the name needs: never, by construction (QP6); counted all the same
        val namesCut = temas.withIndex().count { (k, t) -> (g.chips.getOrNull(k)?.w ?: 0f) + 0.5f < chipWidth(t) }
        val font = (fontScale * 100).toInt()
        Log.i(tag, "quick: w=${(bounds.width() / dp).toInt()}dp h=${(bounds.height() / dp).toInt()}dp font=$font% place=${g.place} tier=${g.tier} tileH=${(g.tileH / dp).toInt()}dp temas=${temas.size} scrollable=${g.maxScroll > 0f} personalizeIcon=${g.personalizeIcon} namesCut=$namesCut labelsCut=$labelsCut statesCut=$statesCut title=${g.titleShown}")
    }

    /** The size [s] fits [maxW] at, from [from] down to [floor]; -1 when even the floor is too wide. */
    private fun shrinkTo(s: String, from: Float, floor: Float, maxW: Float, weight: Int): Float {
        text.typeface = tf(weight)
        var size = from
        text.textSize = size
        while (size > floor && text.measureText(s) > maxW) { size = max(floor, size - 0.25f * dp); text.textSize = size }
        return if (text.measureText(s) <= maxW) size else -1f
    }

    fun draw(c: Canvas, p: Palette, radius: Float, typeface: (Int) -> Typeface, pressed: Hit?, mode: Mode) {
        val t = p.theme
        val g = geo ?: return
        fill.shader = null
        fill.color = Drawn.surface(p, Surface.BACKGROUND)
        c.drawRect(bounds, fill)
        // header: ← Teclado on the left, the title on the right (it yields to the chips when they move up, QP7)
        val close = hits.firstOrNull { it.act == Act.CLOSE }
        if (close != null) {
            val down = close === pressed
            if (down) {
                fill.color = Drawn.surface(p, Surface.PRESSED)
                c.drawRoundRect(close.rect.left + 4 * dp, close.rect.top + 6 * dp, close.rect.right - 4 * dp, close.rect.bottom - 6 * dp, radius, radius, fill)
            }
            val backInk = if (down) Drawn.ink(p, Surface.PRESSED, Ink.TEXT_MOD) else Drawn.ink(p, Surface.BACKGROUND, Ink.TEXT_MOD)
            // the arrow's left edge on the panel's margin, like the chips and the tiles below it (QP8)
            KeyIcons.draw(c, Icon.ARROW_LEFT, close.rect.left + QuickLayout.MARGIN * dp + 7 * dp, close.rect.centerY(), 14 * dp, backInk, stroke, fill)
            text.textAlign = Paint.Align.LEFT
            text.color = backInk
            text.textSize = 14 * dp * ff()
            text.typeface = typeface(600)
            baseline(c, back, close.rect.left + QuickLayout.MARGIN * dp + 21 * dp, close.rect.centerY())
        }
        if (g.titleShown && g.back.r + 16 * dp + titleWidth() <= bounds.right - QuickLayout.MARGIN * dp) {
            text.textAlign = Paint.Align.RIGHT
            text.color = Drawn.ink(p, Surface.BACKGROUND, Ink.ACCENT)
            text.textSize = 11 * dp * ff()
            text.typeface = typeface(700)
            text.letterSpacing = 0.12f
            baseline(c, title, bounds.right - QuickLayout.MARGIN * dp, g.back.t + g.back.h / 2)
            text.letterSpacing = 0f
        }
        // update line (r9 notice, QP5)
        hits.firstOrNull { it.act == Act.UPDATE }?.let { h ->
            val down = h === pressed
            fill.color = Drawn.surface(p, if (down) Surface.PRESSED else Surface.CHIP)
            val rr = h.rect.height() / 2
            c.drawRoundRect(h.rect, rr, rr, fill)
            stroke.color = p.accentGlow
            stroke.strokeWidth = dp
            c.drawRoundRect(h.rect, rr, rr, stroke)
            fill.color = Drawn.surface(p, Surface.SOLID)
            c.drawCircle(h.rect.left + 18 * dp, h.rect.centerY(), 9 * dp, fill)
            drawDownload(c, h.rect.left + 18 * dp, h.rect.centerY(), Drawn.ink(p, Surface.SOLID, Ink.ACCENT_INK))
            text.textAlign = Paint.Align.LEFT
            text.color = if (down) Drawn.ink(p, Surface.PRESSED, Ink.ACCENT) else Drawn.ink(p, Surface.CHIP, Ink.ACCENT)
            text.textSize = 13.5f * dp * ff().coerceAtMost(1.15f)
            text.typeface = typeface(650)
            baseline(c, ellipsize(h.desc, h.rect.width() - 44 * dp), h.rect.left + 34 * dp, h.rect.centerY())
        }
        // r15: the tema row — whole names, scrolled; the pressed chip is the one under the finger
        g.viewport?.let { vp -> drawRow(c, p, g, vp, radius, typeface, pressed) }
        // tiles
        for (h in hits) if (h.act == Act.TILE) {
            val a = h.action ?: continue
            val tile = tiles.firstOrNull { it.action == a } ?: continue
            drawTile(c, p, h, tile, g.tier, radius, typeface, pressed, mode)
        }
    }

    private fun drawRow(c: Canvas, p: Palette, g: QuickLayout.Result, vp: QuickLayout.Box, radius: Float, typeface: (Int) -> Typeface, pressed: Hit?) {
        c.save()
        c.clipRect(vp.l, vp.t - 4 * dp, vp.r, vp.b + 4 * dp)
        temas.forEachIndexed { i, chip ->
            val b = g.chips.getOrNull(i) ?: return@forEachIndexed
            tmp.set(b.l - scroll, b.t, b.r - scroll, b.b)
            if (tmp.right < vp.l || tmp.left > vp.r) return@forEachIndexed
            val down = pressed?.act == Act.TEMA && pressed.tema == chip.id
            drawTema(c, tmp, chip, radius, typeface, down)
        }
        c.restore()
        // edge fades: more temas to that side (decoration, no ink on it)
        if (g.maxScroll > 0f) {
            val w = 22 * dp
            if (scroll > 0.5f) fade(c, p, vp.l, vp.t - 4 * dp, vp.l + w, vp.b + 4 * dp, toRight = false)
            if (scroll < g.maxScroll - 0.5f) fade(c, p, vp.r - w, vp.t - 4 * dp, vp.r, vp.b + 4 * dp, toRight = true)
        }
        // «Personalizar», pinned at the end: labelled when everything fits, else its palette button (TalkBack keeps the name)
        g.personalize?.let { b ->
            tmp.set(b.l, b.t, b.r, b.b)
            drawPersonalize(c, p, tmp, typeface, pressed?.act == Act.PERSONALIZE, g.personalizeIcon)
        }
    }

    /** The two edge fades, cached by colour and place (a shader is built only when either changes, PE1). */
    private val fadeKey = LongArray(2) { -1L }
    private val fadeShader = arrayOfNulls<LinearGradient>(2)

    /** A fade from transparent to the background over [l]…[r] ([toRight]: opaque at the right). */
    private fun fade(c: Canvas, p: Palette, l: Float, t: Float, r: Float, b: Float, toRight: Boolean) {
        val bg = Drawn.surface(p, Surface.BACKGROUND)
        val slot = if (toRight) 1 else 0
        val key = (bg.toLong() shl 32) or (l.toInt().toLong() and 0xFFFFFFFFL)
        var sh = fadeShader[slot]
        if (sh == null || fadeKey[slot] != key) {
            val clear = Drawn.clearOf(p)
            sh = LinearGradient(l, 0f, r, 0f, if (toRight) clear else bg, if (toRight) bg else clear, Shader.TileMode.CLAMP)
            fadeShader[slot] = sh
            fadeKey[slot] = key
        }
        fill.shader = sh
        c.drawRect(l, t, r, b, fill)
        fill.shader = null
    }

    private fun drawPersonalize(c: Canvas, p: Palette, r: RectF, typeface: (Int) -> Typeface, down: Boolean, iconOnly: Boolean) {
        // the palette button is a circle the chip row's height, centred in its 48 dp target
        if (iconOnly) { val d = r.height(); val cx = r.centerX(); r.set(cx - d / 2, r.top, cx + d / 2, r.bottom) }
        val rr = r.height() / 2
        fill.color = Drawn.surface(p, if (down) Surface.PRESSED else Surface.PLAIN)
        c.drawRoundRect(r, rr, rr, fill)
        stroke.color = p.accentGlow
        stroke.strokeWidth = dp
        c.drawRoundRect(r, rr, rr, stroke)
        KeyIcons.draw(c, Icon.PALETTE, if (iconOnly) r.centerX() else r.left + 20 * dp, r.centerY(), 16 * dp,
            if (down) Drawn.ink(p, Surface.PRESSED, Ink.ACCENT) else Drawn.ink(p, Surface.PLAIN, Ink.ACCENT), stroke, fill)
        if (iconOnly) return
        text.textAlign = Paint.Align.LEFT
        text.color = if (down) Drawn.ink(p, Surface.PRESSED, Ink.TEXT) else Drawn.ink(p, Surface.PLAIN, Ink.TEXT)
        text.textSize = chipText()
        text.typeface = typeface(600)
        baseline(c, personalize, r.left + 36 * dp, r.centerY())
    }

    /**
     * A tile: its content centred as a group (QP8) — FULL: the icon in a disc, the label, the state; COMPACT: the plain
     * icon; MINI: icon and label (the state stays in its TalkBack name). The label shrinks to fit before it shortens.
     */
    private fun drawTile(c: Canvas, p: Palette, h: Hit, tile: Quick.Tile, tier: QuickLayout.Tier, radius: Float, typeface: (Int) -> Typeface, pressed: Hit?, mode: Mode) {
        val t = p.theme
        val active = isActive(tile, mode)
        val rad = radius + 4 * dp
        val down = h === pressed && h.enabled
        fill.color = Drawn.surface(p, if (down) Surface.PRESSED else Surface.PLAIN)
        c.drawRoundRect(h.rect, rad, rad, fill)
        stroke.color = if (active) p.accentGlow else t.edge
        stroke.strokeWidth = dp
        c.drawRoundRect(h.rect, rad, rad, stroke)
        val ink = when {
            !h.enabled -> Drawn.disabled(p)
            active && down -> Drawn.ink(p, Surface.PRESSED, Ink.ACCENT)
            active -> Drawn.ink(p, Surface.PLAIN, Ink.ACCENT)
            down -> Drawn.ink(p, Surface.PRESSED, Ink.TEXT)
            else -> Drawn.ink(p, Surface.PLAIN, Ink.TEXT)
        }
        val w = h.rect.width() - 8 * dp
        val labelH = lineH(labelSize(), 600)
        val stateH = lineH(stateSize(), 500)
        val text2 = tier != QuickLayout.Tier.MINI
        val (iconBox, gapAfterIcon) = when (tier) {
            QuickLayout.Tier.FULL -> {
                val room = h.rect.height() - (10 + 4 + 1) * dp - labelH - stateH
                min(52 * dp, max(30 * dp, min(room, h.rect.height() * 0.4f))) to 4 * dp
            }
            QuickLayout.Tier.COMPACT -> min(24 * dp, max(20 * dp, h.rect.height() * 0.22f)) to 4 * dp
            QuickLayout.Tier.MINI -> 16 * dp to 2 * dp
        }
        val group = iconBox + gapAfterIcon + labelH + if (text2) 1 * dp + stateH else 0f
        var y = h.rect.centerY() - group / 2
        val iy = y + iconBox / 2
        if (tier == QuickLayout.Tier.FULL) {
            fill.color = Drawn.surface(p, if (active) Surface.CHIP else Surface.ICON_DISC)
            c.drawCircle(h.rect.centerX(), iy, iconBox / 2, fill)
            KeyIcons.draw(c, iconFor(tile), h.rect.centerX(), iy, iconBox * 0.52f,
                if (active) Drawn.ink(p, Surface.CHIP, Ink.ACCENT) else if (h.enabled) Drawn.ink(p, Surface.ICON_DISC, Ink.TEXT_MOD) else ink, stroke, fill)
        } else {
            KeyIcons.draw(c, iconFor(tile), h.rect.centerX(), iy, iconBox * 0.92f, ink, stroke, fill)
        }
        y += iconBox + gapAfterIcon
        text.textAlign = Paint.Align.CENTER
        text.color = ink
        val ls = shrinkTo(tile.label, labelSize(), LABEL_MIN * dp, w, 600)
        text.textSize = if (ls > 0f) ls else LABEL_MIN * dp
        text.typeface = typeface(600)
        baseline(c, ellipsizeWords(tile.label, w), h.rect.centerX(), y + labelH / 2)
        if (!text2) return
        y += labelH + 1 * dp
        text.color = if (!h.enabled) Drawn.disabled(p) else if (down) Drawn.ink(p, Surface.PRESSED, Ink.MUTED) else Drawn.ink(p, Surface.PLAIN, Ink.MUTED)
        text.textSize = stateSize()
        text.typeface = typeface(500)
        // r14 fix (PS8): the state shrinks to fit (≥ 9.5 dp) and only then shortens, at a word boundary
        val shownState = tile.state
        val stateW = w
        while (text.textSize > STATE_MIN * dp && text.measureText(shownState) > stateW) text.textSize = max(STATE_MIN * dp, text.textSize - 0.25f * dp)
        baseline(c, ellipsizeWords(shownState, stateW), h.rect.centerX(), y + stateH / 2)
    }

    /** One tema in its own palette: a miniature (key, modifier, accent) and its whole name; the one in use ringed in its accent. */
    private fun drawTema(c: Canvas, r: RectF, chip: TemaChip, radius: Float, typeface: (Int) -> Typeface, pressed: Boolean) {
        val tp = chip.palette
        val rad = min(r.height() / 2, radius + 8 * dp)
        fill.color = Drawn.surface(tp, if (pressed) Surface.PRESSED else Surface.CARD)
        c.drawRoundRect(r, rad, rad, fill)
        stroke.color = if (chip.active) Drawn.ink(tp, Surface.CARD, Ink.ACCENT) else tp.theme.edgeHi
        stroke.strokeWidth = if (chip.active) 2 * dp else dp
        c.drawRoundRect(r, rad, rad, stroke)
        // r15: the swatch — a key of the tema (its key colour, edged) with its accent set on its corner
        val k = 15 * dp
        val x0 = r.left + CHIP_PAD * dp
        val y0 = r.centerY() - k / 2 - 1.5f * dp
        fill.color = Drawn.surface(tp, Surface.PLAIN)
        mini.set(x0, y0, x0 + k, y0 + k)
        c.drawRoundRect(mini, 4 * dp, 4 * dp, fill)
        stroke.color = Drawn.miniKeyEdge(tp); stroke.strokeWidth = 0.9f * dp
        c.drawRoundRect(mini, 4 * dp, 4 * dp, stroke)
        val ax = x0 + SWATCH * dp - 5 * dp
        val ay = y0 + k - 1 * dp
        fill.color = Drawn.surface(tp, if (pressed) Surface.PRESSED else Surface.CARD)
        c.drawCircle(ax, ay, 6 * dp, fill)
        fill.color = Drawn.surface(tp, Surface.SOLID)
        c.drawCircle(ax, ay, 4.5f * dp, fill)
        val x = x0 + (SWATCH + SWATCH_GAP) * dp
        text.textAlign = Paint.Align.LEFT
        text.color = if (pressed) Drawn.ink(tp, Surface.PRESSED, Ink.TEXT) else Drawn.ink(tp, Surface.CARD, Ink.TEXT)
        text.textSize = chipText()
        text.typeface = typeface(if (chip.active) 700 else 550)
        baseline(c, chip.name, x, r.centerY()) // QP6: the whole name, always (the chip is as wide as it)
    }

    /** Tiles whose state is "on" get the accent (a mode is on, one-hand is on). */
    private fun isActive(t: Quick.Tile, mode: Mode): Boolean = when (t.action) {
        QuickAction.MODE -> mode != Mode.NONE
        QuickAction.ONE_HAND -> t.mark != Quick.MARK_OFF
        else -> false
    }

    private fun iconFor(t: Quick.Tile): com.resyst.vk.ime.Icon = when (t.action) {
        QuickAction.DAY_NIGHT -> if (t.mark == Quick.MARK_DARK) Icon.SUN else Icon.MOON
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
        stroke.strokeCap = Paint.Cap.BUTT
    }

    private fun ellipsize(s: String, maxW: Float): String {
        if (maxW <= 0f) return ""
        if (text.measureText(s) <= maxW) return s
        var n = s.length
        while (n > 1 && text.measureText(s, 0, n) + text.measureText("…") > maxW) n--
        return s.substring(0, n) + "…"
    }

    /** [s] shortened at a word boundary to fit [maxW] (one long word: at a character). */
    private fun ellipsizeWords(s: String, maxW: Float): String {
        if (maxW <= 0f) return ""
        if (text.measureText(s) <= maxW) return s
        val ell = text.measureText("…")
        var cut = s.lastIndexOf(' ')
        while (cut > 0) {
            if (text.measureText(s, 0, cut) + ell <= maxW) return s.substring(0, cut) + "…"
            cut = s.lastIndexOf(' ', cut - 1)
        }
        return ellipsize(s, maxW)
    }

    private fun baseline(c: Canvas, s: String, x: Float, cy: Float) {
        val fm = text.fontMetrics
        c.drawText(s, x, cy - (fm.ascent + fm.descent) / 2, text)
    }
}
