package com.resyst.vk.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r13d (TB1–TB6, W5-1): the touch says which neighbour. s3's `iueho`: its first touch lifted at the corner
 * where i, k, u and j meet (evidence taps.json, up point 711, 1818 on the Pixel 8 Pro letters layer); VK
 * committed i (final.txt). From the letters alone `juego` and `luego` cost the same two slips (i→j and i→l
 * are mirror diagonals on the ES layout) and `luego` is more frequent, so the word must stay; with the
 * touch record `juego` is the only word two slips away.
 *
 * Bite proof: on 361d784 this file does not compile (TouchKeys / TouchTrail / Writing.touch do not exist),
 * and `iueho` stays `iueho` there with or without a touch (five letters search one edit).
 */
class Round13dTouchTest {
    private val es get() = ShippedWriting.lexicons.getValue(Lang.ES).also { it.foreign = ShippedWriting.lexicons[Lang.EN] }
    private val cells = ShippedTouch.pixel8ProCells
    private val keyWidth = 99.5f
    private val corner = listOf("ujk", "", "", "", "")
    private val left = "El servidor del Bastión sigue corriendo el "

    @Test fun theBorderKeysOfATouch() { // TB1: VK's own hit cells
        assertEquals("s3 iueho: the i / k / u / j corner", "ujk", TouchKeys.near(cells, 711f, 1818f, 'i', keyWidth))
        assertEquals("the middle of i", "", TouchKeys.near(cells, 753f, 1758f, 'i', keyWidth))
        assertEquals("s3 caido: VK committed k, a pixel past the row boundary", "i", TouchKeys.near(cells, 739f, 1820f, 'k', keyWidth).filter { it == 'i' })
        assertEquals("s3 gome: lifted on the f / g boundary", "f", TouchKeys.near(cells, 404f, 1889f, 'g', keyWidth))
        assertEquals("a key's own letter is never listed", "", TouchKeys.near(cells, 753f, 1758f, 'I', keyWidth))
        val r = TouchKeys.BORDER * keyWidth
        assertTrue("about 12 px on the Pixel 8 Pro", r in 11f..13f)
    }

    @Test fun theTrailFollowsTheWordAndNothingElse() { // TB2
        val t = TouchTrail()
        assertNull(t.forWord("iueho"))
        "iueho".forEachIndexed { i, c -> t.add(c, corner[i]) }
        assertArrayEquals(corner.toTypedArray(), t.forWord("iueho"))
        assertArrayEquals("the auto-capital is the same letter", corner.toTypedArray(), t.forWord("Iueho"))
        assertNull("another word", t.forWord("iuehi"))
        assertNull("a longer word", t.forWord("iuehos"))
        t.pop()
        assertTrue(t.spells("iueh") && !t.spells("iueho"))
        t.reset()
        assertTrue(t.spells("") && t.forWord("i") == null)
        repeat(Tokens.MAX_LEN + 3) { t.add('a', "s") }
        assertTrue("bounded: past MAX_LEN it starts over", t.size <= Tokens.MAX_LEN)
        val plain = TouchTrail().also { u -> "hola".forEach { u.add(it, "") } }
        assertNull("no border touch: no record", plain.forWord("hola"))
    }

    @Test fun iuehoWithItsTouchIsJuego() { // W5-1 + TB1 — s3 row 30, ended by a comma
        assertEquals("juego,", ShippedTouch.replay(left, "iueho", ",", corner))
        assertEquals("juego ", ShippedTouch.replay(left, "iueho", " ", corner))
        assertEquals("from the letters alone it is a tie with luego: the word stays", "iueho,", ShippedTouch.replay(left, "iueho", ",", null))
        assertEquals("a touch in the middle of i says nothing", "iueho,", ShippedTouch.replay(left, "iueho", ",", listOf("", "", "", "", "")))
        val bar = es.suggest("iueho", 3)
        assertTrue("both readings offered: $bar", "luego" in bar && "juego" in bar)
    }

    @Test fun barAndSpaceReadTheSameRecord() { // TB3
        val bar = Bar.words("corriendo el iueho", "", false, Lang.ES, es, null, ShiftState.OFF, near = corner.toTypedArray())
        assertEquals("the bar leads with what space applies: $bar", "juego", bar.first())
    }

    @Test fun borderEvidenceNeverRewritesARealWord() { // TB4: s3's real-word slips keep their border touches
        assertEquals("nieva.", ShippedTouch.replay("un push a la branch ", "nieva", ".", listOf("", "ujk", "", "", "")))
        assertEquals("di ", ShippedTouch.replay("en serio. ", "di", " ", listOf("s", "")).lowercase())
        assertEquals("vacan ", ShippedTouch.replay("Igual fue ", "vacan", " ", listOf("", "", "v", "", "")))
        // juneros (VK's input for s3's nuneros): números with the n of the first touch, never juntos
        val got = ShippedTouch.replay("Igual fue bacán ver los ", "juneros", " ", listOf("n", "", "", "", "", "", ""))
        assertTrue(got, got.trim() in setOf("juneros", "números"))
    }

    @Test fun aCornerTouchMakesATwoLetterNeighbourToo() { // TB1 in r13c's two-letter rule
        // `ek` → el (r13c SW1); with the k typed on the k / l border the rule reads l the same way
        assertEquals("el ", ShippedTouch.replay("Mañana hago ", "ek", " ", listOf("", "l")))
        assertFalse(TypoModel.near(TypoModel.centres(Lang.ES), 'i', 'j')) // a corner is not a neighbour by geometry
    }
}
