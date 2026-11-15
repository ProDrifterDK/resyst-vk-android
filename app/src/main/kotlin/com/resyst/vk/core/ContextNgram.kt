package com.resyst.vk.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.pow

/**
 * r13f (offers, HP1, AS1, AS4): the word trigram model of assets/context/wt-m2.vkng (VKNG v2, written by
 * tools/ngram-build/NgramBuild.java), read IN PLACE from a [ByteBuffer] — on the phone a read-only memory map of the
 * uncompressed APK entry, so the model's 11.8 MB are clean file-backed pages the kernel can drop, never Java heap.
 *
 * The r13e spike decoded the whole file into arrays (~41 MB of heap). This reader keeps only small index arrays
 * (≈ 2.3 MB for wt-m2): the byte offset of every word and of every unigram record, the vocabulary in byte order for
 * the word → id search, and every [BLOCK]-th bigram / trigram key with its offset (the delta-coded keys between two
 * index points are decoded on demand). The probabilities are the spike's, bit for bit (AS4, tested against its array
 * reader):
 * ```
 * P1(w)     = (c(w) + 1) / (N + V)
 * P2(w|b)   = (c(b w) + [T(b) + pruned(b ·)]·P1(w)) / (c(b) + T(b))
 * P3(w|a b) = (c(a b w) + [T(a b) + pruned(a b ·)]·P2(w|b)) / (c(a b) + T(a b))   (P2 when a b was pruned / unseen)
 * ```
 * c(·) of a pruned n-gram is 0; its mass (pruned) goes to the backoff, so P(·|h) sums to 1. Counts above 1 are
 * quantised to 1/8 of a power of two. A word outside the vocabulary is `<unk>` as context; as the scored word it
 * costs P(<unk>|h) / unkTypes. Immutable after [read]; absolute reads only, so concurrent readers are safe.
 */
class ContextNgram private constructor(
    private val buf: ByteBuffer,
    val vocabSize: Int,
    val tokens: Long,
    val unkTypes: Long,
    val bigrams: Int,
    val trigrams: Int,
    /** Offset of each word's length prefix, in the byte order of the words (modified UTF-8, unsigned). */
    private val sortedOff: IntArray,
    private val sortedIds: IntArray,
    /** Offset of each id's unigram record (three varints: count, continuation types, kept bigram mass). */
    private val uniOff: IntArray,
    private val bIdxKey: LongArray,
    private val bIdxOff: IntArray,
    private val tIdxKey: LongArray,
    private val tIdxOff: IntArray,
) {
    /** The id of [w] (a token as [ContextTokens] gives it), or [UNK] (0). */
    fun id(w: String): Int {
        val q = mutf8(w) ?: return UNK
        var lo = 0
        var hi = sortedOff.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = compareQuery(sortedOff[mid], q)
            if (c == 0) return sortedIds[mid]
            if (c < 0) lo = mid + 1 else hi = mid - 1
        }
        return UNK
    }

    /** The word of [id] (diagnostics and tests: a linear scan). */
    fun word(id: Int): String {
        val i = sortedIds.indexOf(id)
        require(i >= 0) { "no id $id" }
        val off = sortedOff[i]
        val len = u2(off)
        val bytes = ByteArray(len) { buf.get(off + 2 + it) }
        return decodeMutf8(bytes)
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
    private fun findBigram(key: Long): Int = find(key, bIdxKey, bIdxOff, bigrams, 3)

    /** Offset of the count byte of trigram [key], or −1. */
    private fun findTrigram(key: Long): Int = find(key, tIdxKey, tIdxOff, trigrams, 1)

    private fun find(key: Long, idxKey: LongArray, idxOff: IntArray, n: Int, payload: Int): Int {
        // the last index point ≤ key
        var lo = 0
        var hi = idxKey.size - 1
        var blk = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (idxKey[mid] <= key) { blk = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (blk < 0) return -1
        var p = skipVarint(idxOff[blk])
        var k = idxKey[blk]
        if (k == key) return p
        var e = blk * BLOCK + 1
        val stop = minOf(n, (blk + 1) * BLOCK)
        p += payload
        while (e < stop) {
            var shift = 0
            var d = 0L
            while (true) {
                val x = buf.get(p++).toInt() and 0xff
                d = d or ((x and 0x7f).toLong() shl shift)
                if (x and 0x80 == 0) break
                shift += 7
            }
            k += d
            if (k == key) return p
            if (k > key) return -1
            p += payload
            e++
        }
        return -1
    }

    // ── bytes ─────────────────────────────────────────────────────────────────────────────────────────────────
    private fun u2(off: Int): Int = ((buf.get(off).toInt() and 0xff) shl 8) or (buf.get(off + 1).toInt() and 0xff)

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

    /** Word at [off] compared with the query bytes [q] (unsigned, then length). */
    private fun compareQuery(off: Int, q: ByteArray): Int {
        val len = u2(off)
        val n = minOf(len, q.size)
        for (i in 0 until n) {
            val x = buf.get(off + 2 + i).toInt() and 0xff
            val y = q[i].toInt() and 0xff
            if (x != y) return x - y
        }
        return len - q.size
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
         * section, the exact length; throws on anything else (truncated, foreign, corrupt). [source] is not copied:
         * keep it alive as long as the model (on the phone: the memory map).
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
            var p = HEADER
            // words
            val wordOff = IntArray(v)
            for (i in 0 until v) {
                check(p + 2 <= limit) { "VKNG: truncated words" }
                wordOff[i] = p
                p += 2 + (((b.get(p).toInt() and 0xff) shl 8) or (b.get(p + 1).toInt() and 0xff))
            }
            check(p <= limit) { "VKNG: truncated words" }
            // unigram records
            val uniOff = IntArray(v)
            for (i in 0 until v) {
                uniOff[i] = p
                repeat(3) { p = skip(b, p, limit) }
            }
            // bigrams: key varint (delta), count, T, f
            val nb = (n2 + BLOCK - 1) / BLOCK
            val bKey = LongArray(nb)
            val bOff = IntArray(nb)
            var prev = 0L
            for (e in 0 until n2) {
                if (e % BLOCK == 0) bOff[e / BLOCK] = p
                val start = p
                p = skip(b, p, limit)
                val d = varint(b, start)
                check(d > 0 || e == 0) { "VKNG: bigram keys not ascending" }
                prev += d
                if (e % BLOCK == 0) bKey[e / BLOCK] = prev
                p += 3
            }
            check(p <= limit) { "VKNG: truncated bigrams" }
            // trigrams: key varint (delta), count
            val nt = (n3 + BLOCK - 1) / BLOCK
            val tKey = LongArray(nt)
            val tOff = IntArray(nt)
            prev = 0L
            for (e in 0 until n3) {
                if (e % BLOCK == 0) tOff[e / BLOCK] = p
                val start = p
                p = skip(b, p, limit)
                val d = varint(b, start)
                check(d > 0 || e == 0) { "VKNG: trigram keys not ascending" }
                prev += d
                if (e % BLOCK == 0) tKey[e / BLOCK] = prev
                p += 1
            }
            check(p == limit) { "VKNG: length (${limit - p} bytes left)" }
            // the vocabulary in byte order, for id()
            val order = IntArray(v) { it }
            sortBy(order) { x, y -> compareWords(b, wordOff[x], wordOff[y]) }
            val sortedOff = IntArray(v) { wordOff[order[it]] }
            for (i in 1 until v) check(compareWords(b, sortedOff[i - 1], sortedOff[i]) < 0) { "VKNG: duplicate word" }
            return ContextNgram(b, v, n, unk, n2, n3, sortedOff, order, uniOff, bKey, bOff, tKey, tOff)
        }

        private fun skip(b: ByteBuffer, off: Int, limit: Int): Int {
            var p = off
            var k = 0
            while (true) {
                check(p < limit && k < 10) { "VKNG: bad varint" }
                val x = b.get(p++).toInt()
                k++
                if (x and 0x80 == 0) return p
            }
        }

        private fun varint(b: ByteBuffer, off: Int): Long {
            var p = off
            var shift = 0
            var r = 0L
            while (true) {
                val x = b.get(p++).toInt() and 0xff
                r = r or ((x and 0x7f).toLong() shl shift)
                if (x and 0x80 == 0) return r
                shift += 7
            }
        }

        private fun compareWords(b: ByteBuffer, x: Int, y: Int): Int {
            val lx = ((b.get(x).toInt() and 0xff) shl 8) or (b.get(x + 1).toInt() and 0xff)
            val ly = ((b.get(y).toInt() and 0xff) shl 8) or (b.get(y + 1).toInt() and 0xff)
            val n = minOf(lx, ly)
            for (i in 0 until n) {
                val c = (b.get(x + 2 + i).toInt() and 0xff) - (b.get(y + 2 + i).toInt() and 0xff)
                if (c != 0) return c
            }
            return lx - ly
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
