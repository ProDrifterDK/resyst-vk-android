package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * r13f (docs/failure-modes.md, Round 13f): the four chat-typing failures of live session s4, one block each.
 * F1 CL1-CL3, F2 OP1-OP4, F3 SE1-SE4, F4 SG1-SG3. Every block fails on 8ffd08a (0.11.0).
 */
class Round13fTest {
    private fun char(c: String) = Key(KeyType.CHAR, c, c)
    private fun layer(l: Layer) = Key(KeyType.LAYER, "L", target = l)
    private val space = Key(KeyType.SPACE, " ", " ")
    private val enter = Key(KeyType.ENTER, "⏎")
    private val bs = Key(KeyType.BACKSPACE, "⌫")

    // ── F1: the auto-capital after a layer return (CL1, CL3) ──────────────
    /** "hola" + ?123 + "?" + space + ABC: the editor answered "caps" while the symbols layer was showing. */
    private fun backFromSymbols(capsAfterSpace: Boolean): KeyboardEngine {
        val e = KeyboardEngine()
        e.start(FieldInfo(FieldKind.TEXT, autoCap = true))
        e.updateAutoCap(false)
        e.press(layer(Layer.SYMBOLS), "hola", 0L)
        e.press(char("?"), "hola", 0L)
        e.updateAutoCap(false)
        e.press(space, "hola?", 0L)
        e.updateAutoCap(capsAfterSpace)
        assertEquals("no shift off the letters layer", ShiftState.OFF, e.shift)
        e.press(layer(Layer.LETTERS), "hola? ", 0L)
        return e
    }

    @Test fun cl1LayerReturnReappliesTheCapsAnswer() {
        val e = backFromSymbols(capsAfterSpace = true)
        assertEquals(Layer.LETTERS, e.layer)
        assertEquals("CL1: back on the letters a sentence start is capitalized", ShiftState.AUTO, e.shift)
        assertEquals(listOf(Out.Commit("S")), e.press(char("s"), "hola? ", 0L))
        assertEquals(listOf(Out.Commit("a")), e.press(char("a"), "hola? S", 0L))
    }

    @Test fun cl3LayerReturnMidSentenceStaysLowercase() {
        val e = backFromSymbols(capsAfterSpace = false)
        assertEquals("CL3: the caps answer was no", ShiftState.OFF, e.shift)
        // a shift the user pressed is never overridden by the return
        val f = KeyboardEngine()
        f.start(FieldInfo(FieldKind.TEXT, autoCap = true))
        f.press(layer(Layer.SYMBOLS), "x", 0L)
        f.updateAutoCap(true)
        f.shiftDown(1_000L); f.shiftUp() // ONCE
        f.press(layer(Layer.LETTERS), "x", 0L)
        assertEquals(ShiftState.ONCE, f.shift)
        // the auto-capital setting off: no capital
        val g = KeyboardEngine()
        g.start(FieldInfo(FieldKind.TEXT, autoCap = true))
        g.autoCapEnabled = false
        g.press(layer(Layer.SYMBOLS), "", 0L)
        g.updateAutoCap(true)
        g.press(layer(Layer.LETTERS), "", 0L)
        assertEquals(ShiftState.OFF, g.shift)
    }

    // ── F1: the emptied field (CL2) and F2: openers (OP1, OP2) ───────────
    @Test fun cl2EmptiedFieldIsASentenceStart() {
        assertTrue("CL2: offset 0, nothing before", AutoCaps.hint(false, "", 0))
        assertFalse("an editor that cannot read its text, cursor not at 0", AutoCaps.hint(false, "", 17))
        assertFalse("mid-sentence", AutoCaps.hint(false, "hola ", 5))
        assertTrue("the editor's yes stays yes", AutoCaps.hint(true, "Hola. ", 6))
    }

    @Test fun op1CapitalAfterASentenceInitialOpener() {
        for (b in listOf("¿", "¡", "¿¡", "Hola. ¿", "Listo! ¡", "¿Vienes? ¿", "Hola.\n¿", "  ¿", "Ok. «¿", "(¿", "Ya.  ¡")) {
            assertTrue("OP1: «$b»", AutoCaps.hint(false, b, b.length))
        }
    }

    @Test fun op2NoCapitalAfterAMidSentenceOpener() {
        for (b in listOf("Oye, ¿", "y ¿", "dime ¿", "Hola.¿", "Bueno... ¿", "hola¿", "Oye ¡", "3 ¿", "¿Que ", "¿Que tal")) {
            assertFalse("OP2: «$b»", AutoCaps.hint(false, b, b.length))
        }
    }

    /**
     * OP3: r13b's held-out prose (Quiroga 1917, 12,013 words, 318 openers). Where the opener rule changes the
     * editor's answer, is the letter after the opener a capital? r13e-s4 measured 122 right / 25 wrong with a
     * port that missed Android's ellipsis check; Android's own rule (OP1) gives 101 / 14 (Round13fReplayTest
     * lists them).
     */
    @Test fun op3HeldOutProseCounts() {
        val c = proseOpeners()
        assertEquals("openers in prose A", 318, c.total)
        assertEquals("right capitals: ${c.right}", 101, c.right.size)
        assertEquals("wrong capitals: ${c.wrong}", 14, c.wrong.size)
    }

    // ── F3: the word is corrected before VK's own Enter (SE1, SE3, SE4) ──
    private fun field(action: ImeAction, multiLine: Boolean = false): KeyboardEngine = KeyboardEngine().also {
        it.start(FieldInfo(FieldKind.TEXT, multiLine = multiLine, action = action, autoCap = false))
        it.corrector = ShippedWriting.writing().corrector()
    }

    @Test fun se1EnterCorrectsBeforeTheAction() {
        assertEquals(listOf(Out.DeleteBefore(5), Out.Commit("lento"), Out.Action(ImeAction.DONE)),
            field(ImeAction.DONE).press(enter, "Va muy lenyo", 0L, ""))
        assertEquals("s4's field: action NONE, the Enter key event", listOf(Out.DeleteBefore(5), Out.Commit("lento"), Out.EnterKey),
            field(ImeAction.NONE).press(enter, "Va muy lenyo", 0L, ""))
        assertEquals(listOf(Out.DeleteBefore(5), Out.Commit("lento"), Out.Action(ImeAction.SEND)),
            field(ImeAction.SEND).press(enter, "lenyo", 0L, ""))
        // a right word, an empty field, a word ending in a mark: only the action
        assertEquals(listOf(Out.Action(ImeAction.DONE)), field(ImeAction.DONE).press(enter, "Va muy lento", 0L, ""))
        assertEquals(listOf(Out.EnterKey), field(ImeAction.NONE).press(enter, "", 0L, ""))
        assertEquals(listOf(Out.EnterKey), field(ImeAction.NONE).press(enter, "hola. ", 0L, ""))
        // mid-word (a letter after the cursor): no correction (A11)
        assertEquals(listOf(Out.Action(ImeAction.DONE)), field(ImeAction.DONE).press(enter, "lenyo", 0L, "s"))
    }

    @Test fun se3UndoAndRejectionsAtEnter() {
        val e = field(ImeAction.SEARCH)
        e.press(enter, "lenyo", 0L, "")
        // a search box keeps its text: ⌫ right after restores the typed word and records the rejection
        assertEquals(listOf(Out.DeleteBefore(5), Out.Commit("lenyo")), e.press(bs, "lento", 0L, ""))
        assertEquals("lenyo" to "lento", e.takeRejection())
        // rejected in this field: the next Enter sends it as typed
        assertEquals(listOf(Out.Action(ImeAction.SEARCH)), e.press(enter, "lenyo", 0L, ""))
    }

    @Test fun se4MultiLineKeepsTheNewLine() {
        assertEquals("r13c EP1, unchanged", listOf(Out.DeleteBefore(5), Out.Commit("lento\n")), field(ImeAction.NONE, multiLine = true).press(enter, "lenyo", 0L, ""))
        val none = KeyboardEngine().also { it.start(FieldInfo(FieldKind.TEXT, action = ImeAction.DONE)) } // no corrector (secret, email, opted out)
        assertEquals(listOf(Out.Action(ImeAction.DONE)), none.press(enter, "lenyo", 0L, ""))
    }

    // ── F4: the space bar (SG1-SG3) ──────────────────────────────────────
    class Trace(val session: String, val live: String, val dur: Int, val path: List<FloatArray>, val msg: String?, val intent: String?, val context: String?)

    private val traces: List<Trace> by lazy {
        val root = MiniJson.parse(File("src/test/resources/r13f/space-traces.json").readText()) as Map<*, *>
        (root["sessions"] as Map<*, *>).flatMap { (s, list) ->
            (list as List<*>).map { e ->
                val m = e as Map<*, *>
                Trace(s as String, m["live"] as String, (m["dur_ms"] as Number).toInt(),
                    (m["path"] as List<*>).map { p -> (p as List<*>).map { (it as Number).toFloat() }.toFloatArray() },
                    m["msg"] as String?, m["intent"] as String?, m["context"] as String?)
            }
        }
    }

    /** KeyboardView.move on the space bar: every move is classified until one is not NONE (or the long press fires). */
    private fun classify(path: List<FloatArray>): SpaceGesture.Kind {
        for (p in path) {
            if (p[2] >= 350f) return SpaceGesture.Kind.NONE // the long press (picker) owns it from here
            val k = SpaceGesture.classify(p[0], p[1], p[2].toLong())
            if (k != SpaceGesture.Kind.NONE) return k
        }
        return SpaceGesture.Kind.NONE
    }

    @Test fun sg1sg2TheSevenS4Gestures() {
        val seven = traces.filter { it.intent != null }
        assertEquals(7, seven.size)
        val got = seven.map { "${it.msg} ${it.intent} ${it.context}: ${classify(it.path)}" }
        val accidental = seven.filter { it.intent == "accidental" }
        assertEquals(4, accidental.size)
        for (t in accidental) assertEquals("SG1/SG2: an accidental gesture is a space tap: $got", SpaceGesture.Kind.NONE, classify(t.path))
        for (t in seven.filter { it.intent == "deliberate" }) assertEquals("the owner's deliberate swipes still switch: $got", SpaceGesture.Kind.FLICK, classify(t.path))
    }

    @Test fun sg3EverySpaceTapOfS1toS4StaysATap() {
        val taps = traces.filter { it.intent == null }
        assertEquals("s1 269 + s2 265 + s3 262 + s4 227", 1023, taps.size)
        val moved = taps.filter { classify(it.path) != SpaceGesture.Kind.NONE }.map { "${it.session} ${it.dur}ms ${it.path.last().toList()}" }
        assertEquals("every other space touch is a space", emptyList<String>(), moved)
    }

    @Test fun sg1sg2DeliberateGesturesStillWork() {
        fun path(vararg p: Float) = p.toList().chunked(3).map { floatArrayOf(it[0], it[1], it[2]) }
        assertEquals("a deliberate swipe up", SpaceGesture.Kind.FLICK, classify(path(0f, -12f, 40f, 1f, -28f, 70f, 2f, -44f, 100f)))
        assertEquals("down works too", SpaceGesture.Kind.FLICK, classify(path(0f, 20f, 50f, 3f, 41f, 110f)))
        assertEquals("hold, then drag", SpaceGesture.Kind.CURSOR, classify(path(0.5f, 0.3f, 120f, 1f, 0.5f, 230f, 6f, 0.5f, 260f, 12f, 1f, 300f)))
        assertEquals("a long drag", SpaceGesture.Kind.CURSOR, classify(path(8f, 0f, 40f, 18f, 1f, 70f, 26f, 1f, 100f)))
        assertEquals("a slow drag", SpaceGesture.Kind.CURSOR, classify(path(4f, 0f, 100f, 8f, 0f, 170f, 11f, 0f, 220f)))
        assertEquals("a quick 15 dp slip", SpaceGesture.Kind.NONE, classify(path(4f, 0f, 30f, 15.4f, -3.6f, 66f)))
        assertEquals("a 26 dp drift up", SpaceGesture.Kind.NONE, classify(path(2f, -10f, 50f, 4.3f, -26.1f, 87f)))
        assertEquals("a slow 39 dp swipe up is no flick (300 ms)", SpaceGesture.Kind.NONE, classify(path(0f, -20f, 200f, 0f, -45f, 320f)))
        assertEquals("diagonal: neither", SpaceGesture.Kind.NONE, classify(path(20f, -30f, 90f)))
    }

    companion object {
        class Openers(val total: Int, val right: List<String>, val wrong: List<String>)

        /** OP3: every ¿ / ¡ run of prose A followed by a letter, where AutoCaps.hint says caps and the editor does not. */
        fun proseOpeners(): Openers {
            val prose = File("src/test/resources/r13f/prose-a.txt").readText()
            val right = ArrayList<String>()
            val wrong = ArrayList<String>()
            var total = 0
            for (m in Regex("[¿¡]+[\"'«“]?(\\p{L})").findAll(prose)) {
                total++
                val at = m.groups[1]!!.range.first
                val before = prose.substring(0, at).takeLast(Tokens.READ_WINDOW)
                val editor = S4Chat.androidCaps(before)
                if (editor || !AutoCaps.hint(editor, before, at)) continue
                val shown = prose.substring(maxOf(0, m.range.first - 24), minOf(prose.length, m.range.last + 12)).replace('\n', '⏎')
                if (prose[at].isUpperCase()) right += shown else wrong += shown
            }
            return Openers(total, right, wrong)
        }
    }
}
