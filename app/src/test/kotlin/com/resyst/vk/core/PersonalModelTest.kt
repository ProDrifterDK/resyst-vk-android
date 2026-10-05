package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round 4 — personal n-gram memory; ids map to docs/failure-modes.md (M*). */
class PersonalModelTest {
    private val es = Lang.ES

    /** Feeds [text] word by word the way the IME does: every finished word is one event. */
    private fun PersonalModel.type(text: String, lang: Lang = es) {
        var acc = ""
        for (ch in text) {
            val before = acc
            acc += ch
            if (before.isNotEmpty() && Tokens.isWordChar(before.last()) && !Tokens.isWordChar(ch)) {
                Tokens.finished(acc, windowFull = false)?.let { learn(lang, it.prev, it.word, it.sentenceStart) }
            }
        }
        if (acc.isNotEmpty() && Tokens.isWordChar(acc.last())) {
            Tokens.finished("$acc ", windowFull = false)?.let { learn(lang, it.prev, it.word, it.sentenceStart) }
        }
    }

    // ── Tokens ──────────────────────────────────────────────────────────
    @Test fun contextSurvivesCommasButNotTerminators() { // M1
        assertEquals("hola", Tokens.context("hola, ", false).prev)
        assertEquals("hola", Tokens.context("Hola ", false).prev)
        assertEquals("hola", Tokens.context("hola, ¿", false).prev)
        assertFalse(Tokens.context("hola, ", false).sentenceStart)
        for (t in listOf("Hola. ", "hola! ", "¿hola? ", "hola…", "hola\n", "")) {
            val c = Tokens.context(t, false)
            assertNull(t, c.prev)
            assertTrue(t, c.sentenceStart)
        }
    }

    @Test fun junkIsNeitherLearnedNorUsedAsContext() { // M5
        for (w in listOf("a", "y", "12", "abc123", "user@example.com", "example.com", "a/b", "x".repeat(25), "co-op", "'", "''"))
            assertFalse(w, Tokens.learnable(w))
        for (w in listOf("hola", "Cómo", "don't", "niño", "iPhone")) assertTrue(w, Tokens.learnable(w))
        assertNull(Tokens.context("user@example.com ", false).prev)
        assertFalse(Tokens.context("user@example.com ", false).sentenceStart)
        assertNull(Tokens.context("tengo 12 ", false).prev)
        assertNull(Tokens.finished("user@example.com ", false))
        assertNull(Tokens.finished("abc123 ", false))
        assertNull(Tokens.finished("a ", false))
    }

    @Test fun finishedWordAndItsContext() { // M1 + M7
        val f = Tokens.finished("Hola, ¿cómo ", false)!!
        assertEquals("cómo", f.word); assertEquals("hola", f.prev); assertFalse(f.sentenceStart)
        val g = Tokens.finished("Gracias. Hola,", false)!!
        assertEquals("Hola", g.word); assertNull(g.prev); assertTrue(g.sentenceStart)
        val h = Tokens.finished("estás?", false)!!
        assertEquals("estás", h.word); assertEquals(null, Tokens.finished("hola", false)) // unfinished
    }

    @Test fun truncatedWindowNeverYieldsFragments() { // M8
        // the window starts mid-word: "…ola " — "ola" is a fragment, not a word
        val c = Tokens.context("ola ", windowFull = true)
        assertNull(c.prev)
        assertFalse(c.sentenceStart)
        val f = Tokens.finished("ola ", windowFull = true)
        assertNull(f)
        val g = Tokens.finished("ola que tal ", windowFull = true)!!
        assertEquals("tal", g.word); assertEquals("que", g.prev)
    }

    // ── learning + ranking ──────────────────────────────────────────────
    @Test fun learnedPairIsPredicted() { // M1
        val m = PersonalModel()
        m.type("hola, cómo estás")
        assertEquals(listOf("cómo"), m.predict(es, "hola", false, 3))
        assertEquals(listOf("estás"), m.predict(es, "cómo", false, 3))
    }

    @Test fun chainAtoBtoC() { // M6
        val m = PersonalModel()
        repeat(3) { m.type("Hola, cómo estás. ") }
        val first = m.predict(es, null, true, 3).first()
        assertEquals("hola", first)
        val second = m.predict(es, Tokens.key(first), false, 3).first()
        assertEquals("cómo", second)
        assertEquals("estás", m.predict(es, Tokens.key(second), false, 3).first())
    }

    @Test fun countsRankAndRecencyBreaksTies() { // M2
        val m = PersonalModel()
        m.learn(es, "buen", "viaje", false)
        repeat(3) { m.learn(es, "buen", "día", false) }
        m.learn(es, "buen", "provecho", false)
        assertEquals(listOf("día", "provecho", "viaje"), m.predict(es, "buen", false, 3))
    }

    @Test fun keysAreCaseFoldedButFormsKept() { // M4
        val m = PersonalModel()
        m.learn(es, null, "Hola", true) // auto-capital from the sentence start
        m.learn(es, "hola", "María", false) // a name typed mid-sentence
        m.learn(es, null, "HOLA", true) // shouting, same word
        assertEquals(listOf("hola"), m.predict(es, null, true, 3))
        assertEquals(listOf("María"), m.predict(es, "hola", false, 3))
        assertTrue(m.knows(es, "HOLA") && m.knows(es, "hola"))
        m.learn(es, "hola", "iPhone", false) // mixed case is kept; newer wins the count tie
        assertEquals(listOf("iPhone", "María"), m.predict(es, "hola", false, 3))
    }

    @Test fun startersComeOnlyFromSentenceStarts() { // M7
        val m = PersonalModel()
        repeat(4) { m.type("Hola, que tal. ") }
        m.type("Buenas tardes")
        assertEquals(listOf("hola", "buenas"), m.predict(es, null, true, 3))
        assertFalse("tal" in m.predict(es, null, true, 10))
        assertTrue(m.predict(es, null, false, 3).isEmpty()) // no context, no sentence start → nothing
    }

    @Test fun boundedWithLeastUsefulEvicted() { // M3
        val m = PersonalModel()
        repeat(5) { m.learn(es, "de", "nada", false) }
        for (i in 0 until PersonalModel.NEXT_CAP + 5) m.learn(es, "de", "w" + "abcdefghijklmnop"[i], false)
        val next = m.predict(es, "de", false, 100)
        assertEquals(PersonalModel.NEXT_CAP, next.size)
        assertEquals("nada", next.first()) // the habit survives the churn
        assertTrue("the newest entry is kept", "w" + "abcdefghijklmnop"[PersonalModel.NEXT_CAP + 4] in next)

        for (i in 0 until PersonalModel.STARTER_CAP + 3) m.learn(es, null, "s" + "abcdefghijklmnopqrstuvwxyz"[i], true)
        assertEquals(PersonalModel.STARTER_CAP, m.predict(es, null, true, 100).size)

        val p = PersonalModel()
        for (i in 0..PersonalModel.PREV_CAP + 10) p.learn(es, word(i), "fin", false)
        assertEquals(PersonalModel.PREV_CAP, p.prevCount(es))
        assertTrue(p.predict(es, word(0), false, 3).isEmpty()) // oldest context dropped
        assertEquals(listOf("fin"), p.predict(es, word(PersonalModel.PREV_CAP + 10), false, 3))
        assertTrue(p.vocabCount(es) <= PersonalModel.VOCAB_CAP)
    }

    @Test fun vocabularyIsCapped() { // M3
        val m = PersonalModel()
        repeat(3) { m.learn(es, null, "hola", true) }
        for (i in 0..PersonalModel.VOCAB_CAP + 20) m.learn(es, null, word(i), false)
        assertEquals(PersonalModel.VOCAB_CAP, m.vocabCount(es))
        assertTrue("frequent word survives", m.knows(es, "hola"))
    }

    @Test fun countsDecaySoNewHabitsCanWin() { // M3
        val m = PersonalModel()
        repeat(PersonalModel.COUNT_CAP + 50) { m.learn(es, "buenos", "días", false) }
        assertTrue(m.countOf(es, "buenos", "días") <= PersonalModel.COUNT_CAP)
        m.learn(es, "buenos", "aires", false)
        val before = m.countOf(es, "buenos", "días")
        repeat(PersonalModel.COUNT_CAP) { m.learn(es, "buenos", "aires", false) }
        assertTrue(m.countOf(es, "buenos", "aires") > m.countOf(es, "buenos", "días"))
        assertTrue(m.countOf(es, "buenos", "días") < before)
        assertEquals("aires", m.predict(es, "buenos", false, 1).first())
    }

    @Test fun completionsPreferContextThenHabit() { // B2 (model side)
        val m = PersonalModel()
        repeat(3) { m.learn(es, null, "estupendo", false) }
        m.learn(es, "cómo", "estás", false)
        // "estás" is a continuation of "cómo" → first, even with a lower global count
        assertEquals(listOf("estás", "estupendo"), m.complete(es, "est", "cómo", 3))
        // without context, one-off words aren't offered as completions (noise gate)
        assertEquals(listOf("estupendo"), m.complete(es, "est", null, 3))
        // accent-insensitive, never the typed word itself
        assertEquals(listOf("estás"), m.complete(es, "estas", "cómo", 3))
        assertTrue(m.complete(es, "estupendo", null, 3).isEmpty())
    }

    @Test fun unlearnUndoesAReverted(): Unit { // M10
        val m = PersonalModel()
        m.learn(es, "hola", "the", false)
        m.unlearn(es, "hola", "the", false)
        assertTrue(m.predict(es, "hola", false, 3).isEmpty())
        assertFalse(m.knows(es, "the"))
        repeat(2) { m.learn(es, "hola", "cómo", false) }
        m.unlearn(es, "hola", "cómo", false)
        assertEquals(1, m.countOf(es, "hola", "cómo"))
    }

    @Test fun languagesDontLeak() { // M9
        val m = PersonalModel()
        m.learn(Lang.EN, "how", "are", false)
        assertTrue(m.predict(es, "how", false, 3).isEmpty())
        assertEquals(listOf("are"), m.predict(Lang.EN, "how", false, 3))
    }

    @Test fun persistenceRoundTrip() { // M9
        val m = PersonalModel()
        repeat(2) { m.type("Hola, cómo estás. ") }
        m.learn(es, "hola", "María", false)
        m.learn(Lang.EN, "how", "are", false)
        val back = PersonalModel.fromJson(m.toJson())
        for (l in Lang.values()) {
            assertEquals(m.predict(l, null, true, 10), back.predict(l, null, true, 10))
            for (prev in listOf("hola", "cómo", "how"))
                assertEquals(m.predict(l, prev, false, 10), back.predict(l, prev, false, 10))
            assertEquals(m.vocabCount(l), back.vocabCount(l))
        }
        assertEquals(m.countOf(es, "hola", "cómo"), back.countOf(es, "hola", "cómo"))
        // recency survives too: a new event after reload still ranks by (count, stamp)
        back.learn(es, "hola", "amigo", false)
        assertEquals("cómo", back.predict(es, "hola", false, 3).first())
        assertEquals(m.toJson(), PersonalModel.fromJson(m.toJson()).toJson())
    }

    @Test fun corruptStorageYieldsAnEmptyModel() { // M9
        for (bad in listOf("", "{", "null", "[]", "{\"v\":1,\"langs\":{\"es\":{\"next\":[[\"hola\",[[\"x\",\"NaN\",1]]]]}}}",
            "{\"v\":99}", "{\"v\":1,\"langs\":{\"xx\":{}}}", "\"hola\"")) {
            val m = PersonalModel.fromJson(bad)
            assertTrue(bad, m.predict(es, "hola", false, 3).isEmpty())
            assertEquals(bad, 0, m.vocabCount(es))
        }
        // entries that violate the token gate are dropped on load
        val sneaky = "{\"v\":1,\"clock\":3,\"langs\":{\"es\":{\"vocab\":[[\"user@example.com\",\"user@example.com\",3,1]]," +
            "\"next\":[[\"hola\",[[\"12345\",2,1],[\"cómo\",2,2]]]],\"starters\":[]}}}"
        val m = PersonalModel.fromJson(sneaky)
        assertEquals(listOf("cómo"), m.predict(es, "hola", false, 3))
        assertFalse(m.knows(es, "user@example.com"))
    }

    @Test fun clearWipesEverything() { // X4 (model side)
        val m = PersonalModel()
        m.type("Hola, cómo estás")
        m.clear()
        assertTrue(m.predict(es, null, true, 3).isEmpty())
        assertTrue(m.predict(es, "hola", false, 3).isEmpty())
        assertEquals(0, m.vocabCount(es))
    }

    private fun word(i: Int): String {
        // letters-only distinct tokens: base-26 encoding
        var n = i
        val b = StringBuilder("w")
        do { b.append('a' + n % 26); n /= 26 } while (n > 0)
        return b.toString()
    }
}
