package com.resyst.vk.core

import java.io.File
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * r13i (PM12): the private-eval harness. Input: a JSONL of `{"ts","source","text"}` messages (ts in seconds). Steps,
 * r13g's protocol (fanout r13g-phrase: clean.py, common.py, gen.py, analyze1.py) on THIS build:
 * 1. hygiene (code / URL / path / command / token / log lines dropped; too short, very long or not Spanish-dominant
 *    messages dropped), chronological splits split80 and split65 (by time, no shuffling);
 * 2. the learn part is typed key by key through the shipped engine in a multi-line chat field ([S4Chat.Composer]) with
 *    the PRODUCT learning path attached: the learned words ([Learner], PersonalModel) and the pair memory
 *    ([PairLearner] → [PairMemory]); each message ends with the app's send (the field emptied from outside);
 * 3. the eval part gets r13g's synthetic slips (seeds 1-3; seed 0 = the text as sent), typed key by key through a
 *    fresh field with a frozen copy of the learned words; on the final text, the live O@1 offer flow runs twice:
 *    A = 0.12.1 (no memory, τ 6) and B = 0.13.0 (the learned memory, τ_memory, plus a τ sweep);
 * 4. per token: right (the gold), wrong alternative, false (an offer on a clean word), unscorable (no gold); recall
 *    over the residual errors; Wilson 95 % intervals; an exact McNemar test on the residual targets (A vs B right).
 * Output: aggregates only (counts, rates, intervals, p-values, memory sizes). No word of the input is printed or
 * written, nothing is written next to the input, no network.
 */
object R13iPrivateEval {
    /**
     * One message. PM12b: [source] keys report maps (drop reasons, counts by source), so it is normalised here, for
     * every caller, to one of two fixed names: "phone", anything else "desktop".
     */
    class Msg(val ts: Double, source: String?, val text: String) {
        val source: String = if (source == "phone") "phone" else "desktop"
    }

    // ── 1. input, hygiene, splits (r13g clean.py / common.py) ──────────────────────────────────────────────────
    fun load(f: File): List<Msg> = f.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
        val m = runCatching { MiniJson.parse(line) as Map<*, *> }.getOrNull() ?: return@mapNotNull null
        val ts = (m["ts"] as? Number)?.toDouble() ?: (m["ts"] as? String)?.toDoubleOrNull() ?: return@mapNotNull null
        val text = m["text"] as? String ?: return@mapNotNull null
        Msg(ts, m["source"] as? String, text)
    }.sortedBy { it.ts }

    private val WORD = Regex("[a-záéíóúüñ]+(?:'[a-záéíóúüñ]+)?", RegexOption.IGNORE_CASE)
    private val URL = Regex("https?://|www\\.|\\b[\\w-]+\\.(com|cl|org|net|io|dev|app|ai|md|py|json|kt|ts|js|sh|txt|yaml|yml|toml|apk|log)\\b", RegexOption.IGNORE_CASE)
    private val PATH = Regex("(^|\\s)(~?/[\\w.-]+/|\\.{1,2}/\\w|[A-Za-z]:\\\\)|[\\w-]+/[\\w-]+/[\\w.-]+|\\w+\\.\\w+\\(|::|=>|->|==|!=|&&|\\|\\|\"")
    private val CMD = Regex("^\\s*(\\$ |sudo |git |npm |pnpm |yarn |pip |python3? |cd |ls |cat |adb |gradle|\\./|docker |kubectl |curl |ssh |hermes |systemctl |journalctl |export |source |rm |mv |cp |chmod |make |cargo |node |npx |uv )")
    private val SECRETISH = Regex("\\b[A-Za-z0-9_\\-]{24,}\\b|\\b(sk|pk|ghp|gho|xox[bp])[-_][A-Za-z0-9]{8,}|@\\w+\\.\\w+")
    private val LOGISH = Regex("^\\s*(\\[|\\{|\\}|\\]|<|at |Traceback|File \"|E/|W/|I/|D/|\\d{2}:\\d{2}:\\d{2}|\\d{4}-\\d{2}-\\d{2}|#|>|\\||-{3,}|={3,}|\\*{2,})")

    private fun symbolRatio(s: String): Double = s.count { it in "{}[]<>=;$#|\\`_*/@%^~" }.toDouble() / (s.trim().length.coerceAtLeast(1))

    fun cleanText(raw: String, stats: MutableMap<String, Int>): String {
        var t = raw.replace("\r", "")
        val blocks = Regex("```").findAll(t).count() / 2
        if (blocks > 0) stats.merge("code_blocks", blocks, Int::plus)
        t = Regex("```.*?(```|$)", RegexOption.DOT_MATCHES_ALL).replace(t, "\n")
        val keep = ArrayList<String>()
        for (line in t.split('\n')) {
            val s = line.trim()
            if (s.isEmpty()) continue
            val why = when {
                '`' in s -> "inline_code"
                URL.containsMatchIn(s) -> "url_or_filename"
                PATH.containsMatchIn(s) -> "path_or_code_syntax"
                CMD.containsMatchIn(s) -> "command"
                SECRETISH.containsMatchIn(s) -> "token_like_or_email"
                LOGISH.containsMatchIn(s) || symbolRatio(s) > 0.08 -> "log_json_markup"
                else -> null
            }
            if (why != null) { stats.merge("lines_$why", 1, Int::plus); continue }
            keep += s
        }
        return keep.joinToString("\n")
    }

    fun clean(msgs: List<Msg>, stats: MutableMap<String, Int>): List<Msg> {
        val lex = OffersOracle.lex
        val out = ArrayList<Msg>()
        for (r in msgs) {
            val t = cleanText(r.text, stats)
            val toks = WORD.findAll(t).map { it.value.lowercase() }.toList()
            if (r.text.length > 1000 || t.split(Regex("\\s+")).count { it.isNotEmpty() } > 160) { stats.merge("msg_long_paste/${r.source}", 1, Int::plus); continue }
            if (toks.size < 3) { stats.merge("msg_too_short/${r.source}", 1, Int::plus); continue }
            val es = toks.count { lex.isWord(it) && !lex.isEn(it) }
            val en = toks.count { lex.isEn(it) && !lex.isWord(it) }
            if (en >= es || es < 0.25 * toks.size) { stats.merge("msg_not_spanish_dominant/${r.source}", 1, Int::plus); continue }
            out += Msg(r.ts, r.source, t)
        }
        return out
    }

    val SPLITS = linkedMapOf("split80" to 0.80, "split65" to 0.65)

    fun split(rows: List<Msg>, frac: Double): Pair<List<Msg>, List<Msg>> {
        val t0 = rows.first().ts
        val t1 = rows.last().ts
        val cut = t0 + frac * (t1 - t0)
        return rows.filter { it.ts < cut } to rows.filter { it.ts >= cut }
    }

    // ── 3a. r13g's slip generator (gen.py), on the Pixel 8 Pro letters' drawn boxes (s4 layout.json) ──────────
    private class Box(val x0: Double, val y0: Double, val x1: Double, val y1: Double)

    private val CELLS: Map<Char, Box> by lazy {
        val rows = listOf(
            Triple("qwertyuiop", listOf(10, 110, 209, 309, 409, 508, 608, 708, 807, 907), 1702 to 1814),
            Triple("asdfghjklñ", listOf(10, 110, 209, 309, 409, 508, 608, 708, 807, 907), 1825 to 1938),
            Triple("zxcvbnm", listOf(159, 259, 359, 458, 558, 658, 757), 1949 to 2062),
        )
        val out = LinkedHashMap<Char, Box>()
        // hit cells split the drawn gaps (gen.py: box ± 5 px horizontally, ± 5.5 px vertically)
        for ((letters, lefts, ys) in rows) for (i in letters.indices) out[letters[i]] = Box(lefts[i] - 5.0, ys.first - 5.5, lefts[i] + 90 + 5.0, ys.second + 5.5)
        out
    }
    private const val KW = 99.7
    private const val SPACE_NEAR = "cvbnm"
    private const val P_TYPO = 0.20
    private val TYPO_MIX = linkedMapOf("adj" to 0.70, "adj2" to 0.12, "del" to 0.08, "ins" to 0.07, "trans" to 0.03)
    private const val P_ACCENT_DROP = 0.85
    private const val P_TWO = 0.035
    private val TWO_MIX = linkedMapOf("both" to 0.5, "merge" to 0.25, "split" to 0.25)
    private const val P_CONFUSION = 0.03
    private const val SIGMA_CLEAN = 0.22
    private const val SIGMA_SLIP = 0.45
    private val PAIRS = listOf("casa" to "caza", "si" to "di", "para" to "pata", "y" to "u", "tan" to "yan", "que" to "qué", "mas" to "más",
        "esta" to "está", "como" to "cómo", "el" to "él", "tu" to "tú", "se" to "sé", "de" to "dé", "mi" to "mí",
        "hola" to "hila", "pero" to "perro", "vez" to "ves", "hay" to "ahí", "va" to "ba", "lo" to "ño", "la" to "ña",
        "no" to "ni", "en" to "em", "es" to "ea", "a" to "s", "o" to "p")
    private val CONF: Map<String, List<String>> by lazy {
        val m = LinkedHashMap<String, MutableList<String>>()
        for ((a, b) in PAIRS) { m.getOrPut(a) { ArrayList() } += b; m.getOrPut(b) { ArrayList() } += a }
        m
    }
    val WORDRE = Regex("[A-Za-zÁÉÍÓÚÜÑáéíóúüñ]+")
    private const val ACC = "áéíóúÁÉÍÓÚ"

    fun bare(s: String): String {
        val t = s.lowercase(Locale.ROOT).replace('ñ', '\u0001')
        return Normalizer.normalize(t, Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }.replace('\u0001', 'ñ')
    }

    private fun bareChar(c: Char): Char = bare(c.toString()).firstOrNull() ?: c

    /** One generated unit: the gold text, what was typed, its class (keep, single, real_word, accent, two_word). */
    class GenUnit(val gold: String, val typed: String, val cls: String)

    class Generated(val typed: String, val near: List<String>, val units: List<GenUnit>)

    class Gen(seed: Long) {
        val rng = java.util.Random(seed)
        private val lex = OffersOracle.lex
        private fun eligibleWord(lw: String) = lex.isWord(lw) || lex.isEn(lw)

        private fun cellAt(x: Double, y: Double): Char? = CELLS.entries.firstOrNull { (_, b) -> x >= b.x0 && x <= b.x1 && y >= b.y0 && y <= b.y1 }?.key

        private fun nearOf(x: Double, y: Double, hit: Char): String {
            val r = 0.12 * KW
            val sb = StringBuilder()
            for ((c, b) in CELLS) {
                if (c == hit) continue
                val dx = maxOf(b.x0 - x, 0.0, x - b.x1)
                val dy = maxOf(b.y0 - y, 0.0, y - b.y1)
                if (dx * dx + dy * dy <= r * r) sb.append(c)
            }
            return sb.toString()
        }

        /** (hit letter, near record) for one press meant as [intended] (bare lowercase). */
        private fun touch(intended: Char, slip: Boolean): Pair<Char, String> {
            val b = CELLS[intended] ?: return intended to ""
            val cx = (b.x0 + b.x1) / 2
            val cy = (b.y0 + b.y1) / 2
            repeat(200) {
                val s = if (slip) SIGMA_SLIP else SIGMA_CLEAN
                val x = cx + rng.nextGaussian() * s * KW
                val y = cy + rng.nextGaussian() * s * KW
                val h = cellAt(x, y) ?: return@repeat
                if (slip && h != intended) return h to nearOf(x, y, h)
                if (!slip && h == intended) return h to nearOf(x, y, h)
            }
            return intended to ""
        }

        private fun stripAcc(w: String) = w.map { if (it in ACC) bareChar(it) else it }.joinToString("")
        private fun keycase(c: Char, like: Char) = if (like.isUpperCase()) c.uppercaseChar() else c

        private fun pick(mix: Map<String, Double>): String {
            val x = rng.nextDouble()
            var acc = 0.0
            var last = ""
            for ((k, p) in mix) { acc += p; last = k; if (x < acc) return k }
            return last
        }

        private fun cleanTouch(w: String): List<String> = w.map { c -> val b = bareChar(c); if (b in CELLS) touch(b, false).second else "" }

        private fun typo(w: String, kind0: String): Pair<String, List<String>>? {
            val letters = w.toList()
            val n = letters.size
            var kind = kind0
            if ((kind == "del" || kind == "trans") && n < 3) kind = "adj"
            val slipAt = HashSet<Int>()
            if (kind == "adj" || kind == "adj2") {
                val cand = letters.indices.filter { bareChar(letters[it]) in CELLS }
                if (cand.isEmpty()) return null
                val k = if (kind == "adj2" && cand.size >= 3) 2 else 1
                slipAt += cand.shuffled(rng).take(k)
            }
            val out = ArrayList<Char>()
            val near = ArrayList<String>()
            for ((i, c) in letters.withIndex()) {
                val b = bareChar(c)
                if (b in CELLS) {
                    val (h, nr) = touch(b, i in slipAt)
                    out += if (i in slipAt) keycase(h, c) else c
                    near += nr
                } else { out += c; near += "" }
            }
            when (kind) {
                "del" -> { val i = 1 + rng.nextInt(n - 1); out.removeAt(i); near.removeAt(i) }
                "ins" -> {
                    val i = rng.nextInt(n)
                    val b = bareChar(letters[i])
                    val (extra, nr) = if (rng.nextDouble() < 0.5 || b !in CELLS) letters[i] to "" else touch(b, true).let { keycase(it.first, letters[i]) to it.second }
                    out.add(i + 1, extra); near.add(i + 1, nr)
                }
                "trans" -> {
                    val i = rng.nextInt(n - 1)
                    val t = out[i]; out[i] = out[i + 1]; out[i + 1] = t
                    val u = near[i]; near[i] = near[i + 1]; near[i + 1] = u
                }
            }
            return out.joinToString("") to near
        }

        private class Slip(val typed: String, val near: List<String>, val kind: String?)

        private fun slipWord(w: String, allowConf: Boolean): Slip? {
            val lw = w.lowercase()
            val conf = CONF[lw]
            if (allowConf && conf != null && rng.nextDouble() < 0.5) {
                var t = conf[rng.nextInt(conf.size)]
                if (w[0].isUpperCase()) t = t.replaceFirstChar { it.uppercaseChar() }
                return Slip(t, cleanTouch(t), "confusion")
            }
            var base = w
            var kind: String? = null
            if (w.any { it in ACC } && rng.nextDouble() < P_ACCENT_DROP) { base = stripAcc(w); kind = "accent" }
            val r = if (kind == null || rng.nextDouble() < 0.15) typo(base, pick(TYPO_MIX)) else null
            if (r == null) return if (kind != null) Slip(base, cleanTouch(base), kind) else null
            return Slip(r.first, r.second, if (kind != null) "$kind+typo" else "typo")
        }

        fun message(text: String, slips: Boolean): Generated {
            val toks = WORDRE.findAll(text).toList()
            val units = ArrayList<GenUnit>()
            val out = StringBuilder()
            val near = ArrayList<String>()
            var pos = 0
            var i = 0
            fun sep(s: String) { for (ch in s) { out.append(ch); if (ch != ' ' && ch != '\n') near += "" } }
            fun word(t: String, nr: List<String>) { out.append(t); near.addAll(nr) }
            while (i < toks.size) {
                val m = toks[i]
                sep(text.substring(pos, m.range.first))
                val w = m.value
                val lw = w.lowercase()
                val eligible = slips && eligibleWord(lw) && !(w.length > 1 && w.all { it.isUpperCase() })
                val nxt = toks.getOrNull(i + 1)
                val between = nxt?.let { text.substring(m.range.last + 1, it.range.first) }
                var done = false
                if (eligible && nxt != null && between == " " && rng.nextDouble() < P_TWO) {
                    val w2 = nxt.value
                    val ok2 = eligibleWord(w2.lowercase())
                    when (pick(TWO_MIX)) {
                        "both" -> if (ok2) {
                            val a = slipWord(w, false) ?: Slip(w, cleanTouch(w), null)
                            val b = slipWord(w2, false) ?: Slip(w2, cleanTouch(w2), null)
                            if (a.kind != null || b.kind != null) {
                                word(a.typed, a.near); sep(" "); word(b.typed, b.near)
                                units += GenUnit("$w $w2", "${a.typed} ${b.typed}", "two_word")
                                done = true
                            }
                        }
                        "merge" -> if (ok2) {
                            val a = if (rng.nextDouble() < 0.5) w else stripAcc(w)
                            val b = if (rng.nextDouble() < 0.5) w2 else stripAcc(w2)
                            val t: String
                            val nr: List<String>
                            if (rng.nextDouble() < 0.5) { t = a + b; nr = cleanTouch(a) + cleanTouch(b) }
                            else { val c = SPACE_NEAR[rng.nextInt(SPACE_NEAR.length)]; t = a + c + b; nr = cleanTouch(a) + listOf("") + cleanTouch(b) }
                            word(t, nr)
                            units += GenUnit("$w $w2", t, "two_word")
                            done = true
                        }
                        else -> Unit
                    }
                    if (done) { pos = nxt.range.last + 1; i += 2; continue }
                }
                if (eligible && w.length >= 4 && rng.nextDouble() < P_TWO * TWO_MIX.getValue("split")) {
                    val k = 2 + rng.nextInt(w.length - 3)
                    var a = w.substring(0, k)
                    var b = w.substring(k)
                    if (rng.nextDouble() < 0.5) {
                        val j = (1 until w.length - 1).firstOrNull { bareChar(w[it]) in SPACE_NEAR }
                        if (j != null) { a = w.substring(0, j); b = w.substring(j + 1) }
                    }
                    if (a.isNotEmpty() && b.isNotEmpty()) {
                        word(a, cleanTouch(a)); sep(" "); word(b, cleanTouch(b))
                        units += GenUnit(w, "$a $b", "two_word")
                        pos = m.range.last + 1; i++
                        continue
                    }
                }
                var r: Slip? = null
                if (eligible) {
                    if (CONF.containsKey(lw) && rng.nextDouble() < P_CONFUSION * 4) r = slipWord(w, true)
                    else if (w.any { it in ACC } || rng.nextDouble() < P_TYPO) r = slipWord(w, false)
                }
                if (r == null || r.typed == w) {
                    word(w, cleanTouch(w))
                    units += GenUnit(w, w, "keep")
                } else {
                    word(r.typed, r.near)
                    val lt = r.typed.lowercase()
                    val cls = when {
                        bare(lt) == bare(lw) -> "accent"
                        lex.isWord(lt) -> "real_word"
                        else -> "single"
                    }
                    units += GenUnit(w, r.typed, cls)
                }
                pos = m.range.last + 1; i++
            }
            sep(text.substring(pos))
            val typed = out.toString()
            check(near.size == typed.count { it != ' ' && it != '\n' })
            return Generated(typed, near, units)
        }
    }

    // ── 2. learning through the product path ─────────────────────────────────────────────────────────────────
    /** The composer's keys for [text] typed as written ('\n' = Enter, a new line in this multi-line field). */
    fun keys(text: String, near: List<String>?): List<S4Chat.KeyEv> {
        val keys = ArrayList<S4Chat.KeyEv>()
        var li = 0
        for (ch in text) when (ch) {
            ' ' -> keys += S4Chat.KeyEv(" ", 0, null, 0)
            '\n' -> keys += S4Chat.KeyEv("SEND", 0, null, 0)
            else -> { keys += S4Chat.KeyEv(ch.toString(), 0, near?.getOrNull(li) ?: "", 0); li++ }
        }
        return keys
    }

    class Learned(val model: PersonalModel, val memory: PairMemory, val snapshots: List<Pair<Int, ByteArray>>)

    /**
     * Types [learn] into one chat field (the learned words and the pair memory attached as the IME attaches them);
     * each message ends with the app's send. [checkpoints] = learned-token counts at which the memory is copied
     * (the cold-start size curve).
     */
    fun learn(learn: List<Msg>, checkpoints: List<Int>): Learned {
        val model = PersonalModel()
        val mem = PairMemory()
        val c = S4Chat.Composer(singleLine = false, model = model, store = Rejections(), pairs = mem)
        val snaps = ArrayList<Pair<Int, ByteArray>>()
        var next = 0
        for ((n, m) in learn.withIndex()) {
            c.now = (m.ts * 1000).toLong()
            c.run(S4Chat.Message("l$n", keys(m.text, null)))
            c.appSends()
            while (next < checkpoints.size && mem.tokens >= checkpoints[next]) { snaps += checkpoints[next] to mem.toBytes(); next++ }
        }
        return Learned(model, mem, snaps)
    }

    // ── 3b. typing the eval messages, the final text, gold per final word ────────────────────────────────────
    class Tok(val text: String, val start: Int, val end: Int, val gold: String?, val cls: String)

    class Typed(val source: String, val final: String, val toks: List<Tok>)

    fun typeEval(g: Generated, frozen: String, source: String): Typed {
        val c = S4Chat.Composer(singleLine = false, model = PersonalModel.fromJson(frozen), store = Rejections())
        c.run(S4Chat.Message("e", keys(g.typed, g.near) + S4Chat.KeyEv("SEND", 10_000L, null, 0)))
        val fin = c.text.trimEnd()
        return Typed(source, fin, align(fin, g.units))
    }

    /** The final words aligned to the typed units (word-level edit distance): a 1:1 word gets its unit's gold. */
    fun align(fin: String, units: List<GenUnit>): List<Tok> {
        val t = ArrayList<String>()
        val owner = ArrayList<Int>()
        val pos = ArrayList<Int>()
        for ((ui, u) in units.withIndex()) {
            val ws = WORDRE.findAll(u.typed).map { it.value }.toList()
            for ((k, w) in ws.withIndex()) { t += w.lowercase(); owner += ui; pos += k }
        }
        val fm = WORDRE.findAll(fin).toList()
        val f = fm.map { it.value.lowercase() }
        val n = t.size
        val m = f.size
        val d = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) d[i][0] = i
        for (j in 0..m) d[0][j] = j
        for (i in 1..n) for (j in 1..m) d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (t[i - 1] == f[j - 1]) 0 else 1)
        val gold = arrayOfNulls<String>(m)
        val cls = Array(m) { "keep" }
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            if (d[i][j] == d[i - 1][j - 1] + (if (t[i - 1] == f[j - 1]) 0 else 1)) {
                val u = units[owner[i - 1]]
                val typedN = WORDRE.findAll(u.typed).count()
                val gw = WORDRE.findAll(u.gold).map { it.value }.toList()
                gold[j - 1] = when {
                    typedN == 1 && gw.size == 1 -> gw[0]
                    typedN == gw.size -> gw[pos[i - 1]]
                    else -> null
                }
                cls[j - 1] = u.cls
                i--; j--
            } else if (d[i][j] == d[i - 1][j] + 1) i-- else j--
        }
        return fm.mapIndexed { k, x -> Tok(x.value, x.range.first, x.range.last + 1, gold[k], cls[k]) }
    }

    // ── 3c. decisions: the live O@1 flow on the final text, A and B ──────────────────────────────────────────
    /** One decision: the alternative, its Δ, and whether an active memory took part (τ_memory then, else τ 6). */
    class Pick(val alt: String, val delta: Double, val personal: Boolean)

    /** One decided word: A's decision and B's per memory variant. */
    class Dec(val source: String, val cls: String, val outcomeOf: (String) -> String, val a: Pick?, val b: List<Pick?>)

    fun decisions(t: Typed, memories: List<PairMemory>): List<Dec> {
        val check = OffersOracle.check
        val keep = OffersOracle.keepGeneric
        val offers = ContextOffers()
        val fin = t.final
        val ws = ContextSegments.words(fin)
        val segs = ContextSegments.segments(fin)
        val byStart = t.toks.associateBy { it.start }
        val out = ArrayList<Dec>()
        for ((i, w) in ws.withIndex()) {
            if (i + 1 >= ws.size) continue
            val si = ContextSegments.segmentAt(segs, w.start)
            if (si < 0 || ws[i + 1].start >= segs[si].end) continue
            val nextEnd = ws[i + 1].end
            val endKey = fin.getOrNull(nextEnd) ?: continue
            val job = offers.job(fin.substring(0, nextEnd) + endKey, whole = true) ?: continue
            if (job.typed != w.text) continue
            val p = ContextCheck.Params()
            val ca = check.decide(job.text, job.start, job.end, p, keep)
            val cb = memories.map { mem -> check.decide(job.text, job.start, job.end, p, keep, memory = mem)?.let { Pick(it.alt.word, it.delta, it.personal) } }
            if (ca == null && cb.all { it == null }) continue
            val tok = byStart[w.start]?.takeIf { it.end == w.end }
            val cur = w.text.lowercase()
            val gold = tok?.gold?.lowercase()
            val outcome = { alt: String -> when { gold == null -> "unscorable"; cur == gold -> "false"; alt == gold -> "right"; else -> "wrong" } }
            out += Dec(t.source, tok?.cls ?: "keep", outcome, ca?.let { Pick(it.alt.word, it.delta, false) }, cb)
        }
        return out
    }

    // ── 4. tallies and statistics ────────────────────────────────────────────────────────────────────────────
    fun wilson(k: Int, n: Int, z: Double = 1.96): List<Double>? {
        if (n == 0) return null
        val p = k.toDouble() / n
        val d = 1 + z * z / n
        val c = (p + z * z / (2 * n)) / d
        val h = z * sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / d
        return listOf(round4(maxOf(0.0, c - h)), round4(minOf(1.0, c + h)))
    }

    /** Exact two-sided McNemar (binomial on the discordant pairs). */
    fun mcnemar(onlyA: Int, onlyB: Int): Double {
        val n = onlyA + onlyB
        if (n == 0) return 1.0
        val k = minOf(onlyA, onlyB)
        var lp = -n * ln(2.0)
        var lc = 0.0 // ln C(n, i)
        var s = 0.0
        for (i in 0..k) {
            if (i > 0) lc += ln((n - i + 1).toDouble()) - ln(i.toDouble())
            s += exp(lc + lp)
        }
        return minOf(1.0, 2 * s)
    }

    private fun round4(x: Double) = Math.round(x * 10000.0) / 10000.0

    class Counts(var right: Int = 0, var wrong: Int = 0, var falseOffers: Int = 0, var unscorable: Int = 0) {
        val made get() = right + wrong + falseOffers + unscorable
    }

    /** Offers made at [tau] (for a decision an active memory took part in; any other is compared with τ 6). */
    fun tally(decs: List<Dec>, pick: (Dec) -> Pick?, tau: Double, src: String? = null): Counts {
        val c = Counts()
        for (d in decs) {
            if (src != null && d.source != src) continue
            val x = pick(d) ?: continue
            if (!offered(x, tau)) continue
            when (d.outcomeOf(x.alt)) { "right" -> c.right++; "wrong" -> c.wrong++; "false" -> c.falseOffers++; else -> c.unscorable++ }
        }
        return c
    }

    fun offered(x: Pick, tau: Double) = x.delta >= (if (x.personal) tau else ContextCheck.TAU_OFFER)

    class Inventory(val residual: Int, val clean: Int)

    fun inventory(docs: List<Typed>, src: String? = null): Inventory {
        var r = 0
        var c = 0
        for (t in docs) {
            if (src != null && t.source != src) continue
            for (k in t.toks) { val g = k.gold ?: continue; if (k.text.lowercase() == g.lowercase()) c++ else r++ }
        }
        return Inventory(r, c)
    }

    fun block(c: Counts, inv: Inventory): Map<String, Any?> = linkedMapOf(
        "right" to c.right, "wrong_alt" to c.wrong, "false_on_clean" to c.falseOffers, "unscorable" to c.unscorable, "made" to c.made,
        "precision" to (if (c.made > 0) round4(c.right.toDouble() / c.made) else null), "precision_ci" to wilson(c.right, c.made),
        "recall_residual" to (if (inv.residual > 0) round4(c.right.toDouble() / inv.residual) else null), "recall_residual_ci" to wilson(c.right, inv.residual),
        "residual_targets" to inv.residual, "clean_words" to inv.clean,
        "false_per_1000_clean" to (if (inv.clean > 0) round4(1000.0 * c.falseOffers / inv.clean) else null),
        "false_rate_clean_ci" to wilson(c.falseOffers, inv.clean),
    )

    val TAUS = listOf(4.5, 4.75, 5.0, 5.25, 5.5, 5.75, 6.0)

    /** The B choice: memory [k]'s decision, compared with τ_memory when the memory took part (else τ 6 applies). */
    private fun bPick(k: Int) = { d: Dec -> d.b[k] }

    /** The whole run; returns the aggregate report (numbers and labels only). */
    fun run(raw: List<Msg>, seeds: List<Int> = listOf(1, 2, 3), standIn: Boolean = false, log: (String) -> Unit = {}): Map<String, Any?> {
        val stats = LinkedHashMap<String, Int>()
        val rows = clean(raw, stats)
        val rep = linkedMapOf<String, Any?>(
            "label" to if (standIn) "STAND-IN (public text split as a fake history; not the owner's data)" else "private (aggregates only)",
            "build" to "0.13.0 (A = 0.12.1 offers, no memory, tau 6; B = 0.13.0 offers with the pair memory learned through PairLearner, tau_memory ${ContextCheck.TAU_MEMORY}, lambda ${PairMemory.LAMBDA})",
            "messages_in" to raw.size, "messages_kept" to rows.size, "messages_kept_by_source" to rows.groupingBy { it.source }.eachCount(),
            "words_kept" to rows.sumOf { WORD.findAll(it.text).count() }, "drops" to stats,
            "active_tokens" to PairMemory.ACTIVE_TOKENS,
        )
        if (rows.size < 10) { rep["error"] = "fewer than 10 messages after cleaning"; return rep }
        val splits = linkedMapOf<String, Any?>()
        for ((name, frac) in SPLITS) {
            val t0 = System.currentTimeMillis()
            val (learnPart, evalPart) = split(rows, frac)
            val checkpoints = listOf(250, 500, 1000, 2000, 4000, 8000, 16000)
            val learned = learn(learnPart, checkpoints)
            val frozen = learned.model.toJson()
            log("$name: learned ${learnPart.size} messages in ${System.currentTimeMillis() - t0} ms")
            // B variants: [0] = the product (threshold as shipped), then the size curve (forced active at each checkpoint)
            val variants = ArrayList<PairMemory>()
            variants += learned.memory
            for ((_, b) in learned.snapshots) variants += PairMemory.fromBytes(b, activeTokens = 0.0)
            val seedRes = linkedMapOf<String, Any?>()
            val synth = ArrayList<Dec>()
            val synthDocs = ArrayList<Typed>()
            var cleanDecs: List<Dec> = emptyList()
            var cleanDocs: List<Typed> = emptyList()
            for (seed in listOf(0) + seeds) {
                val gen = Gen(13000L + seed * 7 + (if (name == "split80") 0 else 100))
                val docs = evalPart.map { m -> typeEval(gen.message(m.text, slips = seed > 0), frozen, m.source) }
                val decs = docs.flatMap { decisions(it, variants) }
                if (seed == 0) { cleanDecs = decs; cleanDocs = docs } else { synth += decs; synthDocs += docs }
                log("$name seed $seed: ${docs.size} docs, ${decs.size} decisions, ${System.currentTimeMillis() - t0} ms")
            }
            val inv = inventory(synthDocs)
            val aSynth = tally(synth, { it.a }, ContextCheck.TAU_OFFER)
            val sweep = linkedMapOf<String, Any?>()
            for (tau in TAUS) sweep["%.2f".format(Locale.ROOT, tau)] = block(tally(synth, bPick(0), tau), inv)
            val bSynth = tally(synth, bPick(0), ContextCheck.TAU_MEMORY)
            // McNemar on the residual targets at the shipped points
            var onlyA = 0
            var onlyB = 0
            for (d in synth) {
                val ra = d.a?.let { offered(it, ContextCheck.TAU_OFFER) && d.outcomeOf(it.alt) == "right" } == true
                val rb = d.b[0]?.let { offered(it, ContextCheck.TAU_MEMORY) && d.outcomeOf(it.alt) == "right" } == true
                if (ra && !rb) onlyA++
                if (rb && !ra) onlyB++
            }
            val bestAtBudget = TAUS.firstOrNull { tally(synth, bPick(0), it).falseOffers <= aSynth.falseOffers }
            val strict = TAUS.firstOrNull { tally(synth, bPick(0), it).let { c -> c.falseOffers + c.wrong <= aSynth.falseOffers + aSynth.wrong } }
            val bySource = linkedMapOf<String, Any?>()
            for (src in listOf("phone", "desktop")) {
                val iv = inventory(synthDocs, src)
                if (iv.residual + iv.clean == 0) continue
                bySource[src] = linkedMapOf("A" to block(tally(synth, { it.a }, ContextCheck.TAU_OFFER, src), iv),
                    "B" to block(tally(synth, bPick(0), ContextCheck.TAU_MEMORY, src), iv))
            }
            val byClass = linkedMapOf<String, Any?>()
            for (cls in listOf("single", "real_word", "accent", "two_word")) {
                val ds = synth.filter { it.cls == cls }
                val targets = synthDocs.sumOf { t -> t.toks.count { it.cls == cls && it.gold != null && it.text.lowercase() != it.gold.lowercase() } }
                byClass[cls] = linkedMapOf("residual_targets" to targets, "A_right" to tally(ds, { it.a }, ContextCheck.TAU_OFFER).right,
                    "B_right" to tally(ds, bPick(0), ContextCheck.TAU_MEMORY).right)
            }
            // the clean run: the eval text as sent (no slips): offers on it are false offers on real text
            val cInv = inventory(cleanDocs)
            val clean = linkedMapOf("A_tau6" to block(tally(cleanDecs, { it.a }, ContextCheck.TAU_OFFER), cInv),
                "B_tau_memory" to block(tally(cleanDecs, bPick(0), ContextCheck.TAU_MEMORY), cInv))
            // the size curve (cold start): memory copied after N learned tokens, forced active, at τ_memory
            val curve = ArrayList<Any?>()
            for ((k, snap) in learned.snapshots.withIndex()) {
                val c = tally(synth, bPick(k + 1), ContextCheck.TAU_MEMORY)
                val cc = tally(cleanDecs, bPick(k + 1), ContextCheck.TAU_MEMORY)
                curve += linkedMapOf("learned_tokens" to snap.first, "right" to c.right, "wrong_alt" to c.wrong, "false_on_clean" to c.falseOffers,
                    "false_on_real_text" to cc.falseOffers, "A_right" to aSynth.right, "A_false_on_clean" to aSynth.falseOffers,
                    "A_false_on_real_text" to tally(cleanDecs, { it.a }, ContextCheck.TAU_OFFER).falseOffers)
            }
            val mem = learned.memory
            splits[name] = linkedMapOf(
                "learn_messages" to learnPart.size, "eval_messages" to evalPart.size,
                "memory" to linkedMapOf("learned_tokens" to Math.round(mem.tokens), "entries" to mem.entries, "words" to mem.wordCount,
                    "pairs" to mem.pairCount(), "bytes" to mem.toBytes().size, "active" to mem.active),
                "seeds" to seeds,
                "synthetic" to linkedMapOf("A_0121_tau6" to block(aSynth, inv), "B_0130_tau_memory" to block(bSynth, inv),
                    "right_gain_pct" to (if (aSynth.right > 0) round4(100.0 * (bSynth.right - aSynth.right) / aSynth.right) else null),
                    "recall_gain_pp" to (if (inv.residual > 0) round4(100.0 * (bSynth.right - aSynth.right) / inv.residual) else null),
                    "mcnemar_only_A" to onlyA, "mcnemar_only_B" to onlyB, "mcnemar_p" to mcnemar(onlyA, onlyB),
                    "B_false_le_A_false" to (bSynth.falseOffers <= aSynth.falseOffers),
                    "B_tau_sweep" to sweep, "lowest_tau_at_A_false_budget" to bestAtBudget, "lowest_tau_at_strict_budget" to strict,
                    "by_source" to bySource, "by_class" to byClass),
                "clean_run_seed0" to clean,
                "size_curve" to curve,
                "seconds" to (System.currentTimeMillis() - t0) / 1000,
            )
            seedRes.clear()
        }
        rep["splits"] = splits
        return rep
    }

    // ── the stand-in history (public text): r13f's chat source + held-out prose A, in order, as messages ─────
    fun standIn(): List<Msg> {
        val texts = listOf(File("${OffersOracle.RES}/chat-source.txt").readText(), OffersOracle.proseText)
        val out = ArrayList<Msg>()
        var ts = 1_755_000_000.0 // a fixed start; one message an hour
        for (src in texts) {
            for (para in src.split('\n')) {
                val sents = ContextSegments.segments(para).map { para.substring(it.start, it.end) }
                var i = 0
                var n = 1
                while (i < sents.size) {
                    val m = sents.subList(i, minOf(sents.size, i + n)).joinToString(" ")
                    i += n
                    n = n % 3 + 1
                    out += Msg(ts, "desktop", m)
                    ts += 3600.0
                }
            }
        }
        return out
    }

    // ── tiny JSON writer (numbers and labels only) ───────────────────────────────────────────────────────────
    fun json(x: Any?, indent: String = ""): String = when (x) {
        null -> "null"
        is String -> "\"" + x.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        is Double -> if (x.isFinite()) (if (abs(x) < 1e-4 && x != 0.0) "%.3e".format(Locale.ROOT, x) else "%.4f".format(Locale.ROOT, x).trimEnd('0').trimEnd('.')) else "null"
        is Number, is Boolean -> x.toString()
        is Map<*, *> -> if (x.isEmpty()) "{}" else x.entries.joinToString(",\n", "{\n", "\n$indent}") { "$indent  " + json(it.key.toString()) + ": " + json(it.value, "$indent  ") }
        is Iterable<*> -> x.joinToString(", ", "[", "]") { json(it, indent) }
        else -> json(x.toString())
    }
}
