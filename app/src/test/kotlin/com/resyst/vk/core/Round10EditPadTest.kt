package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Failure modes QE1–QE6 (docs/failure-modes.md, round 10: edit panel + ⌫ swipe). */
class Round10EditPadTest {

    private val view = File("src/main/kotlin/com/resyst/vk/ime/KeyboardView.kt").readText()

    @Test fun everyOpHasExactlyOneButtonInBothGrids() { // QE1
        for (rows in listOf(3, 2)) {
            val g = EditPad.grid(rows)
            assertEquals("$rows rows", EditOp.values().toList().sorted(), g.map { it.op }.sorted())
            assertEquals("$rows rows: one cell per button", g.size, g.map { it.col to it.row }.toSet().size)
            val cols = EditOp.values().size / rows
            for (b in g) assertTrue("$rows rows ${b.op}", b.col in 0 until cols && b.row in 0 until rows)
            for (b in g) assertTrue(b.op.name, b.label.isNotBlank())
        }
        // the arrows sit around Seleccionar like a d-pad in the portrait grid
        val p = EditPad.grid(3).associateBy { it.op }
        assertEquals(p.getValue(EditOp.SELECT).col, p.getValue(EditOp.UP).col)
        assertEquals(p.getValue(EditOp.SELECT).row, p.getValue(EditOp.LEFT).row)
        assertEquals(p.getValue(EditOp.SELECT).row, p.getValue(EditOp.RIGHT).row)
    }

    @Test fun secretsNeverReachTheClipboard() { // QE2
        assertFalse(EditPad.enabled(EditOp.COPY, secret = true))
        assertFalse(EditPad.enabled(EditOp.CUT, secret = true))
        for (op in EditOp.values()) {
            assertTrue(op.name, EditPad.enabled(op, secret = false))
            if (op != EditOp.COPY && op != EditOp.CUT) assertTrue(op.name, EditPad.enabled(op, secret = true))
        }
        assertTrue(EditPad.desc(EditOp.COPY, selecting = false, secret = true).endsWith("no disponible en contraseñas"))
    }

    @Test fun onlyMovesAndDeletesRepeat() { // QE3
        assertEquals(setOf(EditOp.LEFT, EditOp.RIGHT, EditOp.UP, EditOp.DOWN, EditOp.DELETE_WORD), EditOp.values().filter(EditPad::repeats).toSet())
    }

    @Test fun selectionStateIsShownAndSpoken() { // QE4
        assertTrue(EditPad.desc(EditOp.SELECT, selecting = true, secret = false).endsWith("activado"))
        assertTrue(EditPad.desc(EditOp.SELECT, selecting = false, secret = false).endsWith("desactivado"))
        assertTrue(EditPad.desc(EditOp.LEFT, selecting = true, secret = false).contains("extiende la selección"))
        assertFalse(EditPad.desc(EditOp.LEFT, selecting = false, secret = false).contains("selección"))
    }

    @Test fun buttonsStayTouchTargets() { // QE5
        // 3 rows while each gets ≥ 48 dp, else 2 (landscape, short keyboards)
        assertEquals(3, EditPad.rowsFor(gridHeightDp = 3 * 48f))
        assertEquals(2, EditPad.rowsFor(gridHeightDp = 3 * 48f - 1))
        // smallest portrait keyboard: 4 rows × 54 dp × 0.8 + strip 42 + 4, minus the 44 dp header
        val portrait = 4 * 54f * ProfileCodec.HEIGHT_MIN + 42f + 4f - EditPad.HEADER_DP
        assertEquals(3, EditPad.rowsFor(portrait))
        // smallest landscape keyboard (40 dp rows) still gets ≥ 48 dp buttons in 2 rows
        val land = 4 * 40f * ProfileCodec.HEIGHT_MIN + 42f + 4f - EditPad.HEADER_DP
        assertTrue(land / EditPad.rowsFor(land) >= 48f)
        for (op in EditOp.values()) assertTrue(op.name, EditPad.desc(op, false, false).isNotBlank())
        assertTrue(view.contains("editBase"))
    }

    @Test fun deleteSwipeIsADeliberateLeftwardDrag() { // QE6
        val dp = 2.75f
        assertTrue(DeleteSwipe.isSwipe(-24 * dp, 0f, dp))
        assertTrue(DeleteSwipe.isSwipe(-60 * dp, -20 * dp, dp))
        assertFalse("tap", DeleteSwipe.isSwipe(0f, 0f, dp))
        assertFalse("wobble", DeleteSwipe.isSwipe(-23 * dp, 0f, dp))
        assertFalse("rightwards", DeleteSwipe.isSwipe(60 * dp, 0f, dp))
        assertFalse("mostly vertical", DeleteSwipe.isSwipe(-30 * dp, -16 * dp, dp))
        // the view cancels the char repeat when the swipe takes over and fires once
        val bs = view.substringAfter("KeyType.BACKSPACE && !p.swiped", "")
        assertTrue(bs.contains("DeleteSwipe.isSwipe") && bs.substringBefore("return").contains("removeCallbacksAndMessages(p)"))
        assertTrue(view.contains("listener?.onDeleteWord()"))
    }
}
