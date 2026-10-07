package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Round 3 field feedback; ids map to docs/failure-modes.md. */
class Round3Test {

    // ── K: vibration strength ───────────────────────────────────────────
    private val levels = HapticStrength.values().toList() // LOW, MEDIUM, HIGH

    @Test fun everySpecIsPlayable() { // K5
        for (p in HapticPulse.values()) for (st in levels) {
            val s = Haptics.spec(p, st)
            assertTrue("$p/$st amp ${s.amplitude}", s.amplitude in 1..255)
            assertTrue("$p/$st ms ${s.durationMs}", s.durationMs > 0)
            assertTrue("$p/$st scale ${s.scale}", s.scale > 0f && s.scale <= 1f)
        }
    }

    @Test fun levelsStrictlyGrowInAmplitudeAndDuration() { // K6 + K7
        for (p in HapticPulse.values()) {
            val specs = levels.map { Haptics.spec(p, it) }
            for (i in 1 until specs.size) {
                assertTrue("$p amplitude ${specs.map { it.amplitude }}", specs[i].amplitude > specs[i - 1].amplitude)
                assertTrue("$p duration ${specs.map { it.durationMs }}", specs[i].durationMs > specs[i - 1].durationMs)
            }
        }
    }

    @Test fun mediumKeepsTheClassicBases() { // K6: MEDIUM ≈ r2 feel
        assertEquals(160, Haptics.spec(HapticPulse.CLICK, HapticStrength.MEDIUM).amplitude)
        assertEquals(80, Haptics.spec(HapticPulse.TICK, HapticStrength.MEDIUM).amplitude)
        assertEquals(255, Haptics.spec(HapticPulse.HEAVY_CLICK, HapticStrength.HIGH).amplitude)
    }

    @Test fun pulsesStayDistinctAtEveryLevel() { // K8
        for (st in levels) {
            val tick = Haptics.spec(HapticPulse.TICK, st)
            val click = Haptics.spec(HapticPulse.CLICK, st)
            val heavy = Haptics.spec(HapticPulse.HEAVY_CLICK, st)
            assertTrue("$st amplitude", tick.amplitude < click.amplitude && click.amplitude < heavy.amplitude)
            assertTrue("$st duration", tick.durationMs < click.durationMs && click.durationMs < heavy.durationMs)
        }
    }

    @Test fun strengthDefaultsToMediumAndRoundTrips() { // K9
        for (p in ProfileCodec.seed().profiles) assertEquals(p.id, HapticStrength.MEDIUM, p.settings.hapticStrength)
        // r2 storage (no key) and garbage both fall back to MEDIUM
        val r2 = ProfileCodec.encode(ProfileCodec.seed()).filterKeys { !it.endsWith(".hapticStrength") }
        assertEquals(HapticStrength.MEDIUM, ProfileCodec.decode(r2).byId("noche")!!.settings.hapticStrength)
        val junk = ProfileCodec.encode(ProfileCodec.seed()).toMutableMap<String, Any?>().apply { put("p.noche.hapticStrength", "MAX") }
        assertEquals(HapticStrength.MEDIUM, ProfileCodec.decode(junk).byId("noche")!!.settings.hapticStrength)
        // save → load, without leaking into another profile
        val st = ProfileCodec.seed().update("dia") { it.copy(hapticStrength = HapticStrength.HIGH) }
        val back = ProfileCodec.decode(ProfileCodec.encode(st))
        assertEquals(HapticStrength.HIGH, back.byId("dia")!!.settings.hapticStrength)
        assertEquals(HapticStrength.MEDIUM, back.byId("noche")!!.settings.hapticStrength)
    }

    @Test fun mechanismFollowsMotorCapabilities() { // K10
        assertEquals(HapticMechanism.PRIMITIVE, Haptics.mechanism(primitivesSupported = true, amplitudeControl = true))
        assertEquals(HapticMechanism.AMPLITUDE, Haptics.mechanism(primitivesSupported = false, amplitudeControl = true))
        assertEquals(HapticMechanism.DURATION, Haptics.mechanism(primitivesSupported = false, amplitudeControl = false))
    }

    // ── A: autocorrect against the lexicons that ship in the APK ────────
    private fun lexicon(code: String): List<String> {
        val f = File("src/main/assets/lexicon/$code.txt")
        assertTrue("lexicon not found from ${File(".").absolutePath}", f.exists())
        return f.readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() }
    }

    private val esWords by lazy { lexicon("es") }
    private val es by lazy { Suggest(esWords) }
    private val en by lazy { Suggest(lexicon("en")) }

    @Test fun obviousTyposAreCorrected() { // A1 + A6
        val cases = mapOf(
            "casaa" to "casa", "qie" to "que", "tambein" to "también", "grcias" to "gracias",
            "manana" to "mañana", "holaa" to "hola", "porqe" to "porque", "vamso" to "vamos",
            "buneo" to "bueno", "tiemop" to "tiempo", "nesecito" to "necesito", // 2 edits on a long word
            "cssa" to "casa", // A6: s↔a are neighbors, s↔o are not → casa, not cosa
        )
        for ((typo, want) in cases) assertEquals(typo, want, es.correction(typo, sentenceStart = false))
        val enCases = mapOf("teh" to "the", "becuase" to "because", "realy" to "really", "recieve" to "receive", "wiht" to "with")
        for ((typo, want) in enCases) assertEquals(typo, want, en.correction(typo, sentenceStart = false))
    }

    @Test fun lexiconWordsAreNeverCorrected() { // A2, every shipped word
        for ((name, words, s) in listOf(Triple("es", esWords, es), Triple("en", lexicon("en"), en))) {
            var respelled = 0
            for (w in words) {
                if (!w.all { it.isLetter() } || w.length < Suggest.MIN_LEN) continue
                val fix = s.correction(w, sentenceStart = false) ?: continue
                // only the accent / ñ twin (r8: "senor" → "señor"), never a different word
                assertEquals("$name: $w → $fix", Suggest.fold(w).replace('ñ', 'n'), Suggest.fold(fix).replace('ñ', 'n'))
                respelled++
            }
            assertTrue("$name: $respelled accent respellings", respelled < words.size / 50)
        }
        for (w in listOf("casa", "cosa", "esta", "que", "como", "este", "solo")) assertNull(w, es.correction(w, false))
        assertEquals("también", es.correction("tambien", false)) // by design: ≥ 10× more frequent twin
    }

    @Test fun caseIsPreservedAndNamesAreLeftAlone() { // A3
        assertEquals("Casa", es.correction("Casaa", sentenceStart = true))
        assertEquals("También", es.correction("Tambein", sentenceStart = true))
        assertNull(es.correction("Casaa", sentenceStart = false)) // a capital mid-sentence is a name
        assertNull(es.correction("CASAA", sentenceStart = true)) // acronym / shouting
        assertNull(es.correction("cAsaa", sentenceStart = true)) // mixed case (iPhone-like)
    }

    @Test fun shortWordsDigitsAndGluedWordsAreUntouched() { // A4
        for (w in listOf("a", "q", "qe", "xq", "c4sa", "1234", "casa1")) assertNull(w, es.correction(w, false))
        assertFalse(Suggest.standalone("user@casaa", "casaa"))
        assertFalse(Suggest.standalone("12casaa", "casaa"))
        assertFalse(Suggest.standalone("www.casaa", "casaa"))
        assertTrue(Suggest.standalone("¿casaa", "casaa"))
        assertTrue(Suggest.standalone("hola casaa", "casaa"))
        assertTrue(Suggest.standalone("casaa", "casaa"))
    }

    @Test fun farOrAmbiguousWordsAreNotForced() { // A5
        for (w in listOf("xyzzy", "qwrtp", "pololo", "cachai", "weon", "wea", "fome", "pucha", "jaja", "sipo", "bkn", "oka", "carrete", "hol", "sta"))
            assertNull(w, es.correction(w, false))
        // English contraction stems stay as typed (no apostrophes in the lexicon)
        for (w in listOf("doesnt", "didnt", "isnt")) assertNull(w, en.correction(w, false))
        assertEquals(2, Suggest.maxDistance(7))
        assertEquals(1, Suggest.maxDistance(6))
        assertEquals(0, Suggest.maxDistance(2))
    }

    @Test fun barShowsTheCorrectionFirst() { // A12
        assertEquals("casa", es.suggest("casaa").first())
        assertEquals("Casa", es.suggest("Casaa").first())
        assertEquals("está", es.suggest("esta").first()) // r1 accent behavior kept
        assertTrue("hola" in es.suggest("hol"))
    }

    // ── A: space applies the correction (engine) ────────────────────────
    private val space = Key(KeyType.SPACE, "", " ")
    private val bksp = Key(KeyType.BACKSPACE, "⌫")
    private val x = Key(KeyType.CHAR, "x", "x")
    private val fixes = mapOf("casaa" to "casa", "Casaa" to "Casa", "qie" to "que")
    private val asked = ArrayList<Pair<String, Boolean>>()
    private fun engine(on: Boolean = true) = KeyboardEngine().apply {
        start(FieldInfo())
        corrector = if (on) Corrector { w, start -> asked += w to start; fixes[w] } else null
    }

    @Test fun spaceCommitOnAndOff() { // A7
        assertEquals(listOf(Out.DeleteBefore(5), Out.Commit("casa ")), engine().press(space, "mi casaa", 0))
        assertEquals(listOf(Out.Commit(" ")), engine(on = false).press(space, "mi casaa", 0))
        assertEquals(listOf(Out.Commit(" ")), engine().press(space, "mi casa", 0)) // nothing to fix
        assertEquals(listOf(Out.Commit(" ")), engine().press(space, "", 0))
    }

    @Test fun manualPickIsNotCorrectedTwice() { // A8
        val e = engine()
        e.pickSuggestion("casa", "casaa")
        asked.clear()
        assertEquals(listOf(Out.Commit(" ")), e.press(space, "mi casa ", 10))
        assertTrue(asked.isEmpty())
    }

    @Test fun correctionComposesWithDoubleSpace() { // A9
        val e = engine()
        assertEquals(listOf(Out.DeleteBefore(5), Out.Commit("casa ")), e.press(space, "mi casaa", 0))
        assertEquals(listOf(Out.DeleteBefore(1), Out.Commit(". ")), e.press(space, "mi casa ", 300))
        val off = engine().apply { doubleSpacePeriod = false }
        off.press(space, "mi casaa", 0)
        assertEquals(listOf(Out.Commit(" ")), off.press(space, "mi casa ", 300))
    }

    @Test fun backspaceUndoesTheCorrectionOnce() { // A10
        val e = engine()
        e.press(space, "mi casaa", 0)
        assertEquals(listOf(Out.DeleteBefore(5), Out.Commit("casaa")), e.press(bksp, "mi casa ", 100))
        assertEquals(listOf(Out.Commit(" ")), e.press(space, "mi casaa", 200)) // the user meant it
        assertEquals(listOf(Out.Backspace), e.press(bksp, "mi casaa ", 300)) // plain backspace again
        // undo is only offered right after the correction
        val e2 = engine()
        e2.press(space, "mi casaa", 0)
        e2.press(x, "mi casa ", 50)
        assertEquals(listOf(Out.Backspace), e2.press(bksp, "mi casa x", 100))
        // and never deletes text that isn't the correction (cursor moved elsewhere)
        val e3 = engine()
        e3.press(space, "mi casaa", 0)
        assertEquals(listOf(Out.Backspace), e3.press(bksp, "otra cosa", 100))
    }

    @Test fun midWordAndGluedWordsAreNotCorrected() { // A11 + A4
        assertEquals(listOf(Out.Commit(" ")), engine().press(space, "mi casaa", 0, after = "s"))
        assertEquals(listOf(Out.DeleteBefore(5), Out.Commit("casa ")), engine().press(space, "mi casaa", 0, after = "."))
        assertEquals(listOf(Out.Commit(" ")), engine().press(space, "yo@casaa", 0))
        assertEquals(listOf(Out.Commit(" ")), engine().press(space, "12casaa", 0))
    }

    @Test fun correctorLearnsWhetherTheWordStartsASentence() { // A3 plumbing
        asked.clear()
        engine().press(space, "Casaa", 0)
        engine().press(space, "fin. Casaa", 0)
        engine().press(space, "hola Casaa", 0)
        engine().press(space, "¿Casaa", 0)
        assertEquals(listOf("Casaa" to true, "Casaa" to true, "Casaa" to false, "Casaa" to true), asked)
    }

    @Test fun spaceCorrectsDefaultsOnAndRoundTrips() { // A7 storage
        for (p in ProfileCodec.seed().profiles) assertTrue(p.id, p.settings.spaceCorrects)
        val r2 = ProfileCodec.encode(ProfileCodec.seed()).filterKeys { !it.endsWith(".spaceCorrects") }
        assertTrue(ProfileCodec.decode(r2).byId("noche")!!.settings.spaceCorrects)
        val st = ProfileCodec.seed().update("noche") { it.copy(spaceCorrects = false) }
        val back = ProfileCodec.decode(ProfileCodec.encode(st))
        assertFalse(back.byId("noche")!!.settings.spaceCorrects)
        assertTrue(back.byId("dia")!!.settings.spaceCorrects)
    }
}
