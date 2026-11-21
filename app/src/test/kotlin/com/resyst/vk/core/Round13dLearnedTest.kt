package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r13d (OR1–OR6): in a field that opted out of suggestions (Google Keep's note body: TEXT | CAP_SENTENCES |
 * MULTI_LINE | NO_SUGGESTIONS) the learned model is READ — learned casing, learned words as correction
 * targets, completions — and NOTHING typed there is written. Incognito and secret fields read nothing.
 *
 * Fields are driven like ResystImeService drives them, with its two gates taken from the real
 * [FieldPolicy]: the read gate (`offerWords`: the corrector's personal model and rejections) and the
 * write gate (`personalWords`: the learner, strikes, rejected pairs, keep marks).
 *
 * Bite proof: on 361d784 (0.10.0) `gtapheneos` stays as typed in every field (learned words are never
 * correction targets).
 */
class Round13dLearnedTest {
    private val on = KbSettings()
    private val text = FieldPolicy.CLASS_TEXT or FieldPolicy.FLAG_CAP_SENTENCES or FieldPolicy.FLAG_AUTO_CORRECT
    /** Google Keep's note body (s3): prose that opted out of suggestions. */
    private val keep = FieldPolicy.CLASS_TEXT or FieldPolicy.FLAG_CAP_SENTENCES or FieldPolicy.FLAG_MULTI_LINE or FieldPolicy.FLAG_NO_SUGGESTIONS

    /** One text field with the IME's gates: [policy] decides what the corrector reads and what the learner writes. */
    private class GatedField(private val policy: FieldPolicy, private val s: KbSettings, private val model: PersonalModel, private val store: Rejections) {
        private val read = model.takeIf { policy.offerWords(s) }
        private val write: PersonalModel? get() = model.takeIf { policy.personalWords(s) }
        private val w = ShippedWriting.writing(read, store.takeIf { policy.offerWords(s) }).also { it.suggestField = policy.suggestions }
        private val engine = KeyboardEngine()
        private val learner = Learner { write }
        private var learnedLang = Lang.ES
        var text = ""

        init {
            engine.start(FieldInfo(policy.kind, multiLine = true, action = ImeAction.NONE, autoCap = policy.kind == FieldKind.TEXT))
            engine.corrector = w.corrector()
        }

        fun type(s: String) {
            for (c in s) {
                val k = when (c) {
                    ' ' -> Key(KeyType.SPACE, " ")
                    '⌫' -> Key(KeyType.BACKSPACE, "⌫")
                    else -> Key(KeyType.CHAR, c.toString(), c.toString())
                }
                engine.updateAutoCap(ShippedWriting.capsHint(text))
                val b = text
                val ends = k.type == KeyType.SPACE || (k.type == KeyType.CHAR && KeyboardEngine.endsWord(k.text))
                if (ends) w.lang = w.detect(b, b.length >= ShippedWriting.WINDOW)
                val outs = engine.press(k, b, 0L, "")
                text = Edits.apply(b, outs)
                val kept = engine.takeReverted()
                val forGood = rejected(ends)
                val kind = if (k.type == KeyType.BACKSPACE) Learner.Edit.BACKSPACE else Learner.Edit.KEY
                if (kept != null) learner.kept(learnedLang, learnedLang, b, outs, false, kept, mark = forGood)
                else {
                    val lang = if (kind == Learner.Edit.BACKSPACE) learnedLang else w.lang
                    learner.afterEdit(lang, b, outs, windowFull = b.length >= ShippedWriting.WINDOW, kind = kind)
                    if (kind != Learner.Edit.BACKSPACE) learnedLang = w.lang
                }
            }
        }

        /** ResystImeService.rejected: the write gate, then a strike (or a rejected pair). */
        private fun rejected(late: Boolean): Boolean {
            val (typed, fix) = engine.takeRejection() ?: return false
            val m = write ?: return false
            if (late) m.unlearn(learnedLang, null, fix, false)
            return store.strike(typed, fix)
        }
    }

    /** The owner's phone: GrapheneOS, Bastión and cachai taught in ordinary fields, as he writes them. */
    private fun taught(): Pair<PersonalModel, Rejections> {
        val m = PersonalModel()
        val r = Rejections()
        val p = FieldPolicy.of(text, 0)
        assertTrue(p.personalWords(on))
        GatedField(p, on, m, r).type("uso GrapheneOS en el Pixel y GrapheneOS no trae nada. el Bastión sigue y el Bastión guarda todo, cachai que cachai ")
        GatedField(p, on, m, r).type("me gusta GrapheneOS ")
        assertTrue(m.owns(listOf(Lang.ES, Lang.EN), "GrapheneOS", Bar.HABIT))
        assertEquals("GrapheneOS", m.casing(listOf(Lang.ES, Lang.EN), "grapheneos", false))
        return m to r
    }

    @Test fun optedOutFieldReadsTheLearnedWords() { // OR1, OR2 — s3: Keep's note body
        val (m, r) = taught()
        val keepField = FieldPolicy.of(keep, 0)
        assertTrue(keepField.optedOut && keepField.suggestions && keepField.offerWords(on) && !keepField.personalWords(on))
        val f = GatedField(keepField, on, m, r)
        f.type("cachai, grapheneos no trae ")
        assertEquals("learned casing (R5)", "Cachai, GrapheneOS no trae ", f.text)
        f.type("y gtapheneos ")
        assertEquals("a learned word is a correction target (OR1)", "Cachai, GrapheneOS no trae y GrapheneOS ", f.text)
        val g = GatedField(keepField, on, m, r)
        g.type("gtapheneos me gusta ")
        assertEquals("the sentence-start capital", "GrapheneOS me gusta ", g.text)
    }

    @Test fun optedOutFieldWritesNothing() { // OR3: words.json / rejections.json byte for byte
        val (m, r) = taught()
        r.strike("lentl", "lento") // an earlier strike from another field
        val words = m.toJson().toByteArray(Charsets.UTF_8)
        val rejections = r.toJson().toByteArray(Charsets.UTF_8)
        val f = GatedField(FieldPolicy.of(keep, 0), on, m, r)
        f.type("hoy escribo palabras nuevas como zorglub y zorglub, también GrapheneOS y grapheneos y Pixel ")
        f.type("andaba tan lentl ")
        assertTrue(f.text, f.text.endsWith("tan lento "))
        f.type("⌫ ") // rejected right after: a strike in a learning field, nothing here
        f.type("y gtapheneos ")
        f.type("⌫ ") // a learned-target fix rejected
        f.type("otra vez lentl y gtapheneos ")
        assertTrue("words.json unchanged", words.contentEquals(m.toJson().toByteArray(Charsets.UTF_8)))
        assertTrue("rejections.json unchanged", rejections.contentEquals(r.toJson().toByteArray(Charsets.UTF_8)))
        assertFalse(m.knows(Lang.ES, "zorglub"))
    }

    @Test fun incognitoAndSecretFieldsReadNothing() { // OR2
        val (m, r) = taught()
        val incognito = FieldPolicy.of(keep and FieldPolicy.FLAG_NO_SUGGESTIONS.inv(), FieldPolicy.IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertFalse(incognito.offerWords(on) || incognito.personalWords(on))
        val f = GatedField(incognito, on, m, r)
        f.type("cachai, grapheneos y gtapheneos ")
        assertEquals("Cachai, grapheneos y gtapheneos ", f.text)
        val keepIncognito = FieldPolicy.of(keep, FieldPolicy.IME_FLAG_NO_PERSONALIZED_LEARNING)
        val k = GatedField(keepIncognito, on, m, r)
        k.type("grapheneos y gtapheneos ")
        assertEquals("Grapheneos y gtapheneos ", k.text)
        val secret = FieldPolicy.of(FieldPolicy.CLASS_TEXT or FieldPolicy.VARIATION_PASSWORD, 0)
        assertTrue(secret.secret && !secret.offerWords(on))
        val pw = GatedField(secret, on, m, r)
        pw.type("grapheneos gtapheneos ")
        assertEquals("grapheneos gtapheneos ", pw.text)
    }

    @Test fun theChipSaysWhatTheFieldDoes() { // OR6
        val n = MemoryNotice.reason(FieldPolicy.of(keep, 0), on, on)
        assertEquals(NoMemory.OPTED_OUT, n)
        val d = n!!.label(TestTxt.ES) + " " + n.detail(TestTxt.ES)
        assertTrue(d, d.contains("no guarda", ignoreCase = true) && d.contains("aprendió"))
        assertFalse(d, d.contains("no aprende de este campo"))
        assertNull(MemoryNotice.reason(FieldPolicy.of(text, 0), on, on))
    }
}
