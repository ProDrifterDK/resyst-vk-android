package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * r13d acceptance: the owner's three live sessions replayed through the REAL engine + the shipped corrector
 * + the shipped assets (r13c's method: a fresh field holds `left`, `typed` is typed key by key, the key that
 * ended the word live is pressed). Fixtures committed unchanged: r13c/s1-live.json (VK 0.8.1), s2-live.json
 * (VK 0.9.0), r13d/s3-live.json (VK 0.10.0, Google Keep, everyday typing).
 *
 * s3 runs in four ways: the letters alone (`text`); with the keyboard's touch record (`touch`: each letter's
 * border keys from s3's raw touches, r13d/s3-touch.json, TB5); with the owner's learned vocabulary
 * (`learned`: GrapheneOS, Bastión, cachai taught in ordinary fields and READ, as in Keep's opted-out note,
 * OR1); and both (`learned+touch`, what this build does on his phone). Writes build/r13d/replay.txt +
 * replay.json: per session / mode and per end key, this build vs the live `vk_final`, the misses, the
 * noise rows, every word left or changed.
 */
class Round13dReplayTest {
    class Row(val cat: String, val left: String, val typed: String, val end: String, val gold: String, val vk: String?)

    private fun rows(path: String): List<Row> = (MiniJson.parse(File("src/test/resources/$path").readText()) as List<*>).map {
        val m = it as Map<*, *>
        Row(m["cat"] as String, m["left"] as String, m["typed"] as String, m["end"] as String, m["gold"] as String, m["vk_final"] as String?)
    }

    private fun word(out: String, end: String) = out.removeSuffix(" ").removeSuffix(end).trimEnd(' ')
    private fun same(a: String?, b: String) = a != null && a.lowercase() == b.lowercase()
    private fun shown(end: String) = if (end == "\n") "⏎" else if (end == " ") "" else end

    private class Tally { var n = 0; var fixed = 0; var vk = 0 }

    private class Result(val name: String) {
        val byEnd = LinkedHashMap<String, Tally>()
        val all = Tally()
        val clean = Tally() // non-noise typo rows
        val misses = ArrayList<String>()
        val noiseMisses = ArrayList<String>()
        val wrong = ArrayList<String>() // a non-noise typo row turned into another word
        val okBroken = ArrayList<String>()
        val innerCapital = ArrayList<String>()
        val endingLost = ArrayList<String>()
        val got = HashMap<Int, String>()
        val micros = ArrayList<Long>()
    }

    /** The owner's phone: GrapheneOS, Bastión, cachai taught in ordinary fields (casing evidence, B5 uses). */
    private val owner: PersonalModel by lazy {
        val m = PersonalModel()
        val w = ShippedWriting.writing(m)
        ShippedWriting.ImeField(w, m, Rejections()).type("uso GrapheneOS en el Pixel y GrapheneOS no trae nada. El Bastión sigue y el Bastión guarda todo, cachai que cachai ")
        ShippedWriting.ImeField(ShippedWriting.writing(m), m, Rejections()).type("me gusta GrapheneOS y el Bastión ")
        check(m.casing(listOf(Lang.ES, Lang.EN), "grapheneos", false) == "GrapheneOS")
        m
    }

    private fun run(name: String, file: String, noise: Set<String>, learned: Boolean = false, touch: List<List<String>?>? = null): Result {
        val r = Result(name)
        for ((i, row) in rows(file).withIndex()) {
            fun replay(end: String): String {
                val w = ShippedWriting.writing(if (learned) owner else null)
                return if (touch != null) ShippedTouch.replay(row.left, row.typed, end, touch[i], w) else ShippedWriting.replay(row.left, row.typed, end, w)
            }
            val t0 = System.nanoTime()
            val got = word(replay(row.end), row.end)
            if (row.cat == "typo") r.micros += (System.nanoTime() - t0) / 1000
            r.got[i] = got
            if (got.drop(1).any { it.isUpperCase() } && row.gold.drop(1).none { it.isUpperCase() }) r.innerCapital += "${row.typed}→$got"
            // an `ok` row (typed right; s1's are the 0.8.1 rewrites bastion → bastón) stays as typed or becomes the gold
            if (row.cat == "ok" && !same(got, row.gold) && !same(got, row.typed)) r.okBroken += "${row.typed}→$got (gold ${row.gold})"
            if (row.cat != "typo") continue
            val ok = same(got, row.gold)
            val isNoise = row.typed in noise
            for (t in listOfNotNull(r.byEnd.getOrPut(row.end) { Tally() }, r.all, if (isNoise) null else r.clean)) {
                t.n++
                if (ok) t.fixed++
                if (same(row.vk, row.gold)) t.vk++
            }
            if (!ok) (if (isNoise) r.noiseMisses else r.misses) += "${row.typed}${shown(row.end)}→$got (gold ${row.gold})"
            if (!ok && !isNoise && !same(got, row.typed)) r.wrong += "${row.typed}${shown(row.end)}→$got (gold ${row.gold})"
            if (row.end != " " && !ok) {
                val spaced = word(replay(" "), " ")
                if (same(spaced, row.gold)) r.endingLost += "${row.typed}${shown(row.end)}→$got"
            }
        }
        return r
    }

    @Test fun liveSessionsReplay() {
        repeat(2) { rows("r13c/s2-live.json").take(30).forEach { ShippedWriting.replay(it.left, it.typed, it.end) } } // JIT warm-up
        val touch = ShippedTouch.s3()
        assertEquals("s3-touch.json is aligned with s3-live.json", rows("r13d/s3-live.json").size, touch.size)
        val s1 = run("s1", "r13c/s1-live.json", Round13cReplayTest.NOISE_S1)
        val s2 = run("s2", "r13c/s2-live.json", Round13cReplayTest.NOISE_S2)
        val s3 = run("s3 text", "r13d/s3-live.json", NOISE_S3)
        val s3t = run("s3 touch", "r13d/s3-live.json", NOISE_S3, touch = touch)
        val s3l = run("s3 learned", "r13d/s3-live.json", NOISE_S3, learned = true)
        val s3lt = run("s3 learned+touch", "r13d/s3-live.json", NOISE_S3, learned = true, touch = touch)
        val all = listOf(s1, s2, s3, s3t, s3l, s3lt)

        val live = mapOf("s1" to "VK 0.8.1 live", "s2" to "VK 0.9.0 live")
        val report = StringBuilder("r13d live-session replay (KeyboardEngine + Writing.corrector + shipped assets; each word ended by its live key)\n")
        val json = StringBuilder("{")
        for ((i, r) in all.withIndex()) {
            val l = live[r.name] ?: "VK 0.10.0 live"
            report.append("\n${r.name} typo rows: this build ${r.all.fixed}/${r.all.n}, $l ${r.all.vk}/${r.all.n}; outside the tap noise: this build ${r.clean.fixed}/${r.clean.n}, $l ${r.clean.vk}/${r.clean.n}\n")
            report.append(String.format(Locale.ROOT, "  %-6s %10s %16s\n", "end", "this build", l))
            for ((e, t) in r.byEnd) report.append(String.format(Locale.ROOT, "  %-6s %10s %16s\n", if (e == " ") "space" else shown(e), "${t.fixed}/${t.n}", "${t.vk}/${t.n}"))
            report.append("  misses: ${r.misses.joinToString(", ")}\n")
            report.append("  misses on tap-noise rows: ${r.noiseMisses.joinToString(", ")}\n")
            report.append("  wrong words (outside the noise): ${r.wrong.joinToString(", ").ifEmpty { "none" }}\n")
            report.append("  ok rows broken: ${r.okBroken.joinToString(", ").ifEmpty { "none" }}\n")
            val sorted = r.micros.sorted()
            report.append("  per word (all keys + the end key, JVM): median ${sorted[sorted.size / 2]} us, p90 ${sorted[sorted.size * 9 / 10]} us, max ${sorted.last()} us\n")
            if (i > 0) json.append(',')
            json.append(MiniJson.quote(r.name)).append(":{\"typo\":{\"fixed\":${r.all.fixed},\"total\":${r.all.n},\"vk_final\":${r.all.vk}}")
            json.append(",\"outside_noise\":{\"fixed\":${r.clean.fixed},\"total\":${r.clean.n},\"vk_final\":${r.clean.vk}},\"by_end\":{")
            r.byEnd.entries.forEachIndexed { k, (e, t) ->
                if (k > 0) json.append(',')
                json.append(MiniJson.quote(if (e == " ") "space" else if (e == "\n") "newline" else e)).append(":{\"fixed\":${t.fixed},\"total\":${t.n},\"vk_final\":${t.vk}}")
            }
            fun arr(k: String, l: List<String>) = json.append(",\"$k\":[").append(l.joinToString(",") { MiniJson.quote(it) }).append("]")
            json.append("}")
            arr("misses", r.misses); arr("noise_misses", r.noiseMisses); arr("wrong", r.wrong); arr("ok_broken", r.okBroken)
            json.append(",\"press_us\":{\"median\":${sorted[sorted.size / 2]},\"p90\":${sorted[sorted.size * 9 / 10]},\"max\":${sorted.last()}}}")
        }
        json.append(",\"noise_rows\":{\"s1\":[").append(Round13cReplayTest.NOISE_S1.joinToString(",") { MiniJson.quote(it) })
        json.append("],\"s2\":[").append(Round13cReplayTest.NOISE_S2.joinToString(",") { MiniJson.quote(it) })
        json.append("],\"s3\":[").append(NOISE_S3.joinToString(",") { MiniJson.quote(it) }).append("]}")
        json.append(",\"s3_out_of_scope\":[").append(OUT_OF_SCOPE.joinToString(",") { MiniJson.quote(it) }).append("]}")
        File("build/r13d").mkdirs()
        File("build/r13d/replay.txt").writeText(report.toString())
        File("build/r13d/replay.json").writeText(json.toString())
        println(report)

        // r13c's gates, unchanged
        assertEquals("s1: every typo row outside the tap noise is fixed: ${s1.misses}", emptyList<String>(), s1.misses)
        assertTrue("s1 ≥ 43/49: ${s1.all.fixed}", s1.all.fixed >= 43)
        assertTrue("s2 ≥ 55/78: ${s2.all.fixed}", s2.all.fixed >= 55)
        for (r in all) {
            assertEquals("${r.name}: every ok row intact", emptyList<String>(), r.okBroken)
            assertEquals("${r.name}: MC3 no capital inside a word", emptyList<String>(), r.innerCapital)
            assertEquals("${r.name}: EP1 a word space fixes is fixed by its live ending too", emptyList<String>(), r.endingLost)
        }
        // s3: the deterministic misses (FW1, W5-1 + TB1, OR1) and no wrong word (WC1, WC2)
        val s3rows = rows("r13d/s3-live.json")
        fun at(r: Result, typed: String) = r.got.getValue(s3rows.indexOfFirst { it.typed == typed })
        for (r in listOf(s3, s3t, s3l, s3lt)) {
            assertEquals("${r.name}: auer (the first word of the note)", "Ayer", at(r, "auer"))
            assertTrue("${r.name}: yatde never yate: ${at(r, "yatde")}", at(r, "yatde").lowercase() in setOf("yatde", "tarde"))
            assertTrue("${r.name}: nuneros never juntos: ${at(r, "nuneros")}", at(r, "nuneros").lowercase() in setOf("nuneros", "números"))
            assertEquals("${r.name}: no wrong word outside the noise", emptyList<String>(), r.wrong)
        }
        assertEquals("touch: iueho → juego", "juego", at(s3t, "iueho"))
        assertEquals("learned: gtapheneos → GrapheneOS", "GrapheneOS", at(s3l, "gtapheneos"))
        val allowed = OUT_OF_SCOPE + "yatde" // left as typed (tarde and yate are close; WC1)
        assertEquals("learned+touch: every non-noise miss is a real-word slip (or yatde, left)", emptyList<String>(),
            s3lt.misses.map { it.substringBefore('→').trimEnd(',', '.', '⏎') }.filter { it !in allowed })
        assertTrue("s3 learned+touch outside the noise: ${s3lt.clean.fixed}/${s3lt.clean.n}", s3lt.clean.fixed >= s3lt.clean.n - allowed.size)
    }

    companion object {
        /**
         * s3 tap-boundary / recorder noise (report, never tune): typo rows the live build's own engine (361d784
         * = 0.10.0) cannot bring to what it left live from the decoded touches — VK received other keys (VK
         * commits where the finger LIFTS, by its own cells; the decoder reads accessibility boxes): `nuneros`
         * (n slid into j: VK got juneros), `gome` (lifted on the f/g boundary), `hohle` (both h on the g/h
         * boundary), `tuvbo` (`tuvo⌫bo`, v on the v/b boundary); or a touch the recorder did not keep: `pllar`,
         * `cas`, `haolla`, `po` (fixed live; their decoded letters cannot reach the live word); and `l`, a
         * misaligned row (the `l importante` near the end, not `lo que`).
         */
        val NOISE_S3 = setOf("nuneros", "gome", "hohle", "tuvbo", "pllar", "cas", "haolla", "po", "l")

        /** s3 real-word slips (need context; out of scope): yan/tan, de/la, vacan/bacán, pata/para, nieva/nueva, di/Si. */
        val OUT_OF_SCOPE = setOf("yan", "de", "vacan", "pata", "nieva", "di")
    }
}
