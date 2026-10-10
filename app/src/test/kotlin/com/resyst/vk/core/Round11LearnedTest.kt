package com.resyst.vk.core

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * r11 — learned words that come back (docs/failure-modes.md K1–K6, N1–N4, BL8, X6).
 * Each test names the failure it catches; bite proofs (mutations of the fix) are in the r11 report.
 */
class Round11LearnedTest {
    private val es = Lang.ES
    private val en = Lang.EN
    private val on = KbSettings()
    // frequency order, most frequent first: "bastón" is the confident fix of "bastiono"
    private val lex = Suggest(listOf("de", "que", "hoy", "ayer", "bastón", "bastante", "base", "kilo", "kiosco", "zona", "zombi"), Lang.ES)

    private fun bar(before: String, m: PersonalModel?, vocab: List<Lang> = listOf(es), lexicon: Suggest? = lex) =
        Bar.words(before, "", windowFull = false, lang = vocab.first(), lexicon = lexicon, personal = m, shift = ShiftState.OFF, vocab = vocab)

    private val space = Key(KeyType.SPACE, " ")
    private val bs = Key(KeyType.BACKSPACE, "⌫")

    /** The service's key path: engine press → effects → learner (kept or learn). */
    private class Typist(val m: PersonalModel?, lex: Suggest) {
        val engine = KeyboardEngine().apply {
            start(FieldInfo(autoCap = false))
            corrector = Corrector { w, s -> Bar.correction(w, s, lex, m, Lang.ES) }
        }
        val learner = Learner { m }
        var text = ""
        fun key(k: Key) {
            val before = text
            val outs = engine.press(k, before, 0L)
            text = Edits.apply(before, outs)
            val kept = engine.takeReverted()
            if (kept != null) learner.kept(Lang.ES, Lang.ES, before, outs, false, kept)
            else learner.afterEdit(Lang.ES, before, outs, false, if (k.type == KeyType.BACKSPACE) Learner.Edit.BACKSPACE else Learner.Edit.KEY)
        }
        fun type(s: String) { for (c in s) key(if (c == ' ') Key(KeyType.SPACE, " ") else Key(KeyType.CHAR, c.toString(), c.toString())) }
        fun undoChip() {
            val before = text
            val outs = engine.undoCorrection(before) ?: error("no ↶ offer")
            text = Edits.apply(before, outs)
            val kept = engine.takeReverted()
            if (kept != null) learner.kept(Lang.ES, Lang.ES, before, outs, false, kept) else learner.reset()
        }
    }

    // ── K1/K2: the user keeps a word by reverting its correction ──────────
    @Test fun backspaceRevertKeepsTheWordForGood() { // K1 (H2: before r11 it was corrected away again)
        val m = PersonalModel()
        val t = Typist(m, lex)
        t.type("hoy bastiono ")
        assertEquals("hoy bastón ", t.text)
        t.key(bs)
        assertEquals("hoy bastiono", t.text)
        t.key(space)
        assertEquals("hoy bastiono ", t.text)
        assertTrue(m.kept(es, "bastiono"))
        assertFalse("M10: the correction it undid is not left learned", m.knows(es, "bastón"))
        // a fresh field, another context: never corrected again, offered for its prefix
        val t2 = Typist(m, lex)
        t2.type("ayer bastiono ")
        assertEquals("ayer bastiono ", t2.text)
        assertEquals("bastiono", bar("ayer bast", m).first())
    }

    @Test fun undoChipKeepsTheWordAndUnlearnsTheCorrection() { // K2
        val m = PersonalModel()
        val t = Typist(m, lex)
        t.type("hoy bastiono ")
        assertEquals("hoy bastón ", t.text)
        t.undoChip()
        assertEquals("hoy bastiono ", t.text)
        assertTrue(m.kept(es, "bastiono"))
        assertFalse(m.knows(es, "bastón"))
        assertNull(Bar.correction("bastiono", false, lex, m, es))
    }

    @Test fun aTypedWordTheUserDidNotRevertIsNotKept() { // K1 (no false keeps)
        val m = PersonalModel()
        val t = Typist(m, lex)
        t.type("hoy bastiono ") // accepted the correction
        t.type("de ")
        assertFalse(m.kept(es, "bastiono"))
        assertTrue(m.knows(es, "bastón"))
        // a plain ⌫ that is not a revert (deleting the space after an uncorrected word) keeps nothing
        val t2 = Typist(m, lex)
        t2.type("hoy kraviel ")
        t2.key(bs)
        assertFalse(m.kept(es, "kraviel"))
    }

    @Test fun nothingIsKeptWhenTheWriteGateIsClosed() { // K6
        val t = Typist(null, lex) // secret / incognito / opted-out: the service hands the learner null
        t.type("hoy bastiono ")
        t.key(bs)
        t.key(space)
        assertEquals("hoy bastiono ", t.text) // the revert itself still works
    }

    // ── K3/K4/K5: the kept mark lives in the model ────────────────────────
    @Test fun keptSurvivesSaveLoadUnlearnAndEviction() { // K3
        val m = PersonalModel()
        m.learn(es, null, "bastiono", false); m.keep(es, "bastiono")
        m.learn(es, "hoy", "bastiono", false)
        m.unlearn(es, "hoy", "bastiono", false) // ⌫ undoing a LATER use keeps the mark
        assertTrue(m.kept(es, "bastiono"))
        val back = PersonalModel.fromJson(m.toJson())
        assertTrue(back.kept(es, "bastiono"))
        assertEquals(m.toJson(), back.toJson())
        // a full vocabulary evicts one-offs before the kept word, even an older one
        val big = PersonalModel()
        big.learn(es, null, "keptword", false); big.keep(es, "keptword")
        var i = 0
        while (big.vocabCount(es) < PersonalModel.VOCAB_CAP) big.learn(es, null, "w" + word(i++), false)
        big.learn(es, null, "newcomer", false)
        assertTrue(big.kept(es, "keptword"))
        assertTrue(big.knows(es, "newcomer"))
    }

    private fun word(i: Int): String { var n = i; val sb = StringBuilder(); do { sb.append('a' + n % 26); n /= 26 } while (n > 0); return sb.toString() }

    @Test fun v1FilesMigrateWithoutLosingAnything() { // K4
        // a words.json exactly as r4–r10 wrote it (v1)
        val v1 = "{\"v\":1,\"clock\":9,\"langs\":{\"es\":{\"vocab\":[[\"hola\",\"hola\",3,7],[\"pololo\",\"pololo\",1,9]]," +
            "\"starters\":[[\"hola\",3,7]],\"next\":[[\"hola\",[[\"pololo\",1,9]]]]},\"en\":{\"vocab\":[[\"meeting\",\"meeting\",2,4]],\"starters\":[],\"next\":[]}}}"
        val m = PersonalModel.fromJson(v1)
        assertEquals(listOf(PersonalModel.Learned("hola", "hola", 3), PersonalModel.Learned("pololo", "pololo", 1)), m.words(es, 10))
        assertEquals(listOf("meeting"), m.words(en, 10).map { it.form })
        assertEquals(listOf("pololo"), m.predict(es, "hola", false, 3))
        assertEquals(listOf("hola"), m.predict(es, null, true, 3))
        assertFalse(m.kept(es, "pololo"))
        val saved = m.toJson()
        assertTrue(saved.startsWith("{\"v\":2,\"clock\":9,"))
        assertEquals(saved, PersonalModel.fromJson(saved).toJson())
        // junk in the v2 flag means "not kept"; malformed / foreign JSON is still an empty model
        val junk = PersonalModel.fromJson("{\"v\":2,\"clock\":1,\"langs\":{\"es\":{\"vocab\":[[\"zeta\",\"zeta\",1,1,\"yes\"],[\"kept\",\"kept\",1,1,1]]}}}")
        assertFalse(junk.kept(es, "zeta")); assertTrue(junk.kept(es, "kept"))
        for (bad in listOf("", "{", "[]", "{\"v\":3,\"langs\":{}}", "{\"v\":\"2\"}", "{\"v\":0}")) assertTrue(bad, PersonalModel.fromJson(bad).isEmpty())
    }

    @Test fun forgetAndListingSeeTheKeptMark() { // K5
        val m = PersonalModel()
        m.learn(es, null, "bastiono", false); m.keep(es, "bastiono")
        assertEquals(listOf(PersonalModel.Learned("bastiono", "bastiono", 1, kept = true)), m.words(es, 5))
        assertTrue(m.forget(es, "bastiono"))
        assertFalse(m.kept(es, "bastiono"))
        assertEquals("bastón", Bar.correction("bastiono", false, lex, m, es)) // correctable again
        m.keep(es, "bastiono"); m.clear()
        assertFalse(m.kept(es, "bastiono"))
    }

    // ── N1–N4: what the bar offers ────────────────────────────────────────
    @Test fun aWordTypedOnceIsOffered() { // N1 (H1: COMPLETE_MIN = 2 hid it)
        val m = PersonalModel()
        m.learn(es, "hoy", "kraviel", false)
        assertEquals(listOf("kraviel"), bar("ayer krav", m))
    }

    @Test fun aOneOffNeverTakesSlotOneFromTheLexicon() { // N2
        val m = PersonalModel()
        m.learn(es, null, "zonx", false) // a typo of "zona" that space left alone, typed once
        val b = bar("ayer zon", m)
        assertEquals("zona", b[0])
        assertEquals("zonx", b[1])
        // the lexicon has nothing: the one-off is first
        m.learn(es, null, "kraviel", false)
        assertEquals("kraviel", bar("ayer kr", m)[0])
    }

    @Test fun strongWordsKeepTheirPlaceBeforeTheLexicon() { // N3
        val m = PersonalModel()
        repeat(2) { m.learn(es, null, "bastiono", false) }
        m.learn(es, null, "bastardo", false) // a one-off with the same prefix
        val b = bar("ayer bast", m)
        assertEquals(listOf("bastiono", "bastón", "bastardo"), b)
        val k = PersonalModel()
        k.learn(es, null, "bastiono", false); k.keep(es, "bastiono") // kept = strong at count 1
        assertEquals("bastiono", bar("ayer bast", k)[0])
        // a continuation of the previous word is strong too
        val c = PersonalModel()
        c.learn(es, "hoy", "bastiono", false)
        assertEquals("bastiono", bar("hoy bast", c)[0])
    }

    @Test fun aConfidentFixLeavesRoomForPersonalWords() { // N4 (H5)
        val m = PersonalModel()
        repeat(2) { m.learn(es, null, "bastomiro", false) }
        m.learn(es, null, "bastomero", false)
        // "bastom" → confident "bastón" leads (A12); the user's words still get the other slots
        assertEquals("bastón", lex.correction("bastom", false))
        assertEquals(listOf("bastón", "bastomiro", "bastomero"), bar("ayer bastom", m))
    }

    // ── BL8: both vocabularies with bilingual on ──────────────────────────
    @Test fun bilingualReadsBothVocabulariesOnce() { // BL8 (H3)
        val m = PersonalModel()
        m.learn(en, "the", "quorvex", false)
        repeat(2) { m.learn(en, "the", "bastiono", false) }
        m.learn(es, null, "bastiono", false) // known in both tables: offered once
        assertEquals(emptyList<String>(), bar("ayer quor", m, listOf(es)).filter { it == "quorvex" })
        assertEquals(listOf("quorvex"), bar("ayer quor", m, listOf(es, en)))
        assertEquals(1, bar("ayer bast", m, listOf(es, en)).count { it == "bastiono" })
        // protection from space reads both tables
        assertEquals("bastón", Bar.correction("bastiono", false, lex, PersonalModel().also { it.learn(en, null, "bastiono", false); it.learn(en, null, "bastiono", false) }, es))
        assertNull(Bar.correction("bastiono", false, lex, PersonalModel().also { it.learn(en, null, "bastiono", false); it.learn(en, null, "bastiono", false) }, es, vocab = listOf(es, en)))
        // predictions (bigrams) stay per language: "the" → quorvex is English only
        assertTrue(Bar.words("the ", "", false, es, lex, m, ShiftState.OFF, vocab = listOf(es, en)).none { it == "quorvex" })
        assertTrue("quorvex" in Bar.words("the ", "", false, en, null, m, ShiftState.OFF, vocab = listOf(en, es)))
    }

    // ── X6: prose fields that opted out offer, never learn ────────────────
    @Test fun optedOutProseOffersButNeverLearns() { // X6
        val cls = InputType.TYPE_CLASS_TEXT
        val noSugg = InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        val composer = FieldPolicy.of(cls or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE or noSugg, EditorInfo.IME_ACTION_SEND)
        assertTrue(composer.offerWords(on))
        assertFalse(composer.personalWords(on))
        assertEquals(NoMemory.OPTED_OUT, MemoryNotice.reason(composer, on, on)) // still says it does not learn
        assertFalse(composer.offerWords(on.copy(personal = false)))
        assertFalse(composer.offerWords(on.copy(suggest = false)))
        val closed = listOf(
            FieldPolicy.of(cls or noSugg, EditorInfo.IME_ACTION_DONE), // handle / code
            FieldPolicy.of(cls or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or noSugg, EditorInfo.IME_ACTION_SEARCH), // opted-out search
            FieldPolicy.of(cls or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0),
            FieldPolicy.of(cls or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_MULTI_LINE, 0),
            FieldPolicy.of(cls or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, 0),
            FieldPolicy.of(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, 0),
            FieldPolicy.of(cls or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or InputType.TYPE_TEXT_FLAG_MULTI_LINE, 0),
            FieldPolicy.of(cls or InputType.TYPE_TEXT_VARIATION_URI, 0),
            FieldPolicy.of(InputType.TYPE_CLASS_NUMBER, 0),
            FieldPolicy.of(InputType.TYPE_CLASS_PHONE, 0),
            FieldPolicy.of(cls or InputType.TYPE_TEXT_FLAG_MULTI_LINE or noSugg, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING), // incognito
            FieldPolicy.of(cls, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING),
        )
        for (p in closed) { assertFalse(p.toString(), p.offerWords(on)); assertFalse(p.toString(), p.personalWords(on)) }
        // the read gate is never narrower than the write gate
        for (t in 0 until 0x100000 step 0x1001) for (o in listOf(0, EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)) {
            val p = FieldPolicy.of(t, o)
            if (p.personalWords(on)) assertTrue("0x%x".format(t), p.offerWords(on))
            if (p.secret || p.incognito) assertFalse("0x%x".format(t), p.offerWords(on))
        }
    }

    // ── N2 measured: count-1 typos vs slot 1 on the real lexicons ─────────
    /**
     * LexiconEval-style: thumb typos of real words that the space correction LEAVES ALONE (so the
     * keyboard learns them once as typed) are put in a model with count 1; then every prefix of
     * the typo (≥ 2 letters, shorter than it) is typed and slot 1 is read. Gate: a count-1 word
     * takes slot 1 only when the lexicon has no candidate for that prefix — measured, not assumed.
     * Report: build/r11-noise/eval-<lang>.txt.
     */
    @Test fun oneOffTyposDoNotTakeSlotOne() {
        for (lang in listOf("es", "en")) {
            val l = if (lang == "es") Lang.ES else Lang.EN
            val words = File("src/main/assets/lexicon/$lang.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }
            val s = Suggest(words, l)
            val known = words.toHashSet()
            val rnd = Random(11)
            val pool = words.take(12000).filter { w -> w.length >= 4 && w.all { it.isLetter() } }
            var typos = 0; var prefixes = 0; var slot1 = 0; var slot1WithLex = 0; var visible = 0; var oldGate = 0
            repeat(1500) {
                val w = pool[rnd.nextInt(pool.size)]
                val i = 1 + rnd.nextInt(w.length - 1)
                val typo = when (rnd.nextInt(3)) {
                    0 -> w.removeRange(i, i + 1)
                    1 -> w.substring(0, i) + w[i - 1] + w.substring(i) // doubled letter
                    else -> if (i < w.length - 1) w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2) else w + "s"
                }
                if (typo in known || !Tokens.learnable(typo)) return@repeat
                if (s.correction(typo, false) != null) return@repeat // space fixes it: never learned as typed
                typos++
                val m = PersonalModel().also { it.learn(l, "xq", typo, false) }
                for (n in 2 until typo.length) {
                    val prefix = typo.substring(0, n)
                    val b = Bar.words("hoy $prefix", "", false, l, s, m, ShiftState.OFF)
                    val lexHas = s.suggest(prefix, 3).isNotEmpty()
                    prefixes++
                    if (b.firstOrNull() == typo) { slot1++; if (lexHas) slot1WithLex++ }
                    if (typo in b) visible++
                    if (!lexHas) oldGate++ // what "one-offs first" would have shown first anyway
                }
            }
            val r = "lang=$lang typosLearnedOnce=$typos prefixes=$prefixes slot1=$slot1 (%.1f%%) slot1WhileLexiconHadACandidate=$slot1WithLex " +
                "visibleIn3Slots=$visible (%.1f%%) prefixesWithNoLexiconCandidate=$oldGate\n"
            val report = r.format(100.0 * slot1 / maxOf(1, prefixes), 100.0 * visible / maxOf(1, prefixes))
            File("build/r11-noise").mkdirs()
            File("build/r11-noise/eval-$lang.txt").writeText(report)
            println(report)
            assertTrue(report, typos > 200)
            assertEquals(report, 0, slot1WithLex) // N2: never over a lexicon candidate
        }
    }
}
