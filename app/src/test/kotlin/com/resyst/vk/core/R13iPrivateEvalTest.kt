package com.resyst.vk.core

import org.junit.Assert.assertEquals
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

    /** PM12: the only strings in a report are its two labels; every key is a fixed name or a drop reason. */
    private fun assertAggregatesOnly(rep: Map<String, Any?>, input: List<R13iPrivateEval.Msg>) {
        val strings = ArrayList<String>()
        leaves(rep, strings)
        assertTrue("${strings.size} strings in the report, some not a fixed label (not printed: private)", strings.all { it.startsWith("STAND-IN") || it.startsWith("private") || it.startsWith("0.13.0") || it.startsWith("fewer than") })
        val ks = HashSet<String>()
        keys(rep, ks)
        val words = input.flatMap { R13iPrivateEval.WORDRE.findAll(it.text).map { m -> m.value.lowercase() }.toList() }.filter { it.length >= 4 }.toSet()
        val allowed = setOf("desktop", "phone", "label", "build", "made", "right", "words", "seeds", "pairs", "bytes", "active", "memory", "error", "drops", "clean")
        val leaked = ks.filter { it.lowercase() in words && it !in allowed }
        assertTrue("${leaked.size} report keys are input words (not printed: private)", leaked.isEmpty())
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
        assertAggregatesOnly(rep, msgs)
        val out = System.getenv("VK_PRIVATE_EVAL_OUT")?.takeIf { it.isNotBlank() }?.let(::File) ?: File("build/r13i-private-eval/aggregate.json")
        write(rep, out)
    }

    @Test fun standInEval() {
        assumeTrue("VK_R13I_STANDIN not set", System.getenv("VK_R13I_STANDIN") == "1")
        val msgs = R13iPrivateEval.standIn()
        val rep = R13iPrivateEval.run(msgs, standIn = true) { System.err.println("r13i stand-in: $it") }
        assertAggregatesOnly(rep, msgs)
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
        assertAggregatesOnly(rep, msgs)
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

    companion object {
        const val SMOKE_MESSAGES = 200
        /** Frozen on 0.13.0 (λ 0.2, τ_memory 5.0, the 2,000-token cold start); τ sweep 4.5 … 6.0. */
        val SMOKE_FROZEN: Map<String, List<Any?>> = linkedMapOf(
            "split80" to listOf(39, 33, 49, 40, 4, 2, 1, listOf(50, 49, 49, 49, 45, 42, 38)),
            "split65" to listOf(57, 55, 62, 60, 1, 1, 2, listOf(71, 66, 62, 59, 58, 58, 56)),
        )
    }
}
