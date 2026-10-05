package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestTest {
    // frequency order, most frequent first
    private val s = Suggest(listOf("de", "que", "está", "esta", "estaba", "estar", "hola", "hombre", "canción", "canciones", "casa", "don't", "estación"))

    // S1
    @Test fun emptyOrNonLetterPrefixYieldsNothing() {
        assertTrue(s.suggest("").isEmpty())
        assertTrue(s.suggest("12").isEmpty())
    }

    // S2
    @Test fun accentlessInputFindsAccentedWords() {
        assertEquals("canción", s.suggest("cancion").first())
        assertTrue("canción" in s.suggest("cancio"))
    }

    // S3 + S5
    @Test fun frequencyOrderWithAccentCorrection() {
        assertEquals(listOf("está", "estaba", "estar"), s.suggest("esta", 3))
        assertEquals(listOf("hola", "hombre"), s.suggest("ho"))
        assertFalse("esta" in s.suggest("esta", 10))
    }

    @Test fun exactPrefixBeatsFoldedOnly() {
        // "estación" only matches folded ("estac" vs "estaci"), exact "esta" extensions first
        val r = s.suggest("esta", 10)
        assertTrue(r.indexOf("estar") < r.indexOf("estación"))
    }

    // S4
    @Test fun caseIsMirrored() {
        assertEquals("Hola", s.suggest("Hol").first())
        assertEquals("HOLA", s.suggest("HOL").first())
        assertEquals("Hola", Suggest.matchCase("hola", "H"))
    }

    // S6
    @Test fun limitIsRespected() {
        assertEquals(1, s.suggest("e", 1).size)
        assertTrue(s.suggest("e", 3).size <= 3)
    }

    // S7
    @Test fun currentWordExtraction() {
        assertEquals("Qué", Suggest.currentWord("¿Qué"))
        assertEquals("don't", Suggest.currentWord("I don't"))
        assertEquals("", Suggest.currentWord("hola "))
        assertEquals("", Suggest.currentWord(""))
        assertEquals("esta", Suggest.currentWord("no, esta"))
        assertEquals("", Suggest.currentWord("rock'"), )
    }

    @Test fun foldStripsDiacriticsButKeepsEnye() {
        assertEquals("cancion", Suggest.fold("Canción"))
        assertEquals("pinguino", Suggest.fold("pingüino"))
        assertEquals("año", Suggest.fold("año"))
    }
}
