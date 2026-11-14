package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r13c E2 (TS1–TS7): a correction the user rejects is kept as typed in that field; only a second
 * rejection of the same typed → fix, in another field or session, makes it permanent (stored in
 * rejections.json, the word keep-marked). Every path: ⌫ right after, the ↶ chip, a late delete +
 * retype. Fields are driven exactly like ResystImeService drives them ([ShippedWriting.ImeField])
 * over the shipped corrector and assets (`lentl` → `lento`, the owner's WhatsApp report).
 *
 * Bite proof: on de1efa4 (0.9.0) one rejection keep-marks the word and stores the pair, so field B
 * no longer corrects: every row fails there (run with a shim giving de1efa4's semantics to the two
 * calls that did not exist yet — Rejections.strike = add + "for good", Learner.kept(mark) = kept).
 */
class Round13cStrikesTest {
    private val es = Lang.ES

    /** The owner's phone: one personal model and one rejection store across fields and sessions. */
    private class Phone {
        var model = PersonalModel()
        var store = Rejections()
        fun field(sameEditorAs: ShippedWriting.ImeField? = null) =
            ShippedWriting.ImeField(ShippedWriting.writing(model, store), model, store, sameEditorAs)

        /** The IME process restarts: everything comes back from words.json / rejections.json. */
        fun restart() { model = PersonalModel.fromJson(model.toJson()); store = Rejections.fromJson(store.toJson()) }
    }

    /** One rejection of `lentl → lento` in a fresh field, by [how]; returns the field. */
    private fun reject(p: Phone, how: String): ShippedWriting.ImeField {
        val f = p.field()
        when (how) {
            "backspace" -> { f.type("andaba tan lentl "); assertEquals("andaba tan lento ", f.text); f.type("⌫ ") }
            "chip" -> { f.type("andaba tan lentl "); assertEquals("andaba tan lento ", f.text); assertTrue(f.undoChip()) }
            "late" -> {
                f.type("andaba tan lentl y ")
                assertEquals("andaba tan lento y ", f.text)
                f.type("⌫".repeat("lento y ".length))
                f.type("lentl ")
            }
            "comma" -> { f.type("andaba tan lentl,"); assertEquals("andaba tan lento,", f.text); f.type("⌫ ") }
            else -> error(how)
        }
        assertEquals("$how: the typed word stays", "andaba tan lentl ", f.text.replace("lentl, ", "lentl "))
        return f
    }

    private fun corrects(p: Phone): Boolean = p.field().let { it.type("hoy lentl "); it.text == "hoy lento " }

    @Test fun oneRejectionKeepsTheWordInThatFieldOnly() { // TS1, TS3 — every path
        for (how in listOf("backspace", "chip", "late", "comma")) {
            val p = Phone()
            val a = reject(p, how)
            a.type("y lentl ")
            assertTrue("$how: field A keeps it: ${a.text}", a.text.endsWith("y lentl "))
            assertEquals("$how: a first strike, not a rejection", listOf(Rejections.Entry("lentl", "lento")), p.store.strikes())
            assertFalse("$how: space may still apply it elsewhere", p.store.rejects("lentl", "lento"))
            assertFalse("$how: not keep-marked", p.model.kept(es, "lentl"))
            assertTrue("$how: learned as an ordinary use", p.model.knows(es, "lentl", 1))
            assertTrue("$how: field B still corrects", corrects(p))
        }
    }

    @Test fun aSecondRejectionInAnotherFieldIsForGood() { // TS2 — every pair of paths
        for (first in listOf("backspace", "chip", "late", "comma")) for (second in listOf("backspace", "chip", "late", "comma")) {
            val p = Phone()
            reject(p, first)
            reject(p, second) // field B
            assertTrue("$first + $second: stored for good", p.store.rejects("lentl", "lento"))
            assertTrue("$first + $second: no strike left", p.store.strikes().isEmpty())
            assertTrue("$first + $second: keep-marked", p.model.kept(es, "lentl"))
            assertFalse("$first + $second: field C keeps the word", corrects(p))
            val json = p.store.toJson()
            assertTrue("rejections.json holds the pair: $json", json.contains("[\"lentl\",\"lento\"]") && Rejections.fromJson(json).rejects("lentl", "lento"))
        }
    }

    @Test fun theStrikeSurvivesARestart() { // TS2: "or later session"
        val p = Phone()
        reject(p, "backspace")
        p.restart()
        assertEquals(listOf(Rejections.Entry("lentl", "lento")), p.store.strikes())
        assertTrue("still corrected after the restart", corrects(p))
        reject(p, "chip")
        p.restart()
        assertTrue(p.store.rejects("lentl", "lento"))
        assertTrue(p.model.kept(es, "lentl"))
        assertFalse(corrects(p))
    }

    @Test fun comingBackToTheSameFieldKeepsItsProtection() { // TS1 (LR6 memory), TS6
        val p = Phone()
        val a = reject(p, "backspace")
        val again = p.field(sameEditorAs = a) // the same editor restarts / the user comes back
        again.type("hoy lentl ")
        assertEquals("hoy lentl ", again.text)
        assertEquals("no second strike from the same field", 1, p.store.strikes().size)
        assertFalse(p.store.rejects("lentl", "lento"))
    }

    @Test fun data090StaysValid() { // TS4: a 0.9.0 rejections.json (one rejection = for good) and its keep marks
        val old = Rejections.fromJson("{\"v\":1,\"rejected\":[[\"lentl\",\"lento\"]]}")
        assertTrue(old.rejects("lentl", "lento"))
        assertTrue("rejecting it again keeps it", old.strike("lentl", "lento"))
        assertEquals("{\"v\":1,\"rejected\":[[\"lentl\",\"lento\"]]}", old.toJson())
        val p = Phone()
        p.store = Rejections.fromJson("{\"v\":1,\"rejected\":[[\"lentl\",\"lento\"]]}")
        assertFalse("never applied again", corrects(p))
        val kept = PersonalModel.fromJson("{\"v\":2,\"clock\":1,\"langs\":{\"es\":{\"vocab\":[[\"bastiono\",\"bastiono\",1,1,1]],\"starters\":[],\"next\":[]}}}")
        assertTrue(kept.kept(es, "bastiono"))
        // 0.9.0 reads a 0.10.0 file: `rejected` only (MiniJson ignores `strikes`), so every permanent pair survives a downgrade
        val both = Rejections().apply { strike("lentl", "lento"); strike("lentl", "lento"); strike("teh", "the") }
        val json = both.toJson()
        assertEquals("{\"v\":1,\"rejected\":[[\"lentl\",\"lento\"]],\"strikes\":[[\"teh\",\"the\"]]}", json)
        val root = MiniJson.parse(json) as Map<*, *>
        assertEquals(listOf(listOf("lentl", "lento")), root["rejected"])
    }

    @Test fun strikesAreBoundedForgottenAndCounted() { // TS5
        val r = Rejections()
        for (i in 0 until Rejections.CAP + 5) r.strike("typo${'a' + i % 26}${'a' + i / 26}", "fix")
        assertEquals(Rejections.CAP, r.strikes().size)
        assertEquals("the wipe row counts strikes too", Rejections.CAP, r.size)
        val e = r.strikes().first()
        assertTrue(r.forget(e.typed, e.fix))
        assertEquals(Rejections.CAP - 1, r.size)
        r.clear()
        assertTrue(r.isEmpty())
        assertEquals("1 palabra · 1 corrección rechazada · nada sale del teléfono", PersonalSummary.text(PersonalSummary.Counts(1, 0, 0, 0, Rejections().apply { strike("lentl", "lento") }.size)))
        val settings = java.io.File("src/main/kotlin/com/resyst/vk/settings/SettingsActivity.kt").readText()
        assertTrue("\"Lo que sé de ti\" lists the strikes with ✕", settings.contains("for (e in once) knowRow(") && settings.contains("r.strikes()"))
    }

    @Test fun aClosedWriteGateStoresNothingButTheFieldStillKeepsTheWord() { // TS5 (X1–X3)
        val f = ShippedWriting.ImeField(ShippedWriting.writing(), null, null)
        f.type("andaba tan lentl ⌫ y lentl ")
        assertEquals("andaba tan lentl y lentl ", f.text)
    }

    @Test fun theBarStillOffersARejectedFix() { // TS7
        val r = Rejections().apply { strike("lentl", "lento"); strike("lentl", "lento") }
        val es = ShippedWriting.lexicons.getValue(Lang.ES)
        assertTrue(Bar.words("andaba tan lentl", "", false, Lang.ES, es, null, ShiftState.OFF).contains("lento"))
        assertEquals("lentl ", ShippedWriting.replay("andaba tan ", "lentl", " ", ShippedWriting.writing(rejections = r)))
    }
}
