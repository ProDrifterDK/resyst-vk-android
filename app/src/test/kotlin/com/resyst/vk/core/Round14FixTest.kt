package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * r14 fix pass (docs/failure-modes.md LG6-LG11, PS7-PS10), written from the independent review of 6f8d83f: the gate
 * covers what is drawn. One table ([Drawn.PAIRS]) is what the painters and Settings draw from, what the gate corrects
 * and what this sweep checks; the source guard ties the UI files to it.
 */
class Round14FixTest {

    // ── the sweep's space: every palette × accent (the review's stress accents) × tint × contrast × system colours ──
    private val stress: List<Int?> = listOf<Int?>(null) + Themes.ACCENTS.map { it.first } +
        (0 until 36).flatMap { i -> listOf(0.25f, 0.5f, 0.8f).map { l -> Themes.hsl(i * 10f, 0.85f, l) } } +
        Themes.MORE_ACCENTS + listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF808080.toInt(), 0xFF777777.toInt())

    /** Android 12+'s colours: none (the stand-in), a real wallpaper's, and broken ROMs (every shade one colour, LG8). */
    private val systems: List<Pair<String, SystemColors?>> = listOf("none" to null, "sample" to SystemColors.sample()) +
        listOf("#777777", "#808080", "#000000", "#ffffff", "#444444", "#bbbbbb").map { "uniform$it" to SystemColors.uniform(ColorMath.parseHex(it)!!) }

    private fun h(s: String) = ColorMath.parseHex(s)!!

    /** A palette gated with only [decls] (Palette.of's own steps): proves a declared pair is load-bearing. */
    private fun gatedWith(decls: List<Drawn.Decl>, id: String, accent: Int?, tint: Tint = Tint.NONE, hc: Boolean = false, system: SystemColors? = null): Palette {
        val base = Themes.resolve(id, system)
        val wanted = accent ?: base.accent
        val high = hc || base.id == "hc"
        val (t, acc) = Legibility.gate(tint.applyTo(base, wanted), wanted, high, decls)
        return Palette.build(t, acc, high)
    }

    private fun ratio(p: Palette, s: Surface, ink: Ink, cap: KeyCap = KeyCap.FLAT, kind: KeyKind = KeyKind.NORMAL) =
        ColorMath.contrast(ink.of(p), Drawn.surface(p, s, cap, kind))

    @Test fun lgxEveryDrawnPairPassesOnEveryCombination() {
        var palettes = 0
        var configs = 0L
        var configFails = 0L
        var pairsChecked = 0L
        var gateNs = 0L
        val fails = ArrayList<String>()
        val worst = LinkedHashMap<Drawn.Decl, Pair<Double, String>>()
        for (theme in Themes.CATALOG) for ((sysName, sys) in (if (theme.id in Themes.DYNAMIC) systems else systems.take(1))) {
            for (acc in stress) for (tint in Tint.values()) for (hc in listOf(false, true)) {
                val t0 = System.nanoTime()
                val p = Palette.of(theme.id, acc, tint, hc, sys)
                gateNs += System.nanoTime() - t0
                palettes++
                val all = Drawn.checks(p)
                val failedCaps = HashSet<KeyCap?>()
                for (c in all) {
                    pairsChecked++
                    val r = c.ratio
                    val where = "${theme.id}/$sysName/${acc?.let(ColorMath::toHex) ?: "tema"}/$tint/hc=$hc" + (c.cap?.let { "/$it/${c.kind}" } ?: "")
                    if (r < c.min - 1e-9) {
                        failedCaps += c.cap
                        if (fails.size < 25) fails += "$where: ${c.name} %.3f < %.1f".format(r, c.min)
                    }
                    val w = worst[c.decl]
                    if (w == null || r < w.first) worst[c.decl] = r to where
                }
                // every cap × shape × bar style draws the non-key pairs and its cap's key pairs (Drawn.checks(p, cap, shape, bar))
                for (cap in KeyCap.values()) for (shape in KeyShape.values()) for (bar in BarStyle.values()) {
                    configs++
                    if (null in failedCaps || cap in failedCaps) configFails++
                    if (acc == null && tint == Tint.NONE && !hc) {
                        // the selector for one configuration is exactly the cap's subset of the table, whatever the shape / bar
                        val one = Drawn.checks(p, cap, shape, bar).map { listOf(it.decl, it.cap, it.kind, it.fg, it.bg) }
                        assertEquals("$cap $shape $bar", all.filter { it.cap == null || it.cap == cap }.map { listOf(it.decl, it.cap, it.kind, it.fg, it.bg) }, one)
                    }
                }
            }
        }
        report(palettes, configs, pairsChecked, gateNs, worst, fails)
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
        assertEquals(0L, configFails)
        assertTrue("$palettes palettes", palettes > 25_000)
        assertTrue("$configs configurations", configs > 1_000_000)
    }

    /** The table's worst ratio per pair class, for the round's report (app/build/reports/r14-contrast/derived-pairs.json). */
    private fun report(palettes: Int, configs: Long, pairs: Long, gateNs: Long, worst: Map<Drawn.Decl, Pair<Double, String>>, fails: List<String>) {
        val sb = StringBuilder()
        sb.append("{\n  \"palettes\": $palettes,\n  \"configurations\": $configs,\n  \"pairs_checked\": $pairs,\n")
        sb.append("  \"gate_us_per_palette\": ${java.util.Locale.ROOT.let { l -> "%.1f".format(l, gateNs / 1000.0 / palettes) }},\n  \"failures\": ${fails.size},\n  \"worst\": [\n")
        worst.entries.sortedBy { it.value.first / it.key.min }.forEachIndexed { i, (d, w) ->
            val kinds = if (d.surface.key) d.kinds.joinToString("/") else ""
            sb.append("    {\"ink\": \"${d.ink}\", \"surface\": \"${d.surface}\", \"kinds\": \"$kinds\", \"min\": ${d.min}, \"worst\": ${"%.3f".format(java.util.Locale.ROOT, w.first)}, \"at\": \"${w.second}\", \"where\": \"${d.where.replace("\"", "'")}\"}")
            sb.append(if (i < worst.size - 1) ",\n" else "\n")
        }
        sb.append("  ]\n}\n")
        File("build/reports/r14-contrast").mkdirs()
        File("build/reports/r14-contrast/derived-pairs.json").writeText(sb.toString())
        println("LGX sweep: $palettes palettes, $configs configurations, $pairs pairs, ${"%.1f".format(gateNs / 1000.0 / palettes)} µs gate/palette, ${fails.size} failures")
        for ((d, w) in worst.entries.sortedBy { it.value.first / it.key.min }.take(12)) println("  %.3f (≥ %.1f)  %s  [%s]".format(w.first, d.min, d, w.second))
    }

    @Test fun lgxTheReviewsCombinationsNowPass() {
        val paper = Palette.of("paper", null)
        // B1: the Settings hero caption, Papiro, no customisation (4.28:1); worst UI-reachable Sepia + #b73ee0 + Tinte suave (3.99:1)
        assertTrue(ratio(paper, Surface.CARD, Ink.MUTED) >= 4.5)
        assertTrue(ratio(Palette.of("sepia", h("#b73ee0"), Tint.SOFT), Surface.CARD, Ink.MUTED) >= 4.5)
        // B2: KEYCAP Enter ink on its lifted face, Tinta electrónica + Ámbar Resyst (4.21:1) / + Lima (4.12:1)
        for (a in listOf("#c9a84c", "#a8c94c")) {
            val p = Palette.of("eink", h(a))
            assertTrue(a, ratio(p, Surface.KEY_TOP, Ink.ACCENT_INK, KeyCap.KEYCAP, KeyKind.ACCENT_SOLID) >= 4.5)
            assertTrue(a, ratio(p, Surface.KEY_PRESSED, Ink.ACCENT_INK, KeyCap.KEYCAP, KeyKind.ACCENT_SOLID) >= 4.5)
        }
        // M1a: the pressed first suggestion (accent on keyHi): Midnight Laboratory + Tinte intenso (4.01:1); Alto contraste + #3e8fe0 + intenso (3.35:1)
        assertTrue(ratio(Palette.of("lab", null, Tint.STRONG), Surface.PRESSED, Ink.ACCENT) >= 4.5)
        assertTrue(ratio(Palette.of("hc", h("#3e8fe0"), Tint.STRONG), Surface.PRESSED, Ink.ACCENT) >= 4.5)
        // M1b: GIF «Ir» pressed: Papiro (3.91:1), Alto contraste + #e03e3e (3.10:1)
        assertTrue(ratio(paper, Surface.SOLID_PRESSED, Ink.ACCENT_INK) >= 4.5)
        assertTrue(ratio(Palette.of("hc", h("#e03e3e")), Surface.SOLID_PRESSED, Ink.ACCENT_INK) >= 4.5)
        // M2: KEYCAP dark NUM face bottom, Noche de juego + #3ee0e0 + intenso (6.97:1 < 7); RAISED MOD top, Grafito + #8fe03e + intenso (4.498:1)
        assertTrue(ratio(Palette.of("arcade", h("#3ee0e0"), Tint.STRONG), Surface.KEY_BOTTOM, Ink.TEXT, KeyCap.KEYCAP, KeyKind.NUM) >= 7.0)
        assertTrue(ratio(Palette.of("graphite", h("#8fe03e"), Tint.STRONG), Surface.KEY_TOP, Ink.TEXT_MOD, KeyCap.RAISED, KeyKind.MOD) >= 4.5)
        // M3: broken-ROM uniform grey + accents outside the swatches (tema-card sublabel 4.47:1; white over grey keys ≈ 2.5:1)
        val grey = SystemColors.uniform(h("#777777"))
        assertTrue(ratio(Palette.of("you", h("#3cc8d8"), Tint.STRONG, system = grey), Surface.CARD, Ink.TEXT_MOD) >= 4.5)
        for (id in listOf("you", "youlight")) for (a in listOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), Themes.hsl(60f, 0.85f, 0.8f), Themes.hsl(200f, 0.85f, 0.8f)))
            for (tint in Tint.values()) for (cap in KeyCap.values()) {
                val p = Palette.of(id, a, tint, system = grey)
                assertTrue("$id ${ColorMath.toHex(a)} $tint $cap ${Legibility.failures(p, cap)}", Legibility.failures(p, cap).isEmpty())
            }
        // and what nobody gated before: ok / bad, the ink on «Sí, borrar»
        for (t in Themes.CATALOG) for (tint in Tint.values()) {
            val p = Palette.of(t.id, null, tint)
            assertTrue(t.id, ratio(p, Surface.BACKGROUND, Ink.BAD) >= 4.5 && ratio(p, Surface.CARD, Ink.OK) >= 4.5 && ratio(p, Surface.BAD_FILL, Ink.BAD_INK) >= 4.5)
        }
    }

    /** Dropping a declared pair from the gate's table leaves the review's failure in place: each one is load-bearing. */
    @Test fun lgxDroppingADeclaredPairTurnsTheReviewsCombinationRed() {
        class Probe(val name: String, val drop: (Drawn.Decl) -> Boolean, val p: (List<Drawn.Decl>) -> Palette, val measure: (Palette) -> Double, val min: Double)
        val probes = listOf(
            Probe("B1 muted on the card (Papiro)", { it.surface == Surface.CARD && it.ink == Ink.MUTED },
                { gatedWith(it, "paper", null) }, { ratio(it, Surface.CARD, Ink.MUTED) }, 4.5),
            // accent on keyHi is declared three times (a pressed control, the accent row pressed, and the dark KEYCAP
            // face lifted 95 % toward keyHi): the three together are the pair
            Probe("M1a accent on keyHi (Midnight Laboratory, Tinte intenso)",
                { it.ink == Ink.ACCENT && (it.surface == Surface.PRESSED || it.surface == Surface.KEY_PRESSED || it.surface == Surface.KEY_TOP) },
                { gatedWith(it, "lab", null, Tint.STRONG) }, { ratio(it, Surface.PRESSED, Ink.ACCENT) }, 4.5),
            Probe("M2 legend on the KEYCAP dark NUM face bottom (Noche de juego + #3ee0e0, intenso)",
                { it.surface == Surface.KEY_BOTTOM && it.ink == Ink.TEXT },
                { gatedWith(it, "arcade", h("#3ee0e0"), Tint.STRONG) }, { ratio(it, Surface.KEY_BOTTOM, Ink.TEXT, KeyCap.KEYCAP, KeyKind.NUM) }, 7.0),
            Probe("M2 MOD legend on the RAISED modifier top (Grafito + #8fe03e, intenso)",
                { it.surface == Surface.KEY_TOP && it.ink == Ink.TEXT_MOD },
                { gatedWith(it, "graphite", h("#8fe03e"), Tint.STRONG) }, { ratio(it, Surface.KEY_TOP, Ink.TEXT_MOD, KeyCap.RAISED, KeyKind.MOD) }, 4.5),
        )
        for (pr in probes) {
            val full = pr.measure(pr.p(Drawn.PAIRS))
            val dropped = Drawn.PAIRS.filterNot(pr.drop)
            assertTrue("${pr.name}: the table drops something", dropped.size < Drawn.PAIRS.size)
            val without = pr.measure(pr.p(dropped))
            println("LGX ablation: ${pr.name}: with the pair %.3f, without it %.3f (≥ %.1f)".format(full, without, pr.min))
            assertTrue("${pr.name} passes with the table", full >= pr.min)
            assertTrue("${pr.name} must fail without its pair (%.3f)".format(without), without < pr.min)
        }
    }

    @Test fun lg7AnAccentFillsLiftAndPressKeepItsInk() {
        for (t in Themes.CATALOG) for (acc in stress) {
            val p = Palette.of(t.id, acc)
            assertTrue(ColorMath.contrast(p.accentInk, p.solidTop) >= 4.5)
            assertTrue(ColorMath.contrast(p.accentInk, p.solidPressed) >= ColorMath.contrast(p.accentInk, p.accent))
        }
        // the common case keeps the look: dark ink on a light accent lifts 12 % and presses 15 % toward white
        val lab = Palette.of("lab", null)
        assertEquals(ColorMath.mix(lab.accent, 0xFFFFFFFF.toInt(), 0.12f), lab.solidTop)
        assertEquals(ColorMath.mix(lab.accent, 0xFFFFFFFF.toInt(), 0.15f), lab.solidPressed)
    }

    @Test fun lgxEveryKeyInkIsDeclaredForItsKind() {
        val declared = Drawn.PAIRS.filter { it.surface.key }
        var keys = 0
        for (layer in Layer.values()) for (top in TopRow.values()) for (field in FieldKind.values()) for (emoji in listOf(false, true)) for (lang in Lang.values())
            for (row in KeyboardLayouts.rows(layer, LayoutSpec(lang, top, field, emoji))) for (k in row) for (shift in ShiftState.values()) {
                if (k.type == KeyType.SPACER) continue
                keys++
                val kind = Drawn.kindOf(k.type, k.style, shift)
                for (ink in Drawn.keyInks(k.type, k.style, shift)) for (s in listOf(Surface.KEY_TOP, Surface.KEY_BOTTOM, Surface.KEY_PRESSED))
                    assertTrue("${k.type}/${k.style}/$shift '${k.label}': $ink on $s for $kind is not declared",
                        declared.any { it.surface == s && it.ink == ink && kind in it.kinds })
            }
        assertTrue(keys > 1000)
    }

    // ── the source guard: the UI files take every colour from the table ──
    private val uiFiles = listOf("ime/KeyboardView.kt", "ime/KeyPainter.kt", "ime/QuickPanel.kt", "ime/EmojiPanel.kt", "ime/GifPanel.kt",
        "ime/ClipboardPanel.kt", "ime/EditPanel.kt", "ime/KeyIcons.kt", "settings/SettingsActivity.kt", "settings/LookViews.kt")

    private fun code(f: String): List<Pair<Int, String>> = File("src/main/kotlin/com/resyst/vk/$f").readLines().mapIndexedNotNull { i, line ->
        val s = line.trim()
        if (s.startsWith("//") || s.startsWith("*") || s.startsWith("/*")) null else (i + 1) to line.substringBefore(" // ")
    }

    /** Colour arithmetic and alpha a UI file may still do, each with why no text depends on it. */
    private val allowed = mapOf(
        "fill.color = ColorMath.withAlpha(color, press)" to "KeyPainter's press overlay: from the rest face to KEY_PRESSED, both ends declared; between them ≤ 110 ms",
        "text.alpha = dim" to "EmojiPanel: the grid behind the open skin-tone popup is inactive",
        "text.alpha = (if (sel) 255 else 150) * dim / 255" to "EmojiPanel: a colour emoji glyph (its colours are its own, not an ink)",
        "text.alpha = 255" to "EmojiPanel: resets the alpha above",
        "box.alpha = 0.42f" to "Settings: a disabled control (WCAG 1.4.3 exempts inactive components)",
        "alpha = if (enabled) 1f else 0.45f" to "Settings: the system palettes' cards below Android 12 are disabled",
    )

    private val raw = Regex("""\b(?:t|p\.theme|tp\.theme|pp\.theme|pal\.theme|palette\.theme|swatch\.theme)\.(?:bg|bg2|key|keyTop|keyHi|keyMod|keyNum|text|textMod|muted|ok|bad)\b|""" +
        """\b(?:p|tp|pp|pal|palette|swatch)\.(?:accent|accentInk|chipFill|chipPressedFill|chipPressedInk|accentSoft|badInk|solidTop|solidPressed)\b""")
    private val arith = Regex("""ColorMath\.(?:mix|over|withAlpha|ensureContrast)\(|\.alpha = |\balpha = |setAlpha\(""")
    private val pair = Regex("""Surface\.([A-Z_]+),\s*Ink\.([A-Z_]+)""")
    private val settingsInk = Regex("""(?<![\w.])ink\(Ink\.([A-Z_]+)\)""")

    /** The (surface, ink) pairs the UI files name, with where; [table] must declare each one. */
    private fun undeclared(table: List<Drawn.Decl>): List<String> {
        val out = ArrayList<String>()
        fun declared(s: Surface, i: Ink) = table.any { it.surface == s && it.ink == i }
        for (f in uiFiles) for ((n, line) in code(f)) {
            for (m in pair.findAll(line)) {
                val s = Surface.valueOf(m.groupValues[1])
                val i = Ink.valueOf(m.groupValues[2])
                if (!declared(s, i)) out += "$f:$n $i on $s"
            }
            if (f.endsWith("SettingsActivity.kt")) for (m in settingsInk.findAll(line)) {
                val i = Ink.valueOf(m.groupValues[1])
                for (s in listOf(Surface.BACKGROUND, Surface.CARD)) if (!declared(s, i)) out += "$f:$n $i on $s (Settings text on the page or a card)"
            }
        }
        return out
    }

    @Test fun lgxTheUiFilesTakeEveryColourFromTheTable() {
        val bad = ArrayList<String>()
        for (f in uiFiles) for ((n, line) in code(f)) {
            raw.find(line)?.let { bad += "$f:$n raw theme colour '${it.value}': $line".trim() }
            if (arith.containsMatchIn(line) && allowed.keys.none { line.trim().startsWith(it) }) bad += "$f:$n colour arithmetic / alpha outside Drawn: ${line.trim()}"
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
        // every pair a painter names is in the table …
        val missing = undeclared(Drawn.PAIRS)
        assertTrue(missing.joinToString("\n"), missing.isEmpty())
        // … and dropping any of them from the table is caught (the guard is not vacuous)
        var named = 0
        for (d in Drawn.PAIRS.filter { !it.surface.key }) {
            val gone = undeclared(Drawn.PAIRS - d)
            if (gone.isNotEmpty()) named++
        }
        println("LGX guard: ${uiFiles.size} files, ${Drawn.PAIRS.count { !it.surface.key }} non-key pairs declared, $named named by a painter (each one's removal reported)")
        assertTrue(named >= 25)
        // the key pairs: KeyboardView draws keys only through Drawn.kindOf / legendInk / the named extras
        val view = File("src/main/kotlin/com/resyst/vk/ime/KeyboardView.kt").readText()
        for (s in listOf("Drawn.kindOf(k.type, k.style, shift)", "Drawn.legendInk(k.type, k.style, shift)", "Drawn.HINT", "Drawn.SPACE_LABEL", "Drawn.SPACE_STAR", "Drawn.SPACE_GLOBE", "Drawn.SHIFT_ON"))
            assertTrue(s, view.contains(s))
        assertTrue(File("src/main/kotlin/com/resyst/vk/ime/KeyPainter.kt").readText().contains("Drawn.surface(p, Surface.KEY_PRESSED, cap, kind)"))
    }

    @Test fun lg11APaletteIsComputedOnce() {
        assertSame(Palette.of("paper", null), Palette.of("paper", null))
        assertSame(Palette.of("you", h("#5b9cf0"), Tint.SOFT, false, SystemColors.sample()), Palette.of("you", h("#5b9cf0"), Tint.SOFT, false, SystemColors.sample()))
    }

    // ── PS: the minors ──
    @Test fun ps7SettingsTargetsAreAtLeast48dp() {
        val src = File("src/main/kotlin/com/resyst/vk/settings/SettingsActivity.kt").readText()
        val small = Regex("""(?:minHeight|minimumHeight) = px\((\d+(?:\.\d+)?)f\)""").findAll(src).map { it.groupValues[1].toFloat() }.filter { it < 48f }.toList()
        assertTrue("targets under 48 dp: $small", small.isEmpty())
        assertTrue("accent swatches 48 dp", src.contains("row.addView(sw, LinearLayout.LayoutParams(px(48f), px(48f))"))
        assertTrue("slider 48 dp", src.contains("into.addView(bar, lp().apply { height = px(48f) })"))
    }

    @Test fun ps9NewTemaIsNotThePeekingLastCard() {
        val src = File("src/main/kotlin/com/resyst/vk/settings/SettingsActivity.kt").readText()
        val picker = src.substringAfter("private fun temaPicker()").substringBefore("private fun modePicker()")
        assertTrue(picker.contains("getString(R.string.tema_new)"))
        assertTrue("«＋ Nuevo tema» is added under the row, not into it", !picker.contains("row.addView(label(getString(R.string.tema_new)"))
    }

    @Test fun ps8AndPs10TheStateLineAndThePreview() {
        val q = File("src/main/kotlin/com/resyst/vk/ime/QuickPanel.kt").readText()
        assertTrue(q.contains("ellipsizeWords(shownState, stateW)") && q.contains("const val STATE_MIN = 9.5f"))
        for (t in Themes.CATALOG) {
            val p = Palette.of(t.id, null)
            assertEquals(t.id, if (t.dark) p.theme.keyHi else p.theme.keyTop, Drawn.surface(p, Surface.PREVIEW))
            assertTrue(ratio(p, Surface.PREVIEW, Ink.TEXT) >= 7.0)
        }
        assertNotNull(Drawn.PAIRS.firstOrNull { it.surface == Surface.PREVIEW && it.ink == Ink.TEXT && it.min == 7.0 })
    }
}
