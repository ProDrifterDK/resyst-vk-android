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

    /** [fg] drawn at [alpha] over an opaque [bg]: the colour the eye sees. */
    fun over(fg: Int, alpha: Float, bg: Int): Int = mix(bg, fg, alpha)
}

data class Theme(
    val id: String,
    val dark: Boolean,
    val bg: Int, val bg2: Int,
    val key: Int, val keyTop: Int, val keyHi: Int, val keyMod: Int, val keyNum: Int,
    val edge: Int, val edgeHi: Int,
    val accent: Int,
    val text: Int, val textMod: Int, val muted: Int,
    val ok: Int, val bad: Int,
)

/** Palettes ported 1:1 from the desktop Resyst VK theme engine, plus the r14 set ([MORE]). */
object Themes {
    private fun h(s: String) = ColorMath.parseHex(s)!!

    private fun t(
        id: String, dark: Boolean,
        bg: String, bg2: String, key: String, keyTop: String, keyHi: String, keyMod: String, keyNum: String,
        edge: String, edgeHi: String, accent: String, text: String, textMod: String, muted: String,
        ok: String, bad: String,
    ) = Theme(id, dark, h(bg), h(bg2), h(key), h(keyTop), h(keyHi), h(keyMod), h(keyNum),
        h(edge), h(edgeHi), h(accent), h(text), h(textMod), h(muted), h(ok), h(bad))

    /** The eight classic palettes (r1-r8), unchanged: a 0.13.0 tema keeps its colours byte for byte. */
    val ALL: List<Theme> = listOf(
        t("lab", true,
            "#08080f", "#0b0b14", "#15151f", "#1a1a26", "#21212f", "#0f0f18", "#171724",
            "#2c2c3c", "#46465e", "#c9a84c", "#e9e4d6", "#b8b2a2", "#8f8a7a", "#5cbf6a", "#d0605a"),
        t("slate", true,
            "#0d1015", "#12161d", "#1a2029", "#202733", "#28303d", "#151a22", "#1d242e",
            "#2e3745", "#4a5669", "#7fb0ff", "#e3e8f0", "#b3bccb", "#8a94a6", "#5cc58a", "#e0706a"),
        t("arcade", true,
            "#07060e", "#0c0a18", "#14112a", "#1a1636", "#231d46", "#0f0c20", "#17132f",
            "#2c2650", "#4b4185", "#3ee0cf", "#ece8ff", "#bdb6e0", "#8f88b5", "#4fd88a", "#ff5c7a"),
        t("paper", false,
            "#efe9dc", "#e7e0d0", "#faf6ee", "#fffdf8", "#f1ebdf", "#e3dcc9", "#f3eee1",
            "#c9c0ab", "#a89e85", "#8a6d1f", "#3a352a", "#5d5748", "#6e6754", "#3e7d4c", "#a8433d"),
        t("hc", true,
            "#000000", "#0a0a0a", "#000000", "#000000", "#262626", "#000000", "#0d0d0d",
            "#bdbdbd", "#ffffff", "#ffd60a", "#ffffff", "#ffffff", "#d6d6d6", "#3ee07a", "#ff6b6b"),
        // r8: warm daylight paper — sepia keys, umber ink, terracotta accent; easy on the eyes outdoors
        t("sepia", false,
            "#e8dcc4", "#ded0b4", "#f6eedc", "#fbf5e8", "#ece0c8", "#dccfb3", "#efe5d0",
            "#c2b08c", "#a08c64", "#9a4a22", "#3b2a18", "#5a4630", "#6e5a40", "#4a7a3a", "#a83a2a"),
        // r8: AMOLED — true #000 everywhere a pixel can switch off, keys barely lifted, mint accent
        t("amoled", true,
            "#000000", "#000000", "#0b0b0b", "#101010", "#1c1c1c", "#000000", "#070707",
            "#1a1a1a", "#3a3a3a", "#5ee0a8", "#ededed", "#bdbdbd", "#8c8c8c", "#5ee0a8", "#ff6b6b"),
        // r8: e-ink — paper white, pure black ink and outlines, no gradients' worth of grey
        t("eink", false,
            "#ffffff", "#f2f2f2", "#ffffff", "#ffffff", "#e6e6e6", "#f0f0f0", "#f7f7f7",
            "#000000", "#000000", "#000000", "#000000", "#000000", "#3a3a3a", "#1d6b2f", "#b00020"),
    )

    /**
     * r14: four day/night pairs and the wallpaper's own colours. Each dark one has its light twin; the names say a
     * place or a material, not a colour code. "you" / "youlight" are rebuilt from Android 12+'s system palette at
     * runtime ([dynamic]); these entries are their stand-ins when there is none (older Android, previews, tests).
     */
    val MORE: List<Theme> = listOf(
        t("forest", true,
            "#0a110d", "#0e1712", "#16221b", "#1b2a21", "#233529", "#101a14", "#18261e",
            "#26382d", "#3d5747", "#7fd18b", "#e3efe6", "#b4c7b9", "#86a08f", "#6fd38a", "#e37b6e"),
        t("sage", false,
            "#e6ece4", "#dde5da", "#f6f9f4", "#fcfefb", "#e9efe6", "#d9e2d5", "#eef3ec",
            "#b9c7b6", "#94a891", "#2f6b3e", "#1f2a22", "#3f5244", "#56695a", "#2f7a45", "#a8433d"),
        t("ocean", true,
            "#060b16", "#0a1120", "#111b30", "#16223a", "#1e2c48", "#0c1527", "#142038",
            "#22324f", "#3a5078", "#5cc8ff", "#e4ecfa", "#b2c0da", "#8696b4", "#5cd39a", "#ff7a7a"),
        t("mist", false,
            "#e4e9f0", "#dae1ea", "#f7f9fc", "#ffffff", "#e8edf4", "#d6dee8", "#eef2f7",
            "#b8c3d2", "#93a2b8", "#1f5fae", "#1b2433", "#3c4a60", "#55637a", "#2a7a52", "#b0383a"),
        t("plum", true,
            "#0e0812", "#140c1a", "#1f1428", "#261930", "#30203d", "#170f1f", "#22172c",
            "#352543", "#553c6b", "#e08ad8", "#f0e6f4", "#c8b6d0", "#9c88a8", "#6fd38a", "#ff7a8a"),
        t("lavender", false,
            "#ebe6f2", "#e2dcec", "#faf8fd", "#ffffff", "#eee9f5", "#ddd5e9", "#f2eef8",
            "#c3b8d4", "#9f91b6", "#6a3d9a", "#251d30", "#463a57", "#5e526f", "#2f7a45", "#a8433d"),
        t("graphite", true,
            "#121314", "#17181a", "#2a2b2e", "#303135", "#3a3b3f", "#202124", "#2d2e31",
            "#1b1c1e", "#4a4b50", "#8ab4f8", "#e8eaed", "#bdc1c6", "#9aa0a6", "#81c995", "#f28b82"),
        t("cloud", false,
            "#e8eaed", "#dfe1e5", "#ffffff", "#ffffff", "#f1f3f4", "#d2d5da", "#f6f7f8",
            "#c4c7cc", "#9aa0a6", "#1a5fd0", "#202124", "#3c4043", "#5f6368", "#188038", "#c5221f"),
        t("you", true,
            "#111416", "#161a1d", "#22282c", "#283035", "#313a40", "#1a1f23", "#252c31",
            "#1c2226", "#46525a", "#7fd0d6", "#e3eaee", "#b9c5cc", "#93a1a9", "#7fd18b", "#f28b82"),
        t("youlight", false,
            "#e9eef1", "#e0e7eb", "#fbfdfe", "#ffffff", "#eef3f6", "#d5dfe5", "#f2f6f8",
            "#c1ccd3", "#9aa8b1", "#00686e", "#161d21", "#37464e", "#4e5e67", "#2f7a45", "#b3261e"),
    )

    /** Every palette a tema can hold, classic first. */
    val CATALOG: List<Theme> = ALL + MORE

    /** The palettes rebuilt from the system's colours when Android has them (12+). */
    val DYNAMIC = setOf("you", "youlight")

    fun byId(id: String?): Theme = CATALOG.firstOrNull { it.id == id } ?: ALL[0]

    fun known(id: String?): Boolean = CATALOG.any { it.id == id }

    /**
     * Default light ⇄ dark partner for the day/night toggle (r8), used the first time a profile
     * flips; afterwards the profile remembers where it came from ([DayNight]).
     */
    private val TWIN = mapOf(
        "lab" to "paper", "slate" to "sepia", "arcade" to "paper", "amoled" to "eink", "hc" to "eink",
        "paper" to "lab", "sepia" to "slate", "eink" to "amoled",
        "forest" to "sage", "sage" to "forest", "ocean" to "mist", "mist" to "ocean", "plum" to "lavender",
        "lavender" to "plum", "graphite" to "cloud", "cloud" to "graphite", "you" to "youlight", "youlight" to "you",
    )

    fun twin(id: String): String = TWIN[byId(id).id]?.takeIf { t -> byId(t).dark != byId(id).dark }
        ?: if (byId(id).dark) "paper" else "lab"

    /** Curated accents (contrast-corrected against the active theme's key color): colour and id; the name is `accent_<id>` (Names.accent). */
    val ACCENTS: List<Pair<Int, String>> = listOf(
        "#c9a84c" to "amber", "#e2735a" to "coral", "#e06c9f" to "pink", "#9b7bea" to "violet",
        "#5b9cf0" to "blue", "#3cc8d8" to "cyan", "#4fc58f" to "mint", "#a8c94c" to "lime", "#e8e2d0" to "ivory",
    ).map { h(it.first) to it.second }

    /**
     * r14: "Más colores" — 24 hues around the wheel at a saturation and lightness that read on dark and light keys
     * alike; the gate ([Legibility]) still corrects each one for the palette it lands on.
     */
    val MORE_ACCENTS: List<Int> = (0 until 24).map { i -> hsl(i * 15f, 0.72f, 0.56f) }

    /** HSL → packed RGB (h 0-360, s and l 0-1). */
    fun hsl(h: Float, s: Float, l: Float): Int {
        val c = (1 - kotlin.math.abs(2 * l - 1)) * s
        val x = c * (1 - kotlin.math.abs((h / 60f) % 2 - 1))
        val m = l - c / 2
        val (r, g, b) = when {
            h < 60 -> Triple(c, x, 0f); h < 120 -> Triple(x, c, 0f); h < 180 -> Triple(0f, c, x)
            h < 240 -> Triple(0f, x, c); h < 300 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        return ColorMath.rgb(((r + m) * 255).roundToInt(), ((g + m) * 255).roundToInt(), ((b + m) * 255).roundToInt())
    }

    /**
     * r14 (LG3): "Tu fondo" from Android 12+'s Material You tonal palettes. The roles follow Material 3's tone
     * ladder (dark: surfaces at tones 6-24, text at 95; light: surfaces at 92-100, text at 10); whatever the
     * wallpaper gives, the result goes through [Legibility.gate] like every other palette.
     */
    fun dynamic(id: String, s: SystemColors): Theme {
        val stand = byId(id)
        return if (stand.dark) stand.copy(
            bg = s.neutral1(900), bg2 = ColorMath.mix(s.neutral1(900), s.neutral1(800), 0.3f),
            key = s.neutral2(800), keyTop = ColorMath.mix(s.neutral2(800), s.neutral2(700), 0.35f), keyHi = s.neutral2(700),
            keyMod = ColorMath.mix(s.neutral1(900), s.neutral1(800), 0.6f), keyNum = ColorMath.mix(s.neutral2(800), s.accent1(800), 0.35f),
            edge = s.neutral1(900), edgeHi = s.neutral2(600), accent = s.accent1(200),
            text = s.neutral1(50), textMod = s.neutral2(200), muted = s.neutral2(300),
        ) else stand.copy(
            bg = s.neutral1(100), bg2 = ColorMath.mix(s.neutral1(100), s.neutral1(200), 0.3f),
            key = s.neutral1(10), keyTop = s.neutral1(10), keyHi = s.neutral1(50),
            keyMod = s.neutral2(200), keyNum = ColorMath.mix(s.neutral1(10), s.accent1(100), 0.4f),
            edge = s.neutral2(200), edgeHi = s.neutral2(400), accent = s.accent1(600),
            text = s.neutral1(900), textMod = s.neutral2(700), muted = s.neutral2(600),
        )
    }

    /** The palette [id] as drawn: the system's colours for "you" when there are any, else the fixed entry. */
    fun resolve(id: String?, system: SystemColors?): Theme {
        val t = byId(id)
        return if (system != null && t.id in DYNAMIC) dynamic(t.id, system) else t
    }
}

/**
 * r14 (LG3): Android 12+'s Material You tonal palettes, read by the IME from `android.R.color.system_*` (the shades
 * 0, 10, 50, 100, 200 … 900, 1000). Pure data so the palette code stays testable on the JVM.
 */
class SystemColors(private val a1: IntArray, private val n1: IntArray, private val n2: IntArray) {
    init { require(a1.size == SHADES.size && n1.size == SHADES.size && n2.size == SHADES.size) }

    private fun at(arr: IntArray, shade: Int): Int = arr[SHADES.indexOf(shade).also { require(it >= 0) { "shade $shade" } }] or (0xFF shl 24)
    fun accent1(shade: Int) = at(a1, shade)
    fun neutral1(shade: Int) = at(n1, shade)
    fun neutral2(shade: Int) = at(n2, shade)

    override fun equals(other: Any?) = other is SystemColors && a1.contentEquals(other.a1) && n1.contentEquals(other.n1) && n2.contentEquals(other.n2)
    override fun hashCode() = (a1.contentHashCode() * 31 + n1.contentHashCode()) * 31 + n2.contentHashCode()

    companion object {
        val SHADES = intArrayOf(0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000).toList()

        /** Every shade one colour: the worst case a broken ROM could hand over (LG3 test). */
        fun uniform(c: Int) = IntArray(SHADES.size) { c }.let { SystemColors(it, it.copyOf(), it.copyOf()) }

        /** A teal wallpaper's palette as a Pixel builds it (JVM tests, previews). */
        fun sample(): SystemColors {
            fun ladder(vararg hex: String) = hex.map { ColorMath.parseHex(it)!! }.toIntArray()
            return SystemColors(
                ladder("#ffffff", "#f1fffe", "#c9f8fb", "#acebee", "#90cfd2", "#74b3b6", "#589899", "#3c7e80", "#1e6467", "#004f52", "#00373a", "#002022", "#000000"),
                ladder("#ffffff", "#f4fbfb", "#eff2f2", "#e0e3e3", "#c4c7c7", "#a9acac", "#8e9192", "#747878", "#5b5f5f", "#444748", "#2d3131", "#191c1d", "#000000"),
                ladder("#ffffff", "#f1fbfb", "#e8f3f3", "#dae4e4", "#bec8c8", "#a3adad", "#889392", "#6f7979", "#566060", "#3f4948", "#283232", "#141d1d", "#000000"),
            )
        }
    }
}

/**
 * One-tap "modo día / noche" (r8): flips the profile's theme between a dark and a light one.
 * The theme it left is remembered in [KbSettings.altTheme], so the next flip goes back exactly
 * (Pizarra → Sepia → Pizarra), whatever theme the user picked by hand meanwhile.
 */
object DayNight {
    fun toggle(s: KbSettings): KbSettings {
        val from = Themes.byId(s.theme)
        val remembered = s.altTheme?.let { a -> Themes.CATALOG.firstOrNull { it.id == a } }
        val to = remembered?.takeIf { it.dark != from.dark } ?: Themes.byId(Themes.twin(from.id))
        return s.copy(theme = to.id, altTheme = from.id)
    }

    /** r10: the same flip on a tema's [Look]. */
    fun toggle(l: Look): Look = Look.of(toggle(l.applyTo(KbSettings())))

    /**
     * r14 (FS2): the look as it should be drawn while following the system: its own palette when the brightness
     * matches [systemDark], else its day/night partner (the remembered one, else the twin).
     */
    fun resolve(l: Look, systemDark: Boolean): Look = if (Themes.byId(l.theme).dark == systemDark) l else toggle(l)
}

/** r14 (LG2): the accent's hue mixed into the keyboard's surfaces. */
enum class Tint(val amount: Float) {
    NONE(0f), SOFT(0.12f), STRONG(0.30f);

    fun applyTo(t: Theme, accent: Int): Theme {
        if (amount == 0f) return t
        fun m(c: Int, k: Float = 1f) = ColorMath.mix(c, accent, amount * k)
        return t.copy(
            bg = m(t.bg, 0.7f), bg2 = m(t.bg2, 0.8f), key = m(t.key), keyTop = m(t.keyTop), keyHi = m(t.keyHi, 1.2f),
            keyMod = m(t.keyMod, 0.9f), keyNum = m(t.keyNum, 1.1f), edge = m(t.edge), edgeHi = m(t.edgeHi),
        )
    }
}

/**
 * r14 (LG1, fix LG6-LG8): the legibility gate. Every pair the keyboard, its panels and Settings draw is declared once in
 * [Drawn.PAIRS] (built from the same surface functions the painters use); the gate corrects the palette until every
 * declared pair passes, once per palette change, so no combination a person can build (palette × accent × tint × cap ×
 * high contrast × system colours) draws text under WCAG AA — legends at AAA. Text moves; surfaces keep the hue the user
 * chose (only pushed toward their palette's side when even pure white or black could not reach AAA on them).
 */
object Legibility {
    const val LEGEND = Drawn.LEGEND
    const val TEXT = Drawn.TEXT
    const val OUTLINE = Drawn.ICON

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()

    /** Fixed-point rounds before the last resort (every swept palette settles in a few). */
    private const val ROUNDS = 8

    private fun ratio(la: Double, lb: Double) = (max(la, lb) + 0.05) / (min(la, lb) + 0.05)

    /**
     * [fg] moved toward white or black until it reaches, on every surface in [bgs], the matching ratio in [mins] —
     * toward whichever pole can satisfy them all, preferring the one that keeps its own side of the surfaces — in 2 %
     * steps; pure white or black when nothing less does.
     */
    fun ensureAll(fg: Int, bgs: IntArray, mins: DoubleArray): Int {
        require(bgs.size == mins.size)
        if (bgs.isEmpty()) return fg
        val lum = DoubleArray(bgs.size) { ColorMath.luminance(bgs[it]) }
        fun passes(c: Int): Boolean {
            val l = ColorMath.luminance(c)
            for (i in lum.indices) if (ratio(l, lum[i]) < mins[i]) return false
            return true
        }
        if (passes(fg)) return fg
        var whiteWorst = Double.MAX_VALUE
        var blackWorst = Double.MAX_VALUE
        for (i in lum.indices) {
            whiteWorst = min(whiteWorst, ratio(1.0, lum[i]) / mins[i])
            blackWorst = min(blackWorst, ratio(0.0, lum[i]) / mins[i])
        }
        val lightSurfaces = lum.distinct().average() >= 0.18
        val target = when {
            whiteWorst >= 1.0 && blackWorst >= 1.0 -> if (lightSurfaces) BLACK else WHITE
            whiteWorst >= 1.0 -> WHITE
            blackWorst >= 1.0 -> BLACK
            else -> if (whiteWorst >= blackWorst) WHITE else BLACK
        }
        var t = 0.02f
        while (t <= 1.0001f) {
            val c = ColorMath.mix(fg, target, t)
            if (passes(c)) return c
            t += 0.02f
        }
        return target
    }

    /** [ensureAll] with one [ratio] on every surface (the classic form). */
    fun ensure(fg: Int, ratio: Double, vararg bgs: Int): Int = ensureAll(fg, bgs, DoubleArray(bgs.size) { ratio })

    private fun best(vararg bgs: Int): Int =
        if (bgs.minOf { ColorMath.contrast(WHITE, it) } >= bgs.minOf { ColorMath.contrast(BLACK, it) }) WHITE else BLACK

    /** Chip fill: the accent at this alpha over the bar. */
    fun chipAlpha(dark: Boolean) = if (dark) 0.13f else 0.12f

    /**
     * A surface where its palette's text side (white on a dark palette, black on a light one) would stay under
     * [SURFACE] — a mid-grey wallpaper palette, an extreme tint — is pushed toward that palette's side until it would
     * not (LG8: every surface on one side, so the side's pure colour always passes and no ink is stranded between a
     * dark and a light surface). The classic and r14 palettes never need it (their surfaces sit far from mid-grey).
     */
    private fun clampSurface(c: Int, dark: Boolean): Int {
        val pole = if (dark) WHITE else BLACK
        if (ColorMath.contrast(pole, c) >= SURFACE) return c
        val target = if (dark) BLACK else WHITE
        var t = 0.04f
        while (t <= 1.0001f) {
            val m = ColorMath.mix(c, target, t)
            if (ColorMath.contrast(pole, m) >= SURFACE) return m
            t += 0.04f
        }
        return target
    }

    private const val SURFACE = 8.0

    /** The inks the gate moves (the others are derived: the accent's ink, the ink on `bad`, on their own fills). */
    private val MOVED = listOf(Ink.TEXT, Ink.TEXT_MOD, Ink.MUTED, Ink.OK, Ink.BAD, Ink.ACCENT)

    private fun role(t: Theme, acc: Int, ink: Ink): Int = when (ink) {
        Ink.TEXT -> t.text
        Ink.TEXT_MOD -> t.textMod
        Ink.MUTED -> t.muted
        Ink.OK -> t.ok
        Ink.BAD -> t.bad
        Ink.ACCENT -> acc
        else -> error("$ink is derived")
    }

    private fun withRole(t: Theme, ink: Ink, c: Int): Theme = when (ink) {
        Ink.TEXT -> t.copy(text = c)
        Ink.TEXT_MOD -> t.copy(textMod = c)
        Ink.MUTED -> t.copy(muted = c)
        Ink.OK -> t.copy(ok = c)
        Ink.BAD -> t.copy(bad = c)
        else -> t
    }

    /** [fg] (role [ink]) moved until it passes on every surface [checks] draws it on. */
    private fun fix(ink: Ink, fg: Int, checks: List<Drawn.Check>): Int {
        val mine = checks.filter { it.ink == ink }
        if (mine.all { it.ok }) return fg
        return ensureAll(fg, IntArray(mine.size) { mine[it].bg }, DoubleArray(mine.size) { mine[it].min })
    }

    /**
     * The corrected theme and accent: surfaces clamped to their side, high contrast applied, then a fixed point over
     * every pair [decls] declares (default: all of [Drawn.PAIRS]; tests pass fewer to prove each pair is load-bearing).
     */
    fun gate(t0: Theme, accent0: Int, highContrast: Boolean, decls: List<Drawn.Decl> = Drawn.PAIRS): Pair<Theme, Int> {
        val d = t0.dark
        var t = t0.copy(
            bg = clampSurface(t0.bg, d), bg2 = clampSurface(t0.bg2, d), key = clampSurface(t0.key, d), keyTop = clampSurface(t0.keyTop, d),
            keyHi = clampSurface(t0.keyHi, d), keyMod = clampSurface(t0.keyMod, d), keyNum = clampSurface(t0.keyNum, d),
        )
        if (highContrast) {
            val ink = best(t.key, t.keyTop, t.keyHi, t.keyNum, t.keyMod, t.bg)
            t = t.copy(text = ink, textMod = ink)
            val edge = ensure(t.edgeHi, OUTLINE, t.bg, t.key, t.keyMod, t.keyNum)
            t = t.copy(edge = edge, edgeHi = edge, muted = ensure(t.muted, LEGEND, t.key, t.keyNum, t.bg))
        }
        var acc = accent0
        repeat(ROUNDS) {
            val checks = Drawn.checks(Palette.build(t, acc, highContrast), decls = decls)
            if (checks.all { it.ok }) return t to acc
            for (ink in MOVED) {
                val next = fix(ink, role(t, acc, ink), checks)
                if (ink == Ink.ACCENT) acc = next else t = withRole(t, ink, next)
            }
        }
        // last resort (LG8): with every surface on its palette's side, the side's pure colour passes everywhere
        val pole = if (d) WHITE else BLACK
        val left = Drawn.checks(Palette.build(t, acc, highContrast), decls = decls).filter { !it.ok }.map { it.ink }.toSet()
        for (ink in left) if (ink in MOVED) {
            if (ink == Ink.ACCENT) acc = pole else t = withRole(t, ink, pole)
        }
        return t to acc
    }

    /** The ink on an accent fill (Enter, the pressed chip, a selected variant): ≥ 4.5:1, the theme's dark ink if it can. */
    fun ink(t: Theme, accent: Int): Int {
        val darkInk = if (t.dark) t.bg else 0xFF141210.toInt()
        return when {
            ColorMath.contrast(darkInk, accent) >= TEXT && ColorMath.contrast(darkInk, accent) >= ColorMath.contrast(WHITE, accent) -> darkInk
            ColorMath.contrast(WHITE, accent) >= TEXT -> WHITE
            else -> best(accent)
        }
    }

    /**
     * LG7: the KEYCAP accent face's lift toward white (12 %, the keycap's light from above) shrinks until its [ink] keeps
     * 4.5:1 — a dark accent with white ink loses its lift rather than its legibility.
     */
    fun lift(accent: Int, ink: Int): Int {
        for (k in floatArrayOf(0.12f, 0.08f, 0.04f)) {
            val c = ColorMath.mix(accent, WHITE, k)
            if (ColorMath.contrast(ink, c) >= TEXT) return c
        }
        return accent
    }

    /** LG7: an accent fill pressed moves 15 % away from its [ink] (toward black under white ink): the press adds contrast. */
    fun away(accent: Int, ink: Int): Int =
        ColorMath.mix(accent, if (ColorMath.luminance(ink) >= ColorMath.luminance(accent)) BLACK else WHITE, 0.15f)

    data class Check(val name: String, val fg: Int, val bg: Int, val min: Double) {
        val ratio: Double get() = ColorMath.contrast(fg, bg)
        val ok: Boolean get() = ratio >= min - 1e-9
    }

    /** Every text/icon-on-surface pair [cap] draws with palette [p]: [Drawn.PAIRS] resolved (what LG1 checks). */
    fun checks(p: Palette, cap: KeyCap): List<Check> =
        Drawn.checks(p, listOf(cap)).map { Check(it.name, it.fg, it.bg, it.min) }

    /** The checks that fail, as "name ratio < min" (empty = legible). */
    fun failures(p: Palette, cap: KeyCap): List<String> =
        checks(p, cap).filter { !it.ok }.map { "${it.name} %.2f < %.1f".format(it.ratio, it.min) }
}

/**
 * Effective colors: theme (tinted, then gated) + accent with guaranteed contrast everywhere it is drawn (r14 LG1:
 * keys, the accent row, the bar, chips, popups). [chipFill] / [chipPressedFill] are the bar chips' surfaces; a
 * pressed chip inverts (accent fill, [chipPressedInk] text). [highContrast] adds [outline] to every key. [badInk] is
 * the ink on a `bad` fill; [solidTop] / [solidPressed] are an accent fill's KEYCAP lift and its press (LG7). Painters
 * read surfaces and inks through [Drawn], never these fields directly.
 */
data class Palette(
    val theme: Theme,
    val accent: Int,
    val accentInk: Int,
    val accentSoft: Int,
    val accentGlow: Int,
    val ghost: Int,
    val chipFill: Int = accentSoft,
    val chipPressedFill: Int = accent,
    val chipPressedInk: Int = accentInk,
    val outline: Int = theme.edgeHi,
    val highContrast: Boolean = false,
    val badInk: Int = accentInk,
    val solidTop: Int = accent,
    val solidPressed: Int = accent,
) {
    companion object {
        private class Key(val themeId: String?, val accent: Int?, val tint: Tint, val hc: Boolean, val system: SystemColors?) {
            override fun equals(other: Any?) = other is Key && themeId == other.themeId && accent == other.accent && tint == other.tint &&
                hc == other.hc && system == other.system
            override fun hashCode() = ((((themeId?.hashCode() ?: 0) * 31 + (accent ?: 0)) * 31 + tint.ordinal) * 31 + (if (hc) 1 else 0)) * 31 +
                (system?.hashCode() ?: 0)
        }

        /** LG11: a palette is a pure function of its inputs; Settings asks for dozens per render (bounded, LRU). */
        private const val CACHE = 96
        private val cache = object : LinkedHashMap<Key, Palette>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Palette>?) = size > CACHE
        }

        fun of(themeId: String?, accent: Int?, tint: Tint = Tint.NONE, highContrast: Boolean = false, system: SystemColors? = null): Palette {
            val k = Key(themeId, accent, tint, highContrast, system)
            synchronized(cache) { cache[k]?.let { return it } }
            val base = Themes.resolve(themeId, system)
            val wanted = accent ?: base.accent
            val hc = highContrast || base.id == "hc"
            val (t, acc) = Legibility.gate(tint.applyTo(base, wanted), wanted, hc)
            val p = build(t, acc, hc)
            synchronized(cache) { cache[k] = p }
            return p
        }

        /** The palette a gated theme and accent make: every derived colour ([Drawn]'s inputs). */
        fun build(t: Theme, acc: Int, hc: Boolean): Palette {
            val ink = Legibility.ink(t, acc)
            return Palette(
                theme = t,
                accent = acc,
                accentInk = ink,
                accentSoft = ColorMath.withAlpha(acc, Legibility.chipAlpha(t.dark)),
                accentGlow = ColorMath.withAlpha(acc, 0.45f),
                ghost = ColorMath.withAlpha(t.text, 0.5f),
                chipFill = ColorMath.over(acc, Legibility.chipAlpha(t.dark), t.bg),
                chipPressedFill = acc,
                chipPressedInk = ink,
                outline = t.edgeHi,
                highContrast = hc,
                badInk = Legibility.ink(t, t.bad),
                solidTop = Legibility.lift(acc, ink),
                solidPressed = Legibility.away(acc, ink),
            )
        }

        /**
         * r14 (motion): the palette [t] of the way from [a] to [b] — the colours of a tema switch or a day/night flip
         * cross-fading for a few frames. Both ends are gated; the frames between are sRGB mixes of legible pairs and
         * last ≤ 200 ms.
         */
        fun mix(a: Palette, b: Palette, t: Float): Palette {
            if (t <= 0f) return a
            if (t >= 1f) return b
            fun m(x: Int, y: Int) = ColorMath.mix(x, y, t)
            fun ma(x: Int, y: Int): Int {
                val al = ((x ushr 24) + (((y ushr 24) - (x ushr 24)) * t)).toInt().coerceIn(0, 255)
                return (al shl 24) or (m(x, y) and 0xFFFFFF)
            }
            val ta = a.theme
            val tb = b.theme
            return b.copy(
                theme = tb.copy(
                    bg = m(ta.bg, tb.bg), bg2 = m(ta.bg2, tb.bg2), key = m(ta.key, tb.key), keyTop = m(ta.keyTop, tb.keyTop),
                    keyHi = m(ta.keyHi, tb.keyHi), keyMod = m(ta.keyMod, tb.keyMod), keyNum = m(ta.keyNum, tb.keyNum),
                    edge = m(ta.edge, tb.edge), edgeHi = m(ta.edgeHi, tb.edgeHi), accent = m(ta.accent, tb.accent),
                    text = m(ta.text, tb.text), textMod = m(ta.textMod, tb.textMod), muted = m(ta.muted, tb.muted),
                    ok = m(ta.ok, tb.ok), bad = m(ta.bad, tb.bad),
                ),
                accent = m(a.accent, b.accent), accentInk = m(a.accentInk, b.accentInk), accentSoft = ma(a.accentSoft, b.accentSoft),
                accentGlow = ma(a.accentGlow, b.accentGlow), ghost = ma(a.ghost, b.ghost), chipFill = m(a.chipFill, b.chipFill),
                chipPressedFill = m(a.chipPressedFill, b.chipPressedFill), chipPressedInk = m(a.chipPressedInk, b.chipPressedInk),
                outline = m(a.outline, b.outline), badInk = m(a.badInk, b.badInk), solidTop = m(a.solidTop, b.solidTop),
                solidPressed = m(a.solidPressed, b.solidPressed),
            )
        }

        /** The palette [s] asks for (r14: tint; [systemDark] resolves "follow the system" upstream). */
        fun of(s: KbSettings, highContrast: Boolean = false, system: SystemColors? = null): Palette =
            of(s.theme, s.accent, s.tint, highContrast, system)
    }
}
