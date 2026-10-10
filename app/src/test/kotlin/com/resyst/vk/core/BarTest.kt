package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round 4 — what the suggestion bar shows (B*), chain behavior (M6) and the merge gate (X1/X3). */
class BarTest {
    private val es = Lang.ES
    // frequency order, most frequent first
    private val lex = Suggest(listOf("de", "que", "qué", "está", "esta", "estaba", "estar", "estás", "hola", "hombre", "casa", "cosa", "estupendo", "como", "cómo"))

    private fun bar(before: String, personal: PersonalModel?, shift: ShiftState = ShiftState.OFF, after: String = "") =
        Bar.words(before, after, windowFull = false, lang = es, lexicon = lex, personal = personal, shift = shift)

    private fun model(vararg lines: String) = PersonalModel().also { m ->
        for (l in lines) {
            var acc = ""
            for (ch in "$l ") {
                val was = acc
                acc += ch
                if (ch == ' ' && was.isNotEmpty() && was.last() != ' ')
                    Tokens.finished(acc, false)?.let { m.learn(es, it.prev, it.word, it.sentenceStart) }
            }
        }
    }

    @Test fun coldStartPredictsCommonContinuations() { // B1 (seed)
        assertEquals("cómo", bar("hola, ", null).first())
        assertEquals("estás", bar("hola, cómo ", null).first())
        assertEquals("días", bar("Buenos ", null).first())
    }

    @Test fun personalContinuationsOutrankTheSeed() { // B1
        val m = model("hola, que tal", "hola, que tal", "hola, que tal")
        val b = bar("hola, ", m)
        assertEquals("que", b[0])
        assertTrue("the seed fills the rest", "cómo" in b)
        assertEquals(3, b.size)
    }

    @Test fun duplicatesAcrossSourcesAppearOnce() { // B3
        val m = model("hola, cómo estás")
        val b = bar("hola, ", m)
        assertEquals(b.map { it.lowercase() }.distinct(), b.map { it.lowercase() })
        assertEquals("cómo", b[0])
    }

    @Test fun sentenceStartShowsTheUsersOpenerCapitalized() { // M7 + B4
        val m = model("Hola, cómo estás.", "Hola amigo.", "Buenas tardes.")
        assertEquals(listOf("Hola", "Buenas"), bar("", m, ShiftState.AUTO))
        assertEquals("Hola", bar("Gracias. ", m, ShiftState.AUTO).first())
        assertEquals(listOf("HOLA", "BUENAS"), bar("", m, ShiftState.LOCKED))
        assertEquals(listOf("hola", "buenas"), bar("", m, ShiftState.OFF)) // auto-cap disabled: as typed
        assertTrue("no generic starters without a model", bar("", null, ShiftState.AUTO).isEmpty())
    }

    @Test fun predictionsNeedASeparatorAndNoLetterAfterTheCursor() { // B6
        val m = model("hola, cómo estás")
        assertTrue(bar("hola,", m).isEmpty()) // picking would glue "hola,cómo"
        assertTrue(bar("hola, ", m, after = "x").isEmpty())
        assertTrue(bar("hola, cómo es", m, after = "tás").isEmpty())
        assertEquals("cómo", bar("hola, ¿", m).first())
    }

    @Test fun typingKeepsTheConfidentCorrectionFirst() { // B2 + A12
        val m = model("mi casa es estupenda", "casa casa")
        val b = bar("mi csaa", m)
        assertEquals("casa", b.first()) // what space will apply
        assertEquals(lex.correction("csaa", false), b.first())
    }

    @Test fun personalCompletionsRankFirstAmongCompletions() { // B2
        val m = model("estupendo estupendo", "cómo estás")
        // static order would be está, esta, estaba; the user's habit comes first. r11 (N1/N2): the
        // one-off "estás" (typed once, no context here) is offered right after the first lexicon word
        assertEquals(listOf("estupendo", "está", "estás"), bar("muy es", m))
        // with context the continuation wins over the global habit
        assertEquals(listOf("estás", "estupendo", "está"), bar("cómo es", m))
        assertEquals("Estupendo", bar("Es", m).first()) // B4: typed case mirrored
        assertEquals(listOf("está", "esta", "estaba"), bar("muy es", null)) // no model: pure lexicon
    }

    @Test fun aWordTheUserKeepsTypingIsNotCorrected() { // B5
        val m = PersonalModel()
        assertEquals("casa", Bar.correction("cssa", false, lex, m, es))
        assertEquals("casa", bar("mi cssa", m).first())
        m.learn(es, null, "cssa", false)
        assertEquals("one use is not a habit", "casa", Bar.correction("cssa", false, lex, m, es))
        m.learn(es, null, "cssa", false)
        assertNull(Bar.correction("cssa", false, lex, m, es))
        // the bar leads with what space will commit: the word as typed; the fix stays offered
        assertEquals(listOf("cssa", "casa"), bar("mi cssa", m))
        assertNull(Bar.correction("cssa", false, null, m, es))
        assertEquals("casa", Bar.correction("cssa", false, lex, null, es)) // gate closed: static behavior
    }

    @Test fun chainFollowsEachPick() { // M6 end-to-end over the engine
        val m = model("Hola, cómo estás", "Hola, cómo estás")
        val sim = LearningTest.Sim(model = m)
        sim.type("hola, ")
        val first = bar(sim.text, m).first()
        assertEquals("cómo", first)
        sim.pick(first)
        val second = bar(sim.text, m).first()
        assertEquals("estás", second)
        sim.pick(second)
        assertEquals("hola, cómo estás ", sim.text)
        assertEquals(3, m.countOf(es, "hola", "cómo"))
    }

    @Test fun closedGateShowsNoPersonalData() { // X1 + X3 (merge side)
        val m = model("hola, zarzaparrilla", "hola, zarzaparrilla")
        assertTrue("zarzaparrilla" in bar("hola, ", m))
        assertFalse("zarzaparrilla" in bar("hola, ", null))
        assertFalse(bar("zarza", null).any { it.startsWith("zarza") })
        assertTrue(bar("", null, ShiftState.AUTO).isEmpty())
    }

    @Test fun truncatedWindowDoesNotPredictFromAFragment() { // M8
        val m = model("ola bonita", "ola bonita")
        assertTrue(Bar.words("ola ", "", windowFull = true, lang = es, lexicon = lex, personal = m, shift = ShiftState.OFF).isEmpty())
    }

    @Test fun seedTablesAreClean() {
        for (l in Lang.values()) for ((prev, nexts) in Seeds.table(l)) {
            assertTrue("$l $prev", Tokens.learnable(prev) && prev == Tokens.key(prev))
            assertTrue("$l $prev → $nexts", nexts.isNotEmpty() && nexts.all { Tokens.learnable(it) } && nexts.distinct() == nexts)
        }
        assertEquals("you", Seeds.next(Lang.EN, "thank").first())
    }
}
