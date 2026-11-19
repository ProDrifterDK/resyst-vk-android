package com.resyst.vk.core

import com.resyst.vk.core.TouchReplay.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * r13h (docs/failure-modes.md, Round 13h): G1 the language flick as a setting, off by default (LF1-LF4); G2 a merged
 * touch (one pointer reporting a lift and the next touch) is two touches (JG1-JG6), replayed from the owner's raw
 * traces (r13h/touch-paths.json by scripts/r13h_jumps.py; r13f/space-traces.json) through this build's rules and
 * 0.12.0's. Writes build/r13h/replay.json.
 */
class Round13hGesturesTest {
    private val outDir = File("build/r13h").also { it.mkdirs() }

    // ── G1 ─────────────────────────────────────────────────────────────────────────────────────────────────────
    @Test fun lf1lf2TheSwitchIsOffByDefaultAndForA0120Store() {
        assertFalse("LF1: off by default", KbSettings().spaceFlick)
        // LF2: a store written by 0.12.0 has no phone.spaceFlick key: it reads as off (the one-time migration)
        val v012 = ProfileCodec.encode(ProfileCodec.seed()).filterKeys { it != "phone.spaceFlick" }
        assertFalse(v012.containsKey("phone.spaceFlick"))
        assertFalse("a 0.12.0 store reads as off", ProfileCodec.decode(v012).settings.spaceFlick)
        // turned on, it is stored and kept (the migration never runs again)
        val on = ProfileCodec.seed().updatePhone { it.copy(spaceFlick = true) }
        val enc = ProfileCodec.encode(on)
        assertEquals("true", enc["phone.spaceFlick"])
        assertTrue(ProfileCodec.decode(enc).settings.spaceFlick)
        assertTrue("a mode never touches it", Mode.CODE.apply(on.settings).spaceFlick)
        // where it lives: Teclas e idioma, phone-wide, next to the bilingual switch
        assertEquals("teclas", SettingsIA.pageOf(Ctl.SPACE_FLICK)?.id)
        assertEquals(Scope.DEVICE, Ctl.SPACE_FLICK.scope)
        assertEquals("phone.spaceFlick", Ctl.SPACE_FLICK.key)
        val es = File("src/main/res/values/strings.xml").readText()
        val en = File("src/main/res/values-en/strings.xml").readText()
        assertTrue(es.contains(">Cambiar idioma deslizando la barra espaciadora<"))
        assertTrue(en.contains(">Switch language by swiping the space bar<"))
        // the view builds each touch with the stored switch
        val view = File("src/main/kotlin/com/resyst/vk/ime/KeyboardView.kt").readText()
        assertTrue(view.contains("HeldKey(box.key.type, x, y, t, dp, settings.spaceFlick)"))
    }

    @Test fun lf3OffAnUpwardSwipeNeverChangesTheLanguage() {
        // SpaceGesture: off, a deliberate vertical swipe is SWIPE (nothing typed), a drift is a tap, the cursor is unchanged
        assertEquals(SpaceGesture.Kind.SWIPE, SpaceGesture.classify(0f, -44f, 120, flick = false))
        assertEquals(SpaceGesture.Kind.SWIPE, SpaceGesture.classify(3f, 70f, 600, flick = false))
        assertEquals(SpaceGesture.Kind.NONE, SpaceGesture.classify(2f, -26f, 90, flick = false))
        assertEquals(SpaceGesture.Kind.CURSOR, SpaceGesture.classify(30f, 4f, 120, flick = false))
        assertEquals(SpaceGesture.Kind.FLICK, SpaceGesture.classify(0f, -44f, 120, flick = true))
        // every space touch of s1-s4 through this build with the switch off: no flick ever; the owner's 4 accidental
        // gestures and every tap type a space; his 3 deliberate swipes type nothing
        var spaces = 0
        for (t in spaceTraces) {
            val off = TouchReplay(Build.R13H, flick = false).run(t.px())
            assertFalse("${t.session} ${t.msg}: $off", "FLICK" in off)
            if (t.intent == "deliberate") assertEquals("${t.msg}: a deliberate swipe types nothing", emptyList<String>(), off)
            else if (t.intent == "accidental" || t.dur < 350) { assertEquals("${t.session} ${t.msg} ${t.path.last().toList()}", listOf(" "), off.filter { !it.startsWith("CURSOR") }); spaces++ }
        }
        assertTrue(spaces > 1000)
        // on: the deliberate ones still switch (SG1), the accidental ones are spaces
        for (t in spaceTraces.filter { it.intent != null }) {
            val on = TouchReplay(Build.R13H, flick = true).run(t.px())
            assertEquals("${t.msg} ${t.intent}", if (t.intent == "deliberate") listOf("FLICK") else listOf(" "), on)
        }
    }

    @Test fun lf4AManualWayToSwitchStaysDocumented() {
        val ui = File("src/main/kotlin/com/resyst/vk/settings/SettingsActivity.kt").readText()
        val bilingual = ui.substringAfter("Ctl.BILINGUAL -> toggle(").substringBefore("\n")
        assertFalse("the flick is no longer how the bilingual summary says to switch", bilingual.contains("desliza el espacio"))
        assertTrue(bilingual.contains("manteniendo pulsado el espacio") && bilingual.contains("«Idioma»"))
        val es = File("src/main/res/values/strings.xml").readText()
        assertTrue(es.substringAfter("name=\"space_flick_summary\">").substringBefore("<").contains("mantén pulsado el espacio"))
        // the long press still opens Android's keyboard picker (the subtypes Español / English), Idioma is on home
        val ime = File("src/main/kotlin/com/resyst/vk/ime/ResystImeService.kt").readText()
        assertTrue(ime.substringAfter("override fun onSpaceLongPress()").substringBefore("\n    }").contains("showInputMethodPicker()"))
        assertTrue(Ctl.LANG in SettingsIA.HOME)
        assertTrue("the service ignores a flick with the switch off", ime.substringAfter("override fun onSpaceFlick()").substringBefore("\n    }").contains("if (!s.spaceFlick) return"))
    }

    // ── G2 ─────────────────────────────────────────────────────────────────────────────────────────────────────
    /** JG1: the merged touch of s4 (`n` + `c` of `cinco`, one pointer): 0.12.0 typed `c` only; this build `n`, `c`. */
    @Test fun jg1TheS4MergeIsTwoTouches() {
        val j = jumps.single { it.session == "s4" }
        val old = TouchReplay(Build.R12).run(j.path)
        val new = TouchReplay(Build.R13H).run(j.path)
        assertEquals("0.12.0: the n slides onto c (the n is lost)", listOf("c"), old)
        assertEquals("this build: the n where it rested, the c where the next finger landed", listOf("n", "c"), new)
    }

    /**
     * JG1/JG2 on the owner's complaint: the s4 jump's own timing and shape (the merge's samples relative to where it
     * left and where it landed) moved onto a space tap → every letter, and onto a letter → the space, ⌫ → a letter.
     * 0.12.0: cursor runs, flicks, lost keys; this build: the two keys, no caret move, no language change.
     */
    @Test fun jg1SyntheticJumpsFromTheRealTrace() {
        val starts = listOf(floatArrayOf(420f, 2113f), floatArrayOf(560f, 2120f), floatArrayOf(690f, 2110f))
        val letters = "qwertyuiopasdfghjklñzxcvbnm".map { it.toString() }
        var caught = 0
        val rows = ArrayList<Map<String, Any?>>()
        var oldCursor = 0; var oldFlick = 0; var oldLost = 0; var oldMaxSteps = 0; var onFlick = 0
        for (s in starts) for (l in letters) {
            val end = TouchReplay.center(l)
            val path = transplant(s, end)
            val keys = TouchReplay.dist(s, end) / TouchReplay.KEY_PX
            val o = TouchReplay(Build.R12)
            val old = o.run(path)
            val n = TouchReplay(Build.R13H, flick = false) // the default
            val new = n.run(path)
            val withFlick = TouchReplay(Build.R13H, flick = true).run(path)
            if ("FLICK" in withFlick) onFlick++
            if (old.any { it.startsWith("CURSOR") }) oldCursor++
            if ("FLICK" in old) oldFlick++
            if (old != listOf(" ", l)) oldLost++
            oldMaxSteps = maxOf(oldMaxSteps, kotlin.math.abs(o.cursorSteps))
            assertFalse("never a language change: $new", "FLICK" in new)
            assertTrue("JG5: never more than 3 caret steps: $new", kotlin.math.abs(n.cursorSteps) <= HeldKey.MAX_CURSOR_STEPS)
            if (keys >= PointerGuard.LONG_JUMP_KEYS + 0.2f) {
                assertEquals("space → $l (${"%.2f".format(Locale.ROOT, keys)} key widths)", listOf(" ", l), new)
            }
            if (new == listOf(" ", l)) caught++
            rows += linkedMapOf("from" to "space@${s[0].toInt()}", "to" to l, "keys" to round2(keys), "r12" to old.joinToString(" "), "r13h" to new.joinToString(" "),
                "r13h_flick_on" to withFlick.joinToString(" "))
        }
        // the other directions: a letter → the space, ⌫ → a letter
        val more = ArrayList<Map<String, Any?>>()
        for ((from, to) in listOf("a" to " ", "q" to " ", "p" to " ", "s" to " ", "l" to " ", "n" to " ", "⌫" to "m", "⌫" to "n", "⌫" to "j")) {
            val a = TouchReplay.center(from)
            val b = if (to == " ") floatArrayOf(560f, 2120f) else TouchReplay.center(to)
            val path = transplant(a, b)
            val old = TouchReplay(Build.R12).run(path)
            val new = TouchReplay(Build.R13H, flick = false).run(path)
            val want = if (from == "⌫") listOf("⌫", to) else listOf(from, " ")
            if (TouchReplay.dist(a, b) / TouchReplay.KEY_PX >= PointerGuard.LONG_JUMP_KEYS + 0.2f) assertEquals("$from → '$to'", want, new)
            more += linkedMapOf("from" to from, "to" to to, "keys" to round2(TouchReplay.dist(a, b) / TouchReplay.KEY_PX), "r12" to old.joinToString(" "), "r13h" to new.joinToString(" "))
        }
        val total = starts.size * letters.size
        synthetic = linkedMapOf("space_to_letter" to linkedMapOf("jumps" to total, "r12_cursor_runs" to oldCursor, "r12_max_caret_steps" to oldMaxSteps,
            "r12_flicks" to oldFlick, "r12_not_space_plus_letter" to oldLost, "r13h_space_plus_letter" to caught,
            "r13h_flicks_with_the_switch_on" to onFlick, "rows" to rows), "other" to more)
        writeReplay()
        println("r13h synthetic space→letter jumps ($total): 0.12.0 cursor runs $oldCursor (max $oldMaxSteps steps), flicks $oldFlick, wrong $oldLost; this build space+letter $caught; flicks with the switch on $onFlick")
        assertTrue("0.12.0 is the bug: cursor runs of many steps", oldCursor > 0 && oldMaxSteps > 10)
    }

    /** JG4/JG6: every moving keyboard contact of s1-s4 through the guard: the jumps are the s4 merge and s2's three page scrolls. */
    @Test fun jg4NoTypingTouchOfS1toS4IsAJump() {
        val root = TouchReplay.GEO
        val found = linkedMapOf<String, Any?>()
        var fastContacts = 0
        for ((s, list) in root["sessions"] as Map<*, *>) {
            val jumpsAt = ArrayList<Int>()
            var n = 0
            for (e in list as List<*>) {
                val m = e as Map<*, *>
                val path = (m["p"] as List<*>).map { p -> (p as List<*>).map { (it as Number).toFloat() }.toFloatArray() }
                val g = PointerGuard(TouchReplay.KEY_PX)
                g.down(path[0][0], path[0][1], path[0][2].toLong())
                val v = path.drop(1).map { g.feed(it[0], it[1], it[2].toLong()) }
                if (PointerGuard.Verdict.JUMP in v) jumpsAt += (m["at_ms"] as Number).toInt()
                if (PointerGuard.Verdict.FAST in v) fastContacts++
                n++
            }
            found[s as String] = linkedMapOf("moving_contacts" to n, "jumps" to jumpsAt.size, "jump_at_ms" to jumpsAt)
            when (s) {
                "s4" -> assertEquals("s4: the merge only", 1, jumpsAt.size)
                "s2" -> assertTrue("s2: the three page scrolls before the first key, nothing else: $jumpsAt", jumpsAt.size == 3 && jumpsAt.all { it < 4_000 })
                else -> assertEquals("$s: none", 0, jumpsAt.size)
            }
        }
        found["contacts_with_fast_samples"] = fastContacts
        guardScan = found
        writeReplay()
    }

    /** JG4: every space touch of s1-s4 is what 0.12.0 made of it (flick on): taps stay spaces, the deliberate flicks flick. */
    @Test fun jg4EverySpaceTouchClassifiesAsBefore() {
        val diffs = ArrayList<String>()
        for (t in spaceTraces) {
            val old = TouchReplay(Build.R12).run(t.px())
            val new = TouchReplay(Build.R13H).run(t.px())
            if (old.filter { !it.startsWith("CURSOR") } != new.filter { !it.startsWith("CURSOR") } || (old.any { it.startsWith("CURSOR") } != new.any { it.startsWith("CURSOR") })) {
                diffs += "${t.session} ${t.msg ?: ""} ${t.dur}ms ${t.path.last().toList()}: 0.12.0 $old, r13h $new"
            }
        }
        assertEquals(1030, spaceTraces.size)
        assertEquals("space touches that changed", emptyList<String>(), diffs)
    }

    /** JG4/JG5: the real gestures, at the speeds a finger makes them. */
    @Test fun jg4jg5DeliberateGesturesStillWork() {
        val sp = floatArrayOf(560f, 2120f)
        val d = TouchReplay.DP
        // the lift comes 10 ms after the last move, where the finger was (as Android reports it)
        fun lifted(p: List<FloatArray>) = p + floatArrayOf(p.last()[0], p.last()[1], p.last()[2] + 10f)
        fun path(from: FloatArray, vararg pts: Float): List<FloatArray> =
            lifted(listOf(floatArrayOf(from[0], from[1], 0f)) + pts.toList().chunked(3).map { floatArrayOf(from[0] + it[0] * d, from[1] + it[1] * d, it[2]) })
        fun linear(from: FloatArray, dxDp: Float, dyDp: Float, holdMs: Int, ms: Int, step: Int = 4): List<FloatArray> {
            val out = arrayListOf(floatArrayOf(from[0], from[1], 0f))
            var t = holdMs
            while (t <= holdMs + ms) { val f = (t - holdMs).toFloat() / ms; out += floatArrayOf(from[0] + f * dxDp * d, from[1] + f * dyDp * d, t.toFloat()); t += step }
            return lifted(out)
        }
        // hold, then drag 12 dp left: one step (SG2), no space
        assertEquals(listOf("CURSOR-1"), TouchReplay(Build.R13H).run(path(sp, 0f, 0f, 120f, 0f, 0f, 230f, -6f, 0f, 260f, -12f, 0f, 300f)))
        // a quick 30 dp drag in 100 ms: three steps
        assertEquals(listOf("CURSOR-3"), TouchReplay(Build.R13H).run(linear(sp, -30f, 0f, 0, 100)))
        // a slow long drag (200 dp in 900 ms): every step arrives, never more than 3 a sample
        val slow = TouchReplay(Build.R13H)
        assertEquals(listOf("CURSOR+22"), slow.run(linear(sp, 200f, 0f, 0, 900)))
        assertTrue(slow.maxStepsPerSample <= HeldKey.MAX_CURSOR_STEPS)
        // a deliberate 70 dp swipe up in 120 ms: the flick with the switch on, nothing with it off
        assertEquals(listOf("FLICK"), TouchReplay(Build.R13H, flick = true).run(linear(sp, 0f, -70f, 0, 120)))
        assertEquals(emptyList<String>(), TouchReplay(Build.R13H, flick = false).run(linear(sp, 0f, -70f, 0, 120)))
        // ⌫'s leftward word swipe: 40 dp in 120 ms, and a brisk 40 dp in 60 ms
        val bs = TouchReplay.center("⌫")
        assertEquals(listOf("⌫", "DELWORD"), TouchReplay(Build.R13H).run(linear(bs, -40f, 0f, 0, 120)))
        assertEquals(listOf("⌫", "DELWORD"), TouchReplay(Build.R13H).run(linear(bs, -40f, 0f, 0, 60)))
        // a letter slid onto its neighbour still re-targets (r13d roll-over / slide)
        val e = TouchReplay.center("e")
        assertEquals(listOf("r"), TouchReplay(Build.R13H).run(linear(e, 60f / d, 0f, 30, 60)))
        // JG5: a held cursor drag fed one implausible sample moves 3 steps, the excess is dropped, not carried
        val h = HeldKey(KeyType.SPACE, 0f, 0f, 0L, d, flick = false)
        h.move(30 * d, 0f, 50, longFired = false)
        val acts = h.move(330 * d, 0f, 54, longFired = false)
        assertEquals(listOf(HeldKey.Act.Cursor(3)), acts)
        assertEquals(emptyList<HeldKey.Act>(), h.move(330 * d, 0f, 58, longFired = false))
    }

    // ── fixtures ───────────────────────────────────────────────────────────────────────────────────────────────
    class Trace(val session: String, val dur: Int, val path: List<FloatArray>, val msg: String?, val intent: String?) {
        /** The dp path from its space-bar down point as screen px (a down point in the middle of the space bar). */
        fun px(): List<FloatArray> {
            val x0 = 560f; val y0 = 2120f
            val pts = path.map { floatArrayOf(x0 + it[0] * TouchReplay.DP, y0 + it[1] * TouchReplay.DP, it[2]) }
            val last = pts.last()
            return pts + floatArrayOf(last[0], last[1], maxOf(dur.toFloat(), last[2]))
        }
    }

    class Jump(val session: String, val path: List<FloatArray>)

    companion object {
        val spaceTraces: List<Trace> by lazy {
            val root = MiniJson.parse(File("src/test/resources/r13f/space-traces.json").readText()) as Map<*, *>
            (root["sessions"] as Map<*, *>).flatMap { (s, list) ->
                (list as List<*>).map { e ->
                    val m = e as Map<*, *>
                    Trace(s as String, (m["dur_ms"] as Number).toInt(),
                        (m["path"] as List<*>).map { p -> (p as List<*>).map { (it as Number).toFloat() }.toFloatArray() },
                        m["msg"] as String?, m["intent"] as String?)
                }
            }
        }

        val jumps: List<Jump> by lazy {
            (TouchReplay.GEO["jumps"] as List<*>).map { e ->
                val m = e as Map<*, *>
                val path = (m["path"] as List<*>).map { p -> (p as List<*>).map { (it as Number).toFloat() }.toFloatArray() }
                val dur = (m["dur_ms"] as Number).toFloat()
                Jump(m["session"] as String, path + floatArrayOf(path.last()[0], path.last()[1], dur))
            }
        }

        /** The s4 merge's samples as fractions of its jump (from its last resting sample to where it settled). */
        fun transplant(from: FloatArray, to: FloatArray): List<FloatArray> {
            val j = jumps.single { it.session == "s4" }.path
            val a = j[3] // +79 ms: the last sample on the n (the contact collapsing)
            val b = j[10] // +108 ms: settled on the c
            val len = TouchReplay.dist(a, b)
            return j.mapIndexed { i, p ->
                when {
                    i <= 3 -> floatArrayOf(from[0] + (p[0] - j[0][0]), from[1] + (p[1] - j[0][1]), p[2])
                    i >= 10 -> floatArrayOf(to[0] + (p[0] - b[0]), to[1] + (p[1] - b[1]), p[2])
                    else -> {
                        val f = (TouchReplay.dist(a, p) / len).coerceIn(0f, 1f)
                        floatArrayOf(from[0] + f * (to[0] - from[0]), from[1] + f * (to[1] - from[1]), p[2])
                    }
                }
            }
        }

        private fun round2(v: Float) = Math.round(v * 100) / 100.0

        @Volatile var synthetic: Map<String, Any?>? = null
        @Volatile var guardScan: Map<String, Any?>? = null

        @Synchronized fun writeReplay() {
            val j = jumps.single { it.session == "s4" }
            val doc = linkedMapOf<String, Any?>(
                "what" to "r13h (G2): the owner's raw traces through 0.12.0's key-touch rules (r12) and this build's (r13h): " +
                    "the s4 merge, the s4 jump's shape moved onto space taps / letters / ⌫ (synthetic), and the guard over every " +
                    "moving keyboard contact of s1-s4. Keys committed, FLICK, DELWORD, CURSOR±n (caret steps).",
                "s4_merge" to linkedMapOf("r12" to TouchReplay(Build.R12).run(j.path).joinToString(" "),
                    "r13h" to TouchReplay(Build.R13H).run(j.path).joinToString(" ")),
                "synthetic" to synthetic, "guard_scan" to guardScan,
                "thresholds" to linkedMapOf("jump_keys" to PointerGuard.JUMP_KEYS, "window_ms" to PointerGuard.WINDOW_MS,
                    "long_jump_keys" to PointerGuard.LONG_JUMP_KEYS, "long_window_ms" to PointerGuard.LONG_WINDOW_MS,
                    "fast_keys" to PointerGuard.FAST_KEYS, "max_cursor_steps_per_sample" to HeldKey.MAX_CURSOR_STEPS),
            )
            File("build/r13h").mkdirs()
            File("build/r13h/replay.json").writeText(json(doc))
        }

        fun json(x: Any?): String = when (x) {
            null -> "null"
            is String -> MiniJson.quote(x)
            is Boolean -> x.toString()
            is Number -> x.toString()
            is Map<*, *> -> x.entries.joinToString(",", "{", "}") { (k, v) -> MiniJson.quote(k.toString()) + ":" + json(v) }
            is Iterable<*> -> x.joinToString(",", "[", "]") { json(it) }
            else -> MiniJson.quote(x.toString())
        }
    }
}
