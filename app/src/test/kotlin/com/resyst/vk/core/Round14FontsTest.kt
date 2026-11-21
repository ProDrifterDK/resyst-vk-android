package com.resyst.vk.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/**
 * r14 PS5 (docs/failure-modes.md, round 14): a bundled key font that lacks a glyph a key draws shows a fallback glyph
 * in another face on the key. The subsets must cover every legend of every layer (labels, typed texts, top-row
 * hints) in both languages; the licence travels with each font file. A tiny TrueType `cmap` reader, no dependency.
 */
class Round14FontsTest {

    private val dir = File("src/main/assets/fonts")

    /** Code points mapped by the font's Unicode cmap (format 4 or 12). */
    private fun cmap(f: File): Set<Int> {
        val b = ByteBuffer.wrap(f.readBytes())
        val tables = b.getShort(4).toInt() and 0xFFFF
        var cmapOff = -1
        for (i in 0 until tables) {
            val rec = 12 + i * 16
            val tag = String(ByteArray(4) { b.get(rec + it) }, Charsets.US_ASCII)
            if (tag == "cmap") cmapOff = b.getInt(rec + 8)
        }
        require(cmapOff >= 0) { "no cmap in ${f.name}" }
        val n = b.getShort(cmapOff + 2).toInt() and 0xFFFF
        val out = HashSet<Int>()
        for (i in 0 until n) {
            val rec = cmapOff + 4 + i * 8
            val platform = b.getShort(rec).toInt() and 0xFFFF
            val sub = cmapOff + b.getInt(rec + 4)
            if (platform != 3 && platform != 0) continue
            when (b.getShort(sub).toInt() and 0xFFFF) {
                4 -> {
                    val segX2 = b.getShort(sub + 6).toInt() and 0xFFFF
                    val ends = sub + 14
                    val starts = ends + segX2 + 2
                    for (s in 0 until segX2 / 2) {
                        val end = b.getShort(ends + s * 2).toInt() and 0xFFFF
                        val start = b.getShort(starts + s * 2).toInt() and 0xFFFF
                        if (start != 0xFFFF) for (c in start..end) out += c
                    }
                }
                12 -> {
                    val groups = b.getInt(sub + 12)
                    for (g in 0 until groups) {
                        val start = b.getInt(sub + 16 + g * 12)
                        val end = b.getInt(sub + 20 + g * 12)
                        for (c in start..end) out += c
                    }
                }
            }
        }
        return out
    }

    /** Every character a key's legend shows (labels, typed texts, hints) on every layer and spec, both languages. */
    private fun legendChars(): Set<Int> {
        val out = HashSet<Int>()
        for (layer in Layer.values()) for (top in TopRow.values()) for (field in FieldKind.values()) for (lang in Lang.values()) {
            for (row in KeyboardLayouts.rows(layer, LayoutSpec(lang, top, field, emojiKey = true))) for (k in row) {
                if (k.type != KeyType.CHAR && k.type != KeyType.LAYER && k.type != KeyType.SPACE) continue
                for (s in listOfNotNull(k.label, k.text, k.hint)) s.codePoints().forEach { out += it }
                if (k.type == KeyType.CHAR) Variants.shiftChar(k.label).codePoints().forEach { out += it }
            }
        }
        out.remove(' '.code)
        return out
    }

    @Test fun ps5BundledFontsCoverEveryLegend() {
        val need = legendChars()
        assertTrue(need.size > 80)
        for (name in listOf("AtkinsonHyperlegibleNext-var.ttf", "Nunito-var.ttf", "DMSans-var.ttf")) {
            val have = cmap(File(dir, name))
            val missing = need.filter { it !in have }.map { String(Character.toChars(it)) }
            assertTrue("$name lacks $missing", missing.isEmpty())
        }
    }

    @Test fun ps5TheLicencesTravelWithTheFonts() {
        for ((font, licence) in listOf("AtkinsonHyperlegibleNext-var.ttf" to "OFL-AtkinsonHyperlegibleNext.txt", "Nunito-var.ttf" to "OFL-Nunito.txt", "DMSans-var.ttf" to "OFL.txt")) {
            assertTrue(font, File(dir, font).length() > 20_000)
            val text = File(dir, licence).readText()
            assertTrue(licence, text.contains("SIL Open Font License, Version 1.1") || text.contains("SIL OPEN FONT LICENSE Version 1.1"))
        }
        val readme = File(dir, "README.txt").readText()
        assertTrue(readme.contains("Braille Institute") && readme.contains("Nunito Project Authors") && readme.contains("pyftsubset"))
        // the subsets stay small (PE2: APK growth justified per file)
        assertTrue(File(dir, "AtkinsonHyperlegibleNext-var.ttf").length() < 120_000)
        assertTrue(File(dir, "Nunito-var.ttf").length() < 130_000)
        // credited in Settings next to DM Sans, in both languages
        val es = File("src/main/res/values/strings.xml").readText()
        val en = File("src/main/res/values-en/strings.xml").readText()
        assertTrue(es.contains("Atkinson Hyperlegible Next") && es.contains("Nunito") && en.contains("Atkinson Hyperlegible Next"))
    }
}
