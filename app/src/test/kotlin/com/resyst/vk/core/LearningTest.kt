package com.resyst.vk.core

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 4 — the learning hook: an editor simulation drives the real [KeyboardEngine] the way
 * the IME service does (text before the cursor → press → outs → [Learner]). Ids map to
 * docs/failure-modes.md.
 */
class LearningTest {
    private val es = Lang.ES

    /** A one-field editor: the text before the cursor, the engine, the learner. */
    class Sim(val model: PersonalModel = PersonalModel(), val gate: Boolean = true, corrector: Corrector? = null) {
        var text = ""
        val engine = KeyboardEngine().apply { this.corrector = corrector; start(FieldInfo(FieldKind.TEXT, false, ImeAction.SEND, autoCap = false)) }
        val learner = Learner { model.takeIf { gate } }
        private var now = 10_000L

        private fun apply(outs: List<Out>, kind: Learner.Edit) {
            val before = text
            text = Edits.apply(text, outs)
            learner.afterEdit(Lang.ES, before, outs, windowFull = false, kind = kind)
        }

        private fun press(key: Key) {
            now += 2_000 // slow typist: never a double-space period by accident
            val kind = if (key.type == KeyType.BACKSPACE) Learner.Edit.BACKSPACE else Learner.Edit.KEY
            apply(engine.press(key, text, now), kind)
        }

        fun type(s: String) = s.forEach { c ->
            press(if (c == ' ') Key(KeyType.SPACE, " ") else Key(KeyType.CHAR, c.toString(), c.toString()))
        }

        fun backspace() = press(Key(KeyType.BACKSPACE, "⌫"))
        fun send() = press(Key(KeyType.ENTER, "➤"))
        fun pick(word: String) = apply(engine.pickSuggestion(word, Suggest.currentWord(text)), Learner.Edit.PICK)
    }

    @Test fun editsReplayOuts() {
        assertEquals("hola", Edits.apply("hol", listOf(Out.Commit("a"))))
        assertEquals("casa ", Edits.apply("casaa", listOf(Out.DeleteBefore(5), Out.Commit("casa "))))
        assertEquals("ho", Edits.apply("hol", listOf(Out.Backspace)))
        assertEquals("", Edits.apply("a", listOf(Out.DeleteBefore(9))))
        assertEquals("x😀", Edits.apply("x😀😀", listOf(Out.Backspace)))
        assertEquals("ok", Edits.apply("ok", listOf(Out.Action(ImeAction.SEND), Out.EnterKey)))
    }

    @Test fun spaceFinishesAWordOnceCommasKeepTheLink() { // M1
        val s = Sim()
        s.type("hola, cómo  estás")
        s.type(" ")
        assertEquals(listOf("cómo"), s.model.predict(es, "hola", false, 3))
        assertEquals(listOf("estás"), s.model.predict(es, "cómo", false, 3))
        assertEquals(1, s.model.countOf(es, "hola", "cómo")) // the second space didn't learn again
        assertEquals(listOf("hola"), s.model.predict(es, null, true, 3))
    }

    @Test fun picksAndPredictionsAreLearnedWithTheirContext() { // M6
        val s = Sim()
        s.type("hola, ")
        s.pick("cómo") // a prediction: nothing typed yet
        s.type("est")
        s.pick("estás") // a completion
        assertEquals("hola, cómo estás ", s.text)
        assertEquals(listOf("cómo"), s.model.predict(es, "hola", false, 3))
        assertEquals(listOf("estás"), s.model.predict(es, "cómo", false, 3))
    }

    @Test fun theSendActionFinishesTheLastWord() { // M1
        val s = Sim()
        s.type("nos vemos")
        s.send()
        assertEquals(listOf("vemos"), s.model.predict(es, "nos", false, 3))
    }

    @Test fun spaceCorrectionLearnsTheCorrectedWordAndRevertUnlearnsIt() { // M10
        val s = Sim(corrector = Corrector { w, _ -> if (w == "csa") "casa" else null })
        s.type("mi csa ")
        assertEquals("mi casa ", s.text)
        assertTrue(s.model.knows(es, "casa"))
        s.backspace() // ⌫ right after the correction restores "csa"
        assertEquals("mi csa", s.text)
        assertFalse(s.model.knows(es, "casa"))
        assertTrue(s.model.predict(es, "mi", false, 3).isEmpty())
        s.type(" ") // the user insists: their word is learned
        assertTrue(s.model.knows(es, "csa"))
    }

    @Test fun deletingTheSpaceAndRetypingCountsOnce() { // M10
        val s = Sim()
        s.type("buenos días ")
        s.backspace()
        s.type(" ")
        assertEquals(1, s.model.countOf(es, "buenos", "días"))
    }

    @Test fun urlsAndEmailsInTextAreNotLearned() { // M5
        val s = Sim()
        s.type("escribe a user@example.com o www.example.com hoy ")
        assertFalse(s.model.knows(es, "www"))
        assertFalse(s.model.knows(es, "example"))
        assertFalse(s.model.knows(es, "user"))
        assertTrue(s.model.predict(es, "a", false, 3).isEmpty())
        // the email is no context: "o" follows nothing learnable
        assertTrue(s.model.knows(es, "hoy"))
    }

    @Test fun closedGateNeverLearns() { // X1 + X3 (learner side)
        val s = Sim(gate = false)
        s.type("hola, cómo estás ")
        s.pick("bien")
        s.send()
        assertTrue(s.model.isEmpty())
    }

    // ── FieldPolicy: the privacy gate ───────────────────────────────────
    private val on = KbSettings()
    private val text = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES

    @Test fun pinnedConstantsMatchThePlatform() {
        assertEquals(InputType.TYPE_MASK_CLASS, FieldPolicy.MASK_CLASS)
        assertEquals(InputType.TYPE_MASK_VARIATION, FieldPolicy.MASK_VARIATION)
        assertEquals(InputType.TYPE_CLASS_TEXT, FieldPolicy.CLASS_TEXT)
        assertEquals(InputType.TYPE_CLASS_NUMBER, FieldPolicy.CLASS_NUMBER)
        assertEquals(InputType.TYPE_CLASS_PHONE, FieldPolicy.CLASS_PHONE)
        assertEquals(InputType.TYPE_CLASS_DATETIME, FieldPolicy.CLASS_DATETIME)
        assertEquals(InputType.TYPE_TEXT_VARIATION_URI, FieldPolicy.VARIATION_URI)
        assertEquals(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, FieldPolicy.VARIATION_EMAIL)
        assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, FieldPolicy.VARIATION_PASSWORD)
        assertEquals(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, FieldPolicy.VARIATION_VISIBLE_PASSWORD)
        assertEquals(InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, FieldPolicy.VARIATION_WEB_EMAIL)
        assertEquals(InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, FieldPolicy.VARIATION_WEB_PASSWORD)
        assertEquals(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, FieldPolicy.FLAG_NO_SUGGESTIONS)
        assertEquals(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, FieldPolicy.IME_FLAG_NO_PERSONALIZED_LEARNING)
    }

    @Test fun passwordFieldsNeverLearnNorSuggestPersonalData() { // X1
        val secret = listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_PHONE,
        )
        for (t in secret) {
            val p = FieldPolicy.of(t, 0)
            assertFalse("$t suggestions", p.suggestions)
            assertFalse("$t words", p.personalWords(on))
            assertFalse("$t values", p.personalValues(on))
        }
        assertEquals(FieldKind.PASSWORD, FieldPolicy.of(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, 0).kind)
    }

    @Test fun noSuggestionsAndIncognitoFieldsAreClosed() { // X1 + X2
        val noSugg = FieldPolicy.of(text or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, 0)
        assertFalse(noSugg.suggestions || noSugg.personalWords(on))
        val incognito = FieldPolicy.of(text, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_SEND)
        assertTrue(incognito.suggestions) // the static lexicon is not personal data
        assertFalse(incognito.personalWords(on))
        val email = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        assertFalse(FieldPolicy.of(email, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING).personalValues(on))
        // By design: email fields routinely carry NO_SUGGESTIONS just to stop autocorrect from
        // mangling the address — that flag silences word suggestions, not the value memory.
        val emailNoSugg = FieldPolicy.of(email or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, 0)
        assertTrue(emailNoSugg.personalValues(on))
        assertFalse(emailNoSugg.suggestions || emailNoSugg.personalWords(on))
    }

    @Test fun plainTextLearnsWordsEmailLearnsValues() {
        val t = FieldPolicy.of(text, 0)
        assertTrue(t.personalWords(on)); assertFalse(t.personalValues(on))
        for (v in listOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)) {
            val e = FieldPolicy.of(InputType.TYPE_CLASS_TEXT or v, 0)
            assertEquals(FieldKind.EMAIL, e.kind)
            assertFalse(e.suggestions); assertFalse(e.personalWords(on)); assertTrue(e.personalValues(on))
        }
        assertEquals(FieldKind.URL, FieldPolicy.of(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, 0).kind)
        assertFalse(FieldPolicy.of(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, 0).personalValues(on))
        assertEquals(FieldKind.NUMBER, FieldPolicy.of(InputType.TYPE_CLASS_DATETIME, 0).kind)
    }

    @Test fun settingsCloseTheGate() { // X3
        val t = FieldPolicy.of(text, 0)
        val e = FieldPolicy.of(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, 0)
        for (s in listOf(on.copy(personal = false), on.copy(suggest = false))) {
            assertFalse(t.personalWords(s)); assertFalse(e.personalValues(s))
        }
    }

    @Test fun personalToggleDefaultsOnAndRoundTrips() { // X5
        for (p in ProfileCodec.seed().profiles) assertTrue(p.id, p.settings.personal)
        val r3 = ProfileCodec.encode(ProfileCodec.seed()).filterKeys { !it.endsWith(".personal") }
        assertTrue(ProfileCodec.decode(r3).byId("noche")!!.settings.personal)
        val st = ProfileCodec.seed().update("dia") { it.copy(personal = false) }
        val back = ProfileCodec.decode(ProfileCodec.encode(st))
        assertFalse(back.byId("dia")!!.settings.personal)
        assertTrue(back.byId("noche")!!.settings.personal)
    }
}
