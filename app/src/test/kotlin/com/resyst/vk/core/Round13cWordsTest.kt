package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r13c E3 (SW1–SW5), E4 (TW1–TW8), E6 (TA1–TA4): what space commits for the words the brief names,
 * through the shipped corrector and assets ([ShippedWriting]). The session-level numbers are in
 * Round13cReplayTest; r13a's gates (s1_ok, ctx_keep, s1_typo, Chilean words) in Round13ReplayTest.
 */
class Round13cWordsTest {
    private fun commit(left: String, typed: String) = ShippedWriting.replay(left, typed, " ").trimEnd(' ')

    // ── E3: two-letter words ──────────────────────────────────────────────
    @Test fun twoLetterSlipsOfFrequentWordsAreFixed() { // SW1
        for ((typed, fix) in listOf("nl" to "no", "dd" to "de", "sd" to "se", "jn" to "un", "ys" to "ya", "ek" to "el")) {
            assertEquals(typed, fix, commit("Mañana ", typed))
        }
        assertEquals("the sentence-start capital is kept", "No", commit("Listo. ", "Nl"))
    }

    @Test fun realTwoLetterWordsAndShorthandStay() { // SW2, SW4
        val stay = "u o a y e ni tu mi ir of is ok pr ls ql ws td xd wn pq xq tb ns bn nd".split(" ")
        val changed = stay.mapNotNull { w -> commit("Oye, ", w).takeIf { it != w }?.let { "$w→$it" } }
        assertEquals(emptyList<String>(), changed)
    }

    @Test fun singleLettersAreNeverTouched() { // SW3
        val changed = "q k d t x m s g h j l n p r v w z c b f".split(" ").mapNotNull { w -> commit("Oye ", w).takeIf { it != w }?.let { "$w→$it" } }
        assertEquals(emptyList<String>(), changed)
    }

    @Test fun aTieStays() { // SW1: ze is one key from se and from de
        assertEquals("ze", commit("Oye, ", "ze"))
    }

    // ── E4: two slips in 6+ letters, three in 10+ ─────────────────────────
    @Test fun twoSlipsInSixLettersAreFixed() { // TW1, TW2, TW8
        assertEquals("cambia", commit("Si el autocorrector ", "csmvia"))
        assertEquals("google", commit("GrapheneOS no trae servicios de ", "hoohle")) // TW8: not hole
        assertEquals("branch", commit("un commit chico y un push a la ", "nrancj")) // TW2: en 3865, es 21670
        assertEquals("importante", commit("Al final, lo ", "imporrsnte"))
    }

    @Test fun threeSlipsInALongWordAreFixed() { // TW7
        assertEquals("importante", commit("Al final, lo ", "impoersnte"))
        assertEquals("diccionario", commit("no cómo escribe un ", "dkcionafio"))
    }

    @Test fun fiveLettersKeepOneEdit() { // TW4: the brief's report rows
        assertEquals("gjsga", commit("GrapheneOS me ", "gjsga"))
        assertEquals("jaryo", commit("GrapheneOS me gusta ", "jaryo"))
        assertEquals("twemknadl", commit("Habríamos ", "twemknadl")) // four slips
    }

    @Test fun longWordsAreSearched() { // TW5
        assertEquals("esternocleidomastoideo", commit("desoxirribonucleico y ", "esternlcleidomastoideo"))
    }

    // ── E6: typo + missing accent ─────────────────────────────────────────
    @Test fun aTypoOfAnAccentedFormIsFixed() { // TA1, TA4
        assertEquals("pusiésemos", commit("condujéramos, anduvieron y ", "pusiesemls"))
        assertEquals("bastión", commit("El servidor del ", "baation"))
        assertEquals("pixel", commit("revisando el ", "pkxel")) // NW7: pixel is Spanish too
        assertEquals("build", commit("Habríamos terminado antes si el ", "vuild"))
    }

    @Test fun condikeramlsIsReported() { // TA3: geometry prefers condijéramos (2 slips) over condujéramos (3)
        assertEquals("condijéramos", commit("cupiese, satisficiera, ", "condikeramls"))
    }
}
