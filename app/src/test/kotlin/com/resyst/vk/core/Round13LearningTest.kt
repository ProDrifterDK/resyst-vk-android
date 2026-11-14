package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r13 R4 (late rejection, LR1–LR4) and R5 (learned capitalization, LC1–LC3) through the IME's key
 * path: engine press → effects → learner (kept or learn), with a cursor that can sit inside the
 * text. Written against the engine / learner / model API that predates r13, so the same file runs
 * on the base commit (c0bad7b) for the bite proof — where both behaviours fail.
 *
 * r13c (TS1–TS3): the harness follows the IME's rejection flow now (a first rejection is a strike:
 * the word is kept in that field only; the second, elsewhere, keep-marks it), so LR2's "kept for
 * good" needs two rejections (Round13cStrikesTest covers every path).
 */
class Round13LearningTest {
    private val es = Lang.ES
    // frequency order, most frequent first: "bastón" is the confident fix of "bastiono"
    private val lex = Suggest(listOf("de", "que", "hoy", "y", "es", "ayer", "con", "uso", "otra", "vez", "bastón", "bastante", "base"), Lang.ES)

    private val space = Key(KeyType.SPACE, " ")
    private val bs = Key(KeyType.BACKSPACE, "⌫")

    /** One text field: [before] / [after] the cursor, the service's key path, a shared [m]. */
    private class Field(val m: PersonalModel, lex: Suggest, val store: Rejections = Rejections()) {
        val engine = KeyboardEngine().apply {
            start(FieldInfo(autoCap = false))
            corrector = Corrector { w, s -> Bar.correction(w, s, lex, m, Lang.ES, rejected = store) }
        }
        val learner = Learner { m }
        var before = ""
        var after = ""
        val text get() = before + after

        fun key(k: Key) {
            val b = before
            val outs = engine.press(k, b, 0L, after)
            before = Edits.apply(b, outs)
            val kept = engine.takeReverted()
            // r13c: the IME's afterKey — a rejection is a strike, the second one keep-marks
            val forGood = engine.takeRejection()?.let { (typed, fix) ->
                if (k.type != KeyType.BACKSPACE) m.unlearn(Lang.ES, null, fix, false) // a late retype
                store.strike(typed, fix)
            } ?: false
            if (kept != null) learner.kept(Lang.ES, Lang.ES, b, outs, false, kept, mark = forGood)
            else learner.afterEdit(Lang.ES, b, outs, false, if (k.type == KeyType.BACKSPACE) Learner.Edit.BACKSPACE else Learner.Edit.KEY)
        }

        fun type(s: String) { for (c in s) key(if (c == ' ') Key(KeyType.SPACE, " ") else Key(KeyType.CHAR, c.toString(), c.toString())) }

        /** The user taps the text to put the cursor at [at] (the learner forgets its pending undo). */
        fun cursor(at: Int) {
            val t = text
            before = t.substring(0, at); after = t.substring(at)
            learner.reset()
        }
    }

    // ── R4: late rejection ───────────────────────────────────────────────
    @Test fun deletingACorrectionLaterAndRetypingKeepsTheWordForGood() { // LR1, LR2
        val m = PersonalModel()
        val f = Field(m, lex)
        f.type("hoy bastiono ")
        assertEquals("hoy bastón ", f.text)
        f.type("es ") // the user goes on writing: ⌫ right after the correction is long gone
        repeat(4) { f.key(bs) } // "es " and the space after bastón
        repeat(6) { f.key(bs) } // the whole corrected word
        assertEquals("hoy ", f.text)
        f.type("bastiono ")
        assertEquals("LR1: the retyped word is kept as typed", "hoy bastiono ", f.text)
        assertTrue("r13c TS1: a first strike", f.store.strikes().isNotEmpty() && !m.kept(es, "bastiono"))
        // r13c TS2: a NEW field still corrects; rejected there too, it is the user's for good
        val g = Field(m, lex, f.store)
        g.type("ayer bastiono ")
        assertEquals("ayer bastón ", g.text)
        g.key(bs)
        g.key(space)
        assertEquals("ayer bastiono ", g.text)
        assertTrue("LR2 (two strikes): it is the user's now", m.kept(es, "bastiono"))
        val h = Field(m, lex, g.store)
        h.type("hoy bastiono ")
        assertEquals("hoy bastiono ", h.text)
    }

    @Test fun theCursorCanGoBackIntoTheCorrectedWord() { // LR1, LR7: deleted from inside the text
        val m = PersonalModel()
        val f = Field(m, lex)
        f.type("hoy bastiono y algo ")
        assertEquals("hoy bastón y algo ", f.text)
        f.cursor("hoy bastón".length) // tap right after the corrected word
        repeat(6) { f.key(bs) }
        assertEquals("hoy | y algo ", f.before + "|" + f.after)
        f.type("bastiono")
        f.key(space)
        assertEquals("hoy bastiono  y algo ", f.text)
        assertEquals("r13c TS1: a first strike", listOf(Rejections.Entry("bastiono", "bastón")), f.store.strikes())
    }

    @Test fun retypingATypoWhoseFixStayedIsCorrectedAgain() { // LR3: no rejection without a deletion
        val m = PersonalModel()
        val f = Field(m, lex)
        f.type("hoy bastiono es bastiono ")
        assertEquals("hoy bastón es bastón ", f.text)
        assertFalse(m.kept(es, "bastiono"))
    }

    @Test fun immediateBackspaceStillWorks() { // LR4 (r10 A10 + r11 K1 unchanged)
        val m = PersonalModel()
        val f = Field(m, lex)
        f.type("hoy bastiono ")
        f.key(bs)
        assertEquals("hoy bastiono", f.text)
        f.key(space)
        assertEquals("hoy bastiono ", f.text)
        assertTrue("r13c TS3: learned as an ordinary use, a first strike", m.knows(es, "bastiono") && !m.kept(es, "bastiono") && f.store.strikes().size == 1)
    }

    // ── R5: learned capitalization ───────────────────────────────────────
    @Test fun aCasingUsedTwiceIsAppliedToALowercaseTyping() { // LC1
        val m = PersonalModel()
        val f = Field(m, lex)
        f.type("uso GrapheneOS hoy y ")
        assertNull("one use is not enough", Bar.correction("grapheneos", false, lex, m, es))
        val g = Field(m, lex) // another field, later
        g.type("otra vez GrapheneOS con grapheneos ")
        assertEquals("otra vez GrapheneOS con GrapheneOS ", g.text)
    }

    @Test fun sentenceStartCapitalsAreNotEvidence() { // LC2
        val m = PersonalModel()
        val f = Field(m, lex)
        f.type("Grapheneos es. Grapheneos es. Y grapheneos ")
        assertTrue("never Grapheneos from sentence starts: ${f.text}", f.text.endsWith("Y grapheneos "))
    }

    @Test fun aContradictedCasingIsNeverApplied() { // LC3
        val m = PersonalModel()
        val f = Field(m, lex)
        f.type("uso Pixel hoy y Pixel con pixel ")
        assertTrue(f.text, f.text.endsWith("con Pixel "))
        f.key(bs) // the user undoes the casing: a lowercase use
        f.key(space)
        f.type("y pixel ")
        assertTrue(f.text, f.text.endsWith("y pixel "))
    }
}
