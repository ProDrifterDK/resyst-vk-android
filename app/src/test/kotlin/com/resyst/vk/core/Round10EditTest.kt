package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Failure modes W1–W5, U1–U2, O1–O3 (docs/failure-modes.md, round 10 text editing). */
class Round10EditTest {

    private fun del(t: String) = t.dropLast(TextEdit.wordBefore(t))

    @Test fun deleteWordTakesExactlyThePreviousWord() { // W1
        assertEquals("hola ", del("hola mundo"))
        assertEquals("hola ", del("hola mundo "))
        assertEquals("hola ", del("hola mundo   "))
        assertEquals("", del("mundo"))
        assertEquals("dijo ", del("dijo don't"))
        assertEquals("hola mundo", del("hola mundo, "))  // punctuation first, then the word on the next press
        assertEquals("hola ", del(del("hola mundo, ")))
        assertEquals("¿qué ", del("¿qué tal"))
    }

    @Test fun deleteWordEdgeCases() { // W2
        assertEquals(0, TextEdit.wordBefore(""))
        assertEquals(3, TextEdit.wordBefore("   "))
        assertEquals("a ", del("a ..."))
        // an emoji is never split: the pair goes as one run of non-word chars
        val e = "hola 😀"
        val rest = del(e)
        assertEquals("hola ", rest)
        val n = TextEdit.wordBefore("x😀")
        assertTrue(n == 2) // whole pair, not half
    }

    @Test fun heldBackspaceSwitchesToWordsOnlyAfterEightRepeats() { // W3
        val t = "una frase larga"
        for (r in 0 until TextEdit.WORD_AFTER_REPEATS) assertEquals("repeat $r", 1, TextEdit.backspaceSpan(t, r, secret = false))
        assertEquals("larga".length, TextEdit.backspaceSpan(t, TextEdit.WORD_AFTER_REPEATS, secret = false))
        assertEquals(2, TextEdit.backspaceSpan("a😀", 0, secret = false)) // surrogate pair stays whole
        assertEquals(0, TextEdit.backspaceSpan("", 20, secret = false))
    }

    @Test fun secretFieldsLoseOneCharacterPerPress() { // W4
        for (r in listOf(0, 8, 30)) assertEquals(1, TextEdit.backspaceSpan("hunter2 secret", r, secret = true))
        // the service routes the panel's delete-word through the same rule
        val src = File(srcRoot(), "ime/ResystImeService.kt").readText()
        val del = src.substringAfter("EditOp.DELETE_WORD ->").substringBefore("EditOp.").substringBefore("feedback(null)")
        assertTrue(del.contains("policy.secret"))
        val bs = src.substringAfter("if (key.type == KeyType.BACKSPACE) {").substringBefore("}")
        assertTrue(bs.contains("policy.secret"))
    }

    @Test fun wordModeResetsOnEveryNewPress() { // W5 (source guard: onKeyDown zeroes the counter)
        val src = File(srcRoot(), "ime/ResystImeService.kt").readText()
        val down = src.substringAfter("override fun onKeyDown(key: Key) {").substringBefore("feedback(key)")
        assertTrue(down.contains("bsRepeats = 0"))
    }

    // ── ↶ undo offer ────────────────────────────────────────────────────
    private val space = Key(KeyType.SPACE, "", " ")
    private val a = Key(KeyType.CHAR, "a", "a")

    private fun engine() = KeyboardEngine().apply {
        corrector = Corrector { w, _ -> if (w == "teh") "the" else null }
    }

    @Test fun undoOfferLivesExactlyOneEdit() { // U1
        val e = engine()
        assertNull(e.undoOffer("teh"))
        val outs = e.press(space, "hi teh", 0)
        val after = Edits.apply("hi teh", outs)
        assertEquals("hi the ", after)
        assertEquals("teh", e.undoOffer(after))
        assertNull("cursor moved / text changed", e.undoOffer("hi the x"))
        e.press(a, after, 1)
        assertNull("next key clears it", e.undoOffer(after + "a"))
        // no correction → no offer
        val e2 = engine()
        val plain = Edits.apply("hi ok", e2.press(space, "hi ok", 0))
        assertNull(e2.undoOffer(plain))
    }

    @Test fun undoRestoresTypedWordAndSticks() { // U2
        val e = engine()
        val after = Edits.apply("hi teh", e.press(space, "hi teh", 0))
        val undo = e.undoCorrection(after)
        assertNotNull(undo)
        val restored = Edits.apply(after, undo!!)
        assertEquals("hi teh ", restored)
        assertNull(e.undoOffer(restored))
        // the restored word is not corrected again by the next space-after-it
        val again = e.press(space, "hi teh", 10)
        assertEquals("hi teh ", Edits.apply("hi teh", again))
        assertNull("only once", e.undoCorrection(restored))
    }

    @Test fun undoLabelIsNeverALexiconWord() { // U1: the bar can tell the offer from a suggestion
        assertTrue(TextEdit.UNDO_PREFIX.isNotBlank() && !TextEdit.UNDO_PREFIX.first().isLetter())
    }

    // ── ¿ ¡ openers ─────────────────────────────────────────────────────
    @Test fun openerOfferedOnlyForAnUnopenedSentence() { // O1
        assertEquals(TextEdit.Opener(0, '¿', '?'), TextEdit.opener("Qué hora es?", false))
        assertEquals(TextEdit.Opener(0, '¡', '!'), TextEdit.opener("Qué bien!", false))
        assertNull(TextEdit.opener("¿Qué hora es?", false))
        assertNull(TextEdit.opener("¡Qué bien!", false))
        assertNull(TextEdit.opener("?", false))
        assertNull(TextEdit.opener("Hola.", false))
        assertNull(TextEdit.opener("https://x.cl/a?", false))
        assertNull(TextEdit.opener("a=b?", false))
        assertNull(TextEdit.opener("ok ???", false))
        assertNull(TextEdit.opener("", false))
    }

    @Test fun openerGoesAtTheStartOfThisSentence() { // O2
        val t = "Hola. Cómo estás?"
        val o = TextEdit.opener(t, false)!!
        assertEquals(6, o.at)
        assertEquals("Hola. ¿Cómo estás?", Edits.apply(t, TextEdit.applyOpener(t, o)))
        val nl = "Línea uno\n  Vienes mañana?"
        assertEquals("Línea uno\n  ¿Vienes mañana?", Edits.apply(nl, TextEdit.applyOpener(nl, TextEdit.opener(nl, false)!!)))
        val prevQ = "¿Vienes? Y tú?"
        assertEquals("¿Vienes? ¿Y tú?", Edits.apply(prevQ, TextEdit.applyOpener(prevQ, TextEdit.opener(prevQ, false)!!)))
        assertEquals("¿…?", TextEdit.openerLabel(TextEdit.opener("Y tú?", false)!!))
    }

    @Test fun openerNeedsTheSentenceStartInView() { // O3
        val long = "x".repeat(10) + " palabra tras palabra sin punto?"
        assertNull(TextEdit.opener(long, windowFull = true))
        assertNotNull(TextEdit.opener("Antes. Y esto?", windowFull = true))
    }

    @Test fun openerSettingIsSpanishProseAndOffInCodeMode() { // O1 settings side
        assertTrue(KbSettings().autoOpeners)
        assertFalse(Mode.CODE.apply(KbSettings()).autoOpeners)
        val st = ProfileCodec.seed().updatePhone { it.copy(autoOpeners = false) }
        assertFalse(ProfileCodec.decode(ProfileCodec.encode(st)).settings.autoOpeners)
        assertEquals("escritura", SettingsIA.pageOf(Ctl.AUTO_OPENERS)?.id)
        val src = File(srcRoot(), "ime/ResystImeService.kt").readText()
        val gate = src.substringAfter("openerOffer = if (").substringBefore(")")
        assertTrue(gate.contains("Lang.ES") && gate.contains("policy.secret") && gate.contains("proseField") && gate.contains("autoOpeners"))
    }

    private fun srcRoot(): File = listOf(File("src/main/kotlin/com/resyst/vk"), File("app/src/main/kotlin/com/resyst/vk")).first { it.exists() }
}
