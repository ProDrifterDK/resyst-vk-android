package com.resyst.vk.core

import com.resyst.vk.core.OffersOracle.Decision
import com.resyst.vk.core.OffersOracle.RES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp

/**
 * r13f (offers): the context offers' failure modes (docs/failure-modes.md, Round 13f offers: CO, ST, HP, AS, PV) and
 * the offline acceptance — the SHIPPED decision path reproduces the r13e spike's frozen offer numbers.
 *
 * Writes build/r13f-offers/offline.json (every number of the report's `offers` section) and heap.json.
 */
class Round13fOffersTest {
    private val outDir = File("build/r13f-offers").also { it.mkdirs() }
    private val spike: Map<*, *> by lazy { MiniJson.parse(File("$RES/spike-ref.json").readText()) as Map<*, *> }
    private val spikeDecisions: Map<String, Pair<String, Double>> by lazy {
        (spike["decisions"] as Map<*, *>).entries.associate { (k, v) -> val l = v as List<*>; k as String to (l[0] as String to (l[1] as Number).toDouble()) }
    }

    // ── AS — the asset ───────────────────────────────────────────────────────────────────────────────────────
    /** AS3: the builder's golden cases, run against the shipped tokenizer; left / right context positions. */
    @Test fun as3TokenizerMatchesTheBuilder() {
        var n = 0
        for (line in File("$RES/tokenizer-cases.tsv").readLines()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val (inp, want) = line.split('\t')
            val got = ContextTokens.sentences(inp.replace("\\n", "\n")).joinToString(" | ") { it.joinToString(" ") }
            assertEquals("«$inp»", want, got)
            n++
        }
        assertTrue(n >= 15)
        val t = "Voy a la caza. Mañana voy a la casa y"
        val sents = ContextTokens.tokenize(t)
        val (si, ti) = ContextTokens.locate(sents, t.indexOf("Mañana"))!!
        assertEquals(listOf("<s>"), ContextTokens.leftOf(sents[si], ti))
        val (s2, t2) = ContextTokens.locate(sents, t.indexOf("caza"))!!
        assertEquals(listOf("a", "la"), ContextTokens.leftOf(sents[s2], t2))
        assertEquals("the end mark typed right after: </s>", listOf("</s>"), ContextTokens.rightOf(sents[s2], t2, 2))
        val (s3, t3) = ContextTokens.locate(sents, t.indexOf("casa"))!!
        assertEquals("live: only what is typed, no </s>", listOf("y"), ContextTokens.rightOf(sents[s3], t3, 2))
    }

    /** AS2 / LC1: the shipped file is wt-m2 (sha256 pinned), stored uncompressed, with its provenance and notice. */
    @Test fun as2TheShippedAssetIsPinned() {
        val sha = MessageDigest.getInstance("SHA-256").digest(OffersOracle.modelFile.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(SHA256, sha)
        assertTrue(sha.startsWith("dae872a0") && sha.endsWith("87c0"))
        val prov = MiniJson.parse(File("src/main/assets/context/wt-m2.provenance.json").readText()) as Map<*, *>
        assertEquals(SHA256, prov["sha256"])
        assertEquals(OffersOracle.modelFile.length(), (prov["bytes"] as Number).toLong())
        assertTrue(((prov["builder"] as Map<*, *>)["commit"] as String).startsWith("ce8792c"))
        val notice = File("src/main/assets/context/NOTICE.txt").readText()
        for (s in listOf("Wikipedia contributors", "CC BY-SA 4.0", "Tatoeba contributors", "CC BY 2.0 FR", "GPL-3.0-only", "LICENSE-GPL-3.txt")) assertTrue(s, notice.contains(s))
        assertTrue(File("src/main/assets/LICENSE-GPL-3.txt").exists())
        assertTrue("HP1: stored, not deflated", File("build.gradle.kts").readText().contains("noCompress += \"vkng\""))
    }

    /** AS1: a truncated, foreign, padded or garbled asset fails loudly (the IME then offers nothing). */
    @Test fun as1BrokenAssetsFailLoudly() {
        val bytes = OffersOracle.modelFile.readBytes()
        fun fails(b: ByteArray) = runCatching { ContextNgram.read(ByteBuffer.wrap(b)) }.isFailure
        assertTrue("truncated", fails(bytes.copyOf(bytes.size - 3)))
        assertTrue("half", fails(bytes.copyOf(bytes.size / 2)))
        assertTrue("one byte more", fails(bytes + byteArrayOf(0)))
        assertTrue("foreign", fails("NOPE".toByteArray() + bytes.copyOfRange(4, 4096)))
        assertTrue("version", fails(bytes.copyOf().also { it[7] = 1 }))
        assertTrue("empty", fails(ByteArray(0)))
        assertTrue("noise", fails(ByteArray(50_000) { (it * 31 + 7).toByte() }))
        assertFalse(fails(bytes))
    }

    /**
     * AS4: the memory-mapped reader gives the spike array reader's log-probabilities BIT FOR BIT: every vocabulary
     * word's id, unknown words, and P(w | a b) over random ids, over every trigram of prose A and s4, and with
     * short histories.
     */
    @Test fun as4TheMappedReaderIsTheSpikeReader() {
        val m = OffersOracle.model
        val ref = SpikeNgram.read(OffersOracle.modelFile.readBytes())
        assertEquals(ref.vocabSize, m.vocabSize)
        assertEquals(ref.tokens, m.tokens)
        assertEquals(ref.unkTypes, m.unkTypes)
        assertEquals(ref.bigrams, m.bigrams)
        assertEquals(ref.trigrams, m.trigrams)
        for (id in 0 until ref.vocabSize) assertEquals(ref.word(id), id, m.id(ref.word(id)))
        for (w in listOf("zzqxw", "", "caza ", "CASA", "grapheneos", "ñandú", "l'homme")) assertEquals(w, ref.id(w), m.id(w))
        var n = 0L
        fun same(w: Int, a: Int, b: Int) {
            val x = ref.logP(w, a, b)
            val y = m.logP(w, a, b)
            if (x.toBits() != y.toBits()) throw AssertionError("logP($w | $a $b): spike $x, mapped $y")
            n++
        }
        val rng = java.util.Random(13)
        repeat(200_000) {
            val v = ref.vocabSize
            // frequent ids hit stored bigrams / trigrams; uniform ids exercise the back-off
            fun pick() = if (rng.nextBoolean()) rng.nextInt(v) else rng.nextInt(2000)
            same(pick(), if (rng.nextInt(5) == 0) -1 else pick(), if (rng.nextInt(9) == 0) -1 else pick())
        }
        val texts = listOf(OffersOracle.proseText) + File("src/test/resources/r13f/s4-final.txt").readLines()
        var hits = 0
        for (t in texts) for (s in ContextTokens.sentences(t)) {
            val ids = (listOf("<s>") + s + "</s>").map { m.id(it) }
            for (j in 1 until ids.size) {
                same(ids[j], if (j >= 2) ids[j - 2] else -1, ids[j - 1])
                same(ids[j], -1, ids[j - 1])
                same(ids[j], -1, -1)
                if (j >= 2 && ref.logP(ids[j], ids[j - 2], ids[j - 1]) > ref.logP(ids[j], -1, ids[j - 1])) hits++
            }
        }
        assertTrue("$hits stored trigram contexts", hits > 2000)
        assertTrue("$n comparisons", n > 200_000)
        // M2 (r13e): P(·|h) sums to ~1 over the vocabulary
        for (h in listOf(listOf("<s>"), listOf("de", "la"), listOf("por", "qué"), listOf("zzqx", "la"))) {
            val a = if (h.size == 2) m.id(h[0]) else -1
            val b = m.id(h.last())
            var sum = 0.0
            for (w in 0 until m.vocabSize) if (w != ContextNgram.BOS_ID) sum += exp(m.logP(w, a, b))
            assertTrue("Σ P(·|$h) = $sum", sum in 0.85..1.05)
        }
    }

    /** HP1: the model's Java heap after loading (its arrays only; the 11.8 MB stay in the mapped file). */
    @Test fun hp1TheModelIsNotOnTheHeap() {
        val rt = Runtime.getRuntime()
        fun used(): Long { repeat(3) { System.gc(); Thread.sleep(150) }; return rt.totalMemory() - rt.freeMemory() }
        val buf = OffersOracle.map(OffersOracle.modelFile)
        val before = used()
        val t0 = System.nanoTime()
        val m = ContextNgram.read(buf)
        val loadMs = (System.nanoTime() - t0) / 1e6
        val delta = used() - before
        assertTrue(m.id("casa") > 0) // keeps m alive past the measurement
        val bytes = OffersOracle.modelFile.readBytes()
        val before2 = used()
        val spikeModel = SpikeNgram.read(bytes)
        val spikeDelta = used() - before2
        assertTrue(spikeModel.vocabSize == m.vocabSize)
        File(outDir, "heap.json").writeText(json(linkedMapOf("mapped_reader_heap_bytes" to delta, "mapped_reader_load_ms" to loadMs,
            "spike_reader_heap_bytes" to spikeDelta, "note" to "desktop JVM, after 3 GCs; the spike figure excludes its 11.8 MB input array")))
        println("r13f offers heap: mapped ${delta / 1e6} MB (load $loadMs ms), spike reader ${spikeDelta / 1e6} MB")
        assertTrue("heap delta ${delta / 1e6} MB", delta < 5_000_000)
    }

    /** AS4 / CO1: the shipped lexicon is the oracle's — words, accent variants, frequencies, confusion sets. */
    @Test fun theShippedLexiconIsTheOracles() {
        val lex = OffersOracle.lex
        val o = OffersOracle.OracleLex
        val words = LinkedHashSet<String>()
        words += o.allExtra
        val texts = listOf(OffersOracle.proseText, File("$RES/chat-source.txt").readText()) + File("src/test/resources/r13f/s4-final.txt").readLines() +
            OffersOracle.set315().map { it.text }
        val textWords = LinkedHashSet<String>()
        for (t in texts) for (w in ContextSegments.words(t)) textWords += w.text.lowercase()
        words += textWords
        val shipped = ContextCandidates(lex)
        val oracle = ContextCandidates(o)
        var cands = 0
        for (w in words) {
            assertEquals(w, o.isWord(w), lex.isWord(w))
            assertEquals(w, o.accentVariants(w).toSet(), lex.accentVariants(w).toSet())
            assertEquals(w, o.logFreq(w), lex.logFreq(w), 0.0)
            assertEquals(w, o.isEn(w), lex.isEn(w))
            if (w in textWords && o.isWord(w)) {
                val a = oracle.of(w)
                assertEquals(w, a, shipped.of(w))
                cands += a.size
            }
        }
        assertTrue("${words.size} words, $cands candidates", words.size > 20_000 && cands > 3_000)
    }

    // ── CO1 — the offline acceptance ─────────────────────────────────────────────────────────────────────────
    private class Tally(val made: Int, val right: Int) {
        val wrong get() = made - right
        fun map() = linkedMapOf("made" to made, "right" to right, "wrong" to wrong, "precision" to (if (made > 0) right.toDouble() / made else null))
    }

    private fun tally(ds: Collection<Decision>): Tally = ds.filter { it.delta >= ContextCheck.TAU_OFFER }.let { o -> Tally(o.size, o.count { it.right }) }

    private fun describe(d: Decision) = "${d.doc.corpus}/${d.doc.id}: ${d.typed}→${d.alt} (gold ${d.gold}, Δ ${"%.2f".format(Locale.ROOT, d.delta)})"

    @Test fun co1TheShippedPathReproducesTheSpike() {
        val s4 = OffersOracle.s4Spike()
        val set315 = OffersOracle.set315()
        val proseA = OffersOracle.proseA()
        val proseA2 = OffersOracle.proseA2()
        val chat = OffersOracle.chat()
        val groups = linkedMapOf("s4" to s4, "set315" to set315, "proseA" to listOf(proseA), "proseA2" to listOf(proseA2), "chat_synthetic" to chat)
        val res = linkedMapOf<String, Any?>()
        val sg = spike["groups"] as Map<*, *>
        fun spikeOf(g: String) = (sg[g] as Map<*, *>).let { (it["right"] as Number).toInt() to (it["wrong"] as Number).toInt() }
        val spikeT = linkedMapOf<String, Any?>()
        for (g in listOf("s4", "proseA", "proseA2", "chat_synthetic")) spikeT[g] = spikeOf(g).let { (r, w) -> "$r/${r + w}" }
        val s315 = listOf("ctx_fix", "ctx_accent", "ctx_keep", "s1_typo", "s1_accent", "s1_ok").map { spikeOf("set315/$it") }
        spikeT["set315"] = "${s315.sumOf { it.first }}/${s315.sumOf { it.first + it.second }}"
        spikeT["prose_pooled"] = (sg["prose_pooled"] as Map<*, *>).let { "${it["right"]}/${it["offers"]}" }
        res["spike"] = spikeT

        fun tallies(decided: Map<String, List<Decision>>): LinkedHashMap<String, Any?> {
            val t = linkedMapOf<String, Any?>()
            for ((g, ds) in decided) t[g] = tally(ds).map() + linkedMapOf("wrong_list" to ds.filter { it.delta >= ContextCheck.TAU_OFFER && !it.right }.map { describe(it) })
            val s4t = tally(decided.getValue("s4"))
            val s4Targets = OffersOracle.targets(s4)
            t["s4"] = (t["s4"] as Map<*, *>) + linkedMapOf("targets" to s4Targets, "recall" to "${s4t.right}/$s4Targets",
                "right_list" to decided.getValue("s4").filter { it.delta >= ContextCheck.TAU_OFFER && it.right }.map { describe(it) })
            t["set315_by_cat"] = decided.getValue("set315").groupBy { OffersOracle.cat315.getValue(it.doc.id) }.mapValues { tally(it.value).map() }
            val a = tally(decided.getValue("proseA"))
            val a2 = tally(decided.getValue("proseA2"))
            t["prose_pooled"] = Tally(a2.made + a.wrong, a2.right).map() + linkedMapOf("targets" to OffersOracle.targets(listOf(proseA2)))
            t["chat_synthetic_targets"] = OffersOracle.targets(chat)
            return t
        }
        fun short(t: Map<String, Any?>) = t.filterValues { it is Map<*, *> && it["made"] != null }.mapValues { (_, v) -> (v as Map<*, *>).let { "${it["right"]}/${it["made"]}" } }

        // 1. the spike's exact conditions (its contrast guard saw the whole sentence): every word the spike scored gets
        //    the same alternative and the same Δ from the SHIPPED code, nothing else is scored, every tally is the spike's
        val t0 = System.nanoTime()
        val oracleW = groups.mapValues { (_, docs) -> OffersOracle.decisions(docs, oracleWindow = true) }
        val decideMs = (System.nanoTime() - t0) / 1e6
        val mineO = oracleW.values.flatten().associateBy { it.id }
        val missing = ArrayList<String>()
        val differ = ArrayList<String>()
        for ((id, sp) in spikeDecisions) {
            val d = mineO[id]
            if (d == null) { missing += "$id ${sp.first} ${sp.second}"; continue }
            if (d.alt != sp.first || abs(d.delta - sp.second) > 1e-3) differ += "$id spike ${sp.first} ${sp.second} shipped ${d.alt} ${d.delta}"
        }
        val extra = mineO.keys.filter { it !in spikeDecisions }.map { describe(mineO.getValue(it)) }
        val tO = tallies(oracleW)
        res["oracle_window"] = linkedMapOf("spike_scored" to spikeDecisions.size, "shipped_scored" to mineO.size, "missing" to missing,
            "different" to differ, "extra" to extra, "tallies" to tO, "decide_ms_total_jvm" to decideMs)

        // 2. the shipped (live) conditions: the guard sees only what is typed; an offer whose alternative is typed later
        //    in the sentence is withdrawn then (CO5). Every word that differs from (1) is explained by that window.
        val liveW = groups.mapValues { (_, docs) -> OffersOracle.decisions(docs) }
        val cands = ContextCandidates(OffersOracle.lex)
        val mineL = liveW.values.flatten().associateBy { it.id }
        val unexplained = ArrayList<String>()
        val changed = ArrayList<String>()
        for (id in (mineO.keys + mineL.keys)) {
            val o = mineO[id]
            val l = mineL[id]
            if (o != null && l != null && o.alt == l.alt && o.delta == l.delta) continue
            val d = (l ?: o)!!
            val seg = d.doc.segs[ContextSegments.segmentAt(d.doc.segs, d.doc.words[d.i].start)]
            val later = d.doc.words.filter { it.start >= d.doc.words[d.i + 1].end && it.start < seg.end }.map { it.text.lowercase() }.toSet()
            val inSet = cands.of(d.typed.lowercase()).map { it.word }.filter { it in later }
            val why = when {
                l != null && l.alt in later -> "the live pick «${l.alt}» is typed later in the sentence (the oracle's guard blocked it)"
                inSet.isNotEmpty() -> "«${inSet.joinToString("», «")}» of its confusion set is typed later in the sentence: the oracle's guard skipped it, so its K = 2 scored other alternatives"
                d.doc.corpus == "set315" && o == null -> "the oracle judged the sentence's language on the whole sentence"
                else -> null
            }
            val line = "${describe(d)}: oracle ${o?.alt} ${o?.delta?.let { "%.2f".format(Locale.ROOT, it) }} → live ${l?.alt} ${l?.delta?.let { "%.2f".format(Locale.ROOT, it) }}"
            if (why == null) unexplained += line
            if ((o?.delta ?: -99.0) >= ContextCheck.TAU_OFFER || (l?.delta ?: -99.0) >= ContextCheck.TAU_OFFER) changed += "$line — ${why ?: "?"}"
        }
        val tL = tallies(liveW)
        val withdrawn = liveW.values.flatten().filter { d ->
            if (d.delta < ContextCheck.TAU_OFFER) return@filter false
            val seg = d.doc.segs[ContextSegments.segmentAt(d.doc.segs, d.doc.words[d.i].start)]
            d.doc.words.any { it.start >= d.doc.words[d.i + 1].end && it.start < seg.end && it.text.lowercase() == d.alt }
        }.map { describe(it) }
        res["live_window"] = linkedMapOf("tallies" to tL, "offer_changes_vs_oracle_window" to changed, "unexplained" to unexplained,
            "withdrawn_later_CO5" to withdrawn)

        // 3. the IME's live flow (a job at the key that ended the next word; in a chat Enter sends the message)
        val live = linkedMapOf<String, Any?>()
        for ((g, docs) in groups) {
            val lo = OffersOracle.liveOffers(docs).map { it.first }
            val dec = liveW.getValue(g).filter { it.delta >= ContextCheck.TAU_OFFER }
            val lost = dec.filter { d -> lo.none { it.id == d.id } }.map { d ->
                val after = d.doc.text.getOrNull(d.doc.words[d.i + 1].end)
                describe(d) + " — " + (if (after == null) "its next word ends the message: the Enter that completes it sends it" else if (after == '\n') "a new line ended its sentence" else "?")
            }
            live[g] = tally(lo).map() + linkedMapOf("lost_vs_decision" to lost)
        }
        res["live_flow"] = live

        // 4. s4 as THIS build replays it (0.12.0: the word before Enter is corrected, caps after a layer return)
        val s4now = OffersOracle.s4ThisBuild()
        val dNow = OffersOracle.decisions(s4now)
        res["s4_this_build_replay"] = tally(dNow).map() + linkedMapOf("targets" to OffersOracle.targets(s4now),
            "texts_changed" to s4now.zip(s4).filter { (x, y) -> x.text != y.text }.map { (x, y) -> "${x.id}: «${y.text}» → «${x.text}»" },
            "offers_changed" to dNow.filter { it.delta >= 6 }.map { describe(it) }.toSet().let { a ->
                val b = liveW.getValue("s4").filter { it.delta >= 6 }.map { describe(it) }.toSet()
                (a - b).map { "this build only: $it" } + (b - a).map { "0.11.0 replay only: $it" }
            })
        File(outDir, "offline.json").writeText(json(res))
        println("r13f offers offline: spike $spikeT | oracle window ${short(tO)} (missing ${missing.size}, different ${differ.size}, extra ${extra.size}) | live window ${short(tL)} (unexplained ${unexplained.size}) | live flow ${live.mapValues { (_, v) -> (v as Map<*, *>).let { "${it["right"]}/${it["made"]}" } }}")

        // the frozen numbers
        assertTrue("words the spike scored and the shipped path did not: $missing", missing.isEmpty())
        assertTrue("words scored differently: $differ", differ.isEmpty())
        assertTrue("words only the shipped path scored: $extra", extra.isEmpty())
        for (g in listOf("s4", "proseA", "proseA2", "chat_synthetic")) {
            val (r, w) = spikeOf(g)
            val x = tally(oracleW.getValue(g))
            assertEquals("$g right", r, x.right)
            assertEquals("$g wrong", w, x.wrong)
        }
        assertEquals("315 set", spikeT["set315"], tally(oracleW.getValue("set315")).let { "${it.right}/${it.made}" })
        assertEquals("prose pooled", spikeT["prose_pooled"], (tO["prose_pooled"] as Map<*, *>).let { "${it["right"]}/${it["made"]}" })
        val s4live = tally(liveW.getValue("s4"))
        assertEquals("s4 offers made (live window)", 15, s4live.made)
        assertEquals("s4 offers right (live window)", 14, s4live.right)
        assertEquals("s4 in-reach targets", 25, OffersOracle.targets(s4))
        assertTrue("differences the window does not explain: $unexplained", unexplained.isEmpty())
    }

    // ── CO2 — offers never edit by themselves ────────────────────────────────────────────────────────────────
    /**
     * The whole s4 key stream through the engine (S4Chat, the shipped corrector) twice: alone, and with the offers
     * controller attached the way the IME attaches it (a job at every word end, the decision, arrive, refresh after
     * every key, the send). Every field text after every key is identical; the offers shown are recorded.
     */
    @Test fun co2OffersNeverEditByThemselves() {
        fun texts(attach: Boolean): Pair<List<String>, List<String>> {
            val out = ArrayList<String>()
            val shown = ArrayList<String>()
            val offers = ContextOffers()
            val c = S4Chat.Composer()
            c.observer = { comp, k ->
                if (attach) {
                    if (k.k == "SEND") offers.sent()
                    else {
                        offers.refresh(comp.text, whole = true, collapsed = true)
                        val ends = k.k == " " || (k.k.length == 1 && KeyboardEngine.endsWord(k.k)) || k.k.startsWith("VAR:") && KeyboardEngine.endsWord(k.k.removePrefix("VAR:"))
                        if (ends) offers.job(comp.text, whole = true)?.let { job ->
                            offers.decide(job, OffersOracle.check, OffersOracle.keepOwner)?.let { o ->
                                if (offers.arrive(o, comp.text, whole = true)) shown += "«${comp.text}» ${o.typed}→${o.label} Δ ${"%.2f".format(Locale.ROOT, o.delta)}"
                            }
                        }
                    }
                }
                out += comp.text
            }
            for (m in S4Chat.live) c.run(m)
            return out to shown
        }
        val (alone, _) = texts(attach = false)
        val (withOffers, shown) = texts(attach = true)
        assertEquals(alone.size, withOffers.size)
        for (i in alone.indices) assertEquals("key $i", alone[i], withOffers[i])
        File(outDir, "s4-stream-offers.json").writeText(json(linkedMapOf("keys" to alone.size, "offers_shown" to shown)))
        println("r13f offers on the live s4 stream (${shown.size}): " + shown.joinToString(" | "))
        assertTrue("the live stream shows offers", shown.size >= 8)
    }

    // ── ST — the state machine, and the end-to-end cases of the device run ───────────────────────────────────
    private fun offerFor(before: String, keep: ContextKeep.Keep = OffersOracle.keepGeneric): Pair<ContextOffers, ContextOffers.Offer?> {
        val offers = ContextOffers()
        val job = offers.job(before, whole = true) ?: return offers to null
        val o = offers.decide(job, OffersOracle.check, keep) ?: return offers to null
        return offers to o.takeIf { offers.arrive(it, before, whole = true) }
    }

    @Test fun stTheDeviceSentences() {
        // ST4: paso por tu caza como → casa; the tap replaces it, keeps what follows, the cursor after it
        val (offers, o) = offerFor("paso por tu caza como ")
        assertNotNull(o); o!!
        assertEquals("caza", o.typed)
        assertEquals("casa", o.label)
        val edit = offers.take("paso por tu caza como ", whole = true)
        assertEquals("paso por tu casa como ", Edits.apply("paso por tu caza como ", edit!!))
        assertNull("gone after the tap", offers.current)
        // with the next words typed, the tap still lands on the word, text after the cursor untouched
        val (o2, x) = offerFor("Mañana paso por tu caza como ")
        assertNotNull(x)
        assertFalse(o2.refresh("Mañana paso por tu caza como a las", whole = true, collapsed = true))
        assertEquals("Mañana paso por tu casa como a las", Edits.apply("Mañana paso por tu caza como a las", o2.take("Mañana paso por tu caza como a las", true)!!))
        // di → si, the case of the typed word
        assertEquals("si", offerFor("No se di vamos ").second?.label)
        assertEquals("the case of the typed word", "Casa", offerFor("paso por tu Caza como ").second?.label)
        // a right sentence: no offer
        for (s in listOf("paso por tu casa como ", "No sé si vamos ", "Mañana paso por tu casa como a las siete. ", "Voy para la casa de ")) assertNull(s, offerFor(s).second)
        // CO4: the user's own / Chilean words are kept
        assertNull(offerFor("fue fome la ").second)
        assertNull(offerFor("el USB no ").second)
    }

    @Test fun stStaleOffersAndKeptPairs() {
        val start = "Mañana paso por tu caza como "
        // ST1: the sentence moves on (its mark, then the next sentence) → gone, counted as kept
        var (offers, o) = offerFor(start)
        assertNotNull(o)
        assertFalse("its mark alone keeps it", offers.refresh("Mañana paso por tu caza como a las siete.", true, true))
        assertFalse(offers.refresh("Mañana paso por tu caza como a las siete. ", true, true))
        assertTrue("the next sentence starts", offers.refresh("Mañana paso por tu caza como a las siete. Si", true, true))
        assertNull(offers.current)
        assertFalse(offers.suppressed("caza", "casa"))
        // a second kept original in the same field: the pair is not offered again there (ST5)
        val job = offers.job(start, true)!!
        val o2 = offers.decide(job, OffersOracle.check, OffersOracle.keepGeneric)!!
        assertTrue(offers.arrive(o2, start, true))
        assertTrue("a new line moved it on", offers.refresh("Mañana paso por tu caza como\n", true, true))
        assertTrue(offers.suppressed("caza", "casa"))
        assertFalse("ST5: kept twice", offers.arrive(o2, start, true))
        offers.reset()
        assertTrue("another field forgets", offers.arrive(o2, start, true))
        // the IME's Enter sent the message: kept
        assertTrue(offers.sent())
        assertNull(offers.current)
        // ST2: the cursor moved into the word, a selection, the field emptied from outside, an earlier word edited → gone, not kept
        for ((before, collapsed) in listOf("Mañana paso por tu ca" to true, start to false, "" to true, "Hoy paso por tu caza como " to true)) {
            val f = ContextOffers()
            assertTrue(f.arrive(o2, start, true))
            assertTrue(before, f.refresh(before, true, collapsed))
            assertFalse(before, f.suppressed("caza", "casa"))
            assertNull("a stale tap edits nothing", f.take(before, true))
        }
        // ST3: a late decision for a word that changed is not shown
        val f = ContextOffers()
        assertFalse(f.arrive(o2, "Mañana paso por tu cama como ", true))
        assertFalse(f.arrive(o2, "Mañana paso por tu caza. Como ", true))
        // CO5: the alternative typed later in the sentence withdraws it
        assertTrue(f.arrive(o2, start, true))
        assertTrue(f.refresh("Mañana paso por tu caza como si fuera mi casa ", true, true))
        // a window that may start mid-sentence: no job, no offer
        assertNull(f.job(start, whole = false))
        assertNotNull(f.job("Hola. $start", whole = false))
        // the kept-pair memory is bounded (HP3)
        val g = ContextOffers()
        fun tag(n: Int) = buildString { var k = n; do { append('a' + k % 26); k /= 26 } while (k > 0) }
        repeat(ContextOffers.MAX_PAIRS * 3) { n ->
            val x = ContextOffers.Offer("w" + tag(n), "v" + tag(n), "v" + tag(n), 7.0, listOf("w" + tag(n)))
            repeat(2) { assertTrue(g.arrive(x, "w${tag(n)} y ", true)); assertTrue(g.sent()) }
            assertTrue(g.suppressed("w" + tag(n), "v" + tag(n)))
        }
        assertFalse("the oldest pair was dropped", g.suppressed("w" + tag(0), "v" + tag(0)))
    }

    // ── PV / LC — the switch, the gates, the credits ─────────────────────────────────────────────────────────
    @Test fun pvTheSwitchTheGatesAndTheCredits() {
        // PV4: on by default, stored phone-wide, on the Escritura page under Sugerencias; the modes that turn
        // suggestions off turn the offers off with them (the gate reads suggest first)
        assertTrue(KbSettings().contextOffers)
        val st = ProfileCodec.seed().updatePhone { it.copy(contextOffers = false) }
        assertFalse(ProfileCodec.decode(ProfileCodec.encode(st)).settings.contextOffers)
        assertEquals(Ctl.SUGGEST, Ctl.CONTEXT_OFFERS.dependsOn)
        assertEquals("escritura", SettingsIA.pageOf(Ctl.CONTEXT_OFFERS)?.id)
        assertFalse(Mode.CODE.apply(KbSettings()).suggest)
        // PV1 / PV3 / PV4: the IME's gate (secret, incognito, the corrector's field gate, the switch, Spanish)
        val ime = File("src/main/kotlin/com/resyst/vk/ime/ResystImeService.kt").readText()
        val gate = ime.substringAfter("private fun offersAllowed()").substringBefore("\n    }")
        for (s in listOf("st.suggest", "st.contextOffers", "policy.suggestions", "!policy.secret", "!policy.incognito", "FieldKind.TEXT", "Lang.ES")) assertTrue(s, gate.contains(s))
        // PV2 / CO2: the offer code stores nothing and edits only from the tap
        val glue = ime.substringAfter("// ── r13f: context offers").substringBefore("// ── r10: text editing")
        for (s in listOf("PersonalStore.changed", "learner.afterEdit", "learner.kept", "strike(", "rememberValue", ".unlearn(")) assertFalse(s, glue.contains(s))
        assertEquals("one edit path: the tap", 1, Regex("run\\(outs, ic\\)").findAll(glue).count())
        assertTrue(glue.substringAfter("override fun onContextOffer()").contains("offers.take("))
        // HP5: the key path only posts
        assertTrue(ime.substringAfter("private fun scheduleOffer()").substringBefore("\n    }").contains("mainHandler.post"))
        // LC1: credits next to the dictionaries, README, strings in Spanish and English
        val credits = File("src/main/kotlin/com/resyst/vk/settings/SettingsActivity.kt").readText().substringAfter("private fun footer()").substringBefore("companion object")
        for (c in listOf("Wikipedia contributors, Wikipedia, la enciclopedia libre (CC BY-SA 4.0)", "Tatoeba contributors (CC BY 2.0 FR)", "FrequencyWords (CC BY-SA 4.0)")) assertTrue(c, credits.contains(c))
        val readme = File("../README.md").readText()
        assertTrue(readme.contains("GPL-3.0-only") && readme.contains("Tatoeba contributors") && readme.contains("Wikipedia contributors"))
        val es = File("src/main/res/values/strings.xml").readText()
        val en = File("src/main/res/values-en/strings.xml").readText()
        assertTrue(es.contains(">Sugerencias por contexto<") && es.contains(">Cambiar %1\$s por %2\$s<"))
        assertTrue(en.contains(">Context suggestions<") && en.contains(">Replace %1\$s with %2\$s<"))
    }

    // ── JSON (deterministic) ──────────────────────────────────────────────────────────────────────────────────
    private fun json(x: Any?): String = when (x) {
        null -> "null"
        is String -> MiniJson.quote(x)
        is Boolean -> x.toString()
        is Int, is Long -> x.toString()
        is Double -> if (x.isNaN() || x.isInfinite()) "null" else String.format(Locale.ROOT, "%.4f", x)
        is Map<*, *> -> x.entries.joinToString(",\n", "{", "}") { MiniJson.quote(it.key.toString()) + ":" + json(it.value) }
        is Collection<*> -> x.joinToString(",", "[", "]") { json(it) }
        else -> MiniJson.quote(x.toString())
    }

    companion object {
        const val SHA256 = "dae872a0e85e44a3f14b6ef1ad9889852f935b864509fdad8caf9a33aa9487c0"
    }
}
