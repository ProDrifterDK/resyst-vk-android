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
        assertTrue("strings in the report: $strings", strings.all { it.startsWith("STAND-IN") || it.startsWith("private") || it.startsWith("0.13.0") || it.startsWith("fewer than") })
        val ks = HashSet<String>()
        keys(rep, ks)
        val words = input.flatMap { R13iPrivateEval.WORDRE.findAll(it.text).map { m -> m.value.lowercase() }.toList() }.filter { it.length >= 4 }.toSet()
        val allowed = setOf("desktop", "phone", "label", "build", "made", "right", "words", "seeds", "pairs", "bytes", "active", "memory", "error", "drops", "clean")
        val leaked = ks.filter { it.lowercase() in words && it !in allowed }
        assertTrue("report keys that are input words: $leaked", leaked.isEmpty())
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
    }

    @Test fun smoke() {
        val msgs = R13iPrivateEval.standIn().take(120)
        val rep = R13iPrivateEval.run(msgs, seeds = listOf(1), standIn = true)
        assertAggregatesOnly(rep, msgs)
        val splits = rep["splits"] as Map<*, *>
        assertEquals(setOf("split80", "split65"), splits.keys)
        for (s in splits.values) {
            val m = (s as Map<*, *>)["memory"] as Map<*, *>
            assertTrue("the product path learned the stand-in: $m", (m["entries"] as Int) > 100)
        }
        write(rep, File("build/r13i/smoke.json"))
    }
}
