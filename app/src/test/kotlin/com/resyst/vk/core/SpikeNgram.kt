package com.resyst.vk.core

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import kotlin.math.ln
import kotlin.math.pow

/**
 * r13f (offers, AS4) TEST ONLY: the r13e spike's array reader (spike/r13e-ngram ce8792c core/ContextNgram.kt, copied
 * unchanged but for the name) — the reference the shipped memory-mapped [ContextNgram] must match bit for bit.
 *
 * r13e (spike): a word trigram model read from a VKNG v2 asset (tools/ngram-build/NgramBuild.java), pure Kotlin.
 *
 * Witten-Bell interpolation with the continuation-type counts the builder computed before pruning:
 * ```
 * P1(w)     = (c(w) + 1) / (N + V)
 * P2(w|b)   = (c(b w) + [T(b) + pruned(b ·)]·P1(w)) / (c(b) + T(b))
 * P3(w|a b) = (c(a b w) + [T(a b) + pruned(a b ·)]·P2(w|b)) / (c(a b) + T(a b))   (P2 when a b was pruned / unseen)
 * ```
 * c(·) of a pruned n-gram is 0; its mass (pruned) goes to the backoff, so P(·|h) still sums to 1 (VKNG v2).
 * Counts above 1 are quantised to 1/8 of a power of two (≤ 4.4 % error). A word outside the vocabulary is
 * `<unk>` as context; as the scored word it costs P(<unk>|h) / unkTypes. Sentences start with `<s>` and end with
 * `</s>`, never cross a boundary. Not wired into the IME; immutable, thread-safe after construction.
 */
class SpikeNgram private constructor(
    val vocabSize: Int,
    val tokens: Long,
    val unkTypes: Long,
    private val words: Array<String>,
    private val sortedWords: Array<String>,
    private val sortedIds: IntArray,
    private val uni: LongArray,
    private val uniT: LongArray,
    private val uniKept: LongArray,
    private val bKey: LongArray,
    private val bC: ByteArray,
    private val bT: ByteArray,
    private val bF: ByteArray,
    private val tKey: LongArray,
    private val tC: ByteArray,
) {
    val bigrams: Int get() = bKey.size
    val trigrams: Int get() = tKey.size

    /** The id of [w] (a token as [ContextTokens] gives it), or [UNK] (0). */
    fun id(w: String): Int {
        var lo = 0
        var hi = sortedWords.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = sortedWords[mid].compareTo(w)
            if (c == 0) return sortedIds[mid]
            if (c < 0) lo = mid + 1 else hi = mid - 1
        }
        return UNK
    }

    fun word(id: Int): String = words[id]

    /** Raw (weighted) count of a vocabulary word; 0 if unknown. */
    fun count(w: String): Long = id(w).let { if (it == UNK) 0 else uni[it] }

    private fun big(a: Int, b: Int): Int = java.util.Arrays.binarySearch(bKey, (a.toLong() shl 20) or b.toLong())
    private fun tri(a: Int, b: Int, c: Int): Int = java.util.Arrays.binarySearch(tKey, (a.toLong() shl 40) or (b.toLong() shl 20) or c.toLong())

    private fun p1(w: Int): Double = (uni[w] + 1.0) / (tokens + vocabSize)

    private fun p2(w: Int, b: Int): Double {
        val cb = uni[b].toDouble()
        val t = uniT[b].toDouble()
        if (cb <= 0.0 || t <= 0.0) return p1(w)
        val i = big(b, w)
        val c = if (i >= 0) deq(bC[i]) else 0.0
        val bo = t + maxOf(0.0, cb - uniKept[b])
        return (c + bo * p1(w)) / (cb + t)
    }

    private fun p3(w: Int, a: Int, b: Int): Double {
        val i = big(a, b)
        if (i < 0) return p2(w, b)
        val cab = deq(bC[i])
        val t = deq(bT[i])
        if (t <= 0.0) return p2(w, b)
        val j = tri(a, b, w)
        val c = if (j >= 0) deq(tC[j]) else 0.0
        val bo = t + (bF[i].toInt() and 0xff) / 255.0 * cab
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

    companion object {
        const val UNK = 0
        const val BOS_ID = 1

        private val DEQ = DoubleArray(256) { q -> if (q == 0) 0.0 else 2.0.pow((q - 1) / 8.0) }
        private fun deq(q: Byte): Double = DEQ[q.toInt() and 0xff]

        fun read(bytes: ByteArray): SpikeNgram {
            val inp = DataInputStream(ByteArrayInputStream(bytes))
            val magic = ByteArray(4).also { inp.readFully(it) }
            check(String(magic, Charsets.US_ASCII) == "VKNG") { "not a VKNG asset" }
            check(inp.readInt() == 2) { "VKNG version" }
            val v = inp.readInt()
            val n = inp.readLong()
            val unk = inp.readLong()
            val n2 = inp.readInt()
            val n3 = inp.readInt()
            inp.readInt(); inp.readInt() // k2, k3 (informational)
            val words = Array(v) { inp.readUTF() }
            val uni = LongArray(v)
            val uniT = LongArray(v)
            val uniKept = LongArray(v)
            for (i in 0 until v) { uni[i] = varint(inp); uniT[i] = varint(inp); uniKept[i] = varint(inp) }
            val bKey = LongArray(n2)
            val bC = ByteArray(n2)
            val bT = ByteArray(n2)
            val bF = ByteArray(n2)
            var prev = 0L
            for (i in 0 until n2) { prev += varint(inp); bKey[i] = prev; bC[i] = inp.readByte(); bT[i] = inp.readByte(); bF[i] = inp.readByte() }
            val tKey = LongArray(n3)
            val tC = ByteArray(n3)
            prev = 0L
            for (i in 0 until n3) { prev += varint(inp); tKey[i] = prev; tC[i] = inp.readByte() }
            check(inp.read() == -1) { "trailing bytes" }
            val order = (0 until v).sortedBy { words[it] }
            return SpikeNgram(v, n, unk, words, Array(v) { words[order[it]] }, IntArray(v) { order[it] }, uni, uniT, uniKept, bKey, bC, bT, bF, tKey, tC)
        }

        private fun varint(inp: DataInputStream): Long {
            var shift = 0
            var r = 0L
            while (true) {
                val b = inp.readUnsignedByte()
                r = r or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return r
                shift += 7
            }
        }
    }
}
