package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteProfilesTest {

    // P1 — values ported verbatim from the desktop theme source
    @Test fun themeValuesMatchSource() {
        val lab = Themes.byId("lab")
        assertEquals("#08080f", ColorMath.toHex(lab.bg))
        assertEquals("#c9a84c", ColorMath.toHex(lab.accent))
        assertEquals("#15151f", ColorMath.toHex(lab.key))
        assertEquals("#e9e4d6", ColorMath.toHex(lab.text))
        assertEquals("#efe9dc", ColorMath.toHex(Themes.byId("paper").bg))
        assertEquals("#3ee0cf", ColorMath.toHex(Themes.byId("arcade").accent))
        assertEquals("#7fb0ff", ColorMath.toHex(Themes.byId("slate").accent))
        assertEquals("#ffd60a", ColorMath.toHex(Themes.byId("hc").accent))
        assertEquals(listOf("lab", "slate", "arcade", "paper", "hc"), Themes.ALL.map { it.id }.take(5)) // r8 appends sepia/amoled/eink
    }

    // P2 + P3
    @Test fun accentContrastIsGuaranteed() {
        for (t in Themes.ALL) for ((acc, _) in Themes.ACCENTS) {
            val p = Palette.of(t.id, acc)
            assertTrue("${t.id} ${ColorMath.toHex(acc)}", ColorMath.contrast(p.accent, t.key) >= 4.5)
            assertTrue("ink ${t.id}", ColorMath.contrast(p.accentInk, p.accent) >= 3.0)
        }
        val dim = Palette.of("lab", ColorMath.parseHex("#202030")!!)
        assertNotEquals(ColorMath.parseHex("#202030"), dim.accent)
    }

    @Test fun textIsReadableOnKeysInEveryTheme() {
        for (t in Themes.ALL) assertTrue(t.id, ColorMath.contrast(t.text, t.key) >= 7.0)
    }

    // P4
    @Test fun unknownThemeFallsBack() {
        assertEquals("lab", Themes.byId("nope").id)
        assertEquals(Themes.byId("lab").accent, Palette.of("nope", null).accent)
    }

    // R1 (r10: temas + modos; the mode is off on a fresh install)
    @Test fun seedHasThreeTemasAndNoMode() {
        val st = ProfileCodec.seed()
        assertEquals(listOf("resyst", "arcade", "pizarra"), st.temas.map { it.id })
        assertEquals(listOf("lab", "arcade", "slate"), st.temas.map { it.look.theme })
        assertEquals("resyst", st.active)
        assertEquals(Mode.NONE, st.mode)
        assertEquals(KbSettings(), st.settings) // fresh install = the r1 defaults
        val juego = st.withMode(Mode.GAME).settings
        assertEquals(TopRow.NONE, juego.effectiveTopRow)
        assertEquals(false, juego.suggest)
        assertTrue(juego.heightScale < 1f)
        assertEquals(KeyFont.HUMAN, st.withTema("pizarra").settings.font)
    }

    // R2 + R4
    @Test fun corruptValuesAreSanitized() {
        val map = ProfileCodec.encode(ProfileCodec.seed()).toMutableMap<String, Any?>()
        map["active"] = "ghost"
        map["mode"] = "turbo"
        map["t.resyst.theme"] = "neon"
        map["t.resyst.heightScale"] = "9.5"
        map["t.resyst.shape"] = "TRIANGLE"
        map["phone.volume"] = "abc"
        map["t.resyst.accent"] = "#zzz"
        map["phone.longPressMs"] = "5"
        val st = ProfileCodec.decode(map)
        assertEquals("resyst", st.active)
        assertEquals(Mode.NONE, st.mode)
        val s = st.settings
        assertEquals("lab", s.theme)
        assertEquals(1.3f, s.heightScale, 0.0001f)
        assertEquals(KeyShape.SOFT, s.shape)
        assertEquals(0.7f, s.volume, 0.0001f)
        assertEquals(null, s.accent)
        assertEquals(150, s.longPressMs)
    }

    @Test fun emptyStorageIsTheSeed() {
        assertEquals(ProfileCodec.seed(), ProfileCodec.decode(emptyMap<String, Any?>()))
    }

    // R3 + R5: a look edit stays in its tema, a behavior edit is phone-wide; both round-trip
    @Test fun editsAreIsolatedAndRoundTrip() {
        val st = ProfileCodec.seed().withTema("arcade")
            .edit { it.copy(accent = ColorMath.parseHex("#e2735a"), shape = KeyShape.ROUND, sound = true) }
        assertEquals(ProfileCodec.seed().byId("resyst"), st.byId("resyst"))
        assertEquals(KeyShape.ROUND, st.byId("arcade")!!.look.shape)
        assertTrue("behavior is phone-wide", st.withTema("resyst").settings.sound)
        val back = ProfileCodec.decode(ProfileCodec.encode(st))
        assertEquals(st, back)
        assertEquals("arcade", back.active)
        assertEquals(KeyShape.ROUND, back.settings.shape)
    }

    @Test fun nextTemaCycles() {
        val st = ProfileCodec.seed()
        assertEquals("arcade", st.nextTemaId())
        assertEquals("resyst", st.withTema("pizarra").nextTemaId())
    }
}
