package com.resyst.vk.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.pow

/**
 * r13f (offers, HP1, HP5, AS1, AS4): the word trigram model of assets/context/wt-m2.vkng (VKNG v2, written by
 * tools/ngram-build/NgramBuild.java), read IN PLACE from a [ByteBuffer] — on the phone a read-only memory map of the
 * uncompressed APK entry, so the model's n-gram tables (10.7 MB) are clean file-backed pages the kernel can drop,
 * never Java heap.
 *
 * The r13e spike decoded the whole file into arrays (~41 MB of heap). This reader keeps ≈ 3.3 MB for wt-m2: the
 * vocabulary's bytes in byte order with their offsets and ids (the word → id search runs on the heap), the offset of
 * every unigram record, and every [BLOCK]-th bigram / trigram key with its offset; the delta-coded keys of one block
 * are bulk-copied into a small per-thread buffer and decoded there on demand. The probabilities are the spike's, bit
 * for bit (AS4, tested against its array reader):
 * ```
 * P1(w)     = (c(w) + 1) / (N + V)
 * P2(w|b)   = (c(b w) + [T(b) + pruned(b ·)]·P1(w)) / (c(b) + T(b))
 * P3(w|a b) = (c(a b w) + [T(a b) + pruned(a b ·)]·P2(w|b)) / (c(a b) + T(a b))   (P2 when a b was pruned / unseen)
 * ```
 * c(·) of a pruned n-gram is 0; its mass (pruned) goes to the backoff, so P(·|h) sums to 1. Counts above 1 are
 * quantised to 1/8 of a power of two. A word outside the vocabulary is `<unk>` as context; as the scored word it
 * costs P(<unk>|h) / unkTypes. Immutable after [read]; the source is read with absolute gets or through per-thread
 * duplicates, so concurrent readers are safe.
 */
class ContextNgram private constructor(
    private val buf: ByteBuffer,
    val vocabSize: Int,
    val tokens: Long,
    val unkTypes: Long,
    val bigrams: Int,
    val trigrams: Int,
    /** The vocabulary's modified-UTF-8 bytes, concatenated in byte order (unsigned, then length). */
    private val vocab: ByteArray,
    /** Word i of that order = vocab[vocabStart[i], vocabStart[i + 1]). */
    private val vocabStart: IntArray,
    private val sortedIds: IntArray,
    /** Offset of each id's unigram record (three varints: count, continuation types, kept bigram mass). */
    private val uniOff: IntArray,
    private val bIdxKey: LongArray,
    /** Offset of every [BLOCK]-th bigram entry, plus the end of the bigram section. */
    private val bIdxOff: IntArray,
    private val tIdxKey: LongArray,
    private val tIdxOff: IntArray,
    /** The longest block in bytes (the per-thread copy buffer's size). */
    private val maxBlock: Int,
) {
    /** The id of [w] (a token as [ContextTokens] gives it), or [UNK] (0). */
    fun id(w: String): Int {
        val q = mutf8(w) ?: return UNK
        var lo = 0
        var hi = sortedIds.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = compareQuery(mid, q)
            if (c == 0) return sortedIds[mid]
            if (c < 0) lo = mid + 1 else hi = mid - 1
        }
        return UNK
    }

    /** The word of [id] (diagnostics and tests: a linear scan). */
    fun word(id: Int): String {
        val i = sortedIds.indexOf(id)
        require(i >= 0) { "no id $id" }
        return decodeMutf8(vocab.copyOfRange(vocabStart[i], vocabStart[i + 1]))
    }

    /** Raw (weighted) count of a vocabulary word; 0 if unknown. */
    fun count(w: String): Long = id(w).let { if (it == UNK) 0 else uni(it) }

    // ── unigram records ──────────────────────────────────────────────────────────────────────────────────────
    private fun uni(w: Int): Long = varintAt(uniOff[w])
    private fun uniT(w: Int): Long = varintAt(skipVarint(uniOff[w]))
    private fun uniKept(w: Int): Long = varintAt(skipVarint(skipVarint(uniOff[w])))

    // ── probabilities (the spike's expressions, same types, same order) ──────────────────────────────────────
    private fun p1(w: Int): Double = (uni(w) + 1.0) / (tokens + vocabSize)

    private fun p2(w: Int, b: Int): Double {
        val cb = uni(b).toDouble()
        val t = uniT(b).toDouble()
        if (cb <= 0.0 || t <= 0.0) return p1(w)
        val i = findBigram((b.toLong() shl 20) or w.toLong())
        val c = if (i >= 0) deq(buf.get(i)) else 0.0
        val bo = t + maxOf(0.0, cb - uniKept(b))
        return (c + bo * p1(w)) / (cb + t)
    }

    private fun p3(w: Int, a: Int, b: Int): Double {
        val i = findBigram((a.toLong() shl 20) or b.toLong())
        if (i < 0) return p2(w, b)
        val cab = deq(buf.get(i))
        val t = deq(buf.get(i + 1))
        if (t <= 0.0) return p2(w, b)
        val j = findTrigram((a.toLong() shl 40) or (b.toLong() shl 20) or w.toLong())
        val c = if (j >= 0) deq(buf.get(j)) else 0.0
        val bo = t + (buf.get(i + 2).toInt() and 0xff) / 255.0 * cab
        return (c + bo * p2(w, b)) / (cab + t)
    }

    /** ln P(w | history) for ids; [a] / [b] = −1 when the history is shorter. */
    fun logP(w: Int, a: Int, b: Int): Double = ln(
        when {
            b < 0 -> p1(w)
            a < 0 -> p2(w, b)
            else -> p3(w, a, b)
        },
    )

    // ── keyed sections: sparse index + delta-coded run ───────────────────────────────────────────────────────
    /** Offset of the count byte of bigram [key] (then T, f), or −1. */
    private fun findBigram(key: Long): Int = find(key, bIdxKey, bIdxOff, 3)

    /** Offset of the count byte of trigram [key], or −1. */
    private fun findTrigram(key: Long): Int = find(key, tIdxKey, tIdxOff, 1)

    private class Local(val dup: ByteBuffer, val block: ByteArray)

    private val local = object : ThreadLocal<Local>() {
        override fun initialValue() = Local(buf.duplicate(), ByteArray(maxBlock))
    }

    private fun find(key: Long, idxKey: LongArray, idxOff: IntArray, payload: Int): Int {
        // the last index point ≤ key
        var lo = 0
        var hi = idxKey.size - 1
        var blk = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (idxKey[mid] <= key) { blk = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (blk < 0) return -1
        val start = idxOff[blk]
        val len = idxOff[blk + 1] - start
        val l = local.get()!!
        val a = l.block
        l.dup.position(start)
        l.dup.get(a, 0, len) // one copy per lookup instead of a call per byte (HP5)
        var p = 0
        while (a[p++].toInt() and 0x80 != 0) Unit // the block's first key is in the index
        var k = idxKey[blk]
        while (true) {
            if (k == key) return start + p
            if (k > key) return -1
            p += payload
            if (p >= len) return -1
            var shift = 0
            var d = 0L
            while (true) {
                val x = a[p++].toInt() and 0xff
                d = d or ((x and 0x7f).toLong() shl shift)
                if (x and 0x80 == 0) break
                shift += 7
            }
            k += d
        }
    }

    // ── bytes ─────────────────────────────────────────────────────────────────────────────────────────────────
    private fun varintAt(off: Int): Long {
        var p = off
        var shift = 0
        var r = 0L
        while (true) {
            val x = buf.get(p++).toInt() and 0xff
            r = r or ((x and 0x7f).toLong() shl shift)
            if (x and 0x80 == 0) return r
            shift += 7
        }
    }

    private fun skipVarint(off: Int): Int {
        var p = off
        while (buf.get(p++).toInt() and 0x80 != 0) Unit
        return p
    }

    /** Vocabulary word [i] (byte order) compared with the query bytes [q] (unsigned, then length). */
    private fun compareQuery(i: Int, q: ByteArray): Int {
        val s = vocabStart[i]
        val len = vocabStart[i + 1] - s
        val n = minOf(len, q.size)
        for (j in 0 until n) {
            val x = vocab[s + j].toInt() and 0xff
            val y = q[j].toInt() and 0xff
            if (x != y) return x - y
        }
        return len - q.size
    }

    /**
     * Sequential reads of the source through a 64 KB copy (loading): the varint scan of 2.8 M entries without a call
     * per byte into the buffer.
     */
    private class Chunks(src: ByteBuffer, private val limit: Int) {
        private val dup = src.duplicate()
        private val a = ByteArray(1 shl 16)
        private var base = 0
        private var len = 0

        fun at(p: Int): Int {
            if (p < base || p >= base + len) {
                check(p in 0 until limit) { "VKNG: truncated" }
                base = p
                len = minOf(a.size, limit - p)
                dup.position(p)
                dup.get(a, 0, len)
            }
            return a[p - base].toInt() and 0xff
        }
    }

    companion object {
        const val UNK = 0
        const val BOS_ID = 1
        /** Keys per index point: 32 → ≈ 1.1 MB of index for wt-m2, ≤ 31 varints decoded per lookup. */
        const val BLOCK = 32
        private const val HEADER = 44

        private val DEQ = DoubleArray(256) { q -> if (q == 0) 0.0 else 2.0.pow((q - 1) / 8.0) }
        private fun deq(q: Byte): Double = DEQ[q.toInt() and 0xff]

        /**
         * Validates [source] as a VKNG v2 asset and indexes it in place (AS1): magic, version, counts, every
         * section, the exact length; throws on anything else (truncated, foreign, corrupt). The n-gram tables are not
         * copied: keep [source] alive as long as the model (on the phone: the memory map).
         */
        fun read(source: ByteBuffer): ContextNgram {
            val b = source.duplicate().order(ByteOrder.BIG_ENDIAN)
            b.position(0)
            val limit = b.limit()
            check(limit >= HEADER) { "VKNG: too short" }
            check(b.get(0) == 'V'.code.toByte() && b.get(1) == 'K'.code.toByte() && b.get(2) == 'N'.code.toByte() && b.get(3) == 'G'.code.toByte()) { "not a VKNG asset" }
            check(b.getInt(4) == 2) { "VKNG version" }
            val v = b.getInt(8)
            val n = b.getLong(12)
            val unk = b.getLong(20)
            val n2 = b.getInt(28)
            val n3 = b.getInt(32)
            check(v in 5..(1 shl 20) && n > 0 && unk >= 0 && n2 >= 0 && n3 >= 0) { "VKNG: bad counts" }
            val c = Chunks(b, limit)
            var p = HEADER
            // words: one copy of the section, sorted there by byte order
            val wordOff = IntArray(v)
            for (i in 0 until v) {
                wordOff[i] = p
                p += 2 + ((c.at(p) shl 8) or c.at(p + 1))
            }
            check(p <= limit) { "VKNG: truncated words" }
            val raw = ByteArray(p - HEADER)
            b.duplicate().also { it.position(HEADER) }.get(raw)
            fun lenOf(i: Int) = ((raw[wordOff[i] - HEADER].toInt() and 0xff) shl 8) or (raw[wordOff[i] - HEADER + 1].toInt() and 0xff)
            fun cmp(x: Int, y: Int): Int {
                val lx = lenOf(x)
                val ly = lenOf(y)
                val sx = wordOff[x] - HEADER + 2
                val sy = wordOff[y] - HEADER + 2
                for (j in 0 until minOf(lx, ly)) {
                    val d = (raw[sx + j].toInt() and 0xff) - (raw[sy + j].toInt() and 0xff)
                    if (d != 0) return d
                }
                return lx - ly
            }
            val order = IntArray(v) { it }
            sortBy(order) { x, y -> cmp(x, y) }
            for (i in 1 until v) check(cmp(order[i - 1], order[i]) < 0) { "VKNG: duplicate word" }
            val vocabStart = IntArray(v + 1)
            for (i in 0 until v) vocabStart[i + 1] = vocabStart[i] + lenOf(order[i])
            val vocab = ByteArray(vocabStart[v])
            for (i in 0 until v) System.arraycopy(raw, wordOff[order[i]] - HEADER + 2, vocab, vocabStart[i], vocabStart[i + 1] - vocabStart[i])
            // unigram records
            val uniOff = IntArray(v)
            for (i in 0 until v) {
                uniOff[i] = p
                repeat(3) { p = skip(c, p) }
            }
            // bigrams: key varint (delta), count, T, f
            val nb = (n2 + BLOCK - 1) / BLOCK
            val bKey = LongArray(nb)
            val bOff = IntArray(nb + 1)
            var prev = 0L
            for (e in 0 until n2) {
                if (e % BLOCK == 0) bOff[e / BLOCK] = p
                val start = p
                p = skip(c, p)
                val d = varint(c, start)
                check(d > 0 || e == 0) { "VKNG: bigram keys not ascending" }
                prev += d
                if (e % BLOCK == 0) bKey[e / BLOCK] = prev
                p += 3
            }
            check(p <= limit) { "VKNG: truncated bigrams" }
            bOff[nb] = p
            // trigrams: key varint (delta), count
            val nt = (n3 + BLOCK - 1) / BLOCK
            val tKey = LongArray(nt)
            val tOff = IntArray(nt + 1)
            prev = 0L
            for (e in 0 until n3) {
                if (e % BLOCK == 0) tOff[e / BLOCK] = p
                val start = p
                p = skip(c, p)
                val d = varint(c, start)
                check(d > 0 || e == 0) { "VKNG: trigram keys not ascending" }
                prev += d
                if (e % BLOCK == 0) tKey[e / BLOCK] = prev
                p += 1
            }
            check(p == limit) { "VKNG: length (${limit - p} bytes left)" }
            tOff[nt] = p
            var maxBlock = 1
            for (i in 0 until nb) maxBlock = maxOf(maxBlock, bOff[i + 1] - bOff[i])
            for (i in 0 until nt) maxBlock = maxOf(maxBlock, tOff[i + 1] - tOff[i])
            return ContextNgram(b, v, n, unk, n2, n3, vocab, vocabStart, order, uniOff, bKey, bOff, tKey, tOff, maxBlock)
        }

        private fun skip(c: Chunks, off: Int): Int {
            var p = off
            var k = 0
            while (true) {
                check(k < 10) { "VKNG: bad varint" }
                val x = c.at(p++)
                k++
                if (x and 0x80 == 0) return p
            }
        }

        private fun varint(c: Chunks, off: Int): Long {
            var p = off
            var shift = 0
            var r = 0L
            while (true) {
                val x = c.at(p++)
                r = r or ((x and 0x7f).toLong() shl shift)
                if (x and 0x80 == 0) return r
                shift += 7
            }
        }

        /** A stable bottom-up merge sort of [a] (no boxing: the transient cost is one more IntArray). */
        private inline fun sortBy(a: IntArray, cmp: (Int, Int) -> Int) {
            var src = a
            var dst = IntArray(a.size)
            var w = 1
            while (w < a.size) {
                var lo = 0
                while (lo < a.size) {
                    val mid = minOf(lo + w, a.size)
                    val hi = minOf(lo + 2 * w, a.size)
                    var i = lo
                    var j = mid
                    var k = lo
                    while (i < mid && j < hi) dst[k++] = if (cmp(src[i], src[j]) <= 0) src[i++] else src[j++]
                    while (i < mid) dst[k++] = src[i++]
                    while (j < hi) dst[k++] = src[j++]
                    lo = hi
                }
                val t = src; src = dst; dst = t
                w *= 2
            }
            if (src !== a) System.arraycopy(src, 0, a, 0, a.size)
        }

        /** [w] as java.io.DataOutput.writeUTF writes it (modified UTF-8), or null if it cannot be a vocabulary word. */
        fun mutf8(w: String): ByteArray? {
            var n = 0
            for (c in w) n += if (c.code in 1..0x7f) 1 else if (c.code > 0x7ff) 3 else 2
            if (n > 0xffff) return null
            val out = ByteArray(n)
            var i = 0
            for (ch in w) {
                val c = ch.code
                when {
                    c in 1..0x7f -> out[i++] = c.toByte()
                    c > 0x7ff -> {
                        out[i++] = (0xe0 or (c shr 12 and 0x0f)).toByte()
                        out[i++] = (0x80 or (c shr 6 and 0x3f)).toByte()
                        out[i++] = (0x80 or (c and 0x3f)).toByte()
                    }
                    else -> {
                        out[i++] = (0xc0 or (c shr 6 and 0x1f)).toByte()
                        out[i++] = (0x80 or (c and 0x3f)).toByte()
                    }
                }
            }
            return out
        }

        private fun decodeMutf8(b: ByteArray): String {
            val sb = StringBuilder()
            var i = 0
            while (i < b.size) {
                val x = b[i].toInt() and 0xff
                when {
                    x < 0x80 -> { sb.append(x.toChar()); i++ }
                    x shr 5 == 0x6 -> { sb.append((((x and 0x1f) shl 6) or (b[i + 1].toInt() and 0x3f)).toChar()); i += 2 }
                    else -> { sb.append((((x and 0x0f) shl 12) or ((b[i + 1].toInt() and 0x3f) shl 6) or (b[i + 2].toInt() and 0x3f)).toChar()); i += 3 }
                }
            }
            return sb.toString()
        }
    }
}
