package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Failure modes T1–T9, P1–P3 (docs/failure-modes.md, round 10). */
class Round10TemasTest {

    /** A real r9 store as SettingsRepo wrote it: four profiles, user edits in several of them. */
    private fun r9Store(active: String = "escritura"): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["v"] = "1"; m["active"] = active; m["order"] = "noche,dia,juego,escritura"
        m["clip.history"] = "false"; m["clip.purge"] = "true"; m["update.auto"] = "false"
        fun p(id: String, name: String, icon: String, s: KbSettings) {
            m["p.$id.name"] = name; m["p.$id.icon"] = icon
            for ((k, v) in ProfileCodec.fields(s)) m["p.$id.$k"] = v
        }
        p("noche", "Mi noche", "☾", KbSettings(theme = "amoled", accent = ColorMath.parseHex("#e2735a"), lang = Lang.EN))
        p("dia", "Día", "☀", KbSettings(theme = "sepia", altTheme = "slate"))
        p("juego", "Juego", "◆", KbSettings(theme = "arcade", hideTopRow = true, suggest = false, popups = false, cap = KeyCap.FLAT, heightScale = 0.9f, density = Density.TIGHT, autoCap = false, doubleSpace = false))
        p("escritura", "Escritura", "✎", KbSettings(theme = "slate", font = KeyFont.HUMAN, sound = true, soundPack = SoundPack.THOCK, haptics = false, longPressMs = 500))
        return m
    }

    @Test fun v1TemasKeepEveryLookNameAndIcon() { // T1
        val st = ProfileCodec.decode(r9Store())
        assertEquals(listOf("noche", "dia", "juego", "escritura"), st.temas.map { it.id })
        val noche = st.byId("noche")!!
        assertEquals("Mi noche", noche.name)
        assertEquals("amoled", noche.look.theme)
        assertEquals(ColorMath.parseHex("#e2735a"), noche.look.accent)
        assertEquals("slate", st.byId("dia")!!.look.altTheme)
        assertEquals(KeyFont.HUMAN, st.byId("escritura")!!.look.font)
        // device-wide switches carried over
        assertEquals(ClipSettings(history = false, purgeHour = true), st.clip)
        assertFalse(st.autoUpdateCheck)
    }

    @Test fun v1ActiveProfileBehaviorBecomesThePhoneBehavior() { // T2
        val before = r9Store("escritura")
        val st = ProfileCodec.decode(before)
        assertEquals("escritura", st.active)
        assertEquals(Mode.NONE, st.mode)
        val s = st.settings
        assertTrue(s.sound); assertEquals(SoundPack.THOCK, s.soundPack)
        assertFalse(s.haptics); assertEquals(500, s.longPressMs)
        // same effective keyboard as r9 for the active profile
        val r9Effective = KbSettings(theme = "slate", font = KeyFont.HUMAN, sound = true, soundPack = SoundPack.THOCK, haptics = false, longPressMs = 500)
        assertEquals(r9Effective, s)
        // a different active profile → that profile's behavior
        assertEquals(Lang.EN, ProfileCodec.decode(r9Store("noche")).settings.lang)
    }

    @Test fun v1JuegoActiveBecomesTheGameModeNotTheUserChoice() { // T3
        val st = ProfileCodec.decode(r9Store("juego"))
        assertEquals("juego", st.active) // the arcade look stays
        assertEquals(Mode.GAME, st.mode)
        val s = st.settings
        assertFalse(s.suggest); assertTrue(s.hideTopRow); assertFalse(s.popups)
        assertEquals("arcade", s.theme)
        // turning the mode off gives back a full keyboard (behavior of the first other profile)
        val off = st.withMode(Mode.NONE).settings
        assertTrue(off.suggest); assertFalse(off.hideTopRow); assertTrue(off.popups)
        assertEquals(Lang.EN, off.lang) // noche's behavior
    }

    @Test fun partialAndCorruptV1FallsBackPerField() { // T4
        val raw = mapOf<String, Any?>("p.noche.theme" to "neon", "p.noche.heightScale" to "NaN", "p.x!.theme" to "lab", "p.dia.sound" to 3)
        val st = ProfileCodec.decode(raw)
        assertEquals(listOf("noche", "dia", "juego", "escritura"), st.temas.map { it.id })
        assertEquals("lab", st.byId("noche")!!.look.theme)
        assertEquals(1f, st.byId("noche")!!.look.heightScale, 0f)
        assertEquals("paper", st.byId("dia")!!.look.theme)
        assertEquals("noche", st.active)
        // unknown ids in order still load with defaults; junk order falls back
        val custom = ProfileCodec.decode(mapOf("v" to "1", "order" to "mio", "p.mio.theme" to "hc", "active" to "mio"))
        assertEquals("hc", custom.settings.theme)
        assertEquals(listOf("noche", "dia", "juego", "escritura"), ProfileCodec.decode(mapOf("order" to ",,;", "p.dia.theme" to "lab")).temas.map { it.id })
    }

    @Test fun firstV2SaveDropsEveryLegacyKey() { // T5
        val migrated = ProfileCodec.decode(r9Store())
        val written = ProfileCodec.encode(migrated)
        assertEquals("2", written["v"])
        assertFalse(written.keys.any { ProfileCodec.isLegacyKey(it) })
        // what SettingsRepo.save does: remove owned keys the encoder no longer writes
        val prefs = r9Store().toMutableMap()
        prefs.keys.filter { it !in written && ProfileCodec.owns(it) }.forEach { prefs.remove(it) }
        prefs.putAll(written)
        assertFalse(prefs.keys.any { it.startsWith("p.") })
        assertEquals(migrated, ProfileCodec.decode(prefs))
        // a v2 store with leftover p.* keys is NOT migrated again (v=2 wins)
        val edited = migrated.updatePhone { it.copy(haptics = true) }
        val mixed = r9Store() + ProfileCodec.encode(edited)
        assertTrue(ProfileCodec.decode(mixed).settings.haptics)
        // keys of other subsystems are not owned
        assertFalse(ProfileCodec.owns("update.auto")); assertFalse(ProfileCodec.owns("clip.history")); assertFalse(ProfileCodec.owns("mode"))
    }

    @Test fun lookAndBehaviorEditsLandInTheRightPlace() { // T6
        val st = ProfileCodec.seed()
            .edit { it.copy(accent = ColorMath.parseHex("#5b9cf0"), heightScale = 1.2f, haptics = false, lang = Lang.EN) }
        assertEquals(1.2f, st.byId("resyst")!!.look.heightScale, 0f)
        assertEquals(1f, st.withTema("arcade").byId("arcade")!!.look.heightScale.coerceAtLeast(1f), 0f)
        val arcade = st.withTema("arcade").settings
        assertEquals("arcade", arcade.theme)
        assertEquals(null, arcade.accent) // look did not leak
        assertFalse(arcade.haptics); assertEquals(Lang.EN, arcade.lang) // behavior followed
        // phone never stores a look of its own
        assertEquals(Look(), Look.of(st.phone))
        assertEquals(st, ProfileCodec.decode(ProfileCodec.encode(st)))
    }

    @Test fun modeOverridesAreNeverStored() { // T7
        val st = ProfileCodec.seed().withMode(Mode.CODE)
        assertFalse(st.settings.suggest)
        // the settings screen edits base: changing sound under Código keeps suggest as the user had it
        val edited = st.edit { it.copy(sound = true) }
        assertTrue(edited.phone.suggest)
        assertTrue(edited.withMode(Mode.NONE).settings.suggest)
        assertEquals(KeyFont.BRAND, edited.activeTema.look.font) // CODE's mono font not written into the tema
        val back = ProfileCodec.decode(ProfileCodec.encode(edited))
        assertEquals(Mode.CODE, back.mode)
        assertEquals(edited, back)
    }

    @Test fun modesOnlyRestrictTypingAids() { // T8
        val aids: (KbSettings) -> List<Boolean> = { listOf(it.suggest, it.personal, it.spaceCorrects, it.autoCap, it.doubleSpace) }
        val base = listOf(KbSettings(), KbSettings(suggest = false, personal = false, spaceCorrects = false, autoCap = false, doubleSpace = false))
        for (m in Mode.values()) for (b in base) {
            val before = aids(b); val after = aids(m.apply(b))
            for (i in before.indices) assertTrue("$m widened aid $i", !after[i] || before[i])
        }
        assertFalse("code never learns", Mode.CODE.apply(KbSettings()).personal)
        assertTrue(Mode.values().all { it.summary.isNotBlank() && it.label.isNotBlank() })
        assertEquals(Mode.GAME, Mode.byId("game")); assertEquals(null, Mode.byId("juego"))
        // GAME keeps height within the codec's bounds
        assertTrue(Mode.GAME.apply(KbSettings(heightScale = 0.8f)).heightScale >= ProfileCodec.HEIGHT_MIN)
    }

    @Test fun storedV2KeysAreExactlyTheControlledOnes() { // T9
        val enc = ProfileCodec.encode(ProfileCodec.seed()).keys
        val temaKeys = enc.filter { it.startsWith("t.resyst.") }.map { it.removePrefix("t.resyst.") }.toSet() - setOf("name", "icon")
        assertEquals(Look.FIELDS, temaKeys)
        val phoneKeys = enc.filter { it.startsWith(ProfileCodec.PHONE) }.toSet()
        assertTrue(phoneKeys.isNotEmpty())
        assertTrue(phoneKeys.none { it.removePrefix(ProfileCodec.PHONE) in Look.FIELDS })
        // the phone + tema fields partition KbSettings exactly
        assertEquals(ProfileCodec.fields(KbSettings()).keys, Look.FIELDS + phoneKeys.map { it.removePrefix(ProfileCodec.PHONE) })
    }

    // ── pages ───────────────────────────────────────────────────────────
    @Test fun dependentsStayVisibleIndentedAndDisabled() { // P1
        val escritura = SettingsIA.page("escritura")!!
        val off = SettingsIA.rows(escritura) { it != Ctl.SUGGEST }
        val sc = off.first { it.ctl == Ctl.SPACE_CORRECTS }
        assertEquals(1, sc.indent); assertFalse(sc.enabled)
        assertTrue(off.first { it.ctl == Ctl.AUTO_CAP }.enabled)
        assertEquals(escritura.controls.toSet(), off.map { it.ctl }.toSet()) // nothing hidden
        assertTrue(SettingsIA.rows(escritura) { true }.all { it.enabled })
        // the inverted switch: Fila superior is enabled when "Ocultar" is OFF
        val teclas = SettingsIA.page("teclas")!!
        assertTrue(SettingsIA.rows(teclas) { false }.first { it.ctl == Ctl.TOP_ROW }.enabled)
        assertFalse(SettingsIA.rows(teclas) { it == Ctl.HIDE_TOP_ROW }.first { it.ctl == Ctl.TOP_ROW }.enabled)
    }

    @Test fun destructiveRowsComeLast() { // P2
        for (p in SettingsIA.PAGES) {
            val rows = SettingsIA.rows(p) { true }
            val firstDanger = rows.indexOfFirst { it.ctl.destructive }
            if (firstDanger >= 0) assertTrue(p.id, rows.drop(firstDanger).all { it.ctl.destructive })
        }
    }

    @Test fun activityRendersEveryControlAndTagsIt() { // P3 (source guard; the E2E walks the real tree)
        val src = listOf(File("src/main/kotlin/com/resyst/vk/settings/SettingsActivity.kt"), File("app/src/main/kotlin/com/resyst/vk/settings/SettingsActivity.kt"))
            .first { it.exists() }.readText()
        assertFalse("the r7 flat list is gone", src.contains("Restablecer los 4 perfiles"))
        assertTrue(src.contains("SettingsIA.PAGES") || src.contains("SettingsIA.page("))
        assertTrue(src.contains("tag = ctl.name") || src.contains("tag = c.name"))
        // the renderer is an exhaustive `when` over Ctl: a new Ctl without a widget fails to compile
        assertTrue(src.contains("when (ctl) {"))
    }
}
