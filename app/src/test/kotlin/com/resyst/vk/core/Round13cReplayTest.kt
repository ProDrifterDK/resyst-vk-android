package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * r13c acceptance: the owner's two live sessions replayed through the REAL engine + the shipped
 * corrector + the shipped assets ([ShippedWriting]). Each row: a fresh field holds `left`, `typed` is
 * typed key by key, then the key that ended the word LIVE is pressed (space, `,`, `.`, new line) and
 * the committed word is read back. Fixtures (committed unchanged from the evidence):
 * src/test/resources/r13c/s1-live.json (VK 0.8.1, careful typing) and s2-live.json (VK 0.9.0,
 * everyday typing). Writes build/r13c/replay.txt + replay.json: per session and per end key, this
 * build vs the live `vk_final`, the misses, the tap-noise rows, the timings.
 */
class Round13cReplayTest {
    class Row(val cat: String, val left: String, val typed: String, val end: String, val gold: String, val vk: String?)

    private fun rows(name: String): List<Row> = (MiniJson.parse(File("src/test/resources/r13c/$name").readText()) as List<*>).map {
        val m = it as Map<*, *>
        Row(m["cat"] as String, m["left"] as String, m["typed"] as String, m["end"] as String, m["gold"] as String, m["vk_final"] as String?)
    }

    /** The word [replay] committed: what follows `left`, without the ending and a trailing space. */
    private fun word(out: String, end: String) = out.removeSuffix(" ").removeSuffix(end).trimEnd(' ')

    private fun same(a: String?, b: String) = a != null && a.lowercase() == b.lowercase()

    private class Tally { var n = 0; var fixed = 0; var vk = 0; var space = 0 }

    private class Result(val name: String) {
        val byEnd = LinkedHashMap<String, Tally>()
        val all = Tally()
        val misses = ArrayList<String>()
        val noiseMisses = ArrayList<String>()
        val endingLost = ArrayList<String>()
        val innerCapital = ArrayList<String>()
        val micros = ArrayList<Long>()
    }

    private fun run(name: String, file: String, noise: Set<String>): Result {
        val r = Result(name)
        for (row in rows(file)) {
            val t0 = System.nanoTime()
            val got = word(ShippedWriting.replay(row.left, row.typed, row.end), row.end)
            if (row.cat == "typo") r.micros += (System.nanoTime() - t0) / 1000
            // MC3: no capital inside a word unless the word itself has one (GrapheneOS)
            if (got.drop(1).any { it.isUpperCase() } && row.gold.drop(1).none { it.isUpperCase() }) r.innerCapital += "${row.typed}→$got"
            if (row.cat != "typo") continue
            val spaced = word(ShippedWriting.replay(row.left, row.typed, " "), " ")
            val ok = same(got, row.gold)
            for (t in listOf(r.byEnd.getOrPut(row.end) { Tally() }, r.all)) {
                t.n++
                if (ok) t.fixed++
                if (same(row.vk, row.gold)) t.vk++
                if (same(spaced, row.gold)) t.space++
            }
            if (!ok) (if (row.typed in noise) r.noiseMisses else r.misses) += "${row.typed}${shown(row.end)}→$got (gold ${row.gold})"
            if (row.end != " " && same(spaced, row.gold) && !ok) r.endingLost += "${row.typed}${shown(row.end)}→$got"
        }
        return r
    }

    private fun shown(end: String) = if (end == "\n") "⏎" else if (end == " ") "" else end

    @Test fun liveSessionsReplay() {
        repeat(2) { rows("s2-live.json").take(30).forEach { ShippedWriting.replay(it.left, it.typed, it.end) } } // JIT warm-up for the timing
        val s1 = run("s1", "s1-live.json", NOISE_S1)
        val s2 = run("s2", "s2-live.json", NOISE_S2)
        val report = StringBuilder("r13c live-session replay (KeyboardEngine + Writing.corrector + shipped assets; each word ended by its live key)\n")
        val json = StringBuilder("{")
        for ((i, r) in listOf(s1, s2).withIndex()) {
            val live = if (r.name == "s1") "VK 0.8.1 live" else "VK 0.9.0 live"
            report.append("\n${r.name} typo rows: this build ${r.all.fixed}/${r.all.n}, $live ${r.all.vk}/${r.all.n}, this build if space had ended every word ${r.all.space}/${r.all.n}\n")
            report.append(String.format(Locale.ROOT, "  %-6s %10s %14s\n", "end", "this build", live))
            for ((e, t) in r.byEnd) report.append(String.format(Locale.ROOT, "  %-6s %10s %14s\n", if (e == " ") "space" else shown(e), "${t.fixed}/${t.n}", "${t.vk}/${t.n}"))
            report.append("  misses: ${r.misses.joinToString(", ")}\n")
            report.append("  misses on tap-noise rows: ${r.noiseMisses.joinToString(", ")}\n")
            if (i > 0) json.append(',')
            json.append(MiniJson.quote(r.name)).append(":{\"typo\":{\"fixed\":${r.all.fixed},\"total\":${r.all.n},\"vk_final\":${r.all.vk},\"space_everywhere\":${r.all.space}},\"by_end\":{")
            r.byEnd.entries.forEachIndexed { k, (e, t) ->
                if (k > 0) json.append(',')
                json.append(MiniJson.quote(if (e == " ") "space" else if (e == "\n") "newline" else e)).append(":{\"fixed\":${t.fixed},\"total\":${t.n},\"vk_final\":${t.vk}}")
            }
            json.append("},\"misses\":[").append(r.misses.joinToString(",") { MiniJson.quote(it) }).append("]")
            json.append(",\"noise_misses\":[").append(r.noiseMisses.joinToString(",") { MiniJson.quote(it) }).append("]")
            val sorted = r.micros.sorted()
            json.append(",\"press_us\":{\"median\":${sorted[sorted.size / 2]},\"p90\":${sorted[sorted.size * 9 / 10]},\"max\":${sorted.last()}}}")
            report.append("  per word (all keys + the end key, JVM): median ${sorted[sorted.size / 2]} us, p90 ${sorted[sorted.size * 9 / 10]} us, max ${sorted.last()} us\n")
        }
        json.append('}')
        File("build/r13c").mkdirs()
        File("build/r13c/replay.txt").writeText(report.toString())
        File("build/r13c/replay.json").writeText(json.toString())
        println(report)

        // the brief's gates
        assertTrue("s2: typo rows fixed ≥ 70%: ${s2.all.fixed}/${s2.all.n}", s2.all.fixed * 100 >= 70 * s2.all.n)
        assertEquals("s1: every typo row outside the tap noise is fixed: ${s1.misses}", emptyList<String>(), s1.misses)
        assertTrue("s1: ${s1.all.fixed}/${s1.all.n}", s1.all.fixed >= 43)
        assertEquals("EP1: a word space fixes is fixed by its live ending too", emptyList<String>(), s1.endingLost + s2.endingLost)
        assertEquals("MC3: no capital inside a word", emptyList<String>(), s1.innerCapital + s2.innerCapital)
    }

    companion object {
        /**
         * Tap-boundary noise (brief: report, do not tune): typo rows where the live build's OWN engine,
         * given the decoded taps and the live end key, cannot produce what it left live — so the IME
         * received other keys than the touch decoder saw (or the end key differs). Found by replaying
         * the fixtures through VK 0.8.1's core (a0a3ec2, its assets) for s1 and 0.9.0's (de1efa4) for s2.
         */
        val NOISE_S1 = setOf("revisndo", "pr", "ltan", "abieras", "ls", "seriom", "tuvom", "respaldoss", "commmit", "condueramos", "graheneos", "porq", "impoetsnte")
        val NOISE_S2 = setOf("cahai", "lo", "tuvbo", "servido", "jueg", "twmknado", "condijeramls", "graphenoeos", "gfapheneos", "traae", "temprparte", "imporrsnte", "dkcionafio")
    }
}
