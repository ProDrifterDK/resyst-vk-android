package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** r10 bet 4 — bilingual + regional (docs/failure-modes.md FL1–FL5, BL1–BL7, RG1–RG6). */
class Round10BilingualTest {

    private val dir = File("src/main/assets/lexicon")
    private fun list(name: String) = File(dir, name).readLines().map { it.trim() }.filter { it.isNotEmpty() }
    private val esWords by lazy { list("es.txt") }
    private val enWords by lazy { list("en.txt") }
    private val es by lazy { Suggest(esWords, Lang.ES) }
    private val en by lazy { Suggest(enWords, Lang.EN) }
    private fun rule(name: String): String? = File(dir, "regional/$name.txt").takeIf { it.exists() }?.readText()
    private val esCl by lazy { Regional.forRegion(esWords, Region.ES_CL, ::rule) }
    private val es419 by lazy { Regional.forRegion(esWords, Region.ES_419, ::rule) }

    private val rank: (Lang, String) -> Int? = { l, w -> (if (l == Lang.ES) es else en).rank(w) }
    private fun detect(text: String, kb: Lang, knows: (Lang, String) -> Boolean = { _, _ -> false }) =
        BiLang.detect(BiLang.recentWords(text, windowFull = false), kb, rank, knows)

    // ── FL: the language flick on space ─────────────────────────────────
    @Test fun aQuickVerticalFlickIsAFlick() { // FL1
        assertEquals(SpaceGesture.Kind.FLICK, SpaceGesture.classify(0f, -24f, 120))
        assertEquals(SpaceGesture.Kind.FLICK, SpaceGesture.classify(3f, 22f, 200)) // down works too
        assertEquals(SpaceGesture.Kind.FLICK, SpaceGesture.classify(9f, -20f, 300))
    }

    @Test fun axesNeverStealEachOther() { // FL2
        assertEquals(SpaceGesture.Kind.CURSOR, SpaceGesture.classify(30f, 4f, 120))
        assertEquals(SpaceGesture.Kind.CURSOR, SpaceGesture.classify(-15f, 0f, 900))
        // diagonal: too much x for a flick, too much y for a cursor drag
        assertEquals(SpaceGesture.Kind.NONE, SpaceGesture.classify(15f, -25f, 120))
        assertEquals(SpaceGesture.Kind.NONE, SpaceGesture.classify(20f, -20f, 120))
    }

    @Test fun jitterAndSlowSlidesDoNothing() { // FL3
        assertEquals(SpaceGesture.Kind.NONE, SpaceGesture.classify(4f, -6f, 80))
        assertEquals(SpaceGesture.Kind.NONE, SpaceGesture.classify(0f, -19f, 100))
        assertEquals(SpaceGesture.Kind.NONE, SpaceGesture.classify(0f, -40f, 301))
    }

    @Test fun languageIsPhoneWideAndRoundTrips() { // FL5 + RG5 storage
        val st = ProfileCodec.seed().updatePhone { it.copy(lang = Lang.EN, bilingual = false, region = Region.ES_ES) }
        val back = ProfileCodec.decode(ProfileCodec.encode(st))
        assertEquals(st, back)
        for (t in back.temas) assertEquals(t.id, Lang.EN, back.withTema(t.id).settings.lang)
        assertFalse(back.withTema(back.nextTemaId()).settings.bilingual)
        assertEquals(Region.ES_ES, back.withTema(back.nextTemaId()).settings.region)
    }

    @Test fun defaultsAreBilingualChile() { // RG5
        val d = ProfileCodec.decode(emptyMap<String, Any?>()).settings
        assertTrue(d.bilingual)
        assertEquals(Region.ES_CL, d.region)
        val junk = ProfileCodec.encode(ProfileCodec.seed()).toMutableMap<String, Any?>()
        junk["phone.region"] = "ES_AR"; junk["phone.bilingual"] = "maybe"
        assertEquals(Region.ES_CL, ProfileCodec.decode(junk).settings.region)
        assertTrue(ProfileCodec.decode(junk).settings.bilingual)
        // r9 / v1 storage has neither key: lands on the defaults
        val v1 = mapOf("v" to "1", "active" to "noche", "order" to "noche,dia", "p.noche.lang" to "EN")
        val m = ProfileCodec.decode(v1).settings
        assertEquals(Lang.EN, m.lang); assertTrue(m.bilingual); assertEquals(Region.ES_CL, m.region)
    }

    @Test fun settingsExposeBothControlsOnTheLanguagePage() { // RG5
        assertEquals("teclas", SettingsIA.pageOf(Ctl.BILINGUAL)?.id)
        assertEquals("teclas", SettingsIA.pageOf(Ctl.REGION)?.id)
        assertEquals(Scope.DEVICE, Ctl.BILINGUAL.scope)
        assertEquals(Scope.DEVICE, Ctl.REGION.scope)
    }

    // ── BL: bilingual detection + the other-language guard ──────────────
    @Test fun nothingKnownKeepsTheKeyboardLanguage() { // BL1
        assertEquals(Lang.ES, detect("", Lang.ES))
        assertEquals(Lang.EN, detect("", Lang.EN))
        assertEquals(Lang.ES, detect("zzqx brrt ", Lang.ES))
        assertEquals(Lang.ES, detect("no me ", Lang.ES)) // words both languages use weigh ~0
    }

    @Test fun threeClearWordsSwitch() { // BL2
        assertEquals(Lang.EN, detect("I think the ", Lang.ES))
        assertEquals(Lang.EN, detect("see you at the meeting ", Lang.ES))
        assertEquals(Lang.EN, detect("what do you mea", Lang.ES)) // the word being typed is excluded
        assertEquals(Lang.ES, detect("creo que tengo ", Lang.EN))
        assertEquals(Lang.ES, detect("hola cómo estás ", Lang.EN))
        assertEquals(listOf("do", "you"), BiLang.recentWords("what do you mea", false).takeLast(2))
    }

    @Test fun oneForeignWordDoesNotFlipTheSentence() { // BL3
        assertEquals(Lang.ES, detect("mañana tengo meeting ", Lang.ES))
        assertEquals(Lang.ES, detect("voy al meeting ", Lang.ES))
        assertEquals(Lang.EN, detect("I love empanadas ", Lang.EN))
        // only the last WINDOW words count: an old English run doesn't stick
        assertEquals(Lang.ES, detect("I think the gente tiene razón ", Lang.ES))
    }

    @Test fun habitsCountForTheirLanguage() { // BL2 (personal)
        val knows: (Lang, String) -> Boolean = { l, w -> l == Lang.EN && w in setOf("deploy", "merge") }
        assertEquals(Lang.EN, detect("deploy merge ", Lang.ES, knows))
        assertEquals(Lang.ES, detect("deploy merge ", Lang.ES))
    }

    @Test fun otherLanguageWordsAreNotCorrected() { // BL4
        // measured r9 damage (no guard): space rewrote common words of the other language
        val esLeaks = mapOf("anything" to "nothing", "problem" to "problema", "already" to "ready", "message" to "mensaje")
        val enLeaks = mapOf("sabes" to "saves", "mejor" to "major", "siempre" to "simple", "está" to "esta")
        es.foreign = null; en.foreign = null
        for ((w, bad) in esLeaks) assertEquals("r9 ES $w", bad, es.correction(w, false))
        for ((w, bad) in enLeaks) assertEquals("r9 EN $w", bad, en.correction(w, false))
        es.foreign = en; en.foreign = es
        try {
            for (w in esLeaks.keys + listOf("meeting", "thanks", "really")) assertNull("$w on ES", es.correction(w, false))
            for (w in enLeaks.keys + listOf("tengo", "gracias")) assertNull("$w on EN", en.correction(w, false))
            // real typos of the keyboard language are still fixed
            assertEquals("también", es.correction("tambien", false))
            assertEquals("gracias", es.correction("grcias", false))
            assertEquals("because", en.correction("becuase", false))
        } finally { es.foreign = null; en.foreign = null }
    }

    @Test fun guardOffIsR9() { // BL6
        val plain = Suggest(esWords, Lang.ES)
        for (w in listOf("tambien", "grcias", "meeting", "weekend", "cachai", "hoal")) {
            val a = plain.correction(w, false)
            es.foreign = null
            assertEquals(w, a, es.correction(w, false))
        }
    }

    // ── RG: regional re-rank ────────────────────────────────────────────
    @Test fun rulesNeverLoseOrDuplicateWords() { // RG1 + RG2
        for (r in listOf(esCl, es419)) {
            assertEquals(r.size, r.toSet().size)
            assertTrue("a word was lost", r.toSet().containsAll(esWords.toSet()))
        }
        val added = esCl.toSet() - esWords.toSet()
        assertTrue("es-CL inserts Chilean words: $added", added.containsAll(setOf("cachai", "altiro", "pololo", "fome", "bacán")))
        assertTrue(added.size < 120)
    }

    @Test fun demotedWordsStayKnownAndUncorrected() { // RG1
        val s = Suggest(esCl, Lang.ES)
        for (w in listOf("vosotros", "ordenador", "coche", "tenéis", "mirad", "gilipollas")) {
            assertNotNull("$w kept", s.rank(w))
            assertNull("$w typed exactly stays", s.correction(w, false))
        }
        // pure demotion: tail, in order, still present
        val out = Regional.apply(listOf("a", "b", "c", "d"), listOf(Regional.Rule("b", null)))
        assertEquals(listOf("a", "c", "d", "b"), out)
    }

    @Test fun chileanAndLatamFormsRankFirst() { // RG3
        fun r(list: List<String>, w: String) = list.indexOf(w).also { assertTrue("$w missing", it >= 0) }
        for (list in listOf(esCl, es419)) {
            assertTrue(r(list, "carro") < r(list, "coche"))
            assertTrue(r(list, "computadora") < r(list, "ordenador"))
            assertTrue(r(list, "celular") < r(list, "móvil"))
            assertTrue(r(list, "ustedes") < r(list, "vosotros"))
            assertTrue(r(list, "jugo") < r(list, "zumo"))
        }
        assertTrue(r(esCl, "cachai") < 2000)
        assertTrue(r(esCl, "po") < 1000)
        val s = Suggest(esCl, Lang.ES)
        for (w in listOf("cachai", "altiro", "pololo", "fome", "bacán", "weón")) assertNull("$w is a word", s.correction(w, false))
        assertTrue(s.suggest("cach", 3).contains("cachai"))
        assertTrue(s.suggest("polo", 3).contains("pololo"))
        // vosotros forms and peninsular words go to the tail (after every non-demoted word)
        val tail = esCl.size - 300
        for (w in listOf("vosotros", "tenéis", "habéis", "mirad", "sentaos", "ordenador", "coche")) assertTrue("$w ${esCl.indexOf(w)}", esCl.indexOf(w) > tail)
        // and noun plurals that look like imperatives are not touched ("correos" ← correo)
        assertTrue(esCl.indexOf("correos") < tail)
    }

    @Test fun spainIsTheR8List() { // RG4
        assertEquals(esWords, Regional.forRegion(esWords, Region.ES_ES, ::rule))
    }

    @Test fun malformedRulesAreSkipped() { // RG5
        val base = listOf("uno", "dos", "tres", "cuatro")
        val rules = Regional.parse(
            """
            # comment
            tres 0
            cinco x
            seis -3
            nada = inexistente
            dos = uno   # dos takes uno's rank
            -
            -cuatro
            ✦✦ 3
            con espacios 1 2
            siete 99
            """.trimIndent(), base,
        )
        assertEquals(listOf(Regional.Rule("tres", 0), Regional.Rule("dos", 0), Regional.Rule("cuatro", null), Regional.Rule("siete", 99)), rules)
        val out = Regional.apply(base, rules)
        assertEquals(setOf("uno", "dos", "tres", "cuatro", "siete"), out.toSet())
        assertEquals(5, out.size)
        assertEquals("cuatro", out.last())
        // a promotion never moves a word down
        assertEquals(listOf(Regional.Rule("uno", 0)), Regional.parse("uno 3", base))
    }

    @Test fun ruleFilesAreWellFormed() { // RG5 (shipped assets)
        for (name in listOf("es-419", "es-CL")) {
            val text = rule(name)
            assertNotNull(name, text)
            val lines = text!!.lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
            val parsed = Regional.parse(text, esWords)
            assertEquals("$name: every rule line parses", lines.size, parsed.size)
        }
    }
}
