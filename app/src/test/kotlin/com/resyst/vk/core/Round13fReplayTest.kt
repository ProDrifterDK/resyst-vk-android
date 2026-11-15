package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * r13f acceptance on the owner's chat session s4 (r13f/, copied unchanged from the r13e-s4 spike), through the REAL
 * engine + shipped corrector + shipped assets ([S4Chat]):
 * - the session stream (one composer, layers, sends, the page emptying the field) for three decodings of the space
 *   bar: `live` (what the phone did), `intent` (0.12.0's space rule on the same touches), `all-space` (the literal
 *   "every gesture is a space"); each message as sent, against what the chat received (s4-final.txt);
 * - r13d's per-word convention on s4-live.json (231 rows, each row's own end key; SEND = VK's Enter in the
 *   single-line field): per end key, this build vs the chat (0.11.0 live), misses, ok rows;
 * - SE3: every `ok` row of s1-s4 ended by VK's Enter in a single-line field (must stay), and every typo row so ended.
 * Writes build/r13f/replay.txt + replay.json (the same test on 8ffd08a gives the 0.11.0 columns).
 */
class Round13fReplayTest {
    class Row(val cat: String, val left: String, val typed: String, val end: String, val gold: String, val vk: String?, val lang: Lang, val near: List<String>?, val msg: String?)

    private fun rows(path: String): List<Row> = (MiniJson.parse(File("src/test/resources/$path").readText()) as List<*>).map {
        val m = it as Map<*, *>
        Row(m["cat"] as String, m["left"] as String, m["typed"] as String, m["end"] as String, m["gold"] as String, m["vk_final"] as String?,
            if (m["lang"] == "en") Lang.EN else Lang.ES, (m["near"] as List<*>?)?.map { n -> n as String }, m["msg"] as String?)
    }

    private fun word(out: String, end: String) = out.removeSuffix(" ").removeSuffix(if (end == "SEND") "" else end).trimEnd(' ')
    private fun same(a: String?, b: String) = a != null && a.lowercase(Locale.ROOT) == b.lowercase(Locale.ROOT)
    private fun shown(end: String) = when (end) { " " -> ""; "\n" -> "⏎"; "SEND" -> " SEND"; else -> end }
    private fun q(s: String) = MiniJson.quote(s)
    private fun arr(l: List<String>) = l.joinToString(",", "[", "]") { q(it) }

    /** Word-level differences between what was sent and what the chat received (same word count), else the whole line. */
    private fun diff(id: String, sent: String, chat: String): List<String> {
        val a = sent.trim().split(' ')
        val b = chat.trim().split(' ')
        if (a.size != b.size) return listOf("$id: «${sent.trim()}» vs chat «${chat.trim()}»")
        return a.indices.filter { a[it] != b[it] }.map { "$id: ${b[it]}→${a[it]}" }
    }

    private class Tally { var n = 0; var fixed = 0; var vk = 0 }

    @Test fun s4Replay() {
        val out = StringBuilder("r13f s4 replay (KeyboardEngine + Writing.corrector + shipped assets; S4Chat = ResystImeService in s4's single-line field)\n")
        val json = StringBuilder("{")

        // ── the session stream ───────────────────────────────────────────
        val streams = linkedMapOf("live" to S4Chat.live, "intent" to S4Chat.intent, "all-space" to S4Chat.allSpace)
        val sentBy = LinkedHashMap<String, List<String>>()
        json.append("\"stream\":{")
        for ((k, entry) in streams.entries.withIndex()) {
            val (name, msgs) = entry.key to entry.value
            val c = S4Chat.session(msgs)
            assertEquals("$name: 22 messages sent", 22, c.sent.size)
            sentBy[name] = c.sent
            val diffs = c.sent.indices.flatMap { diff("m%02d".format(it + 1), c.sent[it], S4Chat.final[it]) }
            val identical = c.sent.indices.count { c.sent[it].trim() == S4Chat.final[it].trim() }
            out.append("\nstream $name: $identical/22 messages byte-identical to the chat; changed words: ${diffs.joinToString(", ").ifEmpty { "none" }}\n")
            for ((i, s) in c.sent.withIndex()) out.append("  m%02d %s\n".format(i + 1, s.trim()))
            if (k > 0) json.append(',')
            json.append(q(name)).append(":{\"identical\":$identical,\"changed_words\":${arr(diffs)},\"sent\":${arr(c.sent.map { it.trim() })}}")
        }
        json.append("}")

        // ── per word (r13d's convention), each row's own end ─────────────
        val s4 = rows("r13f/s4-live.json")
        val byEnd = LinkedHashMap<String, Tally>()
        val all = Tally()
        val misses = ArrayList<String>()
        val okBroken = ArrayList<String>()
        val caseRows = ArrayList<String>()
        val got = HashMap<Int, String>()
        for ((i, r) in s4.withIndex()) {
            val g = word(S4Chat.replayWord(r.left, r.typed, r.end, r.lang, r.near), r.end)
            got[i] = g
            if (r.cat == "ok" && !same(g, r.gold) && !same(g, r.typed)) okBroken += "${r.msg} ${r.typed}${shown(r.end)}→$g"
            if (r.cat == "accent_or_case") caseRows += "${r.msg} ${r.typed}${shown(r.end)}→$g (gold ${r.gold}, chat ${r.vk})"
            if (r.cat != "typo") continue
            val ok = same(g, r.gold)
            for (t in listOf(byEnd.getOrPut(r.end) { Tally() }, all)) { t.n++; if (ok) t.fixed++; if (same(r.vk, r.gold)) t.vk++ }
            if (!ok) misses += "${r.msg} ${r.typed}${shown(r.end)}→$g (gold ${r.gold})"
        }
        out.append("\ns4 per word: typo rows this build ${all.fixed}/${all.n}, VK 0.11.0 live ${all.vk}/${all.n}\n")
        out.append(String.format(Locale.ROOT, "  %-6s %10s %16s\n", "end", "this build", "VK 0.11.0 live"))
        for ((e, t) in byEnd) out.append(String.format(Locale.ROOT, "  %-6s %10s %16s\n", if (e == " ") "space" else e, "${t.fixed}/${t.n}", "${t.vk}/${t.n}"))
        out.append("  misses: ${misses.joinToString(", ")}\n  ok rows broken: ${okBroken.joinToString(", ").ifEmpty { "none" }}\n  accent/case rows: ${caseRows.joinToString(", ")}\n")
        json.append(",\"s4_words\":{\"typo\":{\"fixed\":${all.fixed},\"total\":${all.n},\"vk_final\":${all.vk}},\"by_end\":{")
        byEnd.entries.forEachIndexed { k, (e, t) -> if (k > 0) json.append(','); json.append(q(if (e == " ") "space" else e)).append(":{\"fixed\":${t.fixed},\"total\":${t.n},\"vk_final\":${t.vk}}") }
        json.append("},\"misses\":${arr(misses)},\"ok_broken\":${arr(okBroken)},\"accent_or_case\":${arr(caseRows)},\"noise_rows\":${arr(NOISE_S4.toList())}}")

        // ── SE3: s1-s4 rows ended by VK's Enter in a single-line field ───
        val sets = linkedMapOf("s1" to "r13c/s1-live.json", "s2" to "r13c/s2-live.json", "s3" to "r13d/s3-live.json", "s4" to "r13f/s4-live.json")
        var okN = 0
        var typoN = 0
        var typoSend = 0
        var typoEnd = 0
        val sendBroken = ArrayList<String>()
        val sendLost = ArrayList<String>()
        for ((s, f) in sets) for (r in rows(f)) {
            if (r.cat == "accent_or_case") continue
            val atSend = word(S4Chat.replayWord(r.left, r.typed, "SEND", r.lang, r.near), "SEND")
            if (r.cat == "ok") {
                okN++
                if (!same(atSend, r.typed) && !same(atSend, r.gold)) sendBroken += "$s ${r.typed}→$atSend"
            } else if (r.cat == "typo") {
                typoN++
                val atEnd = if (s == "s4") word(S4Chat.replayWord(r.left, r.typed, r.end, r.lang, r.near), r.end) else word(ShippedWriting.replay(r.left, r.typed, r.end), r.end)
                if (same(atSend, r.gold)) typoSend++
                if (same(atEnd, r.gold)) typoEnd++
                if (same(atEnd, r.gold) && !same(atSend, r.gold)) sendLost += "$s ${r.typed}${shown(r.end)}→$atEnd, SEND→$atSend"
            }
        }
        out.append("\nSE3 VK's Enter in a single-line field, every s1-s4 row: ok rows changed ${sendBroken.size}/$okN ${sendBroken.joinToString(", ")}; " +
            "typo rows fixed at Enter $typoSend/$typoN (at their live end $typoEnd/$typoN); fixed at the end but not at Enter: ${sendLost.joinToString(", ").ifEmpty { "none" }}\n")
        json.append(",\"se3_send\":{\"ok_rows\":$okN,\"ok_changed\":${arr(sendBroken)},\"typo_rows\":$typoN,\"typo_fixed_at_send\":$typoSend,\"typo_fixed_at_live_end\":$typoEnd,\"fixed_at_end_not_send\":${arr(sendLost)}}")

        // ── F2 prose counts (OP3) ────────────────────────────────────────
        val op = Round13fTest.proseOpeners()
        out.append("\nOP3 prose A openers: ${op.total}; where the rule capitalizes: right ${op.right.size}, wrong ${op.wrong.size}\n  wrong: ${op.wrong.joinToString(" | ")}\n")
        json.append(",\"op3_prose\":{\"openers\":${op.total},\"right\":${op.right.size},\"wrong\":${op.wrong.size},\"wrong_cases\":${arr(op.wrong)}}")
        json.append("}")

        File("build/r13f").mkdirs()
        File("build/r13f/replay.txt").writeText(out.toString())
        File("build/r13f/replay.json").writeText(json.toString())
        println(out)

        // ── acceptance ───────────────────────────────────────────────────
        val live = sentBy.getValue("live")
        assertTrue("F1 salgo after «?⏎» on symbols: ${live[9]}", live[9].startsWith("Salgo "))
        assertTrue("F1 «? el actual»: ${live[17]}", live[17].contains("? El actual"))
        assertTrue("F1+F2 «¿que» at the start of an emptied field: ${live[17]}", live[17].startsWith("¿Que "))
        assertTrue("OP2 a mid-sentence ¿ stays lowercase: ${live[8]}", live[8].contains("bebidas ¿las"))
        assertTrue("F3 lenyo is corrected before VK's Enter: ${live[17]}", live[17].trim().endsWith("muy lento"))
        val liveDiffs = live.indices.flatMap { diff("m%02d".format(it + 1), live[it], S4Chat.final[it]) }
        assertEquals("live stream: only the r13f fixes (and muchl, which every replay fixes) differ from the chat",
            listOf("m10: salgo→Salgo", "m11: muchl→mucho", "m18: ¿que→¿Que", "m18: el→El", "m18: lenyo→lento"), liveDiffs)
        val intent = sentBy.getValue("intent")
        assertTrue("F4 «del final»: ${intent[4]}", intent[4].contains("la parte del final"))
        assertTrue("F4 «la pena»: ${intent[19]}", intent[19].contains(" la pena "))
        assertTrue("F4 «yay algo» keeps its space: ${intent[14]}", Regex("[yh]ay algo").containsMatchIn(intent[14]))
        assertEquals("s4: every ok row intact", emptyList<String>(), okBroken)
        assertTrue("lenyo SEND → lento", misses.none { it.contains("lenyo") })
        assertTrue("s4 typo rows ≥ the chat: ${all.fixed}/${all.n} vs ${all.vk}", all.fixed >= all.vk)
        assertEquals("SE3: no ok row of s1-s4 changes at VK's Enter", emptyList<String>(), sendBroken)
    }

    companion object {
        /** s4 rows the replay cannot settle (report, never tune): muchl (the engine fixes it, the phone did not; r13e-s4 §1). */
        val NOISE_S4 = setOf("muchl")
    }
}
