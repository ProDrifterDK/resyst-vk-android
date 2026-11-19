package com.resyst.vk.core

import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * r13h (G2): one finger's samples through KeyboardView's key-touch rules, on the owner's keyboard geometry (s1-s4:
 * 1008 px wide, the decoder's rows; scripts/r13e_s4_decode.py). Two builds:
 * - [Build.R13H]: this build — KeyboardView.keyMove / up / landing over the shipped [PointerGuard] and [HeldKey] (the
 *   view's glue copied line for line: a JUMP ends the touch at the guard's last calm sample, the pointer lands as a new
 *   touch where it settles or lifts; FAST samples wait for a calm one or the lift);
 * - [Build.R12]: 0.12.0's KeyboardView.move / up (SpaceGesture with the flick on, the cursor drag counted from the
 *   down point without a cap, ⌫'s swipe, a letter re-targeting to the letter under the finger), no guard.
 * What a touch produced: committed keys (letters / marks, " ", "⌫" on its down, "⏎"), "FLICK", "DELWORD", "CURSOR±n".
 * The long press (350 ms) is modelled as the picker / variants owning the touch: nothing committed after it.
 */
class TouchReplay(private val build: Build, private val flick: Boolean = true, val dp: Float = DP) {
    enum class Build { R12, R13H }

    class Key(val label: String, val type: KeyType, val left: Float, val right: Float, val row: Int)

    val out = ArrayList<String>()
    var cursorSteps = 0
        private set
    var maxStepsPerSample = 0
        private set

    // ── one pointer ─────────────────────────────────────────────────────
    private var key: Key? = null
    private var held: HeldKey? = null
    private var guard: PointerGuard? = null
    private var pending = false
    private var landing: PointerGuard? = null
    private var downAt = 0L
    // 0.12.0
    private var downX = 0f
    private var downY = 0f
    private var cursorMode = false
    private var cursorX = 0f
    private var flicked = false
    private var swiped = false

    fun run(path: List<FloatArray>): List<String> {
        val s0 = path.first()
        down(s0[0], s0[1], s0[2].toLong())
        for (s in path.drop(1).dropLast(1)) move(s[0], s[1], s[2].toLong())
        val e = path.last()
        if (path.size > 1) up(e[0], e[1], e[2].toLong()) else up(s0[0], s0[1], s0[2].toLong())
        return out
    }

    private fun down(x: Float, y: Float, t: Long) {
        val k = keyAt(x, y)
        key = k; downAt = t; downX = x; downY = y
        cursorMode = false; flicked = false; swiped = false; pending = false; cursorX = x
        if (k == null) return
        if (build == Build.R13H) {
            held = HeldKey(k.type, x, y, t, dp, flick)
            guard = PointerGuard(KEY_PX).also { it.down(x, y, t) }
        }
        if (k.type == KeyType.BACKSPACE) out += "⌫"
    }

    private fun longFired(t: Long) = t - downAt >= LONG_PRESS_MS && (key?.type == KeyType.SPACE)

    private fun move(x: Float, y: Float, t: Long) {
        if (build == Build.R12) { move12(x, y, t); return }
        landing?.let { g ->
            if (g.feed(x, y, t) == PointerGuard.Verdict.SETTLED) { landing = null; down(x, y, t) }
            return
        }
        keyMove(x, y, t, lift = false)
    }

    private fun keyMove(x: Float, y: Float, t: Long, lift: Boolean): Boolean {
        val b = key ?: return false
        val g = guard
        if (g != null) {
            when (g.feed(x, y, t)) {
                PointerGuard.Verdict.JUMP -> { release(g.calmX, g.calmY, g.calmT); landing = g; return true }
                PointerGuard.Verdict.FAST, PointerGuard.Verdict.LANDING -> if (!lift) { pending = true; return false }
                else -> Unit
            }
        }
        if (lift && !pending) return false
        pending = false
        val h = held ?: return false
        for (a in h.move(x, y, t, longFired(t))) when (a) {
            HeldKey.Act.DeleteWord -> out += "DELWORD"
            HeldKey.Act.Flick -> out += "FLICK"
            HeldKey.Act.Swipe -> Unit
            HeldKey.Act.CursorStart -> Unit
            is HeldKey.Act.Cursor -> cursor(a.steps)
            is HeldKey.Act.Over -> {
                val nb = keyAt(a.x, a.y)
                if (nb != null && nb !== b && nb.type == KeyType.CHAR) key = nb
            }
        }
        return false
    }

    private fun up(x: Float, y: Float, t: Long) {
        if (build == Build.R12) { release(x, y, t); return }
        if (landing != null) { landing = null; down(x, y, t); release(x, y, t); return }
        if (key != null && keyMove(x, y, t, lift = true)) { landing = null; down(x, y, t) }
        release(x, y, t)
    }

    private fun release(x: Float, y: Float, t: Long) {
        val b = key ?: return
        key = null
        val gesture = if (build == Build.R13H) (held?.cursorMode == true || held?.flicked == true) else (cursorMode || flicked)
        when {
            longFired(t) || gesture -> Unit
            b.type == KeyType.BACKSPACE || b.type == KeyType.SHIFT -> Unit
            else -> out += b.label
        }
    }

    private fun move12(x: Float, y: Float, t: Long) {
        val b = key ?: return
        when (b.type) {
            KeyType.BACKSPACE -> if (!swiped && DeleteSwipe.isSwipe(x - downX, y - downY, dp)) { swiped = true; out += "DELWORD" }
            KeyType.SPACE -> {
                if (!cursorMode && !longFired(t) && !flicked) {
                    when (SpaceGesture.classify((x - downX) / dp, (y - downY) / dp, t - downAt)) {
                        SpaceGesture.Kind.FLICK -> { flicked = true; out += "FLICK" }
                        SpaceGesture.Kind.CURSOR -> { cursorMode = true; cursorX = downX }
                        else -> Unit
                    }
                }
                if (cursorMode) {
                    val stepPx = 9 * dp
                    val steps = ((x - cursorX) / stepPx).toInt()
                    if (steps != 0) { cursorX += steps * stepPx; cursor(steps) }
                }
            }
            KeyType.CHAR -> {
                val nb = keyAt(x, y)
                if (nb != null && nb !== b && nb.type == KeyType.CHAR) key = nb
            }
            else -> Unit
        }
    }

    private fun cursor(steps: Int) {
        cursorSteps += steps
        maxStepsPerSample = maxOf(maxStepsPerSample, abs(steps))
        val last = out.lastOrNull()
        if (last != null && last.startsWith("CURSOR")) out[out.size - 1] = "CURSOR" + fmt(last.removePrefix("CURSOR").toInt() + steps)
        else out += "CURSOR" + fmt(steps)
    }

    private fun fmt(n: Int) = if (n >= 0) "+$n" else "$n"

    companion object {
        /** The owner's Pixel 8 Pro as the decoder reads it (r13e_s4_decode.py): px per dp, key width, rows. */
        const val DP = 1.9f
        const val LONG_PRESS_MS = 350L
        val GEO by lazy { MiniJson.parse(File("src/test/resources/r13h/touch-paths.json").readText()) as Map<*, *> }
        val KEY_PX: Float by lazy { (GEO["key_px"] as Number).toFloat() }
        val PAD: Float by lazy { (GEO["pad_px"] as Number).toFloat() }
        val TOP: Float by lazy { (GEO["top_px"] as Number).toFloat() }
        val ROW: Float by lazy { (GEO["row_px"] as Number).toFloat() }

        private fun row(vararg k: Pair<String, Float>) = k.toList()
        private val SPEC = listOf(
            "qwertyuiop".map { it.toString() to 1f },
            "asdfghjklñ".map { it.toString() to 1f },
            listOf("⇧" to 1.5f) + "zxcvbnm".map { it.toString() to 1f } + listOf("⌫" to 1.5f),
            row("123" to 1.5f, "," to 1f, "☺" to 1f, " " to 4f, "." to 1f, "⏎" to 1.5f),
        )

        val KEYS: List<List<Key>> by lazy {
            SPEC.mapIndexed { r, spec ->
                var x = PAD
                spec.map { (l, w) ->
                    val type = when (l) {
                        " " -> KeyType.SPACE; "⌫" -> KeyType.BACKSPACE; "⇧" -> KeyType.SHIFT; "⏎" -> KeyType.ENTER
                        "123" -> KeyType.LAYER; "☺" -> KeyType.EMOJI; else -> KeyType.CHAR
                    }
                    Key(l, type, x, x + w * KEY_PX, r).also { x += w * KEY_PX }
                }
            }
        }

        /** KeyboardView.boxAt: the row under y, the cell under x, the nearest key for padding; null above the keys. */
        fun keyAt(x: Float, y: Float): Key? {
            if (y < TOP) return null
            val r = ((y - TOP) / ROW).toInt().coerceIn(0, KEYS.size - 1)
            val row = KEYS[r]
            return row.firstOrNull { x >= it.left && x < it.right } ?: row.minByOrNull { min(abs(x - it.left), abs(x - it.right)) }
        }

        fun center(label: String): FloatArray {
            val k = KEYS.flatten().first { it.label == label }
            return floatArrayOf((k.left + k.right) / 2, TOP + (k.row + 0.5f) * ROW)
        }

        fun dist(a: FloatArray, b: FloatArray) = hypot(a[0] - b[0], a[1] - b[1])
    }
}
