package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * r14 VO1 (docs/failure-modes.md, round 14): the visual overhaul paints inside the 0.13.0 hit grid and never moves it.
 * Written before the r14 code and pinned on the 0.13.0 tree (base 75cfe1a): the layouts, the long-press popup cells,
 * the one-hand split and the view's geometry lines are byte-for-byte what 0.13.0 typed with.
 */
class Round14GeometryTest {

    private fun sha(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Every key of every layer for every spec a user can reach, one line per key. */
    private fun layoutsCanon(): String = buildString {
        for (layer in Layer.values()) for (top in TopRow.values()) for (field in FieldKind.values()) for (emoji in listOf(false, true)) for (lang in Lang.values()) {
            append("$layer $top $field $emoji $lang\n")
            KeyboardLayouts.rows(layer, LayoutSpec(lang, top, field, emoji)).forEachIndexed { r, row ->
                for (k in row) append("  $r ${k.type}|${k.label}|${k.text}|${k.width}|${k.target}|${k.hint}|${k.variants.joinToString(",")}|${k.style}\n")
            }
        }
    }

    /** The long-press popup's cells and its x → variant mapping (the slide a finger makes), on a 411 dp phone. */
    private fun popupCanon(): String = buildString {
        val dp = 2.625f
        val w = 411 * dp
        for (n in 1..9) for (keyLeft in listOf(0f, 40f, 300f, 600f, 1000f)) {
            val p = PopupGeometry.variants(keyLeft, 37 * dp, n, 37 * dp, w, 4 * dp)
            append("$n $keyLeft ${p.left} ${p.cell} ${p.width}")
            for (i in 0 until n) append(" ${p.columnOf(i)}")
            var x = 0f
            while (x < w) { append(" ${p.indexAt(x)}"); x += 9 * dp }
            append('\n')
        }
        append("${PopupGeometry.previewTop(120f, 140f)} ${PopupGeometry.previewTop(400f, 140f)}\n")
    }

    private fun oneHandCanon(): String = buildString {
        for (dp in listOf(2f, 2.625f, 2.75f, 3.5f)) for (wDp in listOf(200f, 320f, 360f, 411f, 600f, 800f)) for (side in OneHand.values()) {
            val s = OneHand.split(wDp * dp, side, dp)
            append("$dp $wDp $side ${s.keysLeft} ${s.keysRight} ${s.railLeft} ${s.railWidth}\n")
        }
    }

    @Test fun vo1LayoutsAreThe0130Ones() {
        assertEquals("layouts changed: the keys a touch lands on would move", LAYOUTS_0130, sha(layoutsCanon()))
    }

    @Test fun vo1PopupCellsAndTheSlideMappingAreThe0130Ones() {
        assertEquals("the long-press popup's cells or its slide mapping changed", POPUP_0130, sha(popupCanon()))
    }

    @Test fun vo1OneHandSplitIsThe0130One() {
        assertEquals(ONEHAND_0130, sha(oneHandCanon()))
    }

    /** The view computes the hit grid from these lines; a style may only paint inside `Box.rect`. */
    @Test fun vo1ViewGeometryLinesAreThe0130Ones() {
        val view = File("src/main/kotlin/com/resyst/vk/ime/KeyboardView.kt").readText()
        for (line in listOf(
            "private val stripH get() = 42 * dp",
            "val base = if (landscape) 40f else 54f",
            "return baseRowCount * base * dp * settings.heightScale",
            "val content = (stripH + keysHeight() + 4 * dp).toInt()",
            "split = OneHand.split(width.toFloat(), settings.oneHanded, dp)",
            "val padX = 3 * dp",
            "val gap = settings.density.gapDp * dp",
            "val unit = (split.keysRight - split.keysLeft - 2 * padX) / com.resyst.vk.core.KeyboardLayouts.ROW_UNITS",
            "rowH = keysHeight() / rows.size",
            "val b = Box(k, RectF(x + gap / 2, y0 + gap / 2 + dp, x + w - gap / 2, y0 + rowH - gap / 2), x, x + w, r)",
            "val r = ((y - stripH) / rowH).toInt().coerceIn(0, rowBoxes.size - 1)",
            "row.firstOrNull { x >= it.cellLeft && x < it.cellRight }?.let { return it }",
            "return row.minByOrNull { min(abs(x - it.cellLeft), abs(x - it.cellRight)) }",
            "val popup = PopupGeometry.variants(b.rect.left, b.rect.width(), items.size, cell, width.toFloat(), 4 * dp)",
            "val cell = max(b.rect.width(), 34 * dp)",
        )) assertTrue("KeyboardView lost the 0.13.0 geometry line: $line", view.contains(line))
        // the densities a 0.13.0 user can hold keep their gaps (the gap shapes Box.rect, the TalkBack bounds)
        assertEquals(listOf(3f, 5f, 8f), listOf(Density.TIGHT, Density.NORMAL, Density.AIRY).map { it.gapDp })
        assertEquals(0.8f, ProfileCodec.HEIGHT_MIN, 0f)
        assertEquals(1.3f, ProfileCodec.HEIGHT_MAX, 0f)
    }

    /** r14 PE1: nothing is allocated per frame in the drawing section (0.13.0 built a LinearGradient per key per frame). */
    @Test fun pe1TheDrawingLoopAllocatesNothing() {
        val view = File("src/main/kotlin/com/resyst/vk/ime/KeyboardView.kt").readText()
        val drawing = view.substringAfter("// ── drawing ──").substringBefore("// ── accessibility (TalkBack")
        assertTrue(drawing.length > 10_000)
        for (bad in listOf("LinearGradient(", "HashSet<", ".toSet()", "mapNotNull", "RectF(", "Path()", "ValueAnimator", "listOf(", "arrayOf("))
            assertTrue("KeyboardView's drawing allocates: $bad", !drawing.contains(bad))
        val painter = File("src/main/kotlin/com/resyst/vk/ime/KeyPainter.kt").readText()
        val body = painter.substringAfter("fun body(").substringBefore("private fun overlay(")
        assertTrue("KeyPainter.body allocates", listOf("LinearGradient(", "RectF(", "Path()", "Paint(").none { body.contains(it) })
        // gradients come from the per-(row, kind) cache
        assertTrue(painter.contains("private fun gradient(slot: Int"))
    }

    /** r14 VO1b: the names TalkBack reads for the keys and the bar are the E2E contract: byte-identical to 0.13.0. */
    @Test fun vo1bTheKeyNamesAreThe0130Ones() {
        val view = File("src/main/kotlin/com/resyst/vk/ime/KeyboardView.kt").readText()
        val describe = view.substringAfter("private fun describe(k: Key): String = when (k.type) {").substringBefore("private inner class KeyA11y")
        for (n in listOf("\"coma\"", "\"punto\"", "\"arroba\"", "\"barra\"", "\"guion\"", "\"Mayúsculas\"", "\"Mayúsculas, bloqueadas\"", "\"Mayúsculas, activadas\"",
            "\"Borrar\"", "\"Espacio. Mantén pulsado para cambiar de teclado\"", "\"Espacio\"", "\"Símbolos\"", "\"Más símbolos\"", "\"Letras\"", "\"Emojis\""))
            assertTrue("describe() lost $n", describe.contains(n))
        for (n in listOf("\"Ajustes de Resyst VK\"", "\"Sugerencia: \${s.text}\"", "\"Pegar del portapapeles: \${s.text}\"", "\"Mover el teclado a la izquierda\"",
            "\"Mover el teclado a la derecha\"", "\"Teclado a todo el ancho\"", "\"Cambiar de teclado\""))
            assertTrue("the bar / rail lost $n", view.contains(n))
    }

    private companion object {
        // pinned on the 0.13.0 tree (75cfe1a), before any r14 code
        const val LAYOUTS_0130 = "a1b0855dbd52b5973a3c67f9c981130a473d0462517472a75c86559a3d8b4d3a"
        const val POPUP_0130 = "86f1ad6c85c3666dfe0e4962ea4c26d6080bc4b99bda6161aae18d8c09623fcf"
        const val ONEHAND_0130 = "1ebf9d316c27d0f04bbf2d47c5d69878d0aaf7179d47be41aee69ba917425eff"
    }
}
