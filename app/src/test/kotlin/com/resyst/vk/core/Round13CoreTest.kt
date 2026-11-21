package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * r13a phase B — the pieces around the corrector (docs/failure-modes.md LR4–LR7, LC4–LC5, NW2,
 * VR1): the rejection store and its surfaces, the engine's field memory, casing persistence, the
 * keyboard geometry. The session replay is Round13ReplayTest; the key-path behaviours with their
 * bite proofs are Round13LearningTest.
 */
class Round13CoreTest {
    private val es = Lang.ES
    private val en = Lang.EN
    private val lex = Suggest(listOf("de", "que", "hoy", "y", "es", "ayer", "con", "bastón", "bastante", "base"), Lang.ES)
    private val space = Key(KeyType.SPACE, " ")
    private val bs = Key(KeyType.BACKSPACE, "⌫")

    private fun engine(rej: Rejections? = null, m: PersonalModel? = null) = KeyboardEngine().apply {
        start(FieldInfo(autoCap = false))
        corrector = Corrector { w, s -> Bar.correction(w, s, lex, m, es, rejected = rej) }
    }

    private fun KeyboardEngine.typeOn(text: String, s: String): String {
        var t = text
        for (c in s) t = Edits.apply(t, press(if (c == ' ') space else Key(KeyType.CHAR, c.toString(), c.toString()), t, 0L))
        return t
    }

    // ── LR5: the rejection store ─────────────────────────────────────────
    @Test fun rejectionsAreStoredBoundedAndRoundTrip() {
        val r = Rejections()
        assertTrue(r.add("Bastiono", "bastón"))
        assertFalse("same pair again, already newest", r.add("bastiono", "Bastón"))
        assertTrue(r.rejects("BASTIONO", "bastón"))
        assertFalse(r.rejects("bastiono", "bastante"))
        assertFalse("not a word pair", r.add("a", "b") || r.add("x1", "xy") || r.add("hola", "hola"))
        for (i in 0 until Rejections.CAP + 5) r.add("typo${'a' + i % 26}${'a' + i / 26}", "fix")
        assertEquals(Rejections.CAP, r.size)
        assertFalse("the oldest goes first", r.rejects("bastiono", "bastón"))
        val back = Rejections.fromJson(r.toJson())
        assertEquals(r.items(), back.items())
        assertEquals(0, Rejections.fromJson("{basura").size)
        assertEquals(1, Rejections.fromJson("{\"v\":1,\"rejected\":[[\"teh\",\"the\"],[1,2],[\"x\"],\"junk\"]}").size)
        assertTrue(back.forget(back.items().first().typed, back.items().first().fix))
    }

    @Test fun aRejectedFixIsNeverAppliedAgain() { // LR5
        val r = Rejections()
        assertEquals("bastón", Bar.correction("bastiono", false, lex, null, es, rejected = r))
        r.add("bastiono", "bastón")
        assertNull(Bar.correction("bastiono", false, lex, null, es, rejected = r))
        val e = engine(r)
        assertEquals("hoy bastiono ", e.typeOn("", "hoy bastiono "))
    }

    // ── LR1/LR4/LR5: the engine hands every rejection out once ───────────
    @Test fun everyRejectionPathReportsThePair() {
        val e = engine()
        var t = e.typeOn("", "hoy bastiono ")
        assertEquals("hoy bastón ", t)
        t = Edits.apply(t, e.press(bs, t, 0L)) // ⌫ right after (A10)
        assertEquals("bastiono" to "bastón", e.takeRejection())
        assertNull("read once", e.takeRejection())
        assertEquals(0, e.fieldCorrections)

        val chip = engine()
        t = chip.typeOn("", "hoy bastiono ")
        t = Edits.apply(t, chip.undoCorrection(t)!!) // ↶ (U2)
        assertEquals("bastiono" to "bastón", chip.takeRejection())

        val late = engine()
        t = late.typeOn("", "hoy bastiono y ")
        late.deleting("hoy bastón", "", "bastón y") // a selection deleted
        t = "hoy "
        t = late.typeOn(t, "bastiono ")
        assertEquals("hoy bastiono ", t)
        assertEquals("bastiono" to "bastón", late.takeRejection())
        assertEquals("bastiono", late.takeReverted())
    }

    @Test fun aDeletionWithoutTheRetypeExpires() { // LR3: deleting a correction and typing something else
        val e = engine()
        var t = e.typeOn("", "hoy bastiono ")
        e.deleting("hoy bastón", "")
        t = e.typeOn("hoy ", "de que es y ") // 4 other words: the deletion is over
        assertEquals("hoy de que es y bastón ", e.typeOn(t, "bastiono "))
        assertNull(e.takeRejection())
    }

    @Test fun deletingAWordVkDidNotCorrectIsNotARejection() { // LR3
        val e = engine()
        e.typeOn("", "hoy bastón ") // typed by the user, never corrected
        e.deleting("hoy bastón", "")
        assertEquals("hoy bastón ", e.typeOn("hoy ", "bastiono "))
    }

    @Test fun theFieldMemoryIsPerFieldAndBounded() { // LR6
        val e = engine()
        e.typeOn("", "hoy bastiono ")
        assertEquals(1, e.fieldCorrections)
        e.start(FieldInfo(autoCap = false), sameField = true) // a restart / back to the same editor
        assertEquals(1, e.fieldCorrections)
        e.start(FieldInfo(autoCap = false)) // another editor
        assertEquals(0, e.fieldCorrections)
        var t = ""
        repeat(KeyboardEngine.FIELD_MEMORY + 5) { t = e.typeOn(t, "bastiono ") }
        assertEquals(KeyboardEngine.FIELD_MEMORY, e.fieldCorrections)
    }

    // ── LR5: every store under files/personal/ is counted, listed and wiped ──
    @Test fun rejectionsAreOnTheLearnedDataSurfaces() {
        assertFalse(PersonalSummary.Counts(0, 0, 0, 0, rejections = 1).empty)
        assertEquals("2 correcciones rechazadas · nada sale del teléfono", PersonalSummary.text(TestTxt.ES, PersonalSummary.Counts(0, 0, 0, 0, 2)))
        assertEquals("1 palabra · 1 corrección rechazada · nada sale del teléfono", PersonalSummary.text(TestTxt.ES, PersonalSummary.Counts(1, 0, 0, 0, 1)))
        val store = File("src/main/kotlin/com/resyst/vk/ime/PersonalStore.kt").readText()
        assertTrue(store.contains("\"rejections.json\"") && store.contains("rejections = Rejections()"))
        val settings = File("src/main/kotlin/com/resyst/vk/settings/SettingsActivity.kt").readText()
        assertTrue("the wipe row counts them", settings.contains("rejections = PersonalStore.rejections?.size"))
        assertTrue("listed one by one", settings.contains("Ctl.KNOW_REJECTIONS -> knowRejections()") && settings.contains("r.forget(e.typed, e.fix)"))
        assertEquals("datos", SettingsIA.pageOf(Ctl.KNOW_REJECTIONS)?.id)
        // every file the app writes under files/personal/ is named on a surface (repo rule)
        val personalFiles = Regex("\"([a-z_]+\\.(?:json|txt))\"").findAll(store + File("src/main/kotlin/com/resyst/vk/ime/ResystImeService.kt").readText())
            .map { it.groupValues[1] }.filter { it.startsWith("words") || it.startsWith("values") || it.startsWith("rejections") || it.startsWith("emoji_") }.toSet()
        assertEquals(setOf("words.json", "values.json", "rejections.json", "emoji_recents.txt", "emoji_tones.txt"), personalFiles)
    }

    // ── LC4/LC5: casing evidence ─────────────────────────────────────────
    @Test fun casingEvidencePersistsAndMigrates() {
        val m = PersonalModel()
        m.learn(es, "uso", "GrapheneOS", false)
        m.learn(es, "con", "GrapheneOS", false)
        assertEquals("GrapheneOS", m.casing(listOf(es), "grapheneos", false))
        assertEquals("GrapheneOS", m.casing(listOf(es), "Grapheneos", true))
        assertNull("typed with capitals: the user chose (LC4)", m.casing(listOf(es), "GRAPHENEOS", false))
        assertNull(m.casing(listOf(es), "Grapheneos", false))
        val back = PersonalModel.fromJson(m.toJson())
        assertEquals("GrapheneOS", back.casing(listOf(es), "grapheneos", false))
        assertEquals(m.toJson(), back.toJson())
        // still v2 (LC5): an r11 / r12 reader takes 5 fields of [key, form, count, stamp, kept, cap]
        assertTrue(m.toJson().startsWith("{\"v\":2,"))
        assertTrue(m.toJson().contains("[\"grapheneos\",\"GrapheneOS\",2,"))
        // a file written by r11/r12 (5 fields) has no evidence yet: two new uses needed
        val v2 = PersonalModel.fromJson("{\"v\":2,\"clock\":3,\"langs\":{\"es\":{\"vocab\":[[\"pixel\",\"Pixel\",5,3,0]],\"starters\":[],\"next\":[]}}}")
        assertTrue(v2.knows(es, "pixel", 5))
        assertNull(v2.casing(listOf(es), "pixel", false))
        v2.learn(es, null, "Pixel", false)
        v2.learn(es, null, "Pixel", false)
        assertEquals("Pixel", v2.casing(listOf(es), "pixel", false))
        // junk evidence is none
        assertNull(PersonalModel.fromJson("{\"v\":2,\"clock\":1,\"langs\":{\"es\":{\"vocab\":[[\"pixel\",\"Pixel\",5,1,0,99]],\"starters\":[],\"next\":[]}}}").casing(listOf(es), "pixel", false))
        assertNull(PersonalModel.fromJson("{\"v\":2,\"clock\":1,\"langs\":{\"es\":{\"vocab\":[[\"pixel\",\"Pixel\",5,1,0,\"x\"]],\"starters\":[],\"next\":[]}}}").casing(listOf(es), "pixel", false))
        // Olvidar drops it (LC5)
        assertTrue(m.forget(es, "grapheneos"))
        assertNull(m.casing(listOf(es), "grapheneos", false))
    }

    @Test fun casingIsReadInEveryVocabularyAndAContradictionAnywhereWins() { // LC3 + BL8
        val m = PersonalModel()
        repeat(2) { m.learn(en, "my", "Pixel", false) }
        assertEquals("Pixel", m.casing(listOf(es, en), "pixel", false))
        assertNull("bilingual off: the other table is not read", m.casing(listOf(es), "pixel", false))
        m.learn(es, "un", "pixel", false)
        assertNull(m.casing(listOf(es, en), "pixel", false))
        val u = PersonalModel()
        repeat(2) { u.learn(es, "el", "Bastión", false) }
        u.learn(es, null, "Bastión", true) // a sentence start is no evidence either way
        assertEquals("Bastión", u.casing(listOf(es), "bastión", false))
        u.unlearn(es, "el", "Bastión", false) // M10 takes the last use back, evidence included
        u.learn(es, "el", "bastión", false)
        u.unlearn(es, "el", "bastión", false)
        assertEquals("an undone lowercase use is no contradiction", "Bastión", u.casing(listOf(es), "bastión", false))
    }

    // ── NW2: the drawn keyboard ──────────────────────────────────────────
    @Test fun geometryFollowsTheDrawnLayout() {
        val esPos = TypoModel.centres(es)
        val side = TypoModel.sub(esPos, 'n', 'm', true)
        val vertical = TypoModel.sub(esPos, 'i', 'k', true)
        val far = TypoModel.sub(esPos, 'q', 'm', true)
        assertTrue("a side neighbour is the cheapest slip: $side < $vertical", side < vertical)
        assertTrue(vertical < far)
        assertTrue("ñ sits under p on the ES layout", TypoModel.near(esPos, 'ñ', 'p'))
        assertFalse("no ñ on the EN layout", TypoModel.centres(en).containsKey('ñ'))
        assertTrue("EN home row is offset: a is no longer under q", (TypoModel.dist2(TypoModel.centres(en), 'a', 'q') ?: 0f) > (TypoModel.dist2(esPos, 'a', 'q') ?: 9f))
        assertEquals("s/z (seseo) costs a same-sound swap in Spanish", TypoModel.PHON, TypoModel.sub(esPos, 's', 'z', true), 0.001f)
        assertTrue("… and only its key distance in English", TypoModel.sub(esPos, 's', 'z', false) > TypoModel.PHON)
        val rows = TypoModel.EditRows(es)
        rows.reset("provlema", 2, 3f)
        assertTrue(rows.score("problema"))
        assertTrue("one same-sound swap", rows.cost <= TypoModel.PHON + 0.001f)
        rows.reset("kalle", 2, 3f)
        assertTrue(rows.score("calle"))
        rows.reset("aser", 2, 3f)
        assertTrue("silent h + soft c: two sound edits", rows.score("hacer") && rows.cost <= 2 * TypoModel.PHON + 0.001f)
        rows.reset("yamar", 2, 3f)
        assertTrue("yeísmo", rows.score("llamar") && rows.cost <= TypoModel.PHON + 0.001f)
    }

    // ── VR1 → r13c VR2 → r13d VR3 → r13f VR4 → r13h VR5 → r13i VR6 → r14 VR7 → r14b VR8 ──
    @Test fun versionIs0141() { // r14b VR8
        val g = File("build.gradle.kts").readText()
        assertTrue(g.contains("?.toInt() ?: 18"))
        assertTrue(g.contains("?: \"0.14.1\""))
        assertNotNull(Regex("versionCode = \\(findProperty").find(g))
    }
}
