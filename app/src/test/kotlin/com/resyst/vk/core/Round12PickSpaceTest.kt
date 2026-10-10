package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r12 (PS1–PS7, docs/failure-modes.md): the space a suggestion pick adds is provisional. An
 * editor simulation drives the real [KeyboardEngine] + [Learner] the way the IME does (text before
 * the cursor → press / pick / variant → outs → learner). Each test names the failure it catches.
 */
class Round12PickSpaceTest {
    private val es = Lang.ES

    private class Sim(kind: FieldKind = FieldKind.TEXT, val model: PersonalModel = PersonalModel()) {
        var text = ""
        var last: List<Out> = emptyList()
        var now = 10_000L
        val engine = KeyboardEngine().apply { start(FieldInfo(kind, false, ImeAction.SEND, autoCap = false)) }
        val learner = Learner { model }

        private fun apply(outs: List<Out>, kind: Learner.Edit) {
            val before = text
            last = outs
            text = Edits.apply(text, outs)
            learner.afterEdit(Lang.ES, before, outs, windowFull = false, kind = kind)
        }

        fun key(k: Key, gapMs: Long = 2_000) {
            now += gapMs
            apply(engine.press(k, text, now), if (k.type == KeyType.BACKSPACE) Learner.Edit.BACKSPACE else Learner.Edit.KEY)
        }

        fun type(s: String, gapMs: Long = 2_000) = s.forEach { c ->
            key(if (c == ' ') SPACE else Key(KeyType.CHAR, c.toString(), c.toString()), gapMs)
        }

        fun space(gapMs: Long = 2_000) = key(SPACE, gapMs)
        fun backspace() = key(Key(KeyType.BACKSPACE, "⌫"))
        fun variant(v: String) = apply(engine.variant(v, text), Learner.Edit.KEY)
        fun pick(word: String) = apply(engine.pickSuggestion(word, Suggest.currentWord(text)), Learner.Edit.PICK)
    }

    private companion object {
        val SPACE = Key(KeyType.SPACE, "", width = 4f)
        val SHIFT = Key(KeyType.SHIFT, "⇧")
        val SYMBOLS = Key(KeyType.LAYER, "?123", target = Layer.SYMBOLS)
    }

    // PS1: the r4 device row ("Hola , cómo vas") in the engine
    @Test fun aCommaAfterAPickTakesThePicksSpace() {
        val s = Sim()
        s.type("Hol")
        s.pick("Hola")
        assertEquals("Hola ", s.text)
        s.type(",")
        assertEquals("one batch: the provisional space goes, the comma and a new space come", listOf(Out.DeleteBefore(1), Out.Commit(", ")), s.last)
        assertEquals("Hola, ", s.text)
    }

    // PS1: every closing mark, and nothing else, swaps
    @Test fun exactlyTheClosingMarksSwap() {
        for (m in listOf(",", ".", ";", ":", "!", "?", ")", "]", "}", "»", "”", "…")) {
            val s = Sim(); s.pick("Hola"); s.type(m)
            assertEquals("closing '$m'", "Hola$m ", s.text)
        }
        for (m in listOf("¿", "¡", "(", "«", "“", "\"", "@", "/", "-", "'", "a", "7", "😀")) {
            val s = Sim(); s.pick("Hola"); s.type(m)
            assertEquals("not closing '$m': committed after the space, nothing deleted", "Hola $m", s.text)
        }
    }

    // PS1: a closing long-press variant (. → …) swaps too; a non-closing one doesn't
    @Test fun aClosingVariantSwaps() {
        val s = Sim(); s.pick("vamos"); s.variant("…")
        assertEquals("vamos… ", s.text)
        val o = Sim(); o.pick("vamos"); o.variant("¿")
        assertEquals("vamos ¿", o.text)
    }

    // PS2: no second space after a pick or a swap; the absorbing press ends the provisional state
    @Test fun aSpaceAfterAPickOrASwapIsAbsorbedOnce() {
        val s = Sim(); s.pick("Hola"); s.type(","); s.space()
        assertEquals("nothing committed", emptyList<Out>(), s.last)
        assertEquals("Hola, ", s.text)
        s.space()
        assertEquals("the next space is a normal one", "Hola,  ", s.text)
        val p = Sim(); p.pick("Hola"); p.space()
        assertEquals("Hola ", p.text)
    }

    // PS3: double-space period never arms off a provisional space (pick, swap, or the absorbed press)
    @Test fun doubleSpacePeriodNeverArmsOffAProvisionalSpace() {
        val a = Sim(); a.pick("Hola"); a.space(gapMs = 200); a.space(gapMs = 200)
        assertFalse("pick + space + space within 1.5 s: '${a.text}'", a.text.contains('.'))
        val b = Sim(); b.pick("Hola"); b.type(",", gapMs = 200); b.space(gapMs = 200); b.space(gapMs = 200)
        assertFalse("swap + space + space: '${b.text}'", b.text.contains(". "))
        // a typed word still gets its double-space period (nothing else changed)
        val c = Sim(); c.type("hola"); c.space(gapMs = 200); c.space(gapMs = 200)
        assertEquals("hola. ", c.text)
    }

    // PS4: any other key ends it; a later closing mark is committed as typed
    @Test fun otherKeysEndTheProvisionalState() {
        val letter = Sim(); letter.pick("Hola"); letter.type("y,")
        assertEquals("Hola y,", letter.text)
        val opener = Sim(); opener.pick("Hola"); opener.type("¿?")
        assertEquals("Hola ¿?", opener.text)
        val enter = Sim(); enter.pick("Hola"); enter.key(Key(KeyType.ENTER, "⏎")); enter.text += "\n"; enter.type(",")
        assertTrue("enter ended it: '${enter.text}'", enter.text.endsWith("\n,"))
        val bs = Sim(); bs.pick("Hola"); bs.backspace(); bs.type(",")
        assertEquals("⌫ ended it", "Hola,", bs.text)
        val bs2 = Sim(); bs2.pick("Hola"); bs2.backspace(); bs2.space(); bs2.type(",")
        assertEquals("a space typed again is a normal space", "Hola ,", bs2.text)
    }

    // PS4: shift and ?123 keep it (pick → ?123 → '?' is the common way to reach '?')
    @Test fun shiftAndTheLayerKeyKeepIt() {
        val s = Sim(); s.pick("Hola"); s.key(SYMBOLS); s.type("?")
        assertEquals("Hola? ", s.text)
        val t = Sim(); t.pick("Hola"); t.key(SHIFT); t.type(".")
        assertEquals("Hola. ", t.text)
    }

    // PS4: a cursor move, a selection, an outside edit or a panel (endProvisional) end it
    @Test fun cursorMovesAndOutsideEditsEndIt() {
        val moved = Sim(); moved.pick("Hola")
        moved.engine.selectionChanged("Ho", 2, 2) // the user tapped inside the word
        moved.engine.selectionChanged("Hola ", 5, 5) // and came back: not revived
        moved.type(",")
        assertEquals("Hola ,", moved.text)
        val sel = Sim(); sel.pick("Hola")
        sel.engine.selectionChanged("Hola ", 0, 5)
        sel.type(",")
        assertEquals("Hola ,", sel.text)
        val ours = Sim(); ours.pick("Hola")
        ours.engine.selectionChanged("Hola ", 5, 5) // the editor reporting the pick itself
        ours.type(",")
        assertEquals("our own edit's selection update keeps it", "Hola, ", ours.text)
        val panel = Sim(); panel.pick("Hola"); panel.engine.endProvisional(); panel.type(",")
        assertEquals("Hola ,", panel.text)
        val rewritten = Sim(); rewritten.pick("Hola"); rewritten.text = "Hola x"; rewritten.type(",")
        assertEquals("the text no longer ends with the pick: nothing deleted", "Hola x,", rewritten.text)
        val field = Sim(); field.pick("Hola"); field.engine.start(FieldInfo()); field.type(",")
        assertEquals("a field change ends it", "Hola ,", field.text)
    }

    // PS5: ⌫ after a swap deletes one character, it does not undo the swap
    @Test fun backspaceAfterASwapDeletesNormally() {
        val s = Sim(); s.pick("Hola"); s.type(","); s.backspace()
        assertEquals(listOf(Out.Backspace), s.last)
        assertEquals("Hola,", s.text)
    }

    // PS6: never outside plain text fields
    @Test fun neverInUrlEmailNumberPhoneOrPasswordFields() {
        for (k in listOf(FieldKind.URL, FieldKind.EMAIL, FieldKind.NUMBER, FieldKind.PHONE, FieldKind.PASSWORD)) {
            val s = Sim(k); s.pick("hola"); s.type(".")
            assertEquals("$k", "hola .", s.text)
            assertFalse("$k", s.engine.hasProvisional)
        }
    }

    // PS7: the swap and the absorbed space learn nothing; the next word's context is right
    @Test fun theSwapLearnsNothingNewAndKeepsTheContext() {
        val s = Sim()
        s.pick("Hola"); s.type(","); s.space()
        s.pick("cómo"); s.type("?"); s.space()
        s.pick("vas"); s.type(".")
        assertEquals("Hola, cómo? vas. ", s.text)
        val counts = s.model.words(es, 10).associate { it.key to it.count }
        assertEquals("each word learned once: the swap and the absorbed space add nothing", mapOf("hola" to 1, "cómo" to 1, "vas" to 1), counts)
        assertEquals("the comma keeps the link hola → cómo", 1, s.model.countOf(es, "hola", "cómo"))
        assertEquals("'?' starts a sentence: vas is a starter, not after cómo", 0, s.model.countOf(es, "cómo", "vas"))
        assertTrue("vas learned at a sentence start", "vas" in s.model.predict(es, null, true, 5))
        assertEquals("three words, each once", 3, s.model.vocabCount(es))
        val json = s.model.toJson()
        assertFalse("no punctuation token learned: $json", json.contains("hola,") || json.contains("cómo?") || json.contains("vas."))
    }

    // PS7: a word learned by the pick is still taken back by ⌫ right after the pick (M10 unchanged)
    @Test fun backspaceRightAfterThePickStillUnlearns() {
        val s = Sim()
        s.pick("Hola")
        assertEquals(1, s.model.vocabCount(es))
        s.backspace()
        assertEquals(0, s.model.vocabCount(es))
    }
}
