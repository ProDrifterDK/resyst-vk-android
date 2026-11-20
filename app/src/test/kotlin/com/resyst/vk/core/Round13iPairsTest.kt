package com.resyst.vk.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * r13i (docs/failure-modes.md, Round 13i): the pair memory inside the context offers. Learning runs through the
 * shipped engine key by key ([S4Chat.Composer], wired as ResystImeService wires [PairLearner]); the store is the
 * shipped [PairMemory]; the decisions are the shipped [ContextCheck] / [ContextOffers] over the shipped model.
 */
class Round13iPairsTest {
    private val day = PairMemory.DAY_MS
    private val t0 = 1_790_000_000_000L

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────
    private fun keys(text: String) = R13iPrivateEval.keys(text, null)

    /** A multi-line chat field (Enter = new line) with the memory attached behind [gate]. */
    private fun chat(mem: PairMemory, gate: () -> Boolean = { true }, model: PersonalModel = PersonalModel()) =
        S4Chat.Composer(singleLine = false, model = model, store = Rejections(), pairs = mem, pairGate = gate).also { it.now = t0 }

    /** s4's field: single line, VK's Enter sends it. */
    private fun single(mem: PairMemory, gate: () -> Boolean = { true }) =
        S4Chat.Composer(singleLine = true, model = PersonalModel(), store = Rejections(), pairs = mem, pairGate = gate).also { it.now = t0 }

    private fun S4Chat.Composer.type(text: String) = run(S4Chat.Message("m", keys(text)))
    private fun S4Chat.Composer.send(text: String) = run(S4Chat.Message("m", keys(text) + S4Chat.KeyEv("SEND", 0, null, 0)))

    private fun learned(sentences: List<String>, now: Long = t0): PairMemory {
        val m = PairMemory()
        val c = chat(m)
        c.now = now
        for (s in sentences) { c.type(s); c.appSends() }
        return m
    }

    private val everyday = listOf(
        "mañana paso por tu casa a las nueve", "nos vemos en la pega temprano", "voy saliendo de la oficina ahora",
        "compré pan y leche para la once", "hoy hace mucho frío en la mañana", "el perro de mi vecino ladra toda la noche",
    )

    // ── PM2: secret, incognito, opted out, switches off: the store's bytes never change ──────────────────────
    @Test fun pm2ClosedFieldsAndSwitchesWriteNothing() {
        val text = FieldPolicy.CLASS_TEXT or FieldPolicy.FLAG_CAP_SENTENCES or FieldPolicy.FLAG_AUTO_CORRECT
        val done = 6
        val closed = linkedMapOf(
            "password" to (FieldPolicy.of(FieldPolicy.CLASS_TEXT or FieldPolicy.VARIATION_PASSWORD, done) to KbSettings()),
            "visible password" to (FieldPolicy.of(FieldPolicy.CLASS_TEXT or FieldPolicy.VARIATION_VISIBLE_PASSWORD, done) to KbSettings()),
            "web password" to (FieldPolicy.of(FieldPolicy.CLASS_TEXT or FieldPolicy.VARIATION_WEB_PASSWORD, done) to KbSettings()),
            "PIN" to (FieldPolicy.of(FieldPolicy.CLASS_NUMBER or FieldPolicy.NUMBER_VARIATION_PASSWORD, done) to KbSettings()),
            "incognito" to (FieldPolicy.of(text, done or FieldPolicy.IME_FLAG_NO_PERSONALIZED_LEARNING) to KbSettings()),
            "opted out (Sin memoria)" to (FieldPolicy.of(text or FieldPolicy.FLAG_MULTI_LINE or FieldPolicy.FLAG_NO_SUGGESTIONS, 0) to KbSettings()),
            "pair switch off" to (FieldPolicy.of(text, done) to KbSettings(pairMemory = false)),
            "offers off" to (FieldPolicy.of(text, done) to KbSettings(contextOffers = false)),
            "personal off" to (FieldPolicy.of(text, done) to KbSettings(personal = false)),
            "suggestions off" to (FieldPolicy.of(text, done) to KbSettings(suggest = false)),
        )
        val mem = learned(everyday)
        assertTrue(mem.entries > 50)
        for ((name, ps) in closed) {
            val (policy, st) = ps
            val before = mem.toBytes()
            val gate = { policy.pairWrite(st) }
            val c = chat(mem, gate)
            c.type("hola amigo como estas. vamos a la playa mañana. ")
            c.type("nos vemos en la pega temprano")
            c.appSends()
            single(mem, gate).send("mi clave secreta es otra cosa distinta")
            assertArrayEquals("$name: the store's bytes changed", before, mem.toBytes())
        }
        // the read gate: an opted-out prose field reads the memory for its offers; secret and incognito never do
        assertTrue(closed.getValue("opted out (Sin memoria)").first.pairRead(KbSettings()))
        for (n in listOf("password", "PIN", "incognito")) assertFalse(n, closed.getValue(n).first.pairRead(KbSettings()))
        // a normal field writes
        val before = mem.toBytes()
        val normal = FieldPolicy.of(text, done)
        single(mem) { normal.pairWrite(KbSettings()) }.send("mi clave secreta es otra cosa distinta")
        assertFalse("a normal field learns", before.contentEquals(mem.toBytes()))
        // the s4 web composer (inputType 0x4c0a1) is a normal field
        assertTrue(FieldPolicy.of(0x4c0a1, 0).pairWrite(KbSettings()))
    }

    @Test fun pm2AWordNoDictionaryKnowsIsNeverText() {
        val mem = PairMemory()
        single(mem).send("mañana voy con zorblaxq a la playa")
        assertTrue(mem.entries > 0)
        assertFalse(mem.knows("zorblaxq"))
        assertFalse("the file never holds it", String(mem.toBytes(), Charsets.ISO_8859_1).contains("zorblaxq"))
        assertTrue("stored as <unk>", mem.dump().keys.any { "<unk>" in it })
        // the user's own word (typed ≥ 2 times / kept, PersonalModel.owns) is kept as text
        val m2 = PairMemory()
        val words = object : PairLearner.Words {
            override fun isEs(w: String) = OffersOracle.lex.isWord(w)
            override fun isEn(w: String) = OffersOracle.lex.isEn(w)
            override fun owned(w: String) = w == "zorblaxq"
        }
        assertTrue(PairLearner { m2 }.sent("mañana voy con zorblaxq a la playa", true, words, emptyList(), t0))
        assertTrue(m2.knows("zorblaxq"))
        assertTrue(m2.pairCount("con", "zorblaxq") > 0)
    }

    // ── PM1: the engine's own (unconfirmed) fixes are masked; a tapped offer is the user's choice ────────────
    @Test fun pm1SpaceFixesAreNotLearnedTappedOffersAre() {
        val mem = PairMemory()
        val c = single(mem)
        c.type("tambien vamos a la playa ")
        assertTrue("precondition: space corrected it: ${c.text}", c.text.startsWith("También") && c.engine.fieldFixes.any { it.equals("también", true) })
        c.send("mañana temprano")
        assertTrue(mem.pairCount("vamos", "a") > 0)
        assertFalse("the fixed word is never learned", mem.knows("también"))
        assertTrue("no entry crosses it: ${mem.dump().keys}", mem.dump().keys.none { "también" in it || it.startsWith("<s> vamos") })
        // a tapped context offer: learned (the user chose it), the original is not
        val m2 = PairMemory()
        val c2 = single(m2)
        c2.type("paso por tu caza como ")
        val offers = ContextOffers()
        val job = offers.job(c2.text, whole = true)
        assertNotNull(job)
        val o = offers.decide(job!!, OffersOracle.check, OffersOracle.keepGeneric)
        assertEquals("casa", o?.alt)
        assertTrue(offers.arrive(o!!, c2.text, whole = true))
        assertTrue(c2.tapOffer(offers))
        c2.send("a las nueve")
        assertTrue("${m2.dump().keys}", m2.pairCount("tu", "casa") > 0)
        assertEquals(0.0, m2.pairCount("tu", "caza"), 0.0)
    }

    // ── PM3: a reverted correction is not learned ────────────────────────────────────────────────────────────
    @Test fun pm3ARevertedCorrectionIsNotLearned() {
        val mem = PairMemory()
        val c = single(mem)
        c.type("mañana tambien ")
        assertEquals("Mañana también ", c.text)
        c.type("⌫")
        assertEquals("the typed word is back", "Mañana tambien", c.text)
        c.send(" vamos a la playa")
        assertTrue(mem.pairCount("vamos", "a") > 0)
        assertFalse("the reverted fix is never learned: ${mem.dump().keys}", mem.knows("también"))
        if (!OffersOracle.lex.isWord("tambien")) {
            assertFalse("a one-off non-word stays <unk>", mem.knows("tambien"))
            assertEquals("the word the user kept is learned as <unk>", 1.0, mem.pairCount("mañana", "<unk>"), 0.0)
        }
    }

    // ── PM3: when a sentence is learned, and when it is not ──────────────────────────────────────────────────
    @Test fun pm3OnlySentOrKeptText() {
        // (a) it moved on: closed and a word of the next sentence finished
        run {
            val m = PairMemory()
            val c = chat(m)
            c.type("hola amigo como estas.")
            assertEquals("closed, not moved on yet", 0, m.entries)
            c.type(" vamos")
            assertEquals("the next word is not finished yet", 0, m.entries)
            c.type(" ")
            assertEquals(1.0, m.pairCount("hola", "amigo"), 0.0)
            assertEquals("the open sentence is not learned", 0.0, m.pairCount("<s>", "vamos"), 0.0)
            c.type("a la playa. listo ")
            assertEquals("a sentence is learned once while the field holds it", 1.0, m.pairCount("hola", "amigo"), 0.0)
            assertEquals(1.0, m.pairCount("vamos", "a"), 0.0)
            c.pairLearner.reset() // another field: the draft «listo» is never learned
            assertEquals(0.0, m.pairCount("<s>", "listo"), 0.0)
        }
        // (b) VK's Enter sends; (c) the app sends and empties the field
        run {
            val m = PairMemory()
            single(m).send("nos vemos mañana temprano")
            assertEquals(1.0, m.pairCount("nos", "vemos"), 0.0)
            assertEquals("the message's end is </s>", 1.0, m.pairCount("temprano", "</s>"), 0.0)
            val c = chat(m)
            c.type("nos vemos mañana temprano")
            c.appSends()
            assertEquals("the same message sent again, after the field was empty, counts again", 2.0, m.pairCount("nos", "vemos"), 0.0)
        }
        // a sentence VK deleted, a draft left, an English sentence, a one-word message, a paste: nothing
        run {
            val m = PairMemory()
            val c = chat(m)
            val s = "la casa de mi abuela"
            c.type(s)
            c.type("⌫".repeat(s.length))
            assertEquals("", c.text)
            c.appSends()
            c.type("see you tomorrow at the office")
            c.appSends()
            c.type("ok")
            c.appSends()
            c.paste("Te paso lo que dijo: ")
            c.type("vamos a la playa mañana")
            c.appSends()
            assertEquals("${m.dump().keys}", 0, m.entries)
            c.type("vamos a la playa mañana")
            c.appSends()
            assertTrue("after the field was empty again it learns", m.pairCount("vamos", "a") > 0)
        }
    }

    // ── PM4: caps, halving, decay ────────────────────────────────────────────────────────────────────────────
    private fun word(i: Int): String {
        val sb = StringBuilder()
        var x = i
        do { sb.append('a' + x % 26); x /= 26 } while (x > 0)
        return "q$sb"
    }

    private fun fill(m: PairMemory, sentences: Int, vocab: Int, seed: Long = 7L, check: Boolean = true) {
        val rng = java.util.Random(seed)
        for (n in 0 until sentences) {
            val s = List(6) { word(rng.nextInt(vocab)) }
            m.learn(s, bos = true, eos = true, now = t0)
            if (check) {
                assertTrue("entries ${m.entries}", m.entries <= PairMemory.MAX_ENTRIES)
                assertTrue("words ${m.wordCount}", m.wordCount + PairMemory.RESERVED.size <= PairMemory.MAX_WORDS)
                assertTrue("the learn in progress is never evicted", m.pairCount(s[2], s[3]) > 0 && m.tripleCount(s[3], s[4], s[5]) > 0)
            }
        }
    }

    @Test fun pm4CapsHold() {
        val m = PairMemory()
        fill(m, 6_000, 12_000)
        assertTrue(m.entries in PairMemory.PRUNE_TO..PairMemory.MAX_ENTRIES)
        assertTrue(m.wordCount > 1000)
    }

    @Test fun pm4HalvingAndDecay() {
        // saturation: a count past 4,096 halves everything
        val h = PairMemory()
        repeat(5_000) { h.learn(listOf("de", "la"), bos = false, eos = false, now = t0) }
        assertTrue("${h.pairCount("de", "la")}", h.pairCount("de", "la") in 2_000.0..4_096.0)
        // the 30-day half-life
        val m = PairMemory()
        m.learn(listOf("ab", "cd"), false, false, t0)
        assertEquals(1.0, m.pairCount("ab", "cd"), 1e-6)
        m.learn(listOf("ef", "gh"), false, false, t0 + 30 * day)
        assertEquals(0.5, m.pairCount("ab", "cd"), 1e-4)
        m.learn(listOf("ij", "kl"), false, false, t0 + 60 * day)
        assertEquals(0.25, m.pairCount("ab", "cd"), 1e-4)
        // a clock set back: nothing decays, and the day mark stays
        m.learn(listOf("mn", "op"), false, false, t0 + 10 * day)
        assertEquals(0.25, m.pairCount("ab", "cd"), 1e-4)
        m.learn(listOf("qr", "st"), false, false, t0 + 61 * day)
        assertEquals("a pair used once is gone after ~2 months", 0.0, m.pairCount("ab", "cd"), 0.0)
        assertFalse("its words go with it", m.knows("ab"))
        // a clock set forward by years: at most 12 half-lives
        val f = PairMemory()
        repeat(2_000) { f.learn(listOf("xy", "zw"), false, false, t0) }
        f.learn(listOf("aa", "bb"), false, false, t0 + 3650 * day)
        assertEquals(2_000.0 / 4_096.0, f.pairCount("xy", "zw"), 1e-3)
    }

    // ── PM4 / PM11: heap, file size, the file's round trip and its corruption ────────────────────────────────
    private fun heap(): Long {
        val rt = Runtime.getRuntime()
        repeat(4) { System.gc(); Thread.sleep(60) }
        return rt.totalMemory() - rt.freeMemory()
    }

    @Test fun pm4FullMemoryHeapAndFile() {
        val h0 = heap()
        val m = PairMemory()
        fill(m, 7_000, 9_000, check = false)
        val h1 = heap()
        assertTrue("entries ${m.entries} words ${m.wordCount}", m.entries > PairMemory.PRUNE_TO && m.wordCount > 7_000)
        val delta = h1 - h0
        println("r13i heap: entries=${m.entries} words=${m.wordCount} heap delta ${delta / 1024} KB")
        assertTrue("heap delta ${delta / 1024} KB", delta < 2L * 1024 * 1024)
        val b = m.toBytes()
        assertTrue("file ${b.size} B", b.size <= 350_000)
        val back = PairMemory.fromBytes(b)
        assertArrayEquals(b, back.toBytes())
        assertEquals(m.prob("qb", "qc", "qd"), back.prob("qb", "qc", "qd"), 0.0)
        assertTrue(m.entries > 0) // keeps m alive past the measurement
        val bad = listOf(b.copyOf(b.size - 1), b + byteArrayOf(0), b.copyOf().also { it[0] = 'X'.code.toByte() },
            b.copyOf().also { it[4] = 9 }, ByteArray(0), ByteArray(5000) { (it * 37).toByte() }, b.copyOf(b.size / 2))
        for (x in bad) assertTrue(PairMemory.fromBytes(x).isEmpty())
    }

    // ── PM5: forget one, clear all ───────────────────────────────────────────────────────────────────────────
    @Test fun pm5ClearAllLeavesNothing() {
        val m = learned(everyday)
        assertTrue(m.pairCount("la", "pega") > 0 && m.tripleCount("en", "la", "pega") > 0)
        assertTrue(m.forget("la", "pega"))
        assertEquals(0.0, m.pairCount("la", "pega"), 0.0)
        assertTrue("its triples go too", m.dump().keys.none { "la pega" in it })
        assertTrue(m.pairCount("nos", "vemos") > 0)
        val dir = Files.createTempDirectory("r13i-pairs").toFile()
        try {
            PairFile.write(dir, m.toBytes())
            assertTrue(File(dir, PairFile.NAME).exists())
            assertArrayEquals(m.toBytes(), PairFile.read(dir)!!.toBytes())
            // "Borrar todos los pares" (PersonalStore.clearPairs): the object in memory and the file
            m.clear()
            PairFile.delete(dir)
            assertFalse(File(dir, PairFile.NAME).exists())
            assertTrue(dir.listFiles()!!.isEmpty())
            assertNull(PairFile.read(dir))
            assertTrue(m.isEmpty() && m.entries == 0 && m.tokens == 0.0 && m.wordCount == 0 && !m.active)
            assertArrayEquals(PairMemory().toBytes(), m.toBytes())
            assertEquals(PairMemory().prob("casa", "<s>", "la"), m.prob("casa", "<s>", "la"), 0.0)
            assertTrue(m.topPairs(10).isEmpty())
            // an emptied memory is no file at all
            PairFile.write(dir, m.toBytes())
            PairFile.write(dir, null)
            assertFalse(File(dir, PairFile.NAME).exists())
        } finally { dir.deleteRecursively() }
    }

    // ── PM6 / PM7: no memory, an empty one or one still cold: 0.12.1 to the bit ──────────────────────────────
    @Test fun pm7EmptyOrColdMemoryIsByteIdentical() {
        val cold = learned(everyday)
        assertFalse("cold: ${cold.tokens} tokens", cold.active)
        val groups = linkedMapOf("s4" to OffersOracle.s4Spike(), "set315" to OffersOracle.set315(), "proseA" to listOf(OffersOracle.proseA()),
            "proseA2" to listOf(OffersOracle.proseA2()), "chat" to OffersOracle.chat())
        for ((g, docs) in groups) {
            val ref = OffersOracle.decisions(docs).map { Triple(it.id, it.alt, it.delta) }
            for ((name, mem) in listOf("empty" to PairMemory(), "cold" to cold)) {
                val ds = OffersOracle.decisions(docs, memory = mem)
                assertTrue("$g/$name: personal", ds.none { it.personal })
                assertEquals("$g/$name", ref, ds.map { Triple(it.id, it.alt, it.delta) })
            }
            val live = OffersOracle.liveOffers(docs).map { it.second }
            assertEquals("$g live flow", live, OffersOracle.liveOffers(docs, memory = PairMemory()).map { it.second })
            assertEquals("$g live flow (cold)", live, OffersOracle.liveOffers(docs, memory = cold).map { it.second })
        }
        val s4 = OffersOracle.decisions(OffersOracle.s4Spike(), memory = PairMemory()).filter { it.delta >= ContextCheck.TAU_OFFER }
        assertEquals("s4 made (live window)", 15, s4.size)
        assertEquals("s4 right (live window)", 14, s4.count { it.right })
    }

    @Test fun pm6TheMemoryTakesPartOnlyOnceWarm() {
        val m = PairMemory()
        val c = chat(m)
        var n = 0
        val prose = R13iPrivateEval.standIn()
        while (m.tokens < PairMemory.ACTIVE_TOKENS - 30) { c.type(prose[n++].text); c.appSends() }
        assertFalse(m.active)
        val docs = listOf(OffersOracle.proseA2())
        assertEquals(OffersOracle.decisions(docs).map { it.delta }, OffersOracle.decisions(docs, memory = m).map { it.delta })
        while (!m.active) { c.type(prose[n++].text); c.appSends() }
        val ds = OffersOracle.decisions(docs, memory = m)
        assertTrue(ds.isNotEmpty() && ds.all { it.personal })
    }

    // ── PM8: an offer only the memory makes (the device run's twin) ──────────────────────────────────────────
    @Test fun pm8AnOfferOnlyTheTaughtMemoryMakes() {
        val seed = learned(R13iPrivateEval.standIn().map { it.text })
        assertTrue(seed.active)
        val typed = "vamos a la peca temprano "
        fun offer(mem: PairMemory?): ContextOffers.Offer? {
            val o = ContextOffers()
            return o.job(typed, whole = true)?.let { o.decide(it, OffersOracle.check, OffersOracle.keepGeneric, memory = mem) }
        }
        val generic = OffersOracle.check.decide(typed, 11, 15, ContextCheck.Params(), OffersOracle.keepGeneric)
        assertTrue("the generic model does not prefer «pega» here: ${generic?.alt?.word} ${generic?.delta}", generic == null || generic.alt.word != "pega" || generic.delta < 0)
        assertNull("0.12.1: no offer", offer(null))
        assertNull("an active memory that never saw the pair: no offer", offer(seed))
        // taught once, in an incognito field: nothing
        val before = seed.toBytes()
        val text = FieldPolicy.CLASS_TEXT or FieldPolicy.FLAG_CAP_SENTENCES or FieldPolicy.FLAG_AUTO_CORRECT
        val incognito = FieldPolicy.of(text, 6 or FieldPolicy.IME_FLAG_NO_PERSONALIZED_LEARNING)
        single(seed) { incognito.pairWrite(KbSettings()) }.send("vamos a la pega temprano")
        assertArrayEquals(before, seed.toBytes())
        assertNull(offer(seed))
        // taught once in a normal field
        single(seed) { FieldPolicy.of(text, 6).pairWrite(KbSettings()) }.send("vamos a la pega temprano")
        val o = offer(seed)
        assertEquals("pega", o?.alt)
        assertTrue("Δ ${o?.delta}", o!!.delta >= ContextCheck.TAU_MEMORY)
        // clear all: gone
        seed.clear()
        assertNull(offer(seed))
    }

    // ── PM10: a decision reads while the main thread learns ──────────────────────────────────────────────────
    @Test fun pm10ConcurrentLearnAndRead() {
        val m = learned(everyday)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val reader = Thread {
            try { while (!stop.get()) { val p = m.prob("pega", "en", "la"); check(p > 0 && p <= 1.0) } } catch (t: Throwable) { errors += t }
        }
        reader.start()
        fill(m, 4_000, 40_000, check = false)
        stop.set(true)
        reader.join()
        assertTrue("$errors", errors.isEmpty())
    }

    // ── KN1 / KN2: settings, strings, the wipe summary ───────────────────────────────────────────────────────
    @Test fun knTheSectionTheSwitchAndTheStrings() {
        val page = SettingsIA.pageOf(Ctl.KNOW_PAIRS)!!
        assertEquals("datos", page.id)
        assertEquals(page, SettingsIA.pageOf(Ctl.PAIR_MEMORY))
        assertEquals("Pares de palabras que usas a menudo", Ctl.KNOW_PAIRS.label)
        assertTrue("default on", KbSettings().pairMemory)
        val st = ProfileCodec.decode(ProfileCodec.encode(ProfileCodec.seed().let { it.updatePhone { s -> s.copy(pairMemory = false) } }))
        assertFalse(st.settings.pairMemory)
        assertTrue(PersonalSummary.text(PersonalSummary.Counts(0, 0, 0, 0, pairs = 12)).contains("12 pares de palabras"))
        assertFalse(PersonalSummary.Counts(0, 0, 0, 0, pairs = 1).empty)
        val names = listOf("know_pairs_title", "know_pairs_empty", "know_pairs_summary", "know_pairs_learning", "know_pairs_active", "know_pairs_off",
            "know_pairs_forget_desc", "know_pairs_clear", "know_pairs_clear_title", "know_pairs_clear_message", "pair_memory_title", "pair_memory_summary")
        for (loc in listOf("values", "values-en")) {
            val xml = File("src/main/res/$loc/strings.xml").readText()
            for (n in names) assertTrue("$loc: $n", xml.contains("name=\"$n\""))
        }
        assertTrue(File("src/main/res/values-en/strings.xml").readText().contains(">Word pairs you use often<"))
    }
}
