package com.resyst.vk.ime

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.resyst.vk.core.ColorMath
import com.resyst.vk.core.Mode
import com.resyst.vk.core.Palette
import com.resyst.vk.core.Drawn
import com.resyst.vk.core.Ink
import com.resyst.vk.core.Surface
import com.resyst.vk.core.Quick
import com.resyst.vk.core.QuickAction
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
 * Settings › Apariencia — where the look is shaped, one tap from the keyboard. The row yields to the tiles when the
 * keyboard is too short to keep them 48 dp.
 */
class QuickPanel(private val dp: Float) {
    enum class Act { CLOSE, UPDATE, TILE, TEMA, PERSONALIZE }
    class Hit(val act: Act, val action: QuickAction?, val rect: RectF, val desc: String, val enabled: Boolean = true, val tema: String? = null)

    /** A tema as the row shows it: [palette] as it would be drawn now, [desc] = its TalkBack name. */
    class TemaChip(val id: String, val name: String, val icon: String, val palette: Palette, val active: Boolean, val desc: String = "Tema $name" + if (active) ", en uso" else "")

    val bounds = RectF()
    val hits = ArrayList<Hit>()
    private var tiles: List<Quick.Tile> = emptyList()
    private var update: String? = null
    private var temas: List<TemaChip> = emptyList()
    private var personalize = "Personalizar"
    private var title = "✦ AJUSTES RÁPIDOS"
    private var back = "Teclado"
    /** r14 (PS6): the drawn label and state of a tile in the phone's language (the TalkBack name stays the tile's). */
    var localize: (String, String) -> Pair<String, String> = { l, st -> l to st }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private companion object {
        /** r14 fix (PS8): the smallest a tile's state line shrinks to before it shortens (dp). */
        const val STATE_MIN = 9.5f
    }
    private val tmp = RectF()

    private val headH get() = 44 * dp
    private val updH get() = if (update != null) 40 * dp else 0f
    private val temaH get() = 50 * dp

    fun set(tiles: List<Quick.Tile>, update: String?, temas: List<TemaChip> = emptyList(), personalize: String = this.personalize) {
        this.tiles = tiles
        this.update = update
        this.temas = temas
        this.personalize = personalize
        relayout()
    }

    /** r14 (PS6): the panel's own words from the string resources (Spanish / English). */
    fun labels(title: String, back: String) { this.title = title; this.back = back }

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
        // the tema row only when the tiles keep their 48 dp under it (QP4)
        val withTemas = temas.isNotEmpty() && (bounds.bottom - top - pad - temaH) / rows >= Quick.MIN_TILE_DP * dp
        if (withTemas) {
            layoutTemas(top + 6 * dp, top + temaH - 2 * dp)
            top += temaH + 4 * dp
        }
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

    /** The temas left to right, «Personalizar» at the end; when they do not fit, the one in use and its followers stay. */
    private fun layoutTemas(top: Float, bottom: Float) {
        val pad = 9 * dp
        text.textSize = 13 * dp
        // measured before the face is known: a margin for DM Sans 600 being wider than the default face
        val persW = text.measureText(personalize) * 1.15f + 48 * dp
        val avail = bounds.width() - 2 * pad - persW - 6 * dp
        val minW = 76 * dp
        val fit = max(1, min(temas.size, (avail / minW).toInt()))
        val start = temas.indexOfFirst { it.active }.coerceAtLeast(0)
        val shown = if (fit >= temas.size) temas else (0 until fit).map { temas[(start + it) % temas.size] }
        val w = min(150 * dp, (avail - (shown.size - 1) * 6 * dp) / shown.size)
        var x = bounds.left + pad
        for (t in shown) {
            hits += Hit(Act.TEMA, null, RectF(x, top, x + w, bottom), t.desc, tema = t.id)
            x += w + 6 * dp
        }
        hits += Hit(Act.PERSONALIZE, null, RectF(bounds.right - pad - persW, top, bounds.right - pad, bottom), personalize)
    }

    fun draw(c: Canvas, p: Palette, radius: Float, typeface: (Int) -> Typeface, pressed: Hit?, mode: Mode) {
        val t = p.theme
        fill.shader = null
        fill.color = Drawn.surface(p, Surface.BACKGROUND)
        c.drawRect(bounds, fill)
        // header
        val close = hits.firstOrNull { it.act == Act.CLOSE }
        if (close != null) {
            val down = close === pressed
            if (down) {
                fill.color = Drawn.surface(p, Surface.PRESSED)
                c.drawRoundRect(close.rect.left + 4 * dp, close.rect.top + 6 * dp, close.rect.right - 4 * dp, close.rect.bottom - 6 * dp, radius, radius, fill)
            }
            val backInk = if (down) Drawn.ink(p, Surface.PRESSED, Ink.TEXT_MOD) else Drawn.ink(p, Surface.BACKGROUND, Ink.TEXT_MOD)
            KeyIcons.draw(c, Icon.ARROW_LEFT, close.rect.left + 22 * dp, close.rect.centerY(), 14 * dp, backInk, stroke, fill)
            text.textAlign = Paint.Align.LEFT
            text.color = backInk
            text.textSize = 14 * dp
            text.typeface = typeface(600)
            baseline(c, back, close.rect.left + 36 * dp, close.rect.centerY())
        }
        text.textAlign = Paint.Align.RIGHT
        text.color = Drawn.ink(p, Surface.BACKGROUND, Ink.ACCENT)
        text.textSize = 11 * dp
        text.typeface = typeface(700)
        text.letterSpacing = 0.12f
        baseline(c, title, bounds.right - 12 * dp, bounds.top + headH / 2)
        text.letterSpacing = 0f
        fill.color = t.edge
        c.drawRect(bounds.left, bounds.top + headH - 1, bounds.right, bounds.top + headH, fill)
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
            text.textSize = 13.5f * dp
            text.typeface = typeface(650)
            baseline(c, ellipsize(h.desc, h.rect.width() - 44 * dp), h.rect.left + 34 * dp, h.rect.centerY())
        }
        // r14: the tema row
        for (h in hits) when (h.act) {
            Act.TEMA -> temas.firstOrNull { it.id == h.tema }?.let { drawTema(c, h, it, radius, typeface, h === pressed) }
            Act.PERSONALIZE -> {
                val rr = h.rect.height() / 2
                val down = h === pressed
                fill.color = Drawn.surface(p, if (down) Surface.PRESSED else Surface.PLAIN)
                c.drawRoundRect(h.rect, rr, rr, fill)
                stroke.color = p.accentGlow
                stroke.strokeWidth = dp
                c.drawRoundRect(h.rect, rr, rr, stroke)
                KeyIcons.draw(c, Icon.PALETTE, h.rect.left + 19 * dp, h.rect.centerY(), 16 * dp,
                    if (down) Drawn.ink(p, Surface.PRESSED, Ink.ACCENT) else Drawn.ink(p, Surface.PLAIN, Ink.ACCENT), stroke, fill)
                text.textAlign = Paint.Align.LEFT
                text.color = if (down) Drawn.ink(p, Surface.PRESSED, Ink.TEXT) else Drawn.ink(p, Surface.PLAIN, Ink.TEXT)
                text.textSize = 13 * dp
                text.typeface = typeface(600)
                baseline(c, ellipsize(h.desc, h.rect.width() - 40 * dp), h.rect.left + 33 * dp, h.rect.centerY())
            }
            else -> Unit
        }
        // tiles
        for (h in hits) if (h.act == Act.TILE) {
            val a = h.action ?: continue
            val tile = tiles.firstOrNull { it.action == a } ?: continue
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
            // the icon sits in a soft disc (accent when the tile's state is on)
            val iconSize = min(20 * dp, h.rect.height() * 0.26f)
            val iy = h.rect.top + h.rect.height() * 0.32f
            val disc = min(iconSize * 0.95f, h.rect.height() * 0.2f)
            fill.color = Drawn.surface(p, if (active) Surface.CHIP else Surface.ICON_DISC)
            c.drawCircle(h.rect.centerX(), iy, disc + 6 * dp, fill)
            KeyIcons.draw(c, iconFor(tile), h.rect.centerX(), iy, iconSize,
                if (active) Drawn.ink(p, Surface.CHIP, Ink.ACCENT) else if (h.enabled) Drawn.ink(p, Surface.ICON_DISC, Ink.TEXT_MOD) else ink, stroke, fill)
            text.textAlign = Paint.Align.CENTER
            text.color = ink
            text.textSize = min(13 * dp, h.rect.width() * 0.16f)
            text.typeface = typeface(600)
            val (shownLabel, shownState) = localize(tile.label, tile.state)
            baseline(c, ellipsize(shownLabel, h.rect.width() - 8 * dp), h.rect.centerX(), h.rect.top + h.rect.height() * 0.68f)
            text.color = if (!h.enabled) Drawn.disabled(p) else if (down) Drawn.ink(p, Surface.PRESSED, Ink.MUTED) else Drawn.ink(p, Surface.PLAIN, Ink.MUTED)
            text.textSize = min(11 * dp, h.rect.width() * 0.14f)
            text.typeface = typeface(500)
            // r14 fix (PS8): the state shrinks to fit (≥ 9.5 dp) and only then shortens, at a word boundary
            val stateW = h.rect.width() - 8 * dp
            while (text.textSize > STATE_MIN * dp && text.measureText(shownState) > stateW) text.textSize = max(STATE_MIN * dp, text.textSize - 0.25f * dp)
            baseline(c, ellipsizeWords(shownState, stateW), h.rect.centerX(), h.rect.top + h.rect.height() * 0.86f)
        }
    }

    /** One tema in its own palette: a miniature (key, modifier, accent) and its name; the active one ringed in the panel's accent. */
    private fun drawTema(c: Canvas, h: Hit, chip: TemaChip, radius: Float, typeface: (Int) -> Typeface, pressed: Boolean) {
        val tp = chip.palette
        val r = h.rect
        val rad = min(r.height() / 2, radius + 8 * dp)
        fill.color = Drawn.surface(tp, if (pressed) Surface.PRESSED else Surface.CARD)
        c.drawRoundRect(r, rad, rad, fill)
        stroke.color = if (chip.active) Drawn.ink(tp, Surface.CARD, Ink.ACCENT) else tp.theme.edgeHi
        stroke.strokeWidth = if (chip.active) 2 * dp else dp
        c.drawRoundRect(r, rad, rad, stroke)
        // miniature: two keys and the accent
        val s = 9 * dp
        val y0 = r.centerY() - s / 2
        var x = r.left + 10 * dp
        fill.color = Drawn.surface(tp, Surface.PLAIN)
        tmp.set(x, y0, x + s, y0 + s); c.drawRoundRect(tmp, 2.5f * dp, 2.5f * dp, fill)
        stroke.color = Drawn.miniKeyEdge(tp); stroke.strokeWidth = 0.9f * dp; c.drawRoundRect(tmp, 2.5f * dp, 2.5f * dp, stroke)
        x += s + 2.5f * dp
        fill.color = Drawn.surface(tp, Surface.MOD_PLAIN)
        tmp.set(x, y0, x + s, y0 + s); c.drawRoundRect(tmp, 2.5f * dp, 2.5f * dp, fill)
        c.drawRoundRect(tmp, 2.5f * dp, 2.5f * dp, stroke)
        x += s + 2.5f * dp
        fill.color = Drawn.surface(tp, Surface.SOLID)
        c.drawCircle(x + s / 2, r.centerY(), s / 2, fill)
        x += s + 7 * dp
        text.textAlign = Paint.Align.LEFT
        text.color = if (pressed) Drawn.ink(tp, Surface.PRESSED, Ink.TEXT) else Drawn.ink(tp, Surface.CARD, Ink.TEXT)
        text.textSize = 13 * dp
        text.typeface = typeface(if (chip.active) 700 else 550)
        baseline(c, ellipsize(chip.name, r.right - x - 8 * dp), x, r.centerY())
    }

    /** Tiles whose state is "on" get the accent (a mode is on, one-hand is on). */
    private fun isActive(t: Quick.Tile, mode: Mode): Boolean = when (t.action) {
        QuickAction.MODE -> mode != Mode.NONE
        QuickAction.ONE_HAND -> t.state != "Apagado"
        else -> false
    }

    private fun iconFor(t: Quick.Tile): com.resyst.vk.ime.Icon = when (t.action) {
        QuickAction.DAY_NIGHT -> if (t.state.endsWith("Oscuro")) Icon.SUN else Icon.MOON
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
