package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * r12 — the pure halves of SA2 (the tones count while the catalog loads) and UR1–UR3 (the update
 * origin across a restore, the orphan `autoAt`). Ids map to docs/failure-modes.md. The device
 * halves are scripts/e2e_r12.py.
 */
class Round12DetailsTest {

    // ── SA2: the tones count never lies while settings loads the catalog ──
    @Test fun loadingTonesAreNeitherZeroNorEmpty() {
        val loading = PersonalSummary.Counts(0, 0, 0, null)
        assertFalse("tones file has content, catalog not parsed yet: the wipe row must stay enabled", loading.empty)
        val text = PersonalSummary.text(loading)
        assertFalse(text, text.startsWith("Nada aprendido"))
        assertTrue(text, text.startsWith("… tonos"))
        assertFalse(text, text.contains("0 tonos"))
        assertEquals("12 palabras · … tonos · nada sale del teléfono", PersonalSummary.text(PersonalSummary.Counts(12, 0, 0, null)))
        // once known, the r11a-fix wording is unchanged
        assertEquals("2 emojis recientes · 1 tono · nada sale del teléfono", PersonalSummary.text(PersonalSummary.Counts(0, 0, 2, 1)))
        assertTrue(PersonalSummary.Counts(0, 0, 0, 0).empty)
    }

    @Test fun settingsNeverParsesTheCatalogOnItsThread() { // SA1: the r11a-fix recheck finding
        val src = File("src/main/kotlin/com/resyst/vk/settings/SettingsActivity.kt").readText()
        assertFalse("settings calls the parsing EmojiAsset.catalog(...)", src.contains("EmojiAsset.catalog("))
        assertTrue(src.contains("EmojiAsset.cached()") && src.contains("EmojiAsset.load("))
    }

    // ── UR1/UR2: who asked for the update answer, across a restore ──
    @Test fun aManualAnswerRestoresAsManual() { // UR1: restore() hardcoded checkedAuto = true
        assertEquals(UpdateOrigin.Origin.USER, UpdateOrigin.restored(false))
        assertEquals("Comprobado", UpdateOrigin.checkedLabel(UpdateOrigin.restored(false)))
        assertEquals(UpdateOrigin.Origin.OPEN, UpdateOrigin.restored(true))
        assertEquals("Comprobado al abrir el teclado", UpdateOrigin.checkedLabel(UpdateOrigin.restored(true)))
        assertEquals(UpdateOrigin.Origin.USER, UpdateOrigin.of(auto = false))
        assertEquals(UpdateOrigin.Origin.OPEN, UpdateOrigin.of(auto = true))
    }

    @Test fun aManifestStoredWithoutTheFlagClaimsNoOrigin() { // UR2: 0.7.1 / 0.8.0 stored it from both paths
        assertEquals(UpdateOrigin.Origin.UNKNOWN, UpdateOrigin.restored(null))
        assertEquals(UpdateOrigin.Origin.UNKNOWN, UpdateOrigin.restored("true")) // not a boolean pref
        assertEquals("Comprobado", UpdateOrigin.checkedLabel(UpdateOrigin.Origin.UNKNOWN))
    }

    @Test fun theUpdaterStoresAndRestoresTheOrigin() { // UR1 wiring
        val src = File("src/main/kotlin/com/resyst/vk/settings/Updater.kt").readText()
        val restore = src.substringAfter("private fun restore(").substringBefore("\n    }\n")
        assertFalse("restore() still hardcodes the origin", restore.contains("checkedAuto = true") || restore.contains("Origin.OPEN"))
        assertTrue(restore.contains("UpdateOrigin.restored(p.all[UpdateOrigin.KEY])"))
        val remember = src.substringAfter("private fun remember(").substringBefore("\n    }\n")
        assertTrue("the origin is stored next to the manifest", remember.contains("putBoolean(UpdateOrigin.KEY, auto)"))
        assertTrue("forgotten with it", remember.contains("remove(UpdateOrigin.KEY)"))
        val fetch = src.substringAfter("private fun fetch(").substringBefore("\n    // ──")
        // a user tap that promoted an automatic check is the user's (asUser is decided on the main thread)
        assertTrue(fetch.contains("remember(app, decision, json, auto = !asUser)"))
        assertTrue(fetch.indexOf("val asUser") < fetch.indexOf("remember(app, decision, json, auto = !asUser)"))
    }

    // ── UR3: the orphan autoAt goes, nothing else ──
    @Test fun onlyTheOrphanIsCleanedUp() {
        val v070 = setOf("autoAt", "attemptAt", "attemptOk", "id", "manifest", "dismissed", "availManifest", "availAt", "availAuto")
        assertEquals(listOf("autoAt"), UpdateOrigin.cleanup(v070))
        assertEquals(emptyList<String>(), UpdateOrigin.cleanup(v070 - "autoAt"))
        assertEquals(emptyList<String>(), UpdateOrigin.cleanup(emptySet()))
        val src = File("src/main/kotlin/com/resyst/vk/settings/Updater.kt").readText()
        assertTrue(src.substringAfter("private fun restore(").substringBefore("\n    }\n").contains("UpdateOrigin.cleanup(p.all.keys)"))
    }

    /** 0.8.0 (9) is published; r12 ships as 0.8.1 (10): never reuse a published code. */
    @Test fun versionIs081() {
        val g = File("build.gradle.kts").readText()
        assertTrue(g.contains("?.toInt() ?: 10"))
        assertTrue(g.contains("?: \"0.8.1\""))
    }
}
