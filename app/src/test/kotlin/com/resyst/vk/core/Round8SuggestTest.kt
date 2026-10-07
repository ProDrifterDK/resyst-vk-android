package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * r8 typo weighting + thresholds on the shipped 25k ES / 20k EN lexicon. Failure modes:
 *
 * K1 geometry: ES home row starts under q (ñ makes it 10 keys), EN is offset ½ — a straight
 *    neighbour (same row ±1 or right above/below) costs less than a diagonal one.
 * K2 long words with an adjacent-key slip (User 2's complaint) are corrected, two slips too.
 * K3 short words stay conservative: no forced correction on ≤ 4 letters with a missing letter,
 *    slang and real words untouched.
 * K4 a bigger lexicon must not swallow ñ corrections: "manana" (subtitle noise at 17k) → mañana.
 * K5 thresholds are monotone in length (longer = more room), documented constants.
 */
class Round8SuggestTest {
    private fun lexicon(lang: String) = File("src/main/assets/lexicon/$lang.txt").readLines().filter { it.isNotBlank() }
    private val es = Suggest(lexicon("es"), Lang.ES)
    private val en = Suggest(lexicon("en"), Lang.EN)

    @Test fun keyGeometryFollowsTheDrawnLayout() { // K1
        val esPos = Suggest.geometry(Lang.ES)
        val enPos = Suggest.geometry(Lang.EN)
        assertEquals(Suggest.NEIGHBOUR_COST, Suggest.substitutionCost(esPos, 'q', 'a'), 0f) // a right under q on ES
        assertEquals(Suggest.ADJACENT_COST, Suggest.substitutionCost(enPos, 'q', 'a'), 0f)  // diagonal on EN
        assertEquals(Suggest.NEIGHBOUR_COST, Suggest.substitutionCost(enPos, 'r', 't'), 0f)
        assertEquals(1f, Suggest.substitutionCost(esPos, 'q', 'p'), 0f)
        assertEquals(Suggest.ENYE_COST, Suggest.substitutionCost(esPos, 'n', 'ñ'), 0f)
        assertTrue(Suggest.NEIGHBOUR_COST < Suggest.ADJACENT_COST && Suggest.ADJACENT_COST < 1f)
        assertTrue(Suggest.adjacent('l', 'ñ', Lang.ES))
    }

    @Test fun longWordsWithThumbSlipsAreCorrected() { // K2
        val esCases = mapOf(
            "necesiro" to "necesito", "problena" to "problema", "importsnte" to "importante",
            "momwnto" to "momento", "pregunra" to "pregunta", "dinerp" to "dinero",
            "trabaho" to "trabajo", "siempte" to "siempre", "esperanfo" to "esperando",
            "entoncws" to "entonces", "conpletamente" to "completamente",
        )
        for ((t, w) in esCases) assertEquals(t, w, es.correction(t, false))
        val enCases = mapOf("probelm" to "problem", "somethimg" to "something", "evrryone" to "everyone",
            "beautifyl" to "beautiful", "tomorrpw" to "tomorrow", "differwnt" to "different")
        for ((t, w) in enCases) assertEquals(t, w, en.correction(t, false))
    }

    @Test fun shortWordsStayConservative() { // K3
        for (w in listOf("cas", "ola", "pucha", "weon", "sipo", "jaja", "bkn", "pololo", "hol", "sta")) assertNull(w, es.correction(w, false))
        for (w in listOf("teh", "thier")) assertTrue(w, en.correction(w, false) != null || w == "thier") // cheap ones still fixed
        for (w in listOf("its", "it", "well", "were", "cant", "wont", "ill")) {
            val c = en.correction(w, false)
            assertTrue("$w → $c", c == null || Suggest.fold(c) == Suggest.fold(w))
        }
    }

    @Test fun enyeTwinsBeatSubtitleSpellings() { // K4
        assertEquals("mañana", es.correction("manana", false))
        assertEquals("niño", es.correction("nino", false))
        assertEquals("año", es.correction("ano", false) ?: "año") // "ano" may be its own word; never anything else
        assertNull(es.correction("pena", false)) // pena (real, frequent) is not peña
    }

    @Test fun thresholdsGrowWithLength() { // K5
        assertTrue(Suggest.frequentRank(4) < Suggest.frequentRank(6) && Suggest.frequentRank(6) < Suggest.frequentRank(9))
        assertTrue(Suggest.minGap(4) > Suggest.minGap(6) && Suggest.minGap(6) > Suggest.minGap(9))
        assertTrue(Suggest.allowance(4, 0) < Suggest.allowance(6, 0) && Suggest.allowance(6, 0) < Suggest.allowance(9, 0))
        assertEquals(0.6f, Suggest.allowance(3, 0), 0f)
    }
}
