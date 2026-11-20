package com.resyst.vk.core

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.pow

/**
 * r13i (PM1-PM11): the owner's word pairs and triples, learned on the device ([PairLearner] feeds it), mixed into the
 * context checker's probabilities for the OFFER decision only ([ContextCheck.decide]):
 * ```
 * P_offer(w | a b) = (1 − λ)·P_wt-m2(w | a b) + λ·P_personal(w | a b)        λ = 0.2, only while [active]
 * ```
 * P_personal is r13g's prototype (memory.py): an interpolated absolute-discount trigram (D 0.75) over the learned
 * tokens, unigrams add-0.1 over V = the learned vocabulary + `<unk>` + `</s>`; a word outside it is `<unk>` and, as the
 * scored word, gets its share of the unknown mass (÷ [UNK_TYPES]).
 *
 * Bounded (PM4): at most [MAX_ENTRIES] pairs + triples and [MAX_WORDS] words; past a cap the least-used entries go
 * (lowest count, oldest among equals), never one the learn in progress wrote. Counts halve when one passes
 * [COUNT_CAP]; every count halves every [HALF_LIFE_DAYS] days (by the calendar day of the learns: a clock set back never
 * decays, one set forward decays at most [MAX_HALVINGS] half-lives); an entry under [MIN_COUNT] is dropped. The tables
 * are primitive arrays (open addressing on packed word ids), no boxed maps.
 *
 * Thread-safe: the IME learns on the main thread while a decision reads on the offers' thread; every public member
 * takes this object's lock for a few lookups (a decision) or one sentence (a learn).
 */
class PairMemory(
    /** PM6: learned tokens before the memory takes part (the private eval's size curve lowers it; the IME never does). */
    private val activeTokens: Double = ACTIVE_TOKENS,
) {
    // ── vocabulary: id → word, word → id (open addressing over the ids), unigram counts ──────────────────────
    private var words = arrayOfNulls<String>(64)
    private var nWords = 0
    private var wordSlots = IntArray(128) { -1 }
    private var uni = FloatArray(64)
    /** Σ of the unigram counts (N). */
    private var total = 0.0

    // ── pairs and triples: packed key → count, stamp ─────────────────────────────────────────────────────────
    private val grams = Table(withStamps = true)
    /** Per context (b) and (a b): Σ of the counts after it and the number of distinct words after it. */
    private val ctx = Table(withStamps = true)

    /** Logical clock of the learns (ties among equal counts: the older entry goes first). */
    private var clock = 0
    /** Epoch day of the last decay (−1 = never learned). */
    private var lastDay = -1

    /** Bumped by every change (learn, forget, clear, decay); in memory only. */
    @Volatile var version = 0L
        private set

    init { reserve() }

    private fun reserve() {
        for (w in RESERVED) addWord(w)
    }

    // ── reading ──────────────────────────────────────────────────────────────────────────────────────────────
    /** PM6: the memory takes part in the offers only once it holds [ACTIVE_TOKENS] learned tokens. */
    val active: Boolean @Synchronized get() = total >= activeTokens

    /** Learned tokens (N, decayed). */
    val tokens: Double @Synchronized get() = total

    /** Pairs + triples kept. */
    val entries: Int @Synchronized get() = grams.size

    /** Learned words (the special tokens not counted). */
    val wordCount: Int @Synchronized get() = (nWords - RESERVED.size).coerceAtLeast(0)

    @Synchronized fun isEmpty(): Boolean = grams.size == 0 && total == 0.0

    /**
     * P_personal([w] | [a] [b]) for the tokens of [ContextTokens] (lowercase; [ContextTokens.BOS] as context); null
     * context = out of reach.
     */
    @Synchronized fun prob(w: String, a: String?, b: String?): Double {
        val wi = idOf(w).let { if (it < 0 || it == BOS) UNK else it }
        var p = (uni[wi] + 0.1) / (total + 0.1 * vocabSize())
        if (b != null) {
            val bi = idOf(b).let { if (it < 0) UNK else it }
            val c1 = ctx.slot(key(CTX1, 0, 0, bi))
            if (c1 >= 0) {
                val cc = ctx.vals[c1].toDouble()
                if (cc > 0) {
                    val g = grams.slot(key(BI, 0, bi, wi))
                    val c = if (g >= 0) grams.vals[g].toDouble() else 0.0
                    p = maxOf(c - D, 0.0) / cc + D * ctx.aux[c1] / cc * p
                }
            }
            if (a != null) {
                val ai = idOf(a).let { if (it < 0) UNK else it }
                val c2 = ctx.slot(key(CTX2, 0, ai, bi))
                if (c2 >= 0) {
                    val cc = ctx.vals[c2].toDouble()
                    if (cc > 0) {
                        val g = grams.slot(key(TRI, ai, bi, wi))
                        val c = if (g >= 0) grams.vals[g].toDouble() else 0.0
                        p = maxOf(c - D, 0.0) / cc + D * ctx.aux[c2] / cc * p
                    }
                }
            }
        }
        if (wi == UNK) p /= UNK_TYPES
        return p
    }

    /** V of the add-0.1 unigrams: every id but `<s>` (the prototype's learned vocabulary + `<unk>` + `</s>`). */
    private fun vocabSize(): Int = nWords - 1

    /** Count of the pair [a] [b] (0 if absent; tests and the settings list). */
    @Synchronized fun pairCount(a: String, b: String): Double {
        val ai = idOf(a)
        val bi = idOf(b)
        if (ai < 0 || bi < 0) return 0.0
        val g = grams.slot(key(BI, 0, ai, bi))
        return if (g >= 0) grams.vals[g].toDouble() else 0.0
    }

    /** Count of the triple [a] [b] [c] (0 if absent). */
    @Synchronized fun tripleCount(a: String, b: String, c: String): Double {
        val ai = idOf(a)
        val bi = idOf(b)
        val ci = idOf(c)
        if (ai < 0 || bi < 0 || ci < 0) return 0.0
        val g = grams.slot(key(TRI, ai, bi, ci))
        return if (g >= 0) grams.vals[g].toDouble() else 0.0
    }

    /** Whether [w] is a learned word (tests: a one-off non-dictionary word never is). */
    @Synchronized fun knows(w: String): Boolean = idOf(w) >= RESERVED.size

    /** Every entry as text (tests): "a b" / "a b c" → count, special tokens as they are. */
    @Synchronized fun dump(): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        grams.forEach { k, v, _ ->
            val t = tag(k)
            val s = if (t == BI) "${words[idB(k)]} ${words[idC(k)]}" else "${words[idA(k)]} ${words[idB(k)]} ${words[idC(k)]}"
            out[s] = v.toDouble()
        }
        return out
    }

    /** One pair as "Lo que sé de ti" lists it (KN1). */
    data class Pair(val first: String, val second: String, val count: Double)

    /** The most used pairs of two real words (no `<s>`, `</s>`, `<unk>`, comma, number), most used first. */
    @Synchronized fun topPairs(limit: Int): List<Pair> {
        val out = ArrayList<Pair>()
        grams.forEach { k, v, _ ->
            if (tag(k) == BI && idB(k) >= RESERVED.size && idC(k) >= RESERVED.size) out += Pair(words[idB(k)]!!, words[idC(k)]!!, v.toDouble())
        }
        out.sortWith(compareByDescending<Pair> { it.count }.thenBy { it.first }.thenBy { it.second })
        return out.take(limit.coerceAtLeast(0))
    }

    /** Pairs of two real words (the settings' count). */
    @Synchronized fun pairCount(): Int {
        var n = 0
        grams.forEach { k, _, _ -> if (tag(k) == BI && idB(k) >= RESERVED.size && idC(k) >= RESERVED.size) n++ }
        return n
    }

    // ── learning ─────────────────────────────────────────────────────────────────────────────────────────────
    /**
     * Learns one sentence: [toks] are [ContextTokens] tokens already gated by [PairLearner] (a word it may not keep is
     * [ContextTokens.UNK]; null = a masked token: nothing is counted for it and no pair or triple crosses it).
     * [bos] / [eos]: the sentence's start / end are in [toks]. [now] = wall time (ms) for the decay. True when changed.
     */
    @Synchronized fun learn(toks: List<String?>, bos: Boolean, eos: Boolean, now: Long): Boolean {
        decay(now)
        // room for this sentence's new words first: dropping words renumbers the ids
        val fresh = toks.count { it != null && idOf(it) < 0 }
        if (fresh > 0 && nWords + fresh > MAX_WORDS) dropWords(stampNow = -1)
        val stamp = ++clock
        var changed = false
        // runs between masked tokens; <s> only before the first run, </s> only after the last
        var i = 0
        var first = true
        while (i <= toks.size) {
            var j = i
            while (j < toks.size && toks[j] != null) j++
            val run = ArrayList<Int>(j - i + 2)
            if (first && bos) run += BOS
            for (k in i until j) run += idForLearn(toks[k]!!)
            if (j == toks.size && eos) run += EOS
            first = false
            if (run.any { it != BOS && it != EOS }) { count(run, stamp); changed = true }
            i = j + 1
        }
        if (!changed) return false
        version++
        capCounts()
        enforceCaps(stamp)
        return true
    }

    private fun idForLearn(t: String): Int {
        val id = idOf(t)
        if (id >= 0) return id
        return if (nWords >= MAX_WORDS || !storable(t)) UNK else addWord(t)
    }

    /** PM11b: a word the file's reader accepts (non-empty, lowercase, ≤ [MAX_WORD_BYTES] UTF-8 bytes); any other is `<unk>`. */
    private fun storable(w: String): Boolean = w.isNotEmpty() && w == w.lowercase() && w.toByteArray(Charsets.UTF_8).size <= MAX_WORD_BYTES

    private fun count(run: List<Int>, stamp: Int) {
        for (k in run.indices) {
            val w = run[k]
            if (w != BOS) { uni[w] += 1f; total += 1.0 }
            if (k >= 1) addGram(key(BI, 0, run[k - 1], w), stamp, key(CTX1, 0, 0, run[k - 1]))
            if (k >= 2) addGram(key(TRI, run[k - 2], run[k - 1], w), stamp, key(CTX2, 0, run[k - 2], run[k - 1]))
        }
    }

    private fun addGram(k: Long, stamp: Int, c: Long) {
        val s = grams.slot(k)
        val fresh = s < 0
        val slot = if (fresh) grams.put(k) else s
        grams.vals[slot] += 1f
        grams.aux[slot] = stamp
        val cs = ctx.slot(c).let { if (it < 0) ctx.put(c) else it }
        ctx.vals[cs] += 1f
        if (fresh) ctx.aux[cs] += 1
    }

    // ── forgetting ───────────────────────────────────────────────────────────────────────────────────────────
    /** KN1: forgets the pair [a] [b] and every triple that contains it. True when anything was removed. */
    @Synchronized fun forget(a: String, b: String): Boolean {
        val ai = idOf(a)
        val bi = idOf(b)
        if (ai < 0 || bi < 0) return false
        var hit = false
        val ok = compact { k, _ ->
            val drop = when (tag(k)) {
                BI -> idB(k) == ai && idC(k) == bi
                else -> (idA(k) == ai && idB(k) == bi) || (idB(k) == ai && idC(k) == bi)
            }
            if (drop) hit = true
            !drop
        }
        if (ok && hit) version++
        return hit
    }

    /** PM5: everything goes (the file is the caller's: [PairFile.delete]). */
    @Synchronized fun clear() {
        words = arrayOfNulls(64)
        nWords = 0
        wordSlots = IntArray(128) { -1 }
        uni = FloatArray(64)
        total = 0.0
        grams.clear()
        ctx.clear()
        clock = 0
        lastDay = -1
        reserve()
        version++
    }

    // ── bounds (PM4) ─────────────────────────────────────────────────────────────────────────────────────────
    /** The 30-day half-life, at most once per calendar day; a clock set back does nothing. */
    private fun decay(now: Long) {
        val day = Math.floorDiv(now, DAY_MS).toInt()
        if (lastDay < 0) { lastDay = day; return }
        if (day <= lastDay) return
        val halvings = ((day - lastDay).toDouble() / HALF_LIFE_DAYS).coerceAtMost(MAX_HALVINGS.toDouble())
        lastDay = day
        scale(0.5.pow(halvings))
        version++
    }

    /** PersonalModel-like saturation: a unigram count past [COUNT_CAP] halves everything. */
    private fun capCounts() {
        var max = 0f
        for (i in 0 until nWords) if (uni[i] > max) max = uni[i]
        if (max > COUNT_CAP) scale(0.5)
    }

    private fun scale(f: Double) {
        val ff = f.toFloat()
        for (i in 0 until nWords) uni[i] *= ff
        grams.forEachSlot { s -> grams.vals[s] *= ff }
        // entries under MIN_COUNT go; the rebuild recomputes the context sums and the totals
        compact { _, v -> v >= MIN_COUNT }
    }

    private fun enforceCaps(stamp: Int) {
        if (grams.size > MAX_ENTRIES) {
            // the least used go first (lowest count, oldest among equals), never one written by this learn
            val n = grams.size
            val idx = IntArray(n)
            val cnt = FloatArray(n)
            val st = IntArray(n)
            var m = 0
            grams.forEachSlot { s -> if (grams.aux[s] != stamp) { idx[m] = s; cnt[m] = grams.vals[s]; st[m] = grams.aux[s]; m++ } }
            val order = (0 until m).sortedWith(compareBy<Int>({ cnt[it] }, { st[it] }))
            val drop = (n - PRUNE_TO).coerceAtMost(m)
            val gone = HashSet<Long>(drop * 2)
            for (q in 0 until drop) gone += grams.keys[idx[order[q]]]
            compact { k, _ -> k !in gone }
        }
    }

    /** Past [MAX_WORDS]: the least used words go (with their entries) down to 90 % of the cap. */
    private fun dropWords(stampNow: Int) {
        val inUse = HashSet<Int>()
        grams.forEachSlot { s -> if (grams.aux[s] == stampNow) { val k = grams.keys[s]; inUse += idA(k); inUse += idB(k); inUse += idC(k) } }
        val cand = (RESERVED.size until nWords).filter { it !in inUse }.sortedWith(compareBy<Int>({ uni[it] }, { it }))
        val drop = (nWords - MAX_WORDS * 9 / 10).coerceAtMost(cand.size)
        if (drop <= 0) return
        val gone = cand.take(drop).toHashSet()
        compact(dropWord = { it in gone }) { k, _ -> idA(k) !in gone && idB(k) !in gone && idC(k) !in gone }
    }

    /**
     * Rebuilds every table keeping the entries [keep] accepts: ids renumbered (a word with no entry and a unigram count
     * under [MIN_COUNT], or one [dropWord] names, goes), context sums, distinct counts and N recomputed. True if done.
     */
    private fun compact(dropWord: (Int) -> Boolean = { false }, keep: (Long, Float) -> Boolean): Boolean {
        val kept = ArrayList<Long>(grams.size)
        val kv = ArrayList<Float>(grams.size)
        val ks = ArrayList<Int>(grams.size)
        val used = BooleanArray(nWords)
        grams.forEach { k, v, s ->
            if (keep(k, v)) {
                kept += k; kv += v; ks += s
                used[idA(k)] = true; used[idB(k)] = true; used[idC(k)] = true
            }
        }
        val remap = IntArray(nWords) { -1 }
        val oldWords = words
        val oldUni = uni
        val n0 = nWords
        words = arrayOfNulls(maxOf(64, n0))
        uni = FloatArray(maxOf(64, n0))
        nWords = 0
        wordSlots = IntArray(slotsFor(maxOf(64, n0))) { -1 }
        for (i in 0 until n0) {
            val reserved = i < RESERVED.size
            if (!reserved && (dropWord(i) || (!used[i] && oldUni[i] < MIN_COUNT))) continue
            val id = addWord(oldWords[i]!!)
            uni[id] = if (oldUni[i] >= MIN_COUNT || reserved || used[i]) oldUni[i] else 0f
            remap[i] = id
        }
        total = 0.0
        for (i in 0 until nWords) total += uni[i]
        grams.clear()
        ctx.clear()
        for (q in kept.indices) {
            val k = kept[q]
            val a = remap[idA(k)]
            val b = remap[idB(k)]
            val c = remap[idC(k)]
            if (b < 0 || c < 0 || (tag(k) == TRI && a < 0)) continue
            val nk = key(tag(k), if (tag(k) == TRI) a else 0, b, c)
            val s = grams.put(nk)
            grams.vals[s] = kv[q]
            grams.aux[s] = ks[q]
            val ck = if (tag(k) == BI) key(CTX1, 0, 0, b) else key(CTX2, 0, a, b)
            val cs = ctx.slot(ck).let { if (it < 0) ctx.put(ck) else it }
            ctx.vals[cs] += kv[q]
            ctx.aux[cs] += 1
        }
        return true
    }

    // ── vocabulary internals ─────────────────────────────────────────────────────────────────────────────────
    private fun idOf(w: String): Int {
        val mask = wordSlots.size - 1
        var i = mix(w.hashCode()) and mask
        while (true) {
            val id = wordSlots[i]
            if (id < 0) return -1
            if (words[id] == w) return id
            i = (i + 1) and mask
        }
    }

    private fun addWord(w: String): Int {
        if (nWords == words.size) {
            words = words.copyOf(nWords * 2)
            uni = uni.copyOf(nWords * 2)
        }
        val id = nWords++
        words[id] = w
        if (nWords * 2 > wordSlots.size) rehashWords(wordSlots.size * 2) else insertSlot(id)
        return id
    }

    private fun insertSlot(id: Int) {
        val mask = wordSlots.size - 1
        var i = mix(words[id]!!.hashCode()) and mask
        while (wordSlots[i] >= 0) i = (i + 1) and mask
        wordSlots[i] = id
    }

    private fun rehashWords(size: Int) {
        wordSlots = IntArray(size) { -1 }
        for (id in 0 until nWords) insertSlot(id)
    }

    // ── persistence (PM11) ───────────────────────────────────────────────────────────────────────────────────
    /** The compact binary form (`files/personal/pairs.bin`). */
    @Synchronized fun toBytes(): ByteArray {
        val o = ByteArrayOutputStream(16 + grams.size * 10 + nWords * 8)
        o.write(MAGIC)
        o.write(FORMAT)
        varint(o, clock.toLong())
        varint(o, (lastDay + 1).toLong())
        varint(o, (nWords - RESERVED.size).toLong())
        for (i in RESERVED.size until nWords) {
            val b = words[i]!!.toByteArray(Charsets.UTF_8)
            varint(o, b.size.toLong())
            o.write(b)
        }
        for (i in 0 until nWords) float(o, uni[i])
        varint(o, grams.size.toLong())
        // in key order: the same table always gives the same bytes, whatever its insertion history
        val slots = IntArray(grams.size)
        var n = 0
        grams.forEachSlot { slots[n++] = it }
        val keys = grams.keys
        val order = slots.sortedBy { keys[it] }
        for (slot in order) {
            val k = keys[slot]
            val v = grams.vals[slot]
            val s = grams.aux[slot]
            val t = tag(k)
            o.write(t)
            if (t == TRI) varint(o, idA(k).toLong())
            varint(o, idB(k).toLong())
            varint(o, idC(k).toLong())
            float(o, v)
            varint(o, s.toLong())
        }
        return o.toByteArray()
    }

    // ── packed keys ──────────────────────────────────────────────────────────────────────────────────────────
    private fun key(tag: Int, a: Int, b: Int, c: Int): Long =
        (tag.toLong() shl 60) or (a.toLong() shl 40) or (b.toLong() shl 20) or c.toLong()

    private fun tag(k: Long): Int = (k ushr 60).toInt()
    private fun idA(k: Long): Int = ((k ushr 40) and ID_MASK).toInt()
    private fun idB(k: Long): Int = ((k ushr 20) and ID_MASK).toInt()
    private fun idC(k: Long): Int = (k and ID_MASK).toInt()

    /** Open addressing on packed keys (0 = empty), linear probing, grown ×2 at 2/3 load; [aux] = stamp / distinct. */
    private class Table(withStamps: Boolean) {
        var keys = LongArray(64)
        var vals = FloatArray(64)
        var aux = IntArray(if (withStamps) 64 else 0)
        var size = 0
            private set

        fun slot(k: Long): Int {
            val mask = keys.size - 1
            var i = mix64(k) and mask
            while (true) {
                val x = keys[i]
                if (x == 0L) return -1
                if (x == k) return i
                i = (i + 1) and mask
            }
        }

        /** The slot of a new key [k] (absent), value 0. */
        fun put(k: Long): Int {
            if ((size + 1) * 3 > keys.size * 2) grow()
            val mask = keys.size - 1
            var i = mix64(k) and mask
            while (keys[i] != 0L) i = (i + 1) and mask
            keys[i] = k
            vals[i] = 0f
            aux[i] = 0
            size++
            return i
        }

        private fun grow() {
            val ok = keys
            val ov = vals
            val oa = aux
            keys = LongArray(ok.size * 2)
            vals = FloatArray(ok.size * 2)
            aux = IntArray(ok.size * 2)
            size = 0
            for (i in ok.indices) if (ok[i] != 0L) {
                val s = put(ok[i])
                vals[s] = ov[i]
                aux[s] = oa[i]
            }
        }

        fun clear() {
            keys = LongArray(64)
            vals = FloatArray(64)
            aux = IntArray(64)
            size = 0
        }

        inline fun forEachSlot(f: (Int) -> Unit) {
            for (i in keys.indices) if (keys[i] != 0L) f(i)
        }

        inline fun forEach(f: (Long, Float, Int) -> Unit) {
            for (i in keys.indices) if (keys[i] != 0L) f(keys[i], vals[i], aux[i])
        }
    }

    companion object {
        /** λ: the memory's weight in the offer decision (r13g B1). */
        const val LAMBDA = 0.2
        /** Absolute discount of the personal trigram (r13g memory.py). */
        const val D = 0.75
        /** Unknown word types sharing the personal `<unk>` mass (r13g memory.py). */
        const val UNK_TYPES = 50_000.0
        /** PM6: learned tokens before the memory takes part (cold start). */
        const val ACTIVE_TOKENS = 2_000.0
        const val MAX_ENTRIES = 20_000
        const val PRUNE_TO = 18_000
        const val MAX_WORDS = 8_000
        const val COUNT_CAP = 4_096f
        const val MIN_COUNT = 0.25f
        const val HALF_LIFE_DAYS = 30.0
        const val MAX_HALVINGS = 12
        const val DAY_MS = 86_400_000L
        /** PM11b: the longest word the file holds (UTF-8 bytes); the writer and the reader share it. */
        const val MAX_WORD_BYTES = 64

        /** The special tokens, ids 0..4. */
        val RESERVED = listOf(ContextTokens.UNK, ContextTokens.BOS, ContextTokens.EOS, ContextTokens.COMMA, ContextTokens.NUM)
        private const val UNK = 0
        private const val BOS = 1
        private const val EOS = 2

        private const val BI = 2
        private const val TRI = 3
        private const val CTX1 = 4
        private const val CTX2 = 5
        private const val ID_MASK = 0xFFFFFL

        private val MAGIC = byteArrayOf('V'.code.toByte(), 'K'.code.toByte(), 'P'.code.toByte(), 'M'.code.toByte())
        private const val FORMAT = 1

        private fun mix(h: Int): Int {
            var x = h * -0x61c88647
            x = x xor (x ushr 16)
            return x and 0x7fffffff
        }

        private fun mix64(k: Long): Int {
            var x = k * -0x61c8864680b583ebL
            x = x xor (x ushr 29)
            return (x xor (x ushr 32)).toInt() and 0x7fffffff
        }

        private fun slotsFor(n: Int): Int {
            var s = 128
            while (s < n * 2) s *= 2
            return s
        }

        private fun varint(o: ByteArrayOutputStream, v: Long) {
            var x = v
            while (x >= 0x80) { o.write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }
            o.write(x.toInt())
        }

        private fun float(o: ByteArrayOutputStream, f: Float) {
            val b = java.lang.Float.floatToIntBits(f)
            o.write(b ushr 24); o.write(b ushr 16); o.write(b ushr 8); o.write(b)
        }

        /** Parses [toBytes]; anything malformed (magic, format, a count, an id, a value, the exact length) = empty. */
        fun fromBytes(b: ByteArray, activeTokens: Double = ACTIVE_TOKENS): PairMemory =
            runCatching { parse(b, activeTokens) }.getOrNull() ?: PairMemory(activeTokens)

        private fun parse(b: ByteArray, activeTokens: Double): PairMemory? {
            var p = 0
            fun byte(): Int { if (p >= b.size) throw IllegalStateException("short"); return b[p++].toInt() and 0xff }
            fun varint(): Long {
                var r = 0L
                var shift = 0
                while (true) {
                    val x = byte()
                    r = r or ((x and 0x7f).toLong() shl shift)
                    if (x < 0x80) return r
                    shift += 7
                    if (shift > 35) throw IllegalStateException("varint")
                }
            }
            fun float(): Float = java.lang.Float.intBitsToFloat((byte() shl 24) or (byte() shl 16) or (byte() shl 8) or byte())
            for (m in MAGIC) if (byte() != (m.toInt() and 0xff)) return null
            if (byte() != FORMAT) return null
            val m = PairMemory(activeTokens)
            val clock = varint()
            val day = varint() - 1
            val nw = varint()
            if (clock > Int.MAX_VALUE || day > Int.MAX_VALUE || nw > MAX_WORDS) return null
            for (i in 0 until nw.toInt()) {
                val len = varint().toInt()
                if (len <= 0 || len > MAX_WORD_BYTES || p + len > b.size) return null
                val w = String(b, p, len, Charsets.UTF_8)
                p += len
                if (m.idOf(w) >= 0 || w != w.lowercase()) return null
                m.addWord(w)
            }
            for (i in 0 until m.nWords) {
                val f = float()
                if (!f.isFinite() || f < 0f) return null
                m.uni[i] = f
                m.total += f
            }
            val ng = varint()
            if (ng > MAX_ENTRIES) return null
            for (q in 0 until ng.toInt()) {
                val t = byte()
                if (t != BI && t != TRI) return null
                val a = if (t == TRI) varint().toInt() else 0
                val bb = varint().toInt()
                val c = varint().toInt()
                val v = float()
                val s = varint()
                if (a !in 0 until m.nWords || bb !in 0 until m.nWords || c !in 0 until m.nWords || c == BOS) return null
                if (!v.isFinite() || v < MIN_COUNT || s > Int.MAX_VALUE) return null
                val k = m.key(t, a, bb, c)
                if (m.grams.slot(k) >= 0) return null
                val slot = m.grams.put(k)
                m.grams.vals[slot] = v
                m.grams.aux[slot] = s.toInt()
                val ck = if (t == BI) m.key(CTX1, 0, 0, bb) else m.key(CTX2, 0, a, bb)
                val cs = m.ctx.slot(ck).let { if (it < 0) m.ctx.put(ck) else it }
                m.ctx.vals[cs] += v
                m.ctx.aux[cs] += 1
            }
            if (p != b.size) return null
            m.clock = clock.toInt()
            m.lastDay = day.toInt()
            return m
        }
    }
}

/**
 * r13i (PM5, PM11): the memory's file, plain java.io so the JVM tests run the same code as the phone: written to a
 * temp file and renamed over the old one (atomic on one filesystem); [delete] removes the file and any temp left.
 */
object PairFile {
    const val NAME = "pairs.bin"

    fun read(dir: File): PairMemory? {
        val f = File(dir, NAME)
        if (!f.exists()) return null
        return runCatching { PairMemory.fromBytes(f.readBytes()) }.getOrNull()
    }

    /** Writes [bytes] atomically; an empty memory is no file at all ([delete]). */
    fun write(dir: File, bytes: ByteArray?) {
        if (bytes == null) { delete(dir); return }
        dir.mkdirs()
        val tmp = File(dir, "$NAME.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(File(dir, NAME))) {
            File(dir, NAME).delete()
            if (!tmp.renameTo(File(dir, NAME))) tmp.delete()
        }
    }

    fun delete(dir: File) {
        File(dir, NAME).delete()
        File(dir, "$NAME.tmp").delete()
    }
}
