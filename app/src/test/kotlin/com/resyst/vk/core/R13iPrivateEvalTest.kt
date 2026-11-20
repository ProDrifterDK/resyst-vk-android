package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * r13i (PM12): the private-eval harness's entries.
 * - [privateEval]: `VK_PRIVATE_EVAL=/path/messages.jsonl` (+ optional `VK_PRIVATE_EVAL_OUT=/path/aggregate.json`;
 *   default build/r13i-private-eval/aggregate.json): the owner's history; skipped without the variable.
 *   scripts/r13i_private_eval.sh runs it.
 * - [standInEval]: `VK_R13I_STANDIN=1`: public text split as a stand-in history → build/r13i/standin.json.
 * - [smoke]: always: a small slice of the stand-in through the whole pipeline; its report holds numbers and fixed
 *   labels only.
 * Every report passes [assertAggregatesOnly]: fixed labels as its only strings, and keys from the closed schema in
 * src/test/resources/r13i/private-eval-keys.txt (PM12b; tested by [schemaWordsInTheInputPass],
 * [aKeyFromTheInputFails], [aMarkerInTheInputIsNeverWrittenOrPrinted]).
 */
class R13iPrivateEvalTest {
    private fun leaves(x: Any?, out: MutableList<String>) {
        when (x) {
            is String -> out += x
            is Map<*, *> -> x.values.forEach { leaves(it, out) }
            is Iterable<*> -> x.forEach { leaves(it, out) }
        }
    }

    private fun keys(x: Any?, out: MutableSet<String>) {
        when (x) {
            is Map<*, *> -> x.forEach { (k, v) -> out += k.toString(); keys(v, out) }
            is Iterable<*> -> x.forEach { keys(it, out) }
        }
    }

    /**
     * PM12 / PM12b: the only strings in a report are its fixed labels, and every key (at any depth) is in the closed
     * schema ([SCHEMA], a frozen file; nothing in it comes from the input). Fails closed with counts only.
     */
    private fun assertAggregatesOnly(rep: Map<String, Any?>) {
        val strings = ArrayList<String>()
        leaves(rep, strings)
        assertTrue("${strings.size} strings in the report, some not a fixed label (not printed: private)", strings.all { it.startsWith("STAND-IN") || it.startsWith("private") || it.startsWith("0.13.0") || it.startsWith("fewer than") })
        val ks = HashSet<String>()
        keys(rep, ks)
        val outside = ks.count { it !in SCHEMA }
        assertTrue("$outside report keys outside the closed schema (not printed: private)", outside == 0)
    }

    private fun write(rep: Map<String, Any?>, f: File) {
        f.parentFile.mkdirs()
        f.writeText(R13iPrivateEval.json(rep) + "\n")
    }

    @Test fun privateEval() {
        val path = System.getenv("VK_PRIVATE_EVAL")
        assumeTrue("VK_PRIVATE_EVAL not set", !path.isNullOrBlank())
        val msgs = R13iPrivateEval.load(File(path!!))
        val rep = R13iPrivateEval.run(msgs) { System.err.println("r13i private eval: $it") }
        assertAggregatesOnly(rep)
        val out = System.getenv("VK_PRIVATE_EVAL_OUT")?.takeIf { it.isNotBlank() }?.let(::File) ?: File("build/r13i-private-eval/aggregate.json")
        write(rep, out)
    }

    @Test fun standInEval() {
        assumeTrue("VK_R13I_STANDIN not set", System.getenv("VK_R13I_STANDIN") == "1")
        val msgs = R13iPrivateEval.standIn()
        val rep = R13iPrivateEval.run(msgs, standIn = true) { System.err.println("r13i stand-in: $it") }
        assertAggregatesOnly(rep)
        write(rep, File("build/r13i/standin.json"))
        // the same stand-in as the script's input format (public text), to run scripts/r13i_private_eval.sh end to end
        File("build/r13i/standin-messages.jsonl").writeText(msgs.joinToString("") {
            R13iPrivateEval.json(linkedMapOf("ts" to it.ts, "source" to it.source, "text" to it.text)).replace("\n", "").replace("{  ", "{") + "\n"
        })
    }

    /**
     * The device run's seed (scripts/e2e_r13i.py --seed): `VK_R13I_SEED=1` → build/r13i/seed-pairs.bin, the stand-in
     * history (public text) learned through the product path with today's clock, so the phone's memory is active
     * (≥ [PairMemory.ACTIVE_TOKENS]) before the run teaches it one pair by typing.
     */
    @Test fun seedForDevice() {
        assumeTrue("VK_R13I_SEED not set", System.getenv("VK_R13I_SEED") == "1")
        val mem = PairMemory()
        val c = S4Chat.Composer(singleLine = false, model = PersonalModel(), store = Rejections(), pairs = mem)
        c.now = System.currentTimeMillis()
        for ((n, m) in R13iPrivateEval.standIn().withIndex()) { c.run(S4Chat.Message("s$n", R13iPrivateEval.keys(m.text, null))); c.appSends() }
        assertTrue(mem.active && mem.pairCount("la", "pega") == 0.0)
        File("build/r13i").mkdirs()
        File("build/r13i/seed-pairs.bin").writeBytes(mem.toBytes())
        File("build/r13i/seed-pairs.json").writeText(R13iPrivateEval.json(linkedMapOf("learned_tokens" to Math.round(mem.tokens), "entries" to mem.entries,
            "words" to mem.wordCount, "bytes" to mem.toBytes().size, "active" to mem.active, "source" to "STAND-IN public text (r13f chat-source + prose A)")) + "\n")
    }

    /**
     * PM13: enough of the stand-in that both splits learn past the cold start, so the always-on suite runs the mixed
     * path (λ) end to end; its offers are frozen like Round 13f's numbers.
     */
    @Test fun smoke() {
        val msgs = R13iPrivateEval.standIn().take(SMOKE_MESSAGES)
        val rep = R13iPrivateEval.run(msgs, seeds = listOf(1), standIn = true)
        write(rep, File("build/r13i/smoke.json"))
        assertAggregatesOnly(rep)
        val splits = rep["splits"] as Map<*, *>
        assertEquals(setOf("split80", "split65"), splits.keys)
        val got = LinkedHashMap<String, List<Any?>>()
        for ((name, s) in splits) {
            s as Map<*, *>
            val m = s["memory"] as Map<*, *>
            assertTrue("the product path learned the stand-in: $m", (m["entries"] as Int) > 100)
            assertEquals("$name: the memory is past the cold start: $m", true, m["active"])
            val syn = s["synthetic"] as Map<*, *>
            val a = syn["A_0121_tau6"] as Map<*, *>
            val b = syn["B_0130_tau_memory"] as Map<*, *>
            val cleanB = (s["clean_run_seed0"] as Map<*, *>)["B_tau_memory"] as Map<*, *>
            val sweep = syn["B_tau_sweep"] as Map<*, *>
            got[name.toString()] = listOf(a["made"], a["right"], b["made"], b["right"], b["wrong_alt"], b["false_on_clean"], cleanB["made"],
                sweep.values.map { (it as Map<*, *>)["made"] })
        }
        println("r13i smoke frozen: $got")
        assertEquals("the smoke run's offers (A made/right, B made/right/wrong/false, clean B made, B made per τ)", SMOKE_FROZEN, got)
    }

    /**
     * PM12b: a technical writer's history (every schema key written as a word, in kept and in dropped messages) is
     * not refused: no key of the report comes from the input, so a word the code also uses as a field name is no leak.
     */
    @Test fun schemaWordsInTheInputPass() {
        val (msgs, rep) = schemaWordsRun
        assertTrue("the run got past the cleaning (splits in the report)", rep["splits"] is Map<*, *>)
        val ks = HashSet<String>()
        keys(rep, ks)
        val words = msgs.flatMap { R13iPrivateEval.WORDRE.findAll(it.text).map { m -> m.value.lowercase() }.toList() }.toSet()
        assertTrue("the history writes the report's own field names (a real collision, not a vacuous pass)", ks.count { it.lowercase() in words } >= 20)
        assertAggregatesOnly(rep)
    }

    private fun withKey(m: Map<*, *>, path: List<String>, key: String): Map<String, Any?> {
        val c = LinkedHashMap<String, Any?>()
        m.forEach { (k, v) -> c[k.toString()] = v }
        if (path.isEmpty()) c[key] = 1 else c[path[0]] = withKey(m[path[0]] as Map<*, *>, path.drop(1), key)
        return c
    }

    /** PM12b: a report holding one key taken from its input (its most frequent word, at any depth) fails, counts only. */
    @Test fun aKeyFromTheInputFails() {
        val (msgs, rep) = schemaWordsRun
        val freq = msgs.flatMap { R13iPrivateEval.WORDRE.findAll(it.text).map { m -> m.value.lowercase() }.toList() }.groupingBy { it }.eachCount()
        val word = freq.filterKeys { it !in SCHEMA }.maxByOrNull { it.value }!!.key
        val mutants = listOf(
            emptyList<String>() to word,
            listOf("drops") to "lines_$word",
            listOf("messages_kept_by_source") to word,
            listOf("splits", "split80", "memory") to word,
            listOf("splits", "split65", "synthetic", "B_tau_sweep") to word,
        )
        for ((path, key) in mutants) {
            val e = assertThrows(AssertionError::class.java) { assertAggregatesOnly(withKey(rep, path, key)) }
            assertEquals("1 report keys outside the closed schema (not printed: private)", e.message)
        }
    }

    /**
     * PM12 / PM12b: a unique marker word, in kept messages (learned and evaluated), in dropped lines and messages and
     * in the source field, appears neither in the written aggregate nor in anything the run prints; nothing is
     * written next to the input.
     */
    @Test fun aMarkerInTheInputIsNeverWrittenOrPrinted() {
        val marker = "zquorvexmarcador"
        val root = File("build/r13i/marker-probe").apply { deleteRecursively() }
        val dir = File(root, "in").apply { mkdirs() }
        val input = File(dir, "messages.jsonl")
        val base = R13iPrivateEval.standIn().take(60)
        val rows = ArrayList<Map<String, Any?>>()
        for ((i, m) in base.withIndex()) {
            val sp = m.text.indexOf(' ')
            val text = if (i % 3 == 0 && sp > 0) m.text.substring(0, sp) + " $marker" + m.text.substring(sp) else m.text
            rows += linkedMapOf("ts" to m.ts, "source" to (if (i % 5 == 0) marker else if (i % 2 == 0) "phone" else "desktop"), "text" to text)
        }
        val t = base.last().ts
        for ((k, text) in listOf("```$marker```", "git commit $marker", "$marker ok", "la casa del $marker ".repeat(80), "mira https://$marker.com hoy", "$marker = 1;").withIndex()) {
            rows += linkedMapOf("ts" to t - 1000.0 * (k + 1), "source" to "phone", "text" to text)
        }
        input.writeText(rows.joinToString("") { R13iPrivateEval.json(it).replace("\n", "") + "\n" })
        val out = File(root, "out/aggregate.json")
        val captured = java.io.ByteArrayOutputStream()
        val (oldOut, oldErr) = System.out to System.err
        val cap = java.io.PrintStream(captured, true, "UTF-8")
        System.setOut(cap)
        System.setErr(cap)
        var msgs: List<R13iPrivateEval.Msg> = emptyList()
        try {
            msgs = R13iPrivateEval.load(input)
            val rep = R13iPrivateEval.run(msgs, seeds = listOf(1)) { System.err.println("r13i marker probe: $it") }
            assertAggregatesOnly(rep)
            assertTrue("the run got past the cleaning", rep["splits"] is Map<*, *>)
            write(rep, out)
        } finally {
            System.setOut(oldOut)
            System.setErr(oldErr)
        }
        val printed = captured.toString("UTF-8")
        assertTrue("the probe captured the run's log", printed.contains("r13i marker probe: split80"))
        assertTrue("the marker reached the learn/eval path (kept messages hold it)", R13iPrivateEval.clean(msgs, HashMap()).count { marker in it.text } >= 5)
        assertFalse("the marker is in the written aggregate", out.readText().contains(marker, ignoreCase = true))
        assertFalse("the marker is in what the run printed", printed.contains(marker, ignoreCase = true))
        assertEquals("nothing is written next to the input", listOf("messages.jsonl"), dir.list()!!.toList())
    }

    companion object {
        val SCHEMA_FILE = File("src/test/resources/r13i/private-eval-keys.txt")
        val SCHEMA: Set<String> by lazy { SCHEMA_FILE.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet() }

        /** The stand-in's first messages plus messages that write every schema key, run once for the tests that read it. */
        val schemaWordsRun: Pair<List<R13iPrivateEval.Msg>, Map<String, Any?>> by lazy {
            val base = R13iPrivateEval.standIn().take(60)
            val dt = (base.last().ts - base.first().ts) / (base.size * 2)
            val extra = ArrayList<R13iPrivateEval.Msg>()
            val all = SCHEMA.toList()
            // dropped: the raw key lists (not Spanish-dominant, symbols)
            for ((n, chunk) in all.chunked(12).withIndex()) extra += R13iPrivateEval.Msg(base.first().ts + (2 * n + 1) * dt, if (n % 2 == 0) "phone" else "desktop", chunk.joinToString(" "))
            // kept: Spanish sentences carrying the alphabetic keys, so they are learned and evaluated too
            val alpha = all.filter { k -> k.all { it.isLetter() } && k.length >= 4 }
            for ((n, chunk) in alpha.chunked(3).withIndex()) extra += R13iPrivateEval.Msg(base.first().ts + (2 * n + 2) * dt, if (n % 2 == 0) "phone" else "desktop",
                "hoy revisé la ${chunk[0]} con calma y también el ${chunk.getOrElse(1) { "dato" }} de la semana, pero el ${chunk.getOrElse(2) { "resto" }} sigue igual que ayer")
            val msgs = (base + extra).sortedBy { it.ts }
            msgs to R13iPrivateEval.run(msgs, seeds = listOf(1), standIn = true)
        }

        const val SMOKE_MESSAGES = 200
        /** Frozen on 0.13.0 (λ 0.2, τ_memory 5.0, the 2,000-token cold start); τ sweep 4.5 … 6.0. */
        val SMOKE_FROZEN: Map<String, List<Any?>> = linkedMapOf(
            "split80" to listOf(39, 33, 49, 40, 4, 2, 1, listOf(50, 49, 49, 49, 45, 42, 38)),
            "split65" to listOf(57, 55, 62, 60, 1, 1, 2, listOf(71, 66, 62, 59, 58, 58, 56)),
        )
    }
}
