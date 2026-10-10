package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Failure modes QS1–QS4, QP1–QP5, QO1–QO2, QI1–QI2 (docs/failure-modes.md, round 10 quick panel). */
class Round10QuickTest {

    private val view = File("src/main/kotlin/com/resyst/vk/ime/KeyboardView.kt").readText()
    private val icons = File("src/main/kotlin/com/resyst/vk/ime/KeyIcons.kt").readText()
    private val seed = ProfileCodec.seed()

    // ── strip ───────────────────────────────────────────────────────────
    @Test fun stripKeepsOnlyTypingAids() { // QS1
        val kinds = view.substringAfter("enum class StripKind {").substringBefore("}")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        // r11b (authority: the r11b brief, GIF search box): GIF_BACK / GIF_FIELD / GIF_GO replace the whole strip only
        // while the GIF search box is open; the typing strip itself still holds only typing aids
        assertEquals(setOf("PASTE", "SUGGESTION", "SETTINGS", "DAYNIGHT", "UPDATE", "UPDATE_X", "NO_MEMORY", "GIF_BACK", "GIF_FIELD", "GIF_GO"), kinds)
        val layout = view.substringAfter("private fun layoutStrip()").substringBefore("var l = 6 * dp")
        assertTrue("the GIF search strip returns before any typing-strip item is added", layout.substringAfter("gifSearch?.let").substringBefore("StripKind.SETTINGS").contains("return"))
        assertFalse("the sun/moon is opt-in", KbSettings().dayNightChip)
        assertFalse(seed.settings.dayNightChip)
    }

    @Test fun pasteTakesOneSuggestionSlot() { // QS2
        val s = listOf("está", "estaba", "estar", "estás")
        assertEquals(listOf("está", "estaba", "estar"), StripPlan.suggestions(s, paste = false))
        assertEquals(listOf("está", "estaba"), StripPlan.suggestions(s, paste = true))
        assertEquals(listOf("sí"), StripPlan.suggestions(listOf("sí"), paste = true))
        assertTrue(StripPlan.suggestions(emptyList(), paste = true).isEmpty())
        assertEquals(Bar.LIMIT, StripPlan.suggestions(s, paste = false).size)
    }

    @Test fun migratedStoresLeaveTheStripClean() { // QS3
        val v1 = LinkedHashMap<String, Any?>()
        v1["v"] = "1"; v1["active"] = "noche"; v1["order"] = "noche,dia"
        for (id in listOf("noche", "dia")) for ((k, v) in ProfileCodec.fields(KbSettings(dayNightChip = true))) v1["p.$id.$k"] = v
        assertFalse(ProfileCodec.decode(v1).settings.dayNightChip)
        // an explicit v2 choice is kept
        val on = seed.updatePhone { it.copy(dayNightChip = true) }
        assertTrue(ProfileCodec.decode(ProfileCodec.encode(on)).settings.dayNightChip)
    }

    @Test fun gearStillReachesSettingsAndTheUpdateDot() { // QS4
        assertTrue(view.contains("StripKind.SETTINGS -> listener?.onQuickPanel()"))
        assertTrue("long-press ⚙ = Settings", Regex("""StripKind\.SETTINGS\)?\s*\{?[^\n]*onOpenSettings""").containsMatchIn(view) ||
            view.contains("strip.kind == StripKind.SETTINGS") && view.contains("listener?.onOpenSettings()"))
        assertTrue(view.contains("gearDot"))
    }

    // ── quick panel ─────────────────────────────────────────────────────
    @Test fun tilesChangeExactlyWhatTheySay() { // QP1
        val st = seed.updatePhone { it.copy(sound = true, lang = Lang.EN) }
        val dn = Quick.apply(st, QuickAction.DAY_NIGHT)
        assertNotEquals(Themes.byId(st.settings.theme).dark, Themes.byId(dn.settings.theme).dark)
        assertEquals("only the active tema", st.temas.drop(1), dn.temas.drop(1))
        assertEquals(st.phone, dn.phone)

        val tema = Quick.apply(st, QuickAction.TEMA)
        assertEquals(st.nextTemaId(), tema.active)
        assertEquals("behavior is phone-wide", st.phone, tema.phone)
        assertEquals(st.temas, tema.temas)

        val code = Quick.apply(st, QuickAction.MODE)
        assertEquals(Mode.CODE, code.mode)
        assertEquals("a mode never writes into the phone settings", st.phone, code.phone)
        assertEquals(st.temas, code.temas)

        val tall = Quick.apply(st, QuickAction.HEIGHT)
        assertEquals(1.1f, tall.activeTema.look.heightScale, 0.0001f)
        assertEquals(st.temas.drop(1), tall.temas.drop(1))
        for (h in Quick.HEIGHTS) assertTrue(h in ProfileCodec.HEIGHT_MIN..ProfileCodec.HEIGHT_MAX)

        val one = Quick.apply(st, QuickAction.ONE_HAND)
        assertEquals(OneHand.RIGHT, one.settings.oneHanded)
        assertEquals(st.temas, one.temas)
        for (a in listOf(QuickAction.CLIPBOARD, QuickAction.EDIT, QuickAction.SETTINGS)) assertEquals(a.name, st, Quick.apply(st, a))
    }

    @Test fun everyCycleComesBack() { // QP2
        fun turn(a: QuickAction, n: Int): ProfileStore = (1..n).fold(seed) { s, _ -> Quick.apply(s, a) }
        assertEquals(seed, turn(QuickAction.TEMA, seed.temas.size))
        assertEquals(seed, turn(QuickAction.MODE, Mode.values().size))
        assertEquals(seed, turn(QuickAction.ONE_HAND, OneHand.values().size))
        // day/night returns to the same colors (altTheme then remembers the partner, T8-3)
        assertEquals(seed.activeTema.look.theme, turn(QuickAction.DAY_NIGHT, 2).activeTema.look.theme)
        assertEquals(seed.activeTema.look.heightScale, turn(QuickAction.HEIGHT, Quick.HEIGHTS.size).activeTema.look.heightScale, 0.0001f)
        // off-step heights snap to the next step; the top wraps to the bottom
        assertEquals(1.0f, Quick.nextHeight(0.95f), 0.0001f)
        assertEquals(0.9f, Quick.nextHeight(0.8f), 0.0001f)
        assertEquals(0.9f, Quick.nextHeight(1.3f), 0.0001f)
        assertEquals(0.9f, Quick.nextHeight(1.2f), 0.0001f)
    }

    @Test fun clipboardTileIsClosedWhereTheHistoryIs() { // QP3
        val secret = FieldPolicy(FieldKind.PASSWORD, suggestions = false, incognito = false)
        val text = FieldPolicy(FieldKind.TEXT, suggestions = true, incognito = false)
        fun clipTile(st: ProfileStore, p: FieldPolicy) = Quick.tiles(st, p).first { it.action == QuickAction.CLIPBOARD }
        assertTrue(clipTile(seed, text).enabled)
        val s = clipTile(seed, secret)
        assertFalse(s.enabled)
        assertTrue(s.desc, s.desc.contains("no disponible"))
        val off = seed.copy(clip = ClipSettings(history = false))
        assertFalse(clipTile(off, text).enabled)
        assertNotEquals(s.state, clipTile(off, text).state) // says why
    }

    @Test fun tilesAreEightNamedAndDescribed() { // QP4
        val tiles = Quick.tiles(seed, FieldPolicy(FieldKind.TEXT, suggestions = true, incognito = false))
        assertEquals(QuickAction.values().toList(), tiles.map { it.action })
        assertEquals(8, tiles.size)
        for (t in tiles) {
            assertTrue(t.action.name, t.label.isNotBlank() && t.state.isNotBlank())
            assertTrue(t.desc, t.desc.startsWith(t.label))
        }
        assertEquals("Ninguno", tiles.first { it.action == QuickAction.MODE }.state)
        assertEquals("100 %", tiles.first { it.action == QuickAction.HEIGHT }.state)
        assertEquals(seed.activeTema.name, tiles.first { it.action == QuickAction.TEMA }.state)
        // geometry: 48 dp floor for a tile on the smallest supported keyboard (portrait 4 rows × 0.8)
        assertTrue(Quick.MIN_TILE_DP >= 48f)
    }

    @Test fun anAnnouncedUpdateStaysReachable() { // QP5
        assertNull(Quick.updateLine(null))
        val l = Quick.updateLine("0.5.0")!!
        assertTrue(l, l.contains("0.5.0"))
    }

    // ── one hand ────────────────────────────────────────────────────────
    @Test fun oneHandGeometry() { // QO1
        val dp = 2.75f
        for (wDp in listOf(320f, 360f, 411f, 600f)) {
            val w = wDp * dp
            val off = OneHand.split(w, OneHand.OFF, dp)
            assertEquals(0f, off.keysLeft, 0.01f); assertEquals(w, off.keysRight, 0.01f); assertEquals(0f, off.railWidth, 0.01f)
            for (side in listOf(OneHand.LEFT, OneHand.RIGHT)) {
                val s = OneHand.split(w, side, dp)
                val keys = s.keysRight - s.keysLeft
                assertTrue("$wDp $side keys ${keys / w}", keys >= w * OneHand.MIN_FRACTION - 0.01f && keys < w)
                assertTrue("$wDp $side rail ${s.railWidth / dp}", s.railWidth >= OneHand.RAIL_MIN_DP * dp - 0.01f)
                assertEquals(w, keys + s.railWidth, 0.01f) // no overlap, no gap, no overflow
                if (side == OneHand.LEFT) { assertEquals(0f, s.keysLeft, 0.01f); assertEquals(s.keysRight, s.railLeft, 0.01f) }
                else { assertEquals(w, s.keysRight, 0.01f); assertEquals(0f, s.railLeft, 0.01f) }
            }
        }
        // too narrow for a 48 dp rail at ≥ 80 % keys: stays full width
        val tiny = OneHand.split(200 * dp, OneHand.LEFT, dp)
        assertEquals(0f, tiny.railWidth, 0.01f)
    }

    @Test fun oneHandIsPhoneWideAndRoundTrips() { // QO2
        assertEquals(OneHand.OFF, KbSettings().oneHanded)
        val st = seed.updatePhone { it.copy(oneHanded = OneHand.LEFT) }
        val back = ProfileCodec.decode(ProfileCodec.encode(st))
        assertEquals(OneHand.LEFT, back.settings.oneHanded)
        assertEquals(OneHand.LEFT, back.withTema(back.nextTemaId()).settings.oneHanded)
        assertFalse(ProfileCodec.encode(st).keys.any { it.startsWith(ProfileCodec.TEMA) && it.endsWith(".oneHanded") })
        val junk = ProfileCodec.encode(seed).toMutableMap<String, Any?>().apply { put("phone.oneHanded", "UP") }
        assertEquals(OneHand.OFF, ProfileCodec.decode(junk).settings.oneHanded)
        assertEquals("teclas", SettingsIA.pageOf(Ctl.ONE_HANDED)?.id)
        assertEquals(OneHand.RIGHT, OneHand.OFF.next()); assertEquals(OneHand.LEFT, OneHand.RIGHT.next()); assertEquals(OneHand.OFF, OneHand.LEFT.next())
    }

    // ── icons ───────────────────────────────────────────────────────────
    @Test fun keysAndStripUseVectorIcons() { // QI1
        val drawn = Regex("""drawCentered\(c, "([^"]*)"""").findAll(view).map { it.groupValues[1] }.toList()
        for (g in listOf("⇧", "⬆", "⌫", "⚙", "⏎", "⌕", "➤", "⇥", "⇤", "✓")) {
            assertFalse("$g drawn as a font glyph", drawn.any { it.contains(g) })
        }
        assertFalse(view.contains("""if (shift == ShiftState.OFF) "⇧" else "⬆""""))
        for (i in listOf("Icon.SHIFT_LOCK", "Icon.SHIFT_ON", "Icon.BACKSPACE", "Icon.GEAR", "Icon.SUN", "Icon.MOON", "Icon.CLIPBOARD")) {
            assertTrue("$i unused", view.contains(i) || File("src/main/kotlin/com/resyst/vk/ime/QuickPanel.kt").readText().contains(i))
        }
        // one stroke weight for the whole family
        val widths = Regex("""strokeWidth = ([^\n]+)""").findAll(icons).map { it.groupValues[1].trim() }.toSet()
        assertEquals(setOf("s * STROKE"), widths)
    }

    @Test fun shiftStatesDifferInShape() { // QI2
        val shift = view.substringAfter("KeyType.SHIFT -> {", "").substringBefore("KeyType.ENTER")
        assertTrue(shift.contains("Icon.SHIFT_LOCK") && shift.contains("Icon.SHIFT_ON") && shift.contains("Icon.SHIFT"))
        assertTrue("lock = fill + bar", icons.contains("Icon.SHIFT_LOCK") && icons.contains("if (icon == Icon.SHIFT) stroke else fill"))
    }
}
