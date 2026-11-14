package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * r13d (W5-3, T3-2, T3-4): what the new rules could break, measured over the shipped assets. Only APIs that
 * 0.10.0 already had are used, so the same file runs on 361d784 for the before / after numbers
 * (build/r13d/falsefix.json, keyed by -Dvk.evalTag, default "this").
 *
 * - every five-letter a–z string either valid set accepts (lowercase entries) is left alone (R1: 0 changed);
 * - synthetic two-slip typos of the Spanish list's five-letter words (two touching-key substitutions at two
 *   positions, non-words only, 4 per word, seeded): fixed to the word / turned into ANOTHER word / left;
 * - every a–z / ñ three-letter string's decision and a list of Chilean chat shorthand, for the diff.
 */
class Round13dFalseFixTest {
    private val es get() = ShippedWriting.lexicons.getValue(Lang.ES).also { it.foreign = ShippedWriting.lexicons[Lang.EN] }
    private val en get() = ShippedWriting.lexicons.getValue(Lang.EN)
    private val tag = System.getProperty("vk.evalTag") ?: "this"

    private fun nonWord(t: String): Boolean = !ShippedWriting.validEs.contains(t) && !ShippedWriting.validEn.contains(t) &&
        !ShippedWriting.validEs.contains(t.replaceFirstChar { it.uppercaseChar() }) && es.rank(t) == null &&
        (en.rank(t) ?: Int.MAX_VALUE) >= Suggest.FOREIGN_RANK

    @Test fun measure() {
        val es = es
        val out = StringBuilder("{\"tag\":").append(MiniJson.quote(tag))
        val t0 = System.nanoTime()

        // (a) valid five-letter strings are never touched
        val letters = "abcdefghijklmnopqrstuvwxyz"
        var valid = 0
        val touched = ArrayList<String>()
        val buf = CharArray(5)
        fun walk(i: Int) {
            if (i == 5) {
                val s = String(buf)
                if (ShippedWriting.validEs.contains(s) || ShippedWriting.validEn.contains(s)) {
                    valid++
                    es.correction(s, false)?.takeIf { it != s }?.let { touched += "$s→$it" }
                }
                return
            }
            for (c in letters) { buf[i] = c; walk(i + 1) }
        }
        walk(0)
        out.append(",\"valid5\":{\"walked\":$valid,\"changed\":${touched.size},\"examples\":[").append(touched.take(20).joinToString(",") { MiniJson.quote(it) }).append("]}")

        // (b) synthetic two-slip typos of the list's five-letter words (the W5 target band: top 12000)
        val centres = TypoModel.centres(Lang.ES)
        val list = File("src/main/assets/lexicon/es.txt").readLines().map { it.trim() }
        var right = 0; var wrong = 0; var left = 0
        val wrongs = ArrayList<String>()
        for ((rank, w) in list.withIndex()) {
            if (rank >= Suggest.MID_RANK) break
            if (w.length != 5 || !w.all { it in letters || it == 'ñ' }) continue
            val rnd = Random(w.hashCode())
            val pairs = (0 until 5).flatMap { i -> (i + 1 until 5).map { j -> i to j } }.shuffled(rnd).take(4)
            for ((i, j) in pairs) {
                val ni = centres.keys.filter { it != w[i] && TypoModel.near(centres, w[i], it) }
                val nj = centres.keys.filter { it != w[j] && TypoModel.near(centres, w[j], it) }
                if (ni.isEmpty() || nj.isEmpty()) continue
                val t = w.toCharArray().also { it[i] = ni[rnd.nextInt(ni.size)]; it[j] = nj[rnd.nextInt(nj.size)] }.let(::String)
                if (!nonWord(t)) continue
                val got = es.correction(t, false)
                when {
                    got == null -> left++
                    Suggest.fold(got) == Suggest.fold(w) -> right++
                    else -> { wrong++; if (wrongs.size < 40) wrongs += "$t→$got ($w)" }
                }
            }
        }
        val total = right + wrong + left
        out.append(",\"two_slip5\":{\"total\":$total,\"right\":$right,\"wrong\":$wrong,\"left\":$left,\"wrong_examples\":[")
            .append(wrongs.joinToString(",") { MiniJson.quote(it) }).append("]}")

        // (c) three-letter decisions and chat shorthand
        val three = StringBuilder()
        var changed3 = 0
        val alpha3 = "abcdefghijklmnopqrstuvwxyzñ"
        for (a in alpha3) for (b in alpha3) for (c in alpha3) {
            val s = "$a$b$c"
            val got = es.correction(s, false) ?: continue
            if (got == s) continue
            changed3++
            if (three.isNotEmpty()) three.append(',')
            three.append(MiniJson.quote("$s→$got"))
        }
        out.append(",\"three\":{\"changed\":$changed3,\"all\":[").append(three).append("]}")
        val shorthand = listOf("xfa", "pls", "tmb", "bkn", "wna", "ctm", "ntp", "tqm", "npi", "dsp", "dps", "xq", "pq", "wns", "qla", "msj", "grx", "sbs", "tkm", "aki", "toy", "kiero", "wena")
        out.append(",\"shorthand\":{").append(shorthand.joinToString(",") { MiniJson.quote(it) + ":" + MiniJson.quote(es.correction(it, false) ?: it) }).append("}")
        out.append(",\"seconds\":${(System.nanoTime() - t0) / 1_000_000_000}}")
        File("build/r13d").mkdirs()
        File("build/r13d/falsefix-$tag.json").writeText(out.toString())
        println(out.take(4000))
        assertEquals("R1: valid five-letter words changed: $touched", 0, touched.size)
        assertTrue("walked the valid sets: $valid", valid > 10_000)
    }
}
