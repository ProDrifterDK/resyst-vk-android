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
        assertEquals(listOf("lab", "slate", "arcade", "paper", "hc"), Themes.ALL.map { it.id })
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

    // R1
    @Test fun seedHasFourProfiles() {
        val st = ProfileCodec.seed()
        assertEquals(listOf("noche", "dia", "juego", "escritura"), st.profiles.map { it.id })
        assertEquals(listOf("lab", "paper", "arcade", "slate"), st.profiles.map { it.settings.theme })
        assertEquals("noche", st.active)
        val juego = st.byId("juego")!!.settings
        assertEquals(TopRow.NONE, juego.topRow)
        assertEquals(false, juego.suggest)
        assertTrue(juego.heightScale < 1f)
        val esc = st.byId("escritura")!!.settings
        assertEquals(true, esc.sound)
        assertEquals(SoundPack.THOCK, esc.soundPack)
    }

    // R2 + R4
    @Test fun corruptValuesAreSanitized() {
        val map = ProfileCodec.encode(ProfileCodec.seed()).toMutableMap<String, Any?>()
        map["active"] = "ghost"
        map["p.noche.theme"] = "neon"
        map["p.noche.heightScale"] = "9.5"
        map["p.noche.shape"] = "TRIANGLE"
        map["p.noche.volume"] = "abc"
        map["p.noche.accent"] = "#zzz"
        map["p.noche.longPressMs"] = "5"
        val st = ProfileCodec.decode(map)
        assertEquals("noche", st.active)
        val s = st.byId("noche")!!.settings
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

    // R3 + R5
    @Test fun editsAreIsolatedAndRoundTrip() {
        val st = ProfileCodec.seed()
            .update("dia") { it.copy(accent = ColorMath.parseHex("#e2735a"), shape = KeyShape.ROUND, sound = true) }
            .withActive("dia")
        assertEquals(ProfileCodec.seed().byId("noche"), st.byId("noche"))
        val back = ProfileCodec.decode(ProfileCodec.encode(st))
        assertEquals(st, back)
        assertEquals("dia", back.active)
        assertEquals(KeyShape.ROUND, back.activeProfile.settings.shape)
    }

    @Test fun nextProfileCycles() {
        val st = ProfileCodec.seed()
        assertEquals("dia", st.nextId())
        assertEquals("noche", st.withActive("escritura").nextId())
    }
}
