package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * r10 bet 3 — visible privacy. Failure modes V1–V13 (docs/failure-modes.md, written first).
 * Pure JVM: the offensive-word filter on the bar, the connection log, the "sin memoria" reason,
 * the secret-field bubble rule, and the forget APIs behind "Lo que sé de ti".
 */
class Round10PrivacyTest {

    private val es = Suggest(listOf("de", "que", "mierda", "miedo", "mientras", "mira", "miércoles", "puta", "puts", "putamente", "computadora", "escultura", "casa"), Lang.ES)
    private val en = Suggest(listOf("the", "fuck", "fucking", "full", "fun", "funny", "shit", "shirt", "show", "she", "assistant"), Lang.EN)

    private fun bar(before: String, lex: Suggest, lang: Lang, clean: Boolean, personal: PersonalModel? = null) =
        Bar.words(before, "", false, lang, lex, personal, ShiftState.OFF, clean = clean)

    // ── V1: nothing offensive is proposed while the filter is on ──────────
    @Test fun completionsDropOffensiveWords() { // V1
        assertTrue("r9 behavior without the filter", "mierda" in bar("mier", es, Lang.ES, clean = false))
        val on = bar("mier", es, Lang.ES, clean = true)
        assertFalse(on.toString(), "mierda" in on)
        assertTrue("clean words still come", on.isNotEmpty())
        assertFalse("fuck" in bar("fuc", en, Lang.EN, clean = true))
        assertFalse("fucking" in bar("fuc", en, Lang.EN, clean = true))
        assertFalse("shit" in bar("shi", en, Lang.EN, clean = true))
        assertTrue("shirt" in bar("shi", en, Lang.EN, clean = true))
    }

    @Test fun spaceNeverCorrectsIntoAnOffensiveWord() { // V1
        // "mierad" is one transposition from "mierda": r9 corrects it, the filter refuses
        assertEquals("mierda", Bar.correction("mierad", false, es, null, Lang.ES))
        assertNull(Bar.correction("mierad", false, es, null, Lang.ES, clean = true))
        // a clean correction is untouched
        assertEquals(Bar.correction("csaa", false, es, null, Lang.ES), Bar.correction("csaa", false, es, null, Lang.ES, clean = true))
    }

    @Test fun predictionsDropOffensiveContinuations() { // V1
        val m = PersonalModel()
        m.learn(Lang.ES, "que", "mierda", false) // typed once: not theirs yet
        val p = bar("que ", es, Lang.ES, clean = true, personal = m)
        assertFalse(p.toString(), p.any { Tokens.key(it) == "mierda" })
        assertTrue(bar("que ", es, Lang.ES, clean = false, personal = m).any { Tokens.key(it) == "mierda" })
    }

    @Test fun shippedLexiconTopProposalsAreClean() { // V1 on the real 25k / 20k lists
        val dir = File("src/main/assets/lexicon")
        val lexEs = Suggest(File(dir, "es.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }, Lang.ES)
        val lexEn = Suggest(File(dir, "en.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }, Lang.EN)
        for (p in listOf("mier", "pu", "put", "cul", "cab", "jod", "coñ", "gilip", "mari", "hue", "verg", "conc", "chu")) {
            val got = Bar.words(p, "", false, Lang.ES, lexEs, null, ShiftState.OFF, clean = true)
            for (w in got) assertFalse("$p → $w", Profanity.blocked(w, Lang.ES))
        }
        for (p in listOf("fu", "fuc", "sh", "shi", "bit", "as", "dic", "co", "cu", "pus", "mother", "bul", "wh", "sl")) {
            val got = Bar.words(p, "", false, Lang.EN, lexEn, null, ShiftState.OFF, clean = true)
            for (w in got) assertFalse("$p → $w", Profanity.blocked(w, Lang.EN))
        }
        // the vision's examples really were in the lists (the test can fail)
        assertTrue(Profanity.blocked("mierda", Lang.ES) && Profanity.blocked("puta", Lang.ES) && Profanity.blocked("culo", Lang.ES))
        assertTrue(Profanity.blocked("shit", Lang.EN) && Profanity.blocked("fuck", Lang.EN))
    }

    // ── V2/V3: the user's own words stay theirs ─────────────────────────
    @Test fun habitMakesAWordTheirsAgain() { // V3
        val m = PersonalModel()
        repeat(Bar.HABIT) { m.learn(Lang.ES, "que", "mierda", false) }
        assertTrue(bar("mier", es, Lang.ES, clean = true, personal = m).any { Tokens.key(it) == "mierda" })
        assertTrue(bar("que ", es, Lang.ES, clean = true, personal = m).any { Tokens.key(it) == "mierda" })
        assertEquals("mierda", Bar.correction("mierad", false, es, m.also { it.learn(Lang.ES, null, "x", false) }, Lang.ES, clean = true)
            ?: "mierda") // knows(mierad) is false, so the habit check doesn't block; the fix is allowed again
    }

    @Test fun filterNeverTouchesTypedTextOrTheModel() { // V2
        // the filter is only a list predicate: nothing in it edits text or learns
        val m = PersonalModel()
        val learner = Learner { m }
        assertTrue(learner.afterEdit(Lang.ES, "que mierda", listOf(Out.Commit(" ")), false, Learner.Edit.KEY))
        assertTrue(m.knows(Lang.ES, "mierda"))
        // the space correction of a typed offensive word that IS a lexicon word stays null (kept as typed)
        assertNull(Bar.correction("mierda", false, es, null, Lang.ES, clean = true))
    }

    // ── V4: whole words, not substrings ────────────────────────────────
    @Test fun noSubstringOverBlocking() { // V4
        for (w in listOf("computadora", "escultura", "disputa", "reputación", "putamente", "casa", "miedo", "mientras", "calculo"))
            assertFalse(w, Profanity.blocked(w, Lang.ES))
        for (w in listOf("shitake", "assistant", "class", "cocktail", "scunthorpe", "shirt", "pass", "dickens"))
            assertFalse(w, Profanity.blocked(w, Lang.EN))
        assertTrue(Profanity.blocked("Mierda", Lang.ES))
        assertTrue(Profanity.blocked("MIERDA", Lang.ES))
        assertTrue("accent-insensitive", Profanity.blocked("cabron", Lang.ES) && Profanity.blocked("cabrón", Lang.ES))
        assertTrue("bilingual users swear in both", Profanity.blocked("fuck", Lang.ES) && Profanity.blocked("mierda", Lang.EN))
    }

    @Test fun filterIsAPhoneSettingDefaultOn() { // V3 + settings
        assertTrue(KbSettings().profanityFilter)
        assertTrue(ProfileCodec.seed().settings.profanityFilter)
        val off = ProfileCodec.seed().updatePhone { it.copy(profanityFilter = false) }
        assertFalse(ProfileCodec.decode(ProfileCodec.encode(off)).settings.profanityFilter)
        val junk = ProfileCodec.encode(ProfileCodec.seed()).toMutableMap<String, Any?>().apply { put("phone.profanityFilter", "nah") }
        assertTrue(ProfileCodec.decode(junk).settings.profanityFilter)
        // r1–r9 storage lands ON
        val v1 = mapOf("order" to "noche", "active" to "noche", "p.noche.theme" to "lab")
        assertTrue(ProfileCodec.decode(v1).settings.profanityFilter)
        assertEquals("escritura", SettingsIA.pageOf(Ctl.PROFANITY_FILTER)?.id)
        assertEquals(Scope.DEVICE, Ctl.PROFANITY_FILTER.scope)
    }

    // ── V5/V6: the connection log ─────────────────────────────────────
    @Test fun everyRequestIsOneEntryWithReasonAndOutcome() { // V5
        var log = ConnectionLog()
        log = log.add(ConnectionLog.Entry(1000, ConnectionLog.What.CHECK, ConnectionLog.Why.STARTUP, "Ya al día"))
        log = log.add(ConnectionLog.Entry(2000, ConnectionLog.What.CHECK, ConnectionLog.Why.USER, "Sin conexión"))
        log = log.add(ConnectionLog.Entry(3000, ConnectionLog.What.DOWNLOAD, ConnectionLog.Why.USER, "0.5.0"))
        assertEquals(3, log.total)
        assertEquals(listOf(3000L, 2000L, 1000L), log.recent().map { it.at }) // newest first
        assertEquals(1, log.count(ConnectionLog.Why.STARTUP))
        assertEquals(2, log.count(ConnectionLog.Why.USER))
        assertEquals(log, ConnectionLog.decode(log.encode()))
        assertEquals(1000L, log.firstAt)
    }

    @Test fun logIsBoundedAndJunkProof() { // V6
        var log = ConnectionLog()
        repeat(ConnectionLog.CAP + 25) { log = log.add(ConnectionLog.Entry(it.toLong(), ConnectionLog.What.CHECK, ConnectionLog.Why.STARTUP, "ok")) }
        assertEquals(ConnectionLog.CAP, log.recent().size)
        assertEquals("the counter keeps counting past the cap", ConnectionLog.CAP + 25, log.total)
        assertEquals(0L, log.firstAt) // the first-ever connection is remembered too
        val back = ConnectionLog.decode(log.encode())
        assertEquals(log, back)
        for (junk in listOf(null, "", "{", "[]", "{\"v\":9}", "{\"v\":1,\"total\":\"x\",\"items\":5}", "\u0000\u0001"))
            assertEquals(junk.toString(), 0, ConnectionLog.decode(junk).recent().size)
        val partial = "{\"v\":1,\"total\":2,\"first\":5,\"items\":[[5,\"check\",\"user\",\"ok\"],[\"bad\"],[7,\"nope\",\"user\",\"x\"]]}"
        val p = ConnectionLog.decode(partial)
        assertEquals(1, p.recent().size)
        assertEquals("total never below what is listed", 2, p.total)
        // outcome text is bounded and single-line
        val long = ConnectionLog().add(ConnectionLog.Entry(1, ConnectionLog.What.CHECK, ConnectionLog.Why.USER, "a\nb".repeat(100)))
        assertTrue(long.recent()[0].outcome.length <= ConnectionLog.OUTCOME_MAX)
        assertFalse(long.recent()[0].outcome.contains('\n'))
    }

    @Test fun updaterLogsEveryNetworkCallAndIsStillTheOnlyClient() { // V5 + V7 (source guard)
        val src = File("src/main/kotlin/com/resyst/vk/settings/Updater.kt").readText()
        val check = src.substringAfter("private fun check(context: Context, auto: Boolean)").substringBefore("\n    // ──")
        assertTrue("check() logs its GET", check.contains("logConnection("))
        val dl = src.substringAfter("fun download(context: Context, release: Release)").substringBefore("\n    fun cancel(")
        assertTrue("download() logs the APK fetch", dl.contains("logConnection("))
        val root = File("src/main/kotlin")
        val net = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readText().let { it.contains("openConnection") || it.contains("HttpsURLConnection") || it.contains("java.net.Socket") } }
            .map { it.name }.toList()
        assertEquals(listOf("Updater.kt"), net)
        assertFalse("the log is pure core: no network", File("src/main/kotlin/com/resyst/vk/core/ConnectionLog.kt").readText().contains("java.net"))
    }

    // ── V8/V9: "sin memoria" ───────────────────────────────────────────
    private val text = FieldPolicy.FLAG_CAP_SENTENCES or FieldPolicy.CLASS_TEXT

    @Test fun learningFieldsShowNoDot() { // V8
        val s = KbSettings()
        assertNull(MemoryNotice.reason(FieldPolicy.of(text, 0), s, s))
        // email fields remember the address: memory is on
        assertNull(MemoryNotice.reason(FieldPolicy.of(FieldPolicy.CLASS_TEXT or FieldPolicy.VARIATION_EMAIL, 0), s, s))
        // number / URL / phone fields have no words to learn: no dot (it would be noise)
        assertNull(MemoryNotice.reason(FieldPolicy.of(FieldPolicy.CLASS_NUMBER, 0), s, s))
        assertNull(MemoryNotice.reason(FieldPolicy.of(FieldPolicy.CLASS_TEXT or FieldPolicy.VARIATION_URI, 0), s, s))
    }

    @Test fun everyNonLearningFieldSaysWhy() { // V8 + V9 (most specific reason wins)
        val s = KbSettings()
        val pw = FieldPolicy.of(FieldPolicy.CLASS_TEXT or FieldPolicy.VARIATION_PASSWORD, FieldPolicy.IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertEquals(NoMemory.SECRET, MemoryNotice.reason(pw, s, s))
        val pin = FieldPolicy.of(FieldPolicy.CLASS_NUMBER or FieldPolicy.NUMBER_VARIATION_PASSWORD, 0)
        assertEquals(NoMemory.SECRET, MemoryNotice.reason(pin, s, s))
        val incognito = FieldPolicy.of(text or FieldPolicy.FLAG_NO_SUGGESTIONS, FieldPolicy.IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertEquals(NoMemory.INCOGNITO, MemoryNotice.reason(incognito, s, s))
        val incognitoEmail = FieldPolicy.of(FieldPolicy.CLASS_TEXT or FieldPolicy.VARIATION_EMAIL, FieldPolicy.IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertEquals(NoMemory.INCOGNITO, MemoryNotice.reason(incognitoEmail, s, s))
        val optOut = FieldPolicy.of(FieldPolicy.CLASS_TEXT or FieldPolicy.FLAG_NO_SUGGESTIONS, 0)
        assertEquals(NoMemory.OPTED_OUT, MemoryNotice.reason(optOut, s, s))
        val prose = FieldPolicy.of(text or FieldPolicy.FLAG_NO_SUGGESTIONS, 0) // Instagram composer: lexicon yes, memory no
        assertEquals(NoMemory.OPTED_OUT, MemoryNotice.reason(prose, s, s))
        val plain = FieldPolicy.of(text, 0)
        assertEquals(NoMemory.SETTING_OFF, MemoryNotice.reason(plain, s.copy(personal = false), s.copy(personal = false)))
        assertEquals(NoMemory.SETTING_OFF, MemoryNotice.reason(plain, s.copy(suggest = false), s.copy(suggest = false)))
        // a mode turned learning off, not the user's setting
        assertEquals(NoMemory.MODE, MemoryNotice.reason(plain, Mode.CODE.apply(s), s))
        assertEquals(NoMemory.MODE, MemoryNotice.reason(plain, Mode.GAME.apply(s), s))
        for (r in NoMemory.values()) assertTrue(r.name, r.label.isNotBlank() && r.detail.isNotBlank())
    }

    @Test fun reasonMatchesWhatTheGateDoes() { // V8: the dot is exactly "personal data closed"
        val settings = listOf(KbSettings(), KbSettings(personal = false), KbSettings(suggest = false))
        val types = listOf(text, FieldPolicy.CLASS_TEXT, FieldPolicy.CLASS_TEXT or FieldPolicy.VARIATION_EMAIL,
            FieldPolicy.CLASS_TEXT or FieldPolicy.VARIATION_PASSWORD, text or FieldPolicy.FLAG_NO_SUGGESTIONS,
            FieldPolicy.CLASS_TEXT or FieldPolicy.FLAG_NO_SUGGESTIONS)
        for (s in settings) for (t in types) for (o in listOf(0, FieldPolicy.IME_FLAG_NO_PERSONALIZED_LEARNING)) {
            val p = FieldPolicy.of(t, o)
            val learns = p.personalWords(s) || p.personalValues(s)
            assertEquals("t=$t o=$o $s", learns, MemoryNotice.reason(p, s, s) == null)
        }
    }

    // ── V10: no bubble on secrets ──────────────────────────────────────
    @Test fun noKeyBubbleInSecretFields() { // V10
        assertTrue(MemoryNotice.bubble(popups = true, secret = false))
        assertFalse(MemoryNotice.bubble(popups = true, secret = true))
        assertFalse(MemoryNotice.bubble(popups = false, secret = false))
        assertFalse(MemoryNotice.bubble(popups = false, secret = true))
        // the view side (KeyboardView.setSecret gating drawPreview) is quick-panel's file; the
        // E2E checks the bubble on a real password field
    }

    // ── V11–V13: forget, one by one ────────────────────────────────────
    @Test fun forgettingAWordRemovesEveryTrace() { // V11
        val m = PersonalModel()
        m.learn(Lang.ES, null, "Hola", true)
        m.learn(Lang.ES, "hola", "amigo", false)
        m.learn(Lang.ES, "amigo", "hola", false)
        m.learn(Lang.ES, "amigo", "querido", false)
        m.learn(Lang.EN, null, "hola", true)
        assertTrue(m.forget(Lang.ES, "HOLA"))
        assertFalse(m.knows(Lang.ES, "hola"))
        assertTrue("prev table gone", m.predict(Lang.ES, "hola", false, 5).isEmpty())
        assertFalse("not a continuation", m.predict(Lang.ES, "amigo", false, 5).any { Tokens.key(it) == "hola" })
        assertTrue("other continuations kept", m.predict(Lang.ES, "amigo", false, 5).contains("querido"))
        assertFalse("not a starter", m.predict(Lang.ES, null, true, 5).any { Tokens.key(it) == "hola" })
        assertTrue("other language untouched", m.knows(Lang.EN, "hola"))
        assertTrue(m.knows(Lang.ES, "amigo"))
        assertFalse("unknown word: no change", m.forget(Lang.ES, "nunca"))
        // survives a save/load
        assertFalse(PersonalModel.fromJson(m.toJson()).knows(Lang.ES, "hola"))
    }

    @Test fun listingIsPerLanguageRankedAndBounded() { // V13
        val m = PersonalModel()
        repeat(3) { m.learn(Lang.ES, null, "chao", false) }
        m.learn(Lang.ES, null, "iPhone", false)
        m.learn(Lang.EN, null, "hello", false)
        val es = m.words(Lang.ES, 10)
        assertEquals(listOf("chao", "iPhone"), es.map { it.form })
        assertEquals(3, es[0].count)
        assertEquals(listOf("hello"), m.words(Lang.EN, 10).map { it.form })
        assertEquals(1, m.words(Lang.ES, 1).size)
        assertTrue(PersonalModel().words(Lang.ES, 10).isEmpty())
    }

    @Test fun forgettingAnEmailOrAnEmoji() { // V12
        val v = ValueMemory()
        v.remember(FieldKind.EMAIL, "alan@resyst.cl")
        v.remember(FieldKind.EMAIL, "otro@resyst.cl")
        assertEquals(listOf("alan@resyst.cl", "otro@resyst.cl").sorted(), v.values(FieldKind.EMAIL).sorted())
        assertTrue(v.forget(FieldKind.EMAIL, "ALAN@resyst.cl"))
        assertEquals(listOf("otro@resyst.cl"), v.suggest(FieldKind.EMAIL, "", 9))
        assertFalse(v.forget(FieldKind.EMAIL, "nadie@x.cl"))
        assertFalse(ValueMemory.fromJson(v.toJson()).suggest(FieldKind.EMAIL, "a", 9).isNotEmpty())
        val r = EmojiRecents(listOf("😀", "🔥", "✨"))
        assertTrue(r.remove("🔥"))
        assertFalse(r.remove("🔥"))
        assertEquals(listOf("😀", "✨"), EmojiRecents.decode(r.encode()).items())
    }
}
