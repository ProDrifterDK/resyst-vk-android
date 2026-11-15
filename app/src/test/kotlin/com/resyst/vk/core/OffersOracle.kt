package com.resyst.vk.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.math.ln

/**
 * r13f (offers): the r13e oracle's corpora and conventions (spike/r13e-ngram ce8792c + de71341, Round13eOracleTest),
 * ported for the offline acceptance of the SHIPPED offer path ([ContextCheck] / [ContextOffers] over [ContextLexicon]
 * and the memory-mapped [ContextNgram]). Doc construction, seeds, roles and tallies are the oracle's, unchanged:
 * - s4: the owner's chat session, each word as 0.11.0 replayed it in s4's field (r13f/offers/s4-replay-011.json, the
 *   r13e-s4 spike's per-word replay, which is what the r13e oracle scored) — or as THIS build replays it;
 * - the 315 set (r13b), held-out prose A (Quiroga 1917, r13f/prose-a.txt) and A' (468 injected in-reach slips,
 *   seed 1313), the synthetic chat set (seed 2626).
 * The oracle's notebook, automatic modes, sessions s1–s3 and LLM reference are not ported (not part of the offers).
 */
object OffersOracle {
    const val RES = "src/test/resources/r13f/offers"
    val modelFile = File("src/main/assets/context/wt-m2.vkng")

    fun map(f: File): ByteBuffer = FileChannel.open(f.toPath(), StandardOpenOption.READ).use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) }

    /** The shipped model, read in place from the shipped asset. */
    val model: ContextNgram by lazy { ContextNgram.read(map(modelFile)) }

    /** The shipped lexicon over the IME's own objects (es-CL, as ime/Lexicon builds them). */
    val lex: ContextLexicon by lazy { ContextLexicon(ShippedWriting.lexicons.getValue(Lang.ES), ShippedWriting.validEs, ShippedWriting.validEn) }
    val check: ContextCheck by lazy { ContextCheck(model, lex) }
    val regional: Set<String> by lazy { ShippedWriting.lexicons.getValue(Lang.ES).regionalWords }
    /** The oracle's owner vocabulary for the sessions and s4 (his taught words). */
    val ownerWords = listOf("grapheneos", "bastión", "cachai")
    val keepOwner: ContextKeep.Keep by lazy { ContextKeep.of(regional, ownerWords) }
    val keepGeneric: ContextKeep.Keep by lazy { ContextKeep.of(regional) }

    /** The r13e oracle's LangLex (Round13bOracleTest's), verbatim but for the English hook: the equivalence reference. */
    object OracleLex : ContextCheck.Lex {
        private val list by lazy { ShippedWriting.lexicons.getValue(Lang.ES) }
        private val v by lazy { ShippedWriting.validEs }
        private val extra: Map<String, List<String>> by lazy {
            val words = File("src/main/assets/lexicon/es.txt").readLines().map { it.trim().lowercase() }.filter { it.isNotEmpty() } +
                Regional.protectedWords(Region.ES_CL) { File("src/main/assets/lexicon/regional/$it.txt").takeIf { f -> f.exists() }?.readText() }
            words.filter { w -> w.all { it.isLetter() || it == '\'' } }.distinct().groupBy { ContextCandidates.bare(it) }
        }
        val allExtra: Collection<String> get() = extra.values.flatten()
        val RARE = -ln(250_000.0)
        override fun isWord(w: String) = v.contains(w) || (extra[ContextCandidates.bare(w)]?.contains(w) == true)
        override fun accentVariants(w: String): List<String> =
            (v.accentVariants(w).filter { it == it.lowercase() } + (extra[ContextCandidates.bare(w)] ?: emptyList())).distinct()
        override fun logFreq(w: String) = list.rank(w)?.let { -ln(it + 2.0) } ?: RARE
        override fun isEn(w: String) = ShippedWriting.validEn.contains(w)
    }

    // ── documents ─────────────────────────────────────────────────────────────────────────────────────────────
    class Doc(val corpus: String, val id: String, val text: String, val gold: List<String?>, val chat: Boolean,
              val owner: Boolean = false, val only: Set<Int>? = null) {
        val words = ContextSegments.words(text)
        val segs = ContextSegments.segments(text)
        init { check(gold.size == words.size) { "$corpus/$id: ${gold.size} gold vs ${words.size} words" } }
        val keep: ContextKeep.Keep get() = if (owner) keepOwner else keepGeneric
    }

    class Row(val msg: String, val left: String, val typed: String, val end: String, val gold: String, val lang: Lang, val near: List<String>?)

    val s4Rows: List<Row> by lazy {
        (MiniJson.parse(File("src/test/resources/r13f/s4-live.json").readText()) as List<*>).map {
            val m = it as Map<*, *>
            Row(m["msg"] as String, m["left"] as String, m["typed"] as String, m["end"] as String, m["gold"] as String,
                if (m["lang"] == "en") Lang.EN else Lang.ES, (m["near"] as List<*>?)?.map { x -> x as String })
        }
    }

    /** 0.11.0's per-word replay of every s4 row (what the r13e oracle scored). */
    val s4Replay011: List<String> by lazy {
        val root = MiniJson.parse(File("$RES/s4-replay-011.json").readText()) as Map<*, *>
        val rs = (root["rows"] as List<*>).map { it as Map<*, *> }
        check(rs.size == s4Rows.size)
        rs.mapIndexed { i, m -> check(m["typed"] == s4Rows[i].typed && m["msg"] == s4Rows[i].msg); m["replay"] as String }
    }

    /** As the oracle (P1): spans of the replayed words; a span whose word the keyboard changed is marked. */
    private fun replayText(intended: String, rows: List<Row>, replay: (Int, Row) -> String): Pair<String, List<Pair<IntRange, String?>>> {
        val sb = StringBuilder()
        val spans = ArrayList<Pair<IntRange, String?>>()
        var pos = 0
        for ((i, row) in rows.withIndex()) {
            val start = row.left.length
            check(intended.startsWith(row.left) && intended.startsWith(row.gold, start)) { "row ${row.typed} misaligned" }
            if (start < pos) {
                spans[spans.size - 1] = spans.last().first to null
                continue
            }
            sb.append(intended, pos, start)
            val got = replay(i, row)
            val s = sb.length
            sb.append(got)
            spans += (s until sb.length) to (if (got.isNotEmpty() && got.all { ContextSegments.isWordChar(it) }) row.gold else null)
            pos = start + row.gold.length
        }
        sb.append(intended, pos, intended.length)
        return sb.toString() to spans
    }

    private fun goldFor(text: String, spans: List<Pair<IntRange, String?>>): List<String?> =
        ContextSegments.words(text).map { w ->
            val sp = spans.firstOrNull { w.start in it.first || (w.end - 1) in it.first }
            when {
                sp == null -> w.text
                sp.first.first == w.start && sp.first.last == w.end - 1 -> sp.second
                else -> null
            }
        }

    /** s4, one doc per message; [replay] gives each row's word (global row index). */
    fun s4(replay: (Int, Row) -> String): List<Doc> {
        val intended = File("src/test/resources/r13f/s4-intended.txt").readLines().filter { it.isNotEmpty() }
        val out = ArrayList<Doc>()
        var base = 0
        for ((msg, rs) in s4Rows.groupBy { it.msg }) {
            val b = base
            val (text, spans) = replayText(intended[msg.removePrefix("m").toInt() - 1], rs) { i, row -> replay(b + i, row) }
            base += rs.size
            val t = text.trimEnd()
            out += Doc("s4", msg, t, goldFor(t, spans), chat = true, owner = true)
        }
        return out
    }

    fun s4Spike(): List<Doc> = s4 { i, _ -> s4Replay011[i] }

    /** This build's per-word replay (S4Chat: the shipped engine in s4's field). */
    fun s4ThisBuild(): List<Doc> = s4 { _, r ->
        val out = S4Chat.replayWord(r.left, r.typed, r.end, r.lang, r.near)
        if (r.end == "SEND") out else out.removeSuffix(" ").removeSuffix(r.end).trimEnd(' ')
    }

    fun set315(): List<Doc> = (MiniJson.parse(File("$RES/set315.json").readText()) as List<*>).mapIndexed { n, e ->
        val m = e as Map<*, *>
        val left = m["left"] as String
        val typed = m["typed"] as String
        val text = left + typed + (m["right"] as String)
        val ws = ContextSegments.words(text)
        val i = ws.indexOfFirst { it.start == left.length }
        check(i >= 0 && ws[i].text == typed) { "315 item $n: target not found" }
        Doc("set315", "$n", text, ws.mapIndexed { j, w -> if (j == i) m["gold"] as String else w.text }, chat = false, only = setOf(i))
            .also { cat315[it.id] = m["cat"] as String }
    }

    val cat315 = HashMap<String, String>()

    val proseText: String by lazy { File("src/test/resources/r13f/prose-a.txt").readText() }

    fun proseA() = Doc("proseA", "proseA", proseText, ContextSegments.words(proseText).map { it.text }, chat = false)

    private val oracleCands = ContextCandidates(OracleLex)

    private fun inject(text: String, pick: (Int, java.util.Random) -> Boolean, seed: Long, keep: ContextKeep.Keep): Pair<String, Map<Int, String>> {
        val ws = ContextSegments.words(text)
        val folded = ws.map { Suggest.fold(it.text) }
        val rng = java.util.Random(seed)
        val out = LinkedHashMap<Int, String>()
        var last = -10
        for ((i, w) in ws.withIndex()) {
            if (!pick(i, rng)) continue
            if (i - last < 4 || !ContextCheck.eligibleShape(w.text) || !OracleLex.isWord(w.text.lowercase()) || keep.keeps(folded, i)) continue
            val lw = w.text.lowercase()
            val alts = oracleCands.of(lw).filter { a -> oracleCands.of(a.word).any { it.word == lw } }
            if (alts.isEmpty()) continue
            out[i] = Suggest.matchCase(alts[rng.nextInt(alts.size)].word, w.text)
            last = i
        }
        val sb = StringBuilder()
        var pos = 0
        for ((i, typed) in out) { sb.append(text, pos, ws[i].start).append(typed); pos = ws[i].end }
        sb.append(text, pos, text.length)
        return sb.toString() to out
    }

    fun proseA2(): Doc {
        val (t, inj) = inject(proseText, { _, r -> r.nextDouble() < 0.06 }, 1313L, keepGeneric)
        return Doc("proseA2", "proseA2", t, ContextSegments.words(proseText).map { it.text }, chat = false, only = inj.keys)
    }

    fun chat(): List<Doc> {
        val src = File("$RES/chat-source.txt").readText()
        val sentences = ArrayList<String>()
        for (para in src.split('\n')) {
            for (s in ContextSegments.segments(para)) if (s.closed) sentences += para.substring(s.start, s.end)
        }
        val msgs = ArrayList<String>()
        var i = 0
        var n = 2
        while (i + n <= sentences.size) {
            var m = sentences.subList(i, i + n).joinToString(" ")
            i += n
            n = if (n == 2) 3 else 2
            val wc = ContextSegments.words(m).size
            if (wc < 8 || wc > 60) continue
            if (m.endsWith(".") && !m.endsWith("..")) m = m.dropLast(1)
            msgs += m
        }
        val rng = java.util.Random(2626L)
        val out = ArrayList<Doc>()
        for ((k, m) in msgs.withIndex()) {
            val ws = ContextSegments.words(m)
            val doInject = rng.nextDouble() < 0.6
            val at = rng.nextInt(ws.size)
            val (t, inj) = if (doInject) inject(m, { j, _ -> j >= at }, 7000L + k, keepGeneric).let { (tt, mm) -> tt to mm.keys.take(1).toSet() } else m to emptySet<Int>()
            val text = if (doInject && inj.isNotEmpty()) {
                val j = inj.first()
                val typed = ContextSegments.words(t)[j].text
                m.substring(0, ws[j].start) + typed + m.substring(ws[j].end)
            } else m
            out += Doc("chat", "c${k / 10}m${k % 10}", text, ws.map { it.text }, chat = true)
        }
        return out
    }

    // ── roles and tallies (P1's) ──────────────────────────────────────────────────────────────────────────────
    enum class Role { KEEP, FIX_REAL, FIX_ACCENT, OUT_OF_REACH, CASE_ONLY, NON_WORD }

    fun roleOf(typed: String, g: String): Role {
        if (g == typed) return Role.KEEP
        if (g.lowercase() == typed.lowercase()) return Role.CASE_ONLY
        if (!OracleLex.isWord(typed.lowercase())) return Role.NON_WORD
        val inReach = oracleCands.of(typed.lowercase()).any { it.word == g.lowercase() }
        if (!inReach) return Role.OUT_OF_REACH
        return if (ContextCandidates.bare(g) == ContextCandidates.bare(typed)) Role.FIX_ACCENT else Role.FIX_REAL
    }

    private fun spanishSeg(d: Doc, si: Int) = ContextCheck.spanishSegment(ContextSegments.words(d.text, d.segs[si].start, d.segs[si].end).map { it.text }, OracleLex::isWord, OracleLex::isEn)

    /** In-reach targets (real-word + accent) of [docs]: the recall denominator (the oracle's inventory). */
    fun targets(docs: List<Doc>): Int {
        var n = 0
        for (d in docs) for ((i, w) in d.words.withIndex()) {
            if (d.only != null && i !in d.only) continue
            val g = d.gold[i] ?: continue
            if (g.lowercase() == w.text.lowercase()) continue
            val si = ContextSegments.segmentAt(d.segs, w.start)
            if (si >= 0 && !spanishSeg(d, si)) continue
            val r = roleOf(w.text, g)
            if (r == Role.FIX_REAL || r == Role.FIX_ACCENT) n++
        }
        return n
    }

    /** One scored word: where, what, the decision's alternative and Δ. */
    class Decision(val doc: Doc, val i: Int, val alt: String, val delta: Double) {
        val id get() = "${doc.corpus}|${doc.id}|$i"
        val typed get() = doc.words[i].text
        val gold get() = doc.gold[i]!!
        val right get() = alt == gold.lowercase() && roleOf(typed, gold) !in setOf(Role.KEEP, Role.CASE_ONLY)
    }

    /**
     * The shipped decision on the oracle's cuts (O@1: the text cut at the end of the next word of the same
     * sentence) for every word with a gold label; null-decisions are skipped. [oracleWindow]: the contrast guard sees
     * the whole sentence, words typed later included, as in the r13e oracle (the live IME cannot).
     */
    fun decisions(docs: List<Doc>, check: ContextCheck = this.check, oracleWindow: Boolean = false): List<Decision> {
        val out = ArrayList<Decision>()
        for (d in docs) for ((i, w) in d.words.withIndex()) {
            if (d.only != null && i !in d.only) continue
            if (d.gold[i] == null) continue
            if (i + 1 >= d.words.size) continue
            val si = ContextSegments.segmentAt(d.segs, w.start)
            if (si < 0 || d.words[i + 1].start >= d.segs[si].end) continue // no next word in this sentence
            val cut = d.text.substring(0, d.words[i + 1].end)
            val window = if (!oracleWindow) null else {
                val seg = d.segs[si]
                ContextCheck.OracleView(ContextSegments.words(d.text, if (si > 0) d.segs[si - 1].start else seg.start, seg.end).map { it.text.lowercase() },
                    ContextSegments.words(d.text, seg.start, seg.end).map { it.text })
            }
            val c = check.decide(cut, w.start, w.end, ContextCheck.Params(), d.keep, window) ?: continue
            out += Decision(d, i, c.alt.word, c.delta)
        }
        return out
    }

    /**
     * The IME's live flow: [ContextOffers.job] at the key that ended the next word (the text's own next character:
     * space, a mark, a new line; nothing = the message was sent with Enter for a chat, a space otherwise), then
     * [ContextOffers.decide]. Returns the offers made (Δ ≥ τ) with the word they sit on.
     */
    fun liveOffers(docs: List<Doc>, check: ContextCheck = this.check): List<Pair<Decision, ContextOffers.Offer>> {
        val offers = ContextOffers()
        val out = ArrayList<Pair<Decision, ContextOffers.Offer>>()
        for (d in docs) for ((i, w) in d.words.withIndex()) {
            if (d.only != null && i !in d.only) continue
            if (d.gold[i] == null || i + 1 >= d.words.size) continue
            val nextEnd = d.words[i + 1].end
            val endKey = d.text.getOrNull(nextEnd) ?: if (d.chat) continue else ' '
            val job = offers.job(d.text.substring(0, nextEnd) + endKey, whole = true) ?: continue
            check(job.start == w.start) { "${d.id}: job on another word" }
            val o = offers.decide(job, check, d.keep) ?: continue
            out += Decision(d, i, o.alt, o.delta) to o
        }
        return out
    }
}
