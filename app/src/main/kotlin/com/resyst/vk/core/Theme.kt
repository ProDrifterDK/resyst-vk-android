package com.resyst.vk.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** sRGB / WCAG 2.x helpers on packed ARGB ints (0xAARRGGBB). */
object ColorMath {
    fun parseHex(s: String?): Int? {
        if (s == null || !Regex("^#[0-9a-fA-F]{6}$").matches(s)) return null
        return (0xFF000000.toInt()) or s.substring(1).toInt(16)
    }

    fun toHex(c: Int): String = "#%06x".format(c and 0xFFFFFF)

    fun r(c: Int) = (c shr 16) and 0xFF
    fun g(c: Int) = (c shr 8) and 0xFF
    fun b(c: Int) = c and 0xFF

    fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun withAlpha(c: Int, a: Float): Int = ((a.coerceIn(0f, 1f) * 255).roundToInt() shl 24) or (c and 0xFFFFFF)

    fun mix(a: Int, b: Int, t: Float): Int = rgb(
        (r(a) + (r(b) - r(a)) * t).roundToInt(),
        (g(a) + (g(b) - g(a)) * t).roundToInt(),
        (b(a) + (b(b) - b(a)) * t).roundToInt(),
    )

    fun luminance(c: Int): Double {
        fun ch(v: Int): Double {
            val x = v / 255.0
            return if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(r(c)) + 0.7152 * ch(g(c)) + 0.0722 * ch(b(c))
    }

    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** Move [fg] toward white (dark bg) or black (light bg) until it reaches [minRatio] over [bg]. */
    fun ensureContrast(fg: Int, bg: Int, minRatio: Double): Int {
        val target = if (luminance(bg) < 0.4) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        var c = fg
        var t = 0.05f
        while (contrast(c, bg) < minRatio && t <= 1.0001f) {
            c = mix(fg, target, t)
            t += 0.05f
        }
        return c
    }
}

data class Theme(
    val id: String,
    val label: String,
    val dark: Boolean,
    val bg: Int, val bg2: Int,
    val key: Int, val keyTop: Int, val keyHi: Int, val keyMod: Int, val keyNum: Int,
    val edge: Int, val edgeHi: Int,
    val accent: Int,
    val text: Int, val textMod: Int, val muted: Int,
    val ok: Int, val bad: Int,
)

/** Palettes ported 1:1 from the desktop Resyst VK theme engine. */
object Themes {
    private fun h(s: String) = ColorMath.parseHex(s)!!

    private fun t(
        id: String, label: String, dark: Boolean,
        bg: String, bg2: String, key: String, keyTop: String, keyHi: String, keyMod: String, keyNum: String,
        edge: String, edgeHi: String, accent: String, text: String, textMod: String, muted: String,
        ok: String, bad: String,
    ) = Theme(id, label, dark, h(bg), h(bg2), h(key), h(keyTop), h(keyHi), h(keyMod), h(keyNum),
        h(edge), h(edgeHi), h(accent), h(text), h(textMod), h(muted), h(ok), h(bad))

    val ALL: List<Theme> = listOf(
        t("lab", "Midnight Laboratory", true,
            "#08080f", "#0b0b14", "#15151f", "#1a1a26", "#21212f", "#0f0f18", "#171724",
            "#2c2c3c", "#46465e", "#c9a84c", "#e9e4d6", "#b8b2a2", "#8f8a7a", "#5cbf6a", "#d0605a"),
        t("slate", "Pizarra", true,
            "#0d1015", "#12161d", "#1a2029", "#202733", "#28303d", "#151a22", "#1d242e",
            "#2e3745", "#4a5669", "#7fb0ff", "#e3e8f0", "#b3bccb", "#8a94a6", "#5cc58a", "#e0706a"),
        t("arcade", "Noche de juego", true,
            "#07060e", "#0c0a18", "#14112a", "#1a1636", "#231d46", "#0f0c20", "#17132f",
            "#2c2650", "#4b4185", "#3ee0cf", "#ece8ff", "#bdb6e0", "#8f88b5", "#4fd88a", "#ff5c7a"),
        t("paper", "Papiro", false,
            "#efe9dc", "#e7e0d0", "#faf6ee", "#fffdf8", "#f1ebdf", "#e3dcc9", "#f3eee1",
            "#c9c0ab", "#a89e85", "#8a6d1f", "#3a352a", "#5d5748", "#6e6754", "#3e7d4c", "#a8433d"),
        t("hc", "Alto contraste", true,
            "#000000", "#0a0a0a", "#000000", "#000000", "#262626", "#000000", "#0d0d0d",
            "#bdbdbd", "#ffffff", "#ffd60a", "#ffffff", "#ffffff", "#d6d6d6", "#3ee07a", "#ff6b6b"),
        // r8: warm daylight paper — sepia keys, umber ink, terracotta accent; easy on the eyes outdoors
        t("sepia", "Sepia", false,
            "#e8dcc4", "#ded0b4", "#f6eedc", "#fbf5e8", "#ece0c8", "#dccfb3", "#efe5d0",
            "#c2b08c", "#a08c64", "#9a4a22", "#3b2a18", "#5a4630", "#6e5a40", "#4a7a3a", "#a83a2a"),
        // r8: AMOLED — true #000 everywhere a pixel can switch off, keys barely lifted, mint accent
        t("amoled", "AMOLED negro", true,
            "#000000", "#000000", "#0b0b0b", "#101010", "#1c1c1c", "#000000", "#070707",
            "#1a1a1a", "#3a3a3a", "#5ee0a8", "#ededed", "#bdbdbd", "#8c8c8c", "#5ee0a8", "#ff6b6b"),
        // r8: e-ink — paper white, pure black ink and outlines, no gradients' worth of grey
        t("eink", "Tinta electrónica", false,
            "#ffffff", "#f2f2f2", "#ffffff", "#ffffff", "#e6e6e6", "#f0f0f0", "#f7f7f7",
            "#000000", "#000000", "#000000", "#000000", "#000000", "#3a3a3a", "#1d6b2f", "#b00020"),
    )

    fun byId(id: String?): Theme = ALL.firstOrNull { it.id == id } ?: ALL[0]

    /**
     * Default light ⇄ dark partner for the day/night toggle (r8), used the first time a profile
     * flips; afterwards the profile remembers where it came from ([DayNight]).
     */
    private val TWIN = mapOf(
        "lab" to "paper", "slate" to "sepia", "arcade" to "paper", "amoled" to "eink", "hc" to "eink",
        "paper" to "lab", "sepia" to "slate", "eink" to "amoled",
    )

    fun twin(id: String): String = TWIN[byId(id).id]?.takeIf { t -> byId(t).dark != byId(id).dark }
        ?: if (byId(id).dark) "paper" else "lab"

    /** Curated accents (contrast-corrected against the active theme's key color). */
    val ACCENTS: List<Pair<Int, String>> = listOf(
        "#c9a84c" to "Ámbar Resyst", "#e2735a" to "Coral", "#e06c9f" to "Rosa", "#9b7bea" to "Violeta",
        "#5b9cf0" to "Azul", "#3cc8d8" to "Cian", "#4fc58f" to "Menta", "#a8c94c" to "Lima", "#e8e2d0" to "Marfil",
    ).map { h(it.first) to it.second }
}

/**
 * One-tap "modo día / noche" (r8): flips the profile's theme between a dark and a light one.
 * The theme it left is remembered in [KbSettings.altTheme], so the next flip goes back exactly
 * (Pizarra → Sepia → Pizarra), whatever theme the user picked by hand meanwhile.
 */
object DayNight {
    fun toggle(s: KbSettings): KbSettings {
        val from = Themes.byId(s.theme)
        val remembered = s.altTheme?.let { a -> Themes.ALL.firstOrNull { it.id == a } }
        val to = remembered?.takeIf { it.dark != from.dark } ?: Themes.byId(Themes.twin(from.id))
        return s.copy(theme = to.id, altTheme = from.id)
    }

    /** r10: the same flip on a tema's [Look]. */
    fun toggle(l: Look): Look = Look.of(toggle(l.applyTo(KbSettings())))

}

/** Effective colors: theme + optional custom accent with guaranteed ≥ 4.5:1 contrast over keys. */
data class Palette(
    val theme: Theme,
    val accent: Int,
    val accentInk: Int,
    val accentSoft: Int,
    val accentGlow: Int,
    val ghost: Int,
) {
    companion object {
        fun of(themeId: String?, accent: Int?): Palette {
            val t = Themes.byId(themeId)
            val acc = ColorMath.ensureContrast(accent ?: t.accent, t.key, 4.5)
            val darkInk = if (t.dark) t.bg else ColorMath.parseHex("#141210")!!
            val white = 0xFFFFFFFF.toInt()
            val ink = if (ColorMath.contrast(darkInk, acc) >= ColorMath.contrast(white, acc)) darkInk else white
            return Palette(
                theme = t,
                accent = acc,
                accentInk = ink,
                accentSoft = ColorMath.withAlpha(acc, if (t.dark) 0.13f else 0.12f),
                accentGlow = ColorMath.withAlpha(acc, 0.45f),
                ghost = ColorMath.withAlpha(t.text, 0.5f),
            )
        }
    }
}
