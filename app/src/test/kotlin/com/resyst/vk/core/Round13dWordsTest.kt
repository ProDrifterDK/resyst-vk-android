package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * r13d (FW, T3, W5, WC): the deterministic misses of the owner's live session s3, through the REAL engine
 * + the shipped corrector + the shipped assets ([ShippedWriting]): a fresh field holds the text before
 * the word, the word is typed key by key (auto-capital from the field's text) and its ending is pressed.
 *
 * Bite proof: on 361d784 (0.10.0) `auer` stays `Auer` (FW1), `pof` stays (T3-1), the five-letter
 * two-slip rows stay (W5-1) and `yatde` → `yate` / `juneros` → `juntos` (WC1/WC2).
 */
class Round13dWordsTest {
    private val shipped = ShippedWriting
    private val es get() = shipped.lexicons.getValue(Lang.ES).also { it.foreign = shipped.lexicons[Lang.EN] }
    private val en get() = shipped.lexicons.getValue(Lang.EN).also { it.foreign = shipped.lexicons[Lang.ES] }

    /** The word the field holds after [typed] + [end], without the ending. */
    private fun commit(left: String, typed: String, end: String = " ") =
        shipped.replay(left, typed, end).removeSuffix(" ").removeSuffix(end).trimEnd(' ')

    // ── FW: the first word of a field ─────────────────────────────────────
    @Test fun theFirstWordOfAnEmptyFieldIsCorrected() { // FW1, FW2 — s3 row 0
        assertEquals("an empty note, auto-capital on", "Ayer ", shipped.replay("", "auer", " "))
        assertEquals("Ayer", commit("Listo.\n\n", "auer"))
        assertEquals("ayer", commit("Te escribo porque ", "auer"))
        assertEquals("ayer", es.correction("auer", false))
    }

    @Test fun otherLanguageWordsAndCommonNamesStay() { // FW3
        for ((left, w) in listOf(
            "This sentence is in " to "english", "No trae servicios de " to "google", "Me gusta " to "grapheneos",
            "El servidor del " to "bastion", "satisficiera, condujéramos, " to "anduvieron", "es " to "the", "es " to "for",
        )) assertEquals(w, commit(left, w))
        // a name of the other language that its list does not know is no longer a shield (auer), a listed one is
        assertNull(es.correction("english", false))
        assertNull(es.correction("google", false))
        // the writing language's own names typed lowercase stay its user's words (r13a RW1)
        assertNull(es.correction("maria", false) ?.takeIf { it != "maría" })
    }

    // ── T3: three-letter words ────────────────────────────────────────────
    @Test fun aThreeLetterSlipToAVeryFrequentWordIsFixed() { // T3-1 — s3 `pof` (not a fixture row: decoded as a pick)
        assertEquals("por", commit("La idea era entender ", "pof"))
        assertEquals("Por", commit("Listo. ", "pof"))
        assertEquals("por", es.correction("pof", false))
    }

    @Test fun realThreeLetterWordsAreNeverTouched() { // T3-2: every list / regional / valid three-letter word
        val dir = File("src/main/assets/lexicon")
        val words = LinkedHashSet<String>()
        // the writing language's list at any rank; the other language's common words (BL4) or valid ones
        dir.resolve("es.txt").readLines().map { it.trim() }.filterTo(words) { it.length == 3 && it.all(Char::isLetter) }
        dir.resolve("en.txt").readLines().map { it.trim() }.filterIndexedTo(words) { i, it ->
            it.length == 3 && it.all(Char::isLetter) && (i < Suggest.FOREIGN_RANK || shipped.validEn.contains(it))
        }
        for (n in listOf("regional/es-CL.txt", "regional/es-419.txt")) dir.resolve(n).readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.trim().removePrefix("-").substringBefore(' ').substringBefore('=').trim() }
            .filterTo(words) { it.length == 3 && it.all(Char::isLetter) }
        val alpha = "abcdefghijklmnopqrstuvwxyzñáéíóúü"
        for (a in alpha) for (b in alpha) for (c in alpha) {
            val s = "$a$b$c"
            if (shipped.validEs.contains(s) || shipped.validEn.contains(s) || shipped.validEs.contains(s.replaceFirstChar { it.uppercaseChar() })) words += s
        }
        assertTrue("walked ${words.size}", words.size > 1500)
        val es = es
        val en = en
        val touched = words.filter { w -> es.correction(w, false) != null && es.correction(w, false) != w }
        assertEquals("Spanish keyboard: real three-letter words changed", emptyList<String>(), touched.filterNot { es.accentForms(it).isNotEmpty() })
        for (w in listOf("pon", "fue", "sol", "the", "for", "ves", "mía", "dos", "tan", "voy")) {
            assertNull(w, es.correction(w, false))
            assertNull(w, en.correction(w, false)?.takeIf { it.lowercase() != w })
        }
    }

    @Test fun threeLetterTiesStay() { // T3-3: vas by a slip vs casa by a missing key
        assertEquals("cas", commit("Tampoco es lo mismo ", "cas"))
    }

    // ── W5 / WC: five letters, two slips; the two wrong corrections of s3 ─────
    @Test fun fiveLettersWithTwoSlipsAreFixedWhenOneWordFits() { // W5-1
        assertEquals("s2: two vertical slips (j for u, g for t), nothing else within two slips", "gusta", commit("GrapheneOS me ", "gjsga"))
        assertEquals("s3 iueho from its letters alone: luego and juego tie, the word stays", "iueho", commit("sigue corriendo el ", "iueho", ","))
        assertEquals("a slip plus an extra / missing key is not two slips: cahai stays cachai", "cachai", commit("GrapheneOS, Bastión y ", "cahai"))
        assertEquals("pixel", commit("revisando el ", "pkxel"))
    }

    @Test fun yatdeIsNeverYate() { // W5-2, WC1 — s3 row 2 (all five touches well inside their keys)
        val got = commit("Ayer en la ", "yatde")
        assertTrue("yatde → $got", got in setOf("yatde", "tarde"))
        val bar = es.suggest("yatde", 3)
        assertTrue("both close readings are offered: $bar", "tarde" in bar && "yate" in bar)
    }

    @Test fun junerosIsNeverJuntos() { // WC2 — s3 row 14: VK received `juneros` (n slid into j)
        val got = commit("Igual fue bacán ver los ", "juneros")
        assertTrue("juneros → $got", got in setOf("juneros", "números"))
        val bar = es.suggest("juneros", 3)
        assertTrue("both close readings are offered: $bar", "juntos" in bar && "números" in bar)
        assertEquals("the decoded letters still give the gold", "números", commit("Igual fue bacán ver los ", "nuneros"))
    }
}
