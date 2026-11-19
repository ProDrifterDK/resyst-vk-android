package com.resyst.vk.core

import kotlin.math.hypot

/**
 * r13h (G2, docs/failure-modes.md JG1-JG6): a touchscreen may report one finger's lift and the next finger's touch as
 * ONE pointer that moves (same tracking id, no UP / DOWN in between). Live session s4 has one: the finger rests on `n`,
 * its contact collapses, then the same pointer travels 3.2 key widths to `c` in 25 ms (the firmware smoothing the jump
 * over six reports) and rests there. No finger typing moved more than 0.34 key widths in 12 ms in s1-s4, and no
 * deliberate gesture (the owner's language flicks) more than 0.49 in 12 ms or 0.93 in 25 ms.
 *
 * One guard per held key pointer, fed with every sample (MotionEvent history included), distances in px, [keyPx] the
 * width of a one-unit key:
 * - [Verdict.JUMP]: ≥ [JUMP_KEYS] key widths against the previous sample (any age: a real finger that far is reported
 *   on the way) or against any sample of the last [WINDOW_MS], or ≥ [LONG_JUMP_KEYS] within [LONG_WINDOW_MS] (a
 *   shorter jump the firmware smoothed over its ~25 ms). The touch ended at the last calm sample ([calmX], [calmY]);
 *   the guard is now landing.
 * - [Verdict.FAST]: ≥ [FAST_KEYS] key widths within [WINDOW_MS]: a jump may be on its way, so this sample's effects wait
 *   (the smoothed jump crosses the cursor-drag threshold before it reaches a key width, JG2).
 * - [Verdict.LANDING] / [Verdict.SETTLED]: after a jump, until a [WINDOW_MS] window moves less than [FAST_KEYS] (the
 *   tail of the smoothing); SETTLED once, where the new touch starts (JG3).
 * - [Verdict.CALM]: anything else.
 * Pure and allocation-free per sample; KeyboardView owns one per key pointer, the JVM replay drives the same object.
 */
class PointerGuard(private val keyPx: Float) {
    enum class Verdict { CALM, FAST, JUMP, LANDING, SETTLED }

    private val ts = LongArray(SIZE)
    private val xs = FloatArray(SIZE)
    private val ys = FloatArray(SIZE)
    private var n = 0
    private var head = 0

    /** The last sample that was calm (or the down point): where a touch ends when a jump is found. */
    var calmX = 0f
        private set
    var calmY = 0f
        private set
    var calmT = 0L
        private set

    var landing = false
        private set

    fun down(x: Float, y: Float, t: Long) {
        n = 0; head = 0; landing = false
        push(x, y, t)
        calmX = x; calmY = y; calmT = t
    }

    fun feed(x: Float, y: Float, t: Long): Verdict {
        if (n == 0) { down(x, y, t); return Verdict.CALM }
        val last = (head - 1 + SIZE) % SIZE
        val prev = hypot(x - xs[last], y - ys[last])
        var win = 0f
        var longWin = 0f
        for (k in 0 until n) {
            val i = (head - 1 - k + SIZE * 2) % SIZE
            val age = t - ts[i]
            if (age > LONG_WINDOW_MS) break
            val d = hypot(x - xs[i], y - ys[i])
            longWin = maxOf(longWin, d)
            if (age <= WINDOW_MS) win = maxOf(win, d)
        }
        push(x, y, t)
        val v = when {
            landing -> if (win < FAST_KEYS * keyPx) { landing = false; Verdict.SETTLED } else Verdict.LANDING
            maxOf(prev, win) >= JUMP_KEYS * keyPx || longWin >= LONG_JUMP_KEYS * keyPx -> { landing = true; Verdict.JUMP }
            win >= FAST_KEYS * keyPx -> Verdict.FAST
            else -> Verdict.CALM
        }
        if (v == Verdict.CALM || v == Verdict.SETTLED) { calmX = x; calmY = y; calmT = t }
        return v
    }

    private fun push(x: Float, y: Float, t: Long) {
        ts[head] = t; xs[head] = x; ys[head] = y
        head = (head + 1) % SIZE
        if (n < SIZE) n++
    }

    companion object {
        const val WINDOW_MS = 12L
        const val JUMP_KEYS = 1.0f
        const val FAST_KEYS = 0.4f
        const val LONG_WINDOW_MS = 25L
        const val LONG_JUMP_KEYS = 1.3f
        private const val SIZE = 32
    }
}

/**
 * r13h: what the moving finger of a held key means on a calm sample — KeyboardView's move rules for the space bar
 * (r10 / r13f [SpaceGesture], with the r13h flick switch), ⌫ ([DeleteSwipe]) and a letter (roll-over onto another
 * letter), pure so that the JVM replay runs the same rules on the owner's raw traces. [dp] px per dp.
 */
class HeldKey(
    val type: KeyType,
    val downX: Float,
    val downY: Float,
    val downAt: Long,
    private val dp: Float,
    private val flick: Boolean,
) {
    sealed interface Act {
        /** The space flick (switch on): ES ⇄ EN, nothing typed. */
        object Flick : Act
        /** A vertical swipe with the switch off: nothing typed, nothing changed (LF3). */
        object Swipe : Act
        /** The space bar became a cursor drag (the pending long press goes). */
        object CursorStart : Act
        /** Move the caret by [steps] (never more than [MAX_CURSOR_STEPS] a sample, JG5). */
        data class Cursor(val steps: Int) : Act
        /** ⌫'s leftward swipe: delete the word before the cursor (once per touch). */
        object DeleteWord : Act
        /** A letter's finger is over ([x], [y]): the view re-targets to the letter there, if another. */
        data class Over(val x: Float, val y: Float) : Act
    }

    var cursorMode = false
        private set
    /** A flick or (switch off) a vertical swipe happened: the lift types nothing. */
    var flicked = false
        private set
    var swiped = false
        private set
    private var cursorX = downX

    /** A calm sample at ([x], [y]), [t] ms; [longFired] = the space long press already opened the picker. */
    fun move(x: Float, y: Float, t: Long, longFired: Boolean): List<Act> {
        when (type) {
            KeyType.BACKSPACE -> {
                if (!swiped && DeleteSwipe.isSwipe(x - downX, y - downY, dp)) { swiped = true; return listOf(Act.DeleteWord) }
                return emptyList()
            }
            KeyType.SPACE -> {
                val out = ArrayList<Act>(2)
                if (!cursorMode && !longFired && !flicked) {
                    when (SpaceGesture.classify((x - downX) / dp, (y - downY) / dp, t - downAt, flick)) {
                        SpaceGesture.Kind.FLICK -> { flicked = true; out += Act.Flick }
                        SpaceGesture.Kind.SWIPE -> { flicked = true; out += Act.Swipe }
                        SpaceGesture.Kind.CURSOR -> { cursorMode = true; cursorX = downX; out += Act.CursorStart }
                        SpaceGesture.Kind.NONE -> Unit
                    }
                }
                if (cursorMode) {
                    val stepPx = CURSOR_STEP_DP * dp
                    val steps = ((x - cursorX) / stepPx).toInt()
                    if (steps > MAX_CURSOR_STEPS || steps < -MAX_CURSOR_STEPS) {
                        cursorX = x // JG5: the excess of one sample is dropped, never carried over
                        out += Act.Cursor(if (steps > 0) MAX_CURSOR_STEPS else -MAX_CURSOR_STEPS)
                    } else if (steps != 0) {
                        cursorX += steps * stepPx
                        out += Act.Cursor(steps)
                    }
                }
                return out
            }
            KeyType.CHAR -> return listOf(Act.Over(x, y))
            else -> return emptyList()
        }
    }

    companion object {
        const val CURSOR_STEP_DP = 9f
        const val MAX_CURSOR_STEPS = 3
    }
}
