package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r13c E1 (EP1–EP8) and E5 (MC1–MC2): a word ended by punctuation or a new line gets the correction
 * space would apply, through the REAL engine + the shipped corrector + the shipped assets
 * ([ShippedWriting]). The first four rows use only the engine API of de1efa4 (0.9.0), so the same
 * rows run there for the bite proof — where all four fail: the correction only ran on space.
 */
class Round13cEndingsTest {
    private val shipped = ShippedWriting

    // ── EP1: bite-proof rows (de1efa4 API only) ───────────────────────────
    @Test fun commaEndsAWordLikeSpace() { // s2: `tan lentl,`
        assertEquals("lento,", shipped.replay("Al final el problema era que andaba tan ", "lentl", ","))
    }

    @Test fun periodEndsAWordLikeSpace() { // s1 + s2: `que pillae.`
        assertEquals("pillar.", shipped.replay("Eso es lo que tenemos que ", "pillae", "."))
    }

    @Test fun newLineEndsAWordLikeSpace() { // s1: `tuvom⏎`
        assertEquals("tuvo\n", shipped.replay("ni tubo que ", "tuvom", "\n"))
    }

    @Test fun everyClosingMarkEndsAWord() { // EP1: the r12 closing set
        for (mark in listOf(";", ":", "!", "?", ")")) assertEquals(mark, "lento$mark", shipped.replay("andaba tan ", "lentl", mark))
    }

    // ── EP1/EP2: the same decision as space ───────────────────────────────
    @Test fun aLongPressVariantEndsAWordToo() { // … ! ? from the period's long-press
        val e = KeyboardEngine().apply { start(FieldInfo(autoCap = false)); corrector = shipped.writing().corrector() }
        val before = "andaba tan lentl"
        assertEquals("andaba tan lento…", Edits.apply(before, e.variant("…", before, "")))
        assertEquals("the GIF box (no text) never corrects", listOf<Out>(Out.Commit("…")), e.variant("…", null))
    }

    @Test fun anActionEnterCorrectsFirst() { // EP1 said send / search / go correct nothing; r13f SE1: the word is fixed, then sent (s4: lenyo)
        val e = KeyboardEngine().apply {
            start(FieldInfo(FieldKind.TEXT, multiLine = false, action = ImeAction.SEND, autoCap = false))
            corrector = shipped.writing().corrector()
        }
        assertEquals(listOf(Out.DeleteBefore(5), Out.Commit("lento"), Out.Action(ImeAction.SEND)), e.press(Key(KeyType.ENTER, "⏎"), "andaba tan lentl", 0L))
    }

    @Test fun endingsKeepTheSpaceGates() { // EP2: real words, names, mid-word, glued words, the user's words
        assertEquals("lento,", shipped.replay("andaba tan ", "lento", ","))
        assertEquals("Lentl,", shipped.replay("andaba tan ", "Lentl", ",")) // a capital mid-sentence is a name (A3)
        val e = KeyboardEngine().apply { start(FieldInfo(autoCap = false)); corrector = shipped.writing().corrector() }
        assertEquals("A11: a letter after the cursor = mid-word", listOf<Out>(Out.Commit(",")), e.press(Key(KeyType.CHAR, ",", ","), "tan lentl", 0L, "x"))
        assertEquals("A4: glued to a digit", listOf<Out>(Out.Commit(".")), e.press(Key(KeyType.CHAR, ".", "."), "v2lentl", 0L, ""))
        val m = PersonalModel().apply { repeat(Bar.HABIT) { learn(Lang.ES, "tan", "lentl", false) } }
        assertEquals("B5: the user's own word", "lentl,", shipped.replay("andaba tan ", "lentl", ",", shipped.writing(personal = m)))
        val r = Rejections().apply { add("lentl", "lento") }
        assertEquals("LR5: a stored rejection", "lentl,", shipped.replay("andaba tan ", "lentl", ",", shipped.writing(rejections = r)))
    }

    @Test fun abbreviationsAndDomainsStayBeforeAPeriod() { // EP6 + the domains EP5 is about
        val kept = listOf("ej", "pág", "etc", "aprox", "depto", "núm", "tel", "prof", "sr", "dra", "lic", "ing", "av", "atte", "obs", "hrs", "nro", "ud", "cel") +
            listOf("youtube", "github", "gmail", "instagram", "falabella", "mercadolibre", "klipy")
        val changed = kept.mapNotNull { w -> shipped.replay("Ver ", w, ".").takeIf { it != "$w." }?.let { "$w.→$it" } }
        assertEquals(emptyList<String>(), changed)
    }

    // ── EP3: undo right after an ending ───────────────────────────────────
    private fun field(m: PersonalModel? = PersonalModel(), r: Rejections? = Rejections()) = ShippedWriting.ImeField(shipped.writing(m, r), m, r)

    @Test fun oneBackspaceAfterAMarkRestoresTheTypedWordAndKeepsTheMark() { // EP3
        val f = field()
        f.type("andaba tan lentl,")
        assertEquals("andaba tan lento,", f.text)
        f.type("⌫")
        assertEquals("one ⌫ undoes the fix, the comma stays", "andaba tan lentl,", f.text)
        f.type(" y lentl ")
        assertEquals("TS1: kept as typed in this field", "andaba tan lentl, y lentl ", f.text)
        f.type("⌫⌫")
        assertEquals("further ⌫ are plain deletions", "andaba tan lentl, y lent", f.text)
    }

    @Test fun backspaceAfterSpaceStillTakesTheSpace() { // EP3: r10 A10 unchanged
        val f = field()
        f.type("andaba tan lentl ")
        assertEquals("andaba tan lento ", f.text)
        f.type("⌫")
        assertEquals("andaba tan lentl", f.text)
    }

    @Test fun theUndoChipRestoresTheMarkToo() { // EP3: ↶ while the text ends with the fix + mark
        val f = field()
        f.type("que pillae.")
        assertEquals("que pillar.", f.text)
        assertEquals("pillae", f.engine.undoOffer(f.before))
        assertTrue(f.undoChip())
        assertEquals("que pillae.", f.text)
        val g = field()
        g.type("que pillae. ")
        assertNull("the undo is only right after: a space ended it", g.engine.undoOffer(g.before))
        g.type("⌫")
        assertEquals("a plain ⌫ then", "que pillar.", g.text)
    }

    @Test fun aNewLineUndoesLikeAMark() { // EP3
        val f = field()
        f.type("ni tubo que tuvom\n")
        assertEquals("ni tubo que tuvo\n", f.text)
        f.type("⌫")
        assertEquals("ni tubo que tuvom\n", f.text)
    }

    @Test fun aLateRetypeEndedByAMarkIsARejection() { // EP3 + LR1 at an ending
        val m = PersonalModel()
        val r = Rejections()
        val f = field(m, r)
        f.type("andaba tan lentl y ")
        assertEquals("andaba tan lento y ", f.text)
        f.type("⌫⌫⌫⌫⌫⌫⌫⌫") // "lento y " deleted
        assertEquals("andaba tan ", f.text)
        f.type("lentl,")
        assertEquals("andaba tan lentl,", f.text)
        assertEquals(listOf(Rejections.Entry("lentl", "lento")), r.strikes())
    }

    // ── EP4: r12 rows still hold ──────────────────────────────────────────
    @Test fun provisionalSpaceAndDoubleSpaceStillWork() { // EP4
        val e = KeyboardEngine().apply { start(FieldInfo(autoCap = false)); corrector = shipped.writing().corrector() }
        var t = Edits.apply("", e.pickSuggestion("Hola", ""))
        t = Edits.apply(t, e.press(Key(KeyType.CHAR, ",", ","), t, 0L))
        assertEquals("PS1: the pick's space gives way to the comma", "Hola, ", t)
        val d = field()
        d.type("andaba tan lentl  ")
        assertEquals("the first space corrects, the second makes the period", "andaba tan lento. ", d.text)
    }

    // ── EP5: a period inside a token ──────────────────────────────────────
    @Test fun aLetterRightAfterTheCorrectingPeriodWithdrawsIt() { // EP5
        val m = PersonalModel()
        val r = Rejections()
        val f = field(m, r)
        f.type("Mira pillae.")
        assertEquals("Mira pillar.", f.text)
        f.type("cl ")
        assertEquals("the period was inside a token", "Mira pillae.cl ", f.text)
        assertFalse("the fix is never learned", m.knows(Lang.ES, "pillar"))
        assertTrue("not a rejection", r.isEmpty())
        val g = field()
        g.type("que pillae. Y")
        assertEquals("a space after the period is a sentence end", "que pillar. Y", g.text)
    }

    // ── EP7: learning ─────────────────────────────────────────────────────
    @Test fun anEndingLearnsTheFixOnceAndItsUndoLearnsTheTypedWord() { // EP7
        val m = PersonalModel()
        val f = field(m)
        f.type("andaba tan lentl, pero ")
        assertEquals("learned once, when the space after the comma came", 1, m.countOf(Lang.ES, "tan", "lento"))
        assertEquals("the comma keeps the link to the next word", 1, m.countOf(Lang.ES, "lento", "pero"))
        val n = PersonalModel()
        val g = field(n)
        g.type("andaba tan lentl,⌫ y")
        assertEquals("andaba tan lentl, y", g.text)
        assertEquals(0, n.countOf(Lang.ES, "tan", "lento"))
        assertTrue("the typed word is learned once (TS3)", n.knows(Lang.ES, "lentl", 1) && !n.knows(Lang.ES, "lentl", 2))
        assertFalse("not kept after one rejection (TS1)", n.kept(Lang.ES, "lentl"))
    }

    // ── E5: a capital inside a word ───────────────────────────────────────
    @Test fun autoCapitalNeverFiresInsideAWord() { // MC1
        val e = KeyboardEngine().apply { start(FieldInfo(autoCap = true)) }
        e.updateAutoCap(true) // a stale caps-mode answer right after ⌫
        assertEquals(listOf<Out>(Out.Commit("a")), e.press(Key(KeyType.CHAR, "a", "a"), "Cachai, GrapheneOS no tr", 0L))
        e.updateAutoCap(true)
        assertEquals("a word start still gets it", listOf<Out>(Out.Commit("T")), e.press(Key(KeyType.CHAR, "t", "t"), "No. ", 0L))
        e.shiftDown(0L); e.shiftUp()
        assertEquals("a shift the user pressed is theirs (GrapheneOS)", listOf<Out>(Out.Commit("O")), e.press(Key(KeyType.CHAR, "o", "o"), "Graphene", 0L))
    }

    @Test fun aLearnedInnerCapitalIsAppliedOnlyToNonDictionaryWords() { // MC2
        val m = PersonalModel()
        repeat(2) { m.learn(Lang.ES, "no", "trAe", false); m.learn(Lang.ES, "uso", "GrapheneOS", false) }
        assertEquals("trAe", m.casing(listOf(Lang.ES), "trae", false)) // the evidence is there …
        val w = shipped.writing(personal = m)
        assertEquals("… but trae is a word: never trAe", "trae ", shipped.replay("GrapheneOS no ", "trae", " ", w))
        assertEquals("traae → trae, never trAe", "trae ", shipped.replay("GrapheneOS no ", "traae", " ", w))
        assertEquals("GrapheneOS is no dictionary word", "GrapheneOS ", shipped.replay("me gusta ", "grapheneos", " ", w))
        val es = shipped.lexicons.getValue(Lang.ES)
        assertFalse(es.casingFits("trAe"))
        assertTrue(es.casingFits("Pixel") && es.casingFits("GrapheneOS"))
        val bar = Bar.words("GrapheneOS no trae", "", false, Lang.ES, es, m, ShiftState.OFF)
        assertFalse("the bar never offers trAe: $bar", "trAe" in bar)
    }
}
