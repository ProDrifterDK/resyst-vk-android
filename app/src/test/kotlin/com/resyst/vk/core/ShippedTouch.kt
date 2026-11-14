package com.resyst.vk.core

import java.io.File

/**
 * r13d (TB): [ShippedWriting.replay] with the keyboard's touch record — each letter typed with the border
 * keys of its touch, kept in a [TouchTrail] exactly as ResystImeService keeps it (a letter appended adds
 * itself, anything else clears it) and read by the corrector through [Writing.touch].
 */
object ShippedTouch {
    /**
     * A fresh multi-line field holding [left]; [typed] is typed key by key with [near] (per letter, the
     * border keys of its touch; null = no record), then [end] is pressed. Returns what follows [left].
     */
    fun replay(left: String, typed: String, end: String, near: List<String>?, w: Writing = ShippedWriting.writing()): String {
        val trail = TouchTrail()
        w.touch = { trail }
        val engine = KeyboardEngine()
        engine.start(FieldInfo(FieldKind.TEXT, multiLine = true, action = ImeAction.NONE, autoCap = true))
        w.lang = w.settings.lang
        engine.corrector = w.corrector()
        var text = left
        for ((i, c) in typed.withIndex()) {
            engine.updateAutoCap(ShippedWriting.capsHint(text))
            val before = text.takeLast(ShippedWriting.WINDOW)
            val outs = engine.press(Key(KeyType.CHAR, c.toString(), c.toString()), before, 0L)
            text = text.dropLast(before.length) + Edits.apply(before, outs)
            val one = (outs.singleOrNull() as? Out.Commit)?.text?.singleOrNull()
            if (one != null && one.isLetter()) trail.add(one, near?.getOrNull(i) ?: "") else trail.reset()
        }
        engine.updateAutoCap(ShippedWriting.capsHint(text))
        val before = text.takeLast(ShippedWriting.WINDOW)
        w.lang = w.detect(before, before.length >= ShippedWriting.WINDOW)
        text = text.dropLast(before.length) + Edits.apply(before, engine.press(ShippedWriting.endKey(end), before, 10_000L, ""))
        trail.reset()
        w.touch = { null }
        check(text.startsWith(left)) { "the field keeps its left context" }
        return text.substring(left.length)
    }

    /** s3's touch evidence per fixture row (scripts/r13d_touch_keys.py → r13d/s3-touch.json), aligned with s3-live.json. */
    fun s3(): List<List<String>?> = (MiniJson.parse(File("src/test/resources/r13d/s3-touch.json").readText()) as List<*>).map { e ->
        (e as? Map<*, *>)?.let { m -> (m["near"] as List<*>).map { it as String } }
    }

    /**
     * The Pixel 8 Pro letters layer's HIT cells (evidence layout.json, as KeyboardView.boxAt splits it: rows
     * of 123 px whose boundary sits gap/2 = 4.2 px below each drawn box, keys meeting at the gap midpoints).
     */
    val pixel8ProCells: List<TouchKeys.Cell> by lazy {
        val boxes = listOf(
            "qwertyuiop" to listOf(10, 110, 209, 309, 409, 508, 608, 708, 807, 907),
            "asdfghjklñ" to listOf(10, 110, 209, 309, 409, 508, 608, 708, 807, 907),
            "zxcvbnm" to listOf(159, 259, 359, 458, 558, 658, 757),
        )
        val bottoms = listOf(1814f, 1938f, 2062f)
        val gap = 11f - 2.625f
        val out = ArrayList<TouchKeys.Cell>()
        for ((r, row) in boxes.withIndex()) {
            val (letters, lefts) = row
            val bottom = bottoms[r] + gap / 2
            for (i in letters.indices) {
                val l = lefts[i].toFloat()
                val rt = l + 90f
                val left = if (i > 0) (lefts[i - 1] + 90 + lefts[i]) / 2f else l - gap / 2
                val right = if (i < letters.length - 1) (rt + lefts[i + 1]) / 2f else rt + gap / 2
                out += TouchKeys.Cell(letters[i], left, bottom - 123f, right, bottom)
            }
        }
        out
    }
}
