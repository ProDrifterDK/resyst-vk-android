package com.resyst.vk.core

/**
 * r13d (TB1–TB2): which neighbour a touch could have meant. The corrector sees letters only, so a
 * touch on the border of two (or four) keys looks like a touch in the middle of the key it fell in —
 * the owner's `iueho` (first touch on the i / k / u / j corner, meant j) ties with `luego` without it.
 *
 * [near] lists the other letter keys whose hit cell lies within [BORDER] key widths of the point where
 * the finger lifted; [TouchTrail] keeps that list per letter of the word being typed, for
 * [TypoModel.EditRows] to cost those neighbours [TypoModel.BORDER]. Pure, no Android.
 */
object TouchKeys {
    /**
     * A touch this many key widths (or less) from another letter key's hit cell may have meant it: the
     * drawn gap between keys is ≈ 0.1 key widths (10 px of the Pixel 8 Pro's 99.7 px pitch, evidence
     * layout.json) and the hit cells split it in the middle, so 0.12 covers a touch anywhere in the gap
     * plus a pixel or two inside the next key, and nothing nearer a key's middle.
     */
    const val BORDER = 0.12f

    /** One letter key's HIT cell (not its drawn box): what the keyboard commits for a touch inside it. */
    class Cell(val letter: Char, val left: Float, val top: Float, val right: Float, val bottom: Float)

    /**
     * The letters (lowercase, each once, in [cells] order) of the cells within [BORDER] × [keyWidth] of
     * ([x], [y]), without [hit] (the key the touch committed).
     */
    fun near(cells: List<Cell>, x: Float, y: Float, hit: Char, keyWidth: Float): String {
        val r = BORDER * keyWidth
        val h = hit.lowercaseChar()
        val sb = StringBuilder(4)
        for (c in cells) {
            val l = c.letter.lowercaseChar()
            if (l == h || !l.isLetter() || sb.indexOf(l.toString()) >= 0) continue
            val dx = maxOf(c.left - x, 0f, x - c.right)
            val dy = maxOf(c.top - y, 0f, y - c.bottom)
            if (dx * dx + dy * dy <= r * r) sb.append(l)
        }
        return sb.toString()
    }
}

/**
 * r13d (TB2): the touch record of the word being typed — each letter with the neighbours its touch was
 * on the border of. Memory only (never logged, never persisted), at most [Tokens.MAX_LEN] letters; the
 * IME adds a letter per key / long-press variant, drops one per ⌫ and clears it at a word end, a pick,
 * a field change or a cursor move. Used only while its letters spell the word being corrected.
 */
class TouchTrail {
    private val letters = StringBuilder()
    private val near = ArrayList<String>()

    val size: Int get() = letters.length

    /** One letter typed: [keys] = the border neighbours of its touch ("" = none, or not typed by a touch). */
    fun add(letter: Char, keys: String) {
        if (letters.length >= Tokens.MAX_LEN) { reset(); return }
        letters.append(letter.lowercaseChar())
        near.add(keys)
    }

    /** ⌫ took the last letter. */
    fun pop() {
        if (letters.isEmpty()) return
        letters.setLength(letters.length - 1)
        near.removeAt(near.size - 1)
    }

    fun reset() {
        letters.setLength(0)
        near.clear()
    }

    /** This record's letters are exactly [word]'s (case aside); "" for an empty record. */
    fun spells(word: CharSequence): Boolean {
        if (word.length != letters.length) return false
        for (i in word.indices) if (word[i].lowercaseChar() != letters[i]) return false
        return true
    }

    /** The border neighbours per letter of [word] when this record spells it (case aside) and has any; else null. */
    fun forWord(word: String): Array<String>? {
        if (word.isEmpty() || !spells(word) || near.all { it.isEmpty() }) return null
        return near.toTypedArray()
    }
}
