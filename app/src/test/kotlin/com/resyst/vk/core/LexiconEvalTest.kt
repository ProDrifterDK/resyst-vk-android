package com.resyst.vk.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * r8 correction quality on the SHIPPED lexicon (app/src/main/assets/lexicon), measured with
 * deterministic synthetic typos a thumb makes: one adjacent-key substitution, a transposition, an
 * omitted letter, an extra adjacent letter, and for long words two adjacent substitutions.
 * Typed without accents, like people type. Writes build/r8-lex/eval-<lang>.txt.
 *
 * Gates (failure modes):
 * Q1 long words (≥ 7) with one thumb typo are corrected to the right word ≥ 75 % of the time.
 * Q2 wrong corrections (a different word than intended) stay ≤ 4 % of attempts, at every length.
 * Q3 short words (≤ 4): wrong corrections ≤ 4 % — the its/it's class of disaster.
 * Q4 no lexicon word is ever "corrected" to another word (only accent re-spelling).
 */
class LexiconEvalTest {
    private val dir = File(System.getProperty("vk.lexDir") ?: "src/main/assets/lexicon")

    private val rows = listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm")
    // typos come from the ES geometry for es, EN for en (a ñ is never a typo on the EN layout)
    private var lang: Lang = Lang.ES
    private fun neighbors(c: Char): List<Char> = rows.flatMap { it.toList() }
        .filter { (lang == Lang.ES || it != 'ñ') && Suggest.adjacent(c, it, lang) }

    private enum class Kind { ADJ, TRANSPOSE, OMIT, INSERT, ADJ2 }

    private fun typo(w: String, kind: Kind, rnd: Random): String? {
        val n = w.length
        return when (kind) {
            Kind.ADJ -> { val i = rnd.nextInt(n); neighbors(w[i]).takeIf { it.isNotEmpty() }?.let { w.substring(0, i) + it[rnd.nextInt(it.size)] + w.substring(i + 1) } }
            Kind.TRANSPOSE -> { val i = rnd.nextInt(n - 1); if (w[i] == w[i + 1]) null else w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2) }
            Kind.OMIT -> { val i = 1 + rnd.nextInt(n - 1); w.removeRange(i, i + 1) }
            Kind.INSERT -> { val i = rnd.nextInt(n); neighbors(w[i]).takeIf { it.isNotEmpty() }?.let { w.substring(0, i + 1) + it[rnd.nextInt(it.size)] + w.substring(i + 1) } }
            Kind.ADJ2 -> typo(w, Kind.ADJ, rnd)?.let { t -> typo(t, Kind.ADJ, rnd) }?.takeIf { it != w }
        }
    }

    private class Score { var tries = 0; var fixed = 0; var wrong = 0; var realWord = 0 }

    /** r10: [region] re-ranks Spanish (RG6); [guard] adds the other language's lexicon (BL5). */
    private fun evaluate(lang: String, sample: Int = 1600, region: Region = Region.ES_ES, guard: Boolean = false, tag: String = ""): Map<String, Score> {
        val raw = File(dir, "$lang.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }
        val words = if (lang == "es") Regional.forRegion(raw, region) { File(dir, "regional/$it.txt").takeIf { f -> f.exists() }?.readText() } else raw
        this.lang = if (lang == "es") Lang.ES else Lang.EN
        val s = Suggest(words, this.lang)
        if (guard) {
            val other = if (lang == "es") "en" else "es"
            s.foreign = Suggest(File(dir, "$other.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }, if (lang == "es") Lang.EN else Lang.ES)
        }
        val known = words.map(Suggest::fold).toHashSet()
        val rnd = Random(8)
        val out = linkedMapOf<String, Score>()
        // targets: real words people write, from the top of the list (rank < 12000), letters only
        val pool = raw.take(12000).filter { w -> w.length >= 3 && w.all { it.isLetter() } }
        repeat(sample) {
            val w = pool[rnd.nextInt(pool.size)]
            val f = Suggest.fold(w)
            val bucket = when { w.length <= 4 -> "len≤4"; w.length <= 6 -> "len5-6"; else -> "len≥7" }
            val kinds = if (w.length >= 8) Kind.values().toList() else Kind.values().filter { it != Kind.ADJ2 }
            val kind = kinds[rnd.nextInt(kinds.size)]
            val t = typo(f, kind, rnd) ?: return@repeat
            val sc = out.getOrPut(bucket) { Score() }
            val kc = out.getOrPut("$bucket/$kind") { Score() }
            for (x in listOf(sc, kc)) {
                x.tries++
                if (t in known) { x.realWord++; continue }
                val c = s.correction(t, sentenceStart = false)
                if (c == null) continue
                if (Suggest.fold(c) == f) x.fixed++ else x.wrong++
            }
        }
        // latency: the space-correction path for a long typo (worst case: 2 edits, whole lexicon)
        val probes = pool.filter { it.length >= 8 }.take(200).map { Suggest.fold(it).let { f -> f.substring(0, 3) + f.substring(4) + "x" } }
        val t0 = System.nanoTime()
        for (p in probes) { s.best("~"); s.correction(p, false) }
        val msPer = (System.nanoTime() - t0) / 1e6 / probes.size
        val report = StringBuilder("lexicon=${dir.path} lang=$lang words=${words.size} jvmMsPerLongCorrection=%.2f\n".format(msPer))
        for ((k, v) in out) {
            val att = v.tries - v.realWord
            report.append("%-16s tries=%4d realWord=%3d fixed=%5.1f%% wrong=%4.1f%%\n".format(
                k, v.tries, v.realWord, 100.0 * v.fixed / maxOf(1, att), 100.0 * v.wrong / maxOf(1, att)))
        }
        File("build/r8-lex").mkdirs()
        File("build/r8-lex/eval-$lang$tag${System.getProperty("vk.evalTag") ?: ""}.txt").writeText(report.toString())
        println(report)
        return out
    }

    private fun rate(s: Score?, f: (Score) -> Int) = if (s == null) 0.0 else f(s).toDouble() / maxOf(1, s.tries - s.realWord)

    @Test fun spanishThumbTypos() = gates(evaluate("es"))

    @Test fun englishThumbTypos() = gates(evaluate("en"))

    /** r10 RG6: the Chilean re-rank keeps the r8 gates (promoted words don't become wrong targets). */
    @Test fun chileanThumbTypos() = gates(evaluate("es", region = Region.ES_CL, tag = "-cl"))

    /** r10 BL5: the other-language guard doesn't cost real typo fixes. */
    @Test fun bilingualGuardKeepsTheGates() {
        gates(evaluate("es", region = Region.ES_CL, guard = true, tag = "-cl-guard"))
        gates(evaluate("en", guard = true, tag = "-guard"))
    }

    private fun gates(r: Map<String, Score>) {
        if (System.getProperty("vk.evalOnly") != null) return
        assertTrue("Q1 long fixed ${rate(r["len≥7"]) { it.fixed }}", rate(r["len≥7"]) { it.fixed } >= 0.75)
        for (b in listOf("len≤4", "len5-6", "len≥7")) assertTrue("Q2 $b wrong ${rate(r[b]) { it.wrong }}", rate(r[b]) { it.wrong } <= 0.04)
    }

    @Test fun lexiconWordsAreNeverReplacedByOtherWords() { // Q4
        for (lang in listOf("es", "en")) {
            val words = File(dir, "$lang.txt").readLines().filter { it.isNotBlank() }
            val s = Suggest(words)
            for (w in words.filterIndexed { i, _ -> i % 7 == 0 }) {
                if (!w.all { it.isLetter() }) continue
                val c = s.correction(w, false) ?: continue
                assertTrue("$lang $w → $c", Suggest.fold(c).replace('ñ', 'n') == Suggest.fold(w).replace('ñ', 'n'))
            }
        }
    }
}
