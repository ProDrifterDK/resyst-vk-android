package com.resyst.vk.core

import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

/**
 * r14 (LG4): legend sizes. The 0.13.0 size, × the user's size ([KbSettings.legendScale]) × the system font scale
 * (from 1 to 2: below 1 the 0.13.0 size stays, keyboard legends are already compact), capped so a legend never
 * exceeds [MAX_HEIGHT] of its key's height nor its width minus 8 dp.
 */
object Legend {
    const val SCALE_MIN = 0.85f
    const val SCALE_MAX = 1.3f
    const val MAX_HEIGHT = 0.62f

    fun fontFactor(fontScale: Float): Float = fontScale.coerceIn(1f, 2f)

    private fun cap(px: Float, keyW: Float, keyH: Float, dp: Float): Float =
        min(px, min(keyH * MAX_HEIGHT, max(6 * dp, keyW - 8 * dp)))

    /** A letter / digit / symbol legend; [small] = the accent and number rows (0.13.0: 0.36 of the row, ≤ 19 dp). */
    fun letter(rowH: Float, keyW: Float, keyH: Float, small: Boolean, scale: Float, fontScale: Float, dp: Float): Float {
        val base = min(rowH * (if (small) 0.36f else 0.42f), (if (small) 19 else 23) * dp)
        return cap(base * scale.coerceIn(SCALE_MIN, SCALE_MAX) * fontFactor(fontScale), keyW, rowH, dp).let { cap(it, keyW, keyH, dp) }
    }

    /** A word label ("?123", "ABC"): 14 dp at scale 1, shrunk until [measure] says it fits the key's width minus 8 dp. */
    fun label(rowH: Float, keyW: Float, text: String, scale: Float, fontScale: Float, dp: Float, measure: (String, Float) -> Float): Float {
        var px = min(14 * dp * scale.coerceIn(SCALE_MIN, SCALE_MAX) * fontFactor(fontScale), rowH * 0.4f)
        val room = keyW - 8 * dp
        while (px > 8 * dp && measure(text, px) > room) px -= 0.5f * dp
        return px
    }

    /** The small top-right hint (digits over q…p): 0.13.0's size, × size and font scale, ≤ a quarter of the row. */
    fun hint(rowH: Float, scale: Float, fontScale: Float, dp: Float): Float =
        min(min(rowH * 0.2f, 10 * dp) * scale.coerceIn(SCALE_MIN, SCALE_MAX) * fontFactor(fontScale), rowH * 0.25f)
}

/** r14 (HS1): how a key feels, matching the sound kinds (key, space, modifier, Enter) plus ⌫'s auto-repeat. */
enum class KeyFeel {
    LETTER, SPACE, MOD, ENTER, REPEAT;

    companion object {
        fun of(type: KeyType, style: KeyStyle): KeyFeel = when {
            type == KeyType.SPACE -> SPACE
            type == KeyType.ENTER -> ENTER
            style == KeyStyle.MOD || type == KeyType.SHIFT || type == KeyType.LAYER || type == KeyType.EMOJI || type == KeyType.BACKSPACE -> MOD
            else -> LETTER
        }
    }
}

/**
 * r14 (PS2): a style is a curated look — palette, shape, cap, font and legend treatment that belong together — set
 * on the active tema in one tap. Behaviour (language, suggestions, sound, vibration) and the tema's height are never
 * touched; «Deshacer» puts the previous look back. Names and lines live in the string resources (style_<id>).
 */
class Style(val id: String, val icon: String, private val f: (Look) -> Look) {
    fun on(l: Look): Look = f(l)
}

object Styles {
    val ALL: List<Style> = listOf(
        Style("resyst", "✦") {
            it.copy(theme = "lab", accent = null, altTheme = null, shape = KeyShape.SOFT, cap = KeyCap.RAISED, font = KeyFont.BRAND, density = Density.NORMAL,
                subLegends = true, tint = Tint.NONE, legendWeight = LegendWeight.REGULAR, legendScale = 1f, upperLegends = false, bubble = Bubble.CONNECTED, barStyle = BarStyle.TEXT)
        },
        Style("mecanico", "⌘") {
            it.copy(theme = "graphite", accent = ColorMath.parseHex("#e2735a"), altTheme = "cloud", shape = KeyShape.SOFT, cap = KeyCap.KEYCAP, font = KeyFont.BRAND,
                density = Density.NORMAL, subLegends = true, tint = Tint.NONE, legendWeight = LegendWeight.MEDIUM, legendScale = 0.95f, upperLegends = true,
                bubble = Bubble.CONNECTED, barStyle = BarStyle.TEXT)
        },
        Style("minimal", "○") {
            it.copy(theme = "cloud", accent = null, altTheme = "graphite", shape = KeyShape.ROUND, cap = KeyCap.BORDERLESS, font = KeyFont.BRAND, density = Density.NORMAL,
                subLegends = false, tint = Tint.NONE, legendWeight = LegendWeight.LIGHT, legendScale = 1.05f, upperLegends = false, bubble = Bubble.FLOATING, barStyle = BarStyle.TEXT)
        },
        Style("burbuja", "●") {
            it.copy(theme = "lavender", accent = null, altTheme = "plum", shape = KeyShape.PILL, cap = KeyCap.FLAT, font = KeyFont.ROUNDED, density = Density.NORMAL,
                subLegends = false, tint = Tint.SOFT, legendWeight = LegendWeight.MEDIUM, legendScale = 1f, upperLegends = false, bubble = Bubble.CONNECTED, barStyle = BarStyle.CHIPS)
        },
        Style("terminal", "›") {
            it.copy(theme = "amoled", accent = ColorMath.parseHex("#5ee0a8"), altTheme = "eink", shape = KeyShape.SQUARE, cap = KeyCap.OUTLINE, font = KeyFont.TECH,
                density = Density.TIGHT, subLegends = true, tint = Tint.NONE, legendWeight = LegendWeight.REGULAR, legendScale = 1f, upperLegends = false,
                bubble = Bubble.FLOATING, barStyle = BarStyle.TEXT)
        },
        Style("papel", "✎") {
            it.copy(theme = "paper", accent = null, altTheme = "lab", shape = KeyShape.ROUND, cap = KeyCap.FLAT, font = KeyFont.HUMAN, density = Density.AIRY,
                subLegends = false, tint = Tint.NONE, legendWeight = LegendWeight.REGULAR, legendScale = 1.05f, upperLegends = false, bubble = Bubble.CONNECTED, barStyle = BarStyle.TEXT)
        },
        Style("arcade", "◆") {
            it.copy(theme = "arcade", accent = null, altTheme = "paper", shape = KeyShape.CHAMFER, cap = KeyCap.FLAT, font = KeyFont.BRAND, density = Density.TIGHT,
                subLegends = true, tint = Tint.SOFT, legendWeight = LegendWeight.BOLD, legendScale = 0.95f, upperLegends = true, bubble = Bubble.CONNECTED, barStyle = BarStyle.CHIPS)
        },
        Style("legible", "◎") {
            it.copy(theme = "hc", accent = null, altTheme = "eink", shape = KeyShape.SOFT, cap = KeyCap.RAISED, font = KeyFont.LEGIBLE, density = Density.NORMAL,
                subLegends = false, tint = Tint.NONE, legendWeight = LegendWeight.BOLD, legendScale = 1.15f, upperLegends = false, bubble = Bubble.CONNECTED, barStyle = BarStyle.TEXT)
        },
    )

    fun byId(id: String?): Style? = ALL.firstOrNull { it.id == id }

    /** The store with [s] on the active tema, and the look it replaced (for «Deshacer»). */
    fun apply(st: ProfileStore, s: Style): Pair<ProfileStore, Look> =
        st.updateLook(st.activeTema.id) { s.on(it) } to st.activeTema.look

    fun undo(st: ProfileStore, previous: Look): ProfileStore = st.updateLook(st.activeTema.id) { previous }
}

/** r14 (PS3): create, rename and delete temas without ever leaving the store invalid. */
object TemaOps {
    const val MAX_TEMAS = 8
    const val NAME_MAX = 18

    fun cleanName(name: String): String = name.trim().replace(Regex("\\s+"), " ").take(NAME_MAX).trim()

    fun cleanIcon(icon: String): String = icon.trim().take(2).ifEmpty { "✦" }

    /** An id from the name: ASCII letters and digits, dashes between words, ≤ 20 characters ("tema" when nothing is left). */
    fun slug(name: String): String {
        val ascii = Normalizer.normalize(name.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return ascii.replace(Regex("[^a-z0-9]+"), "-").trim('-').take(20).trim('-').ifEmpty { "tema" }
    }

    private fun freeId(st: ProfileStore, name: String): String {
        val base = slug(name)
        if (st.byId(base) == null) return base
        var i = 2
        while (st.byId("$base-$i") != null) i++
        return "$base-$i"
    }

    /** A new tema named [name], a copy of [from] (the active tema's look), appended and put in use. */
    fun create(st: ProfileStore, name: String, icon: String, from: Look = st.activeTema.look): ProfileStore {
        if (st.temas.size >= MAX_TEMAS) return st
        val n = cleanName(name)
        if (n.isEmpty()) return st
        val id = freeId(st, n)
        return st.copy(temas = st.temas + Tema(id, n, cleanIcon(icon), from), active = id)
    }

    fun rename(st: ProfileStore, id: String, name: String, icon: String): ProfileStore {
        val n = cleanName(name)
        if (n.isEmpty() || st.byId(id) == null) return st
        return st.copy(temas = st.temas.map { if (it.id == id) it.copy(name = n, icon = cleanIcon(icon)) else it })
    }

    /** Removes [id]; never the last tema; deleting the active one puts the next one in use. */
    fun delete(st: ProfileStore, id: String): ProfileStore {
        val i = st.temas.indexOfFirst { it.id == id }
        if (i < 0 || st.temas.size <= 1) return st
        val rest = st.temas.filterIndexed { j, _ -> j != i }
        val active = if (st.active == id) rest[i.coerceAtMost(rest.size - 1)].id else st.active
        return st.copy(temas = rest, active = active)
    }
}
