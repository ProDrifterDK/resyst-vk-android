package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Round 9 — automatic update check + update notice (A*, N*, S* in docs/failure-modes.md). */
class Round9Test {

    private fun rel(version: String, code: Int? = null) = Release(
        version = version, versionCode = code, sha256 = "a".repeat(64),
        url = "https://kv.resyst.cl/download/resyst-vk-$version.apk",
        sizeBytes = 2_000_000, size = "2.0 MB", date = "2026-10-07", minSdk = 26, minAndroid = "8.0",
    )

    private fun available(v: String) = UpdateDecision.Available(rel(v))

    // ── r9's once-per-process gate (A1–A3) is replaced by OpenCheck in r11c: Round11UpdateCheckTest ──
    @Test fun settingsNeverFetchesOnOpen() { // A4 (source guard: the screen reuses Updater.state)
        val src = File("src/main/kotlin/com/resyst/vk/settings/SettingsActivity.kt").readText()
        assertFalse("settings must not start the startup check", src.contains("autoCheck("))
        // every check() call sits behind the user's button (its quiet link + pill variants)
        val all = Regex("""Updater\.check\(""").findAll(src).count()
        val btn = src.substringAfter("private fun checkButton(").substringBefore("\n    }\n")
        assertEquals(all, Regex("""Updater\.check\(""").findAll(btn).count())
        assertTrue(all >= 1)
        assertFalse("settings must not start the automatic check", src.contains("onKeyboardShown("))
        // and the keyboard service only asks through the gate (r11c: on keyboard show, OC1)
        val ime = File("src/main/kotlin/com/resyst/vk/ime/ResystImeService.kt").readText()
        assertFalse(ime.contains("Updater.check("))
        assertEquals(1, Regex("""Updater\.onKeyboardShown\(""").findAll(ime).count())
    }

    @Test fun oneNetworkPath() { // A1/A5 + r5 promise: no second HTTP client anywhere
        val root = File("src/main/kotlin")
        val net = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readText().let { it.contains("openConnection") || it.contains("HttpsURLConnection") || it.contains("java.net.Socket") } }
            .map { it.name }.toList()
        assertEquals(listOf("Updater.kt"), net)
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("usesCleartextTraffic=\"false\""))
        assertFalse("no status-bar notifications", manifest.contains("POST_NOTIFICATIONS"))
    }

    @Test fun failedAutoCheckIsSilent() { // A5
        assertNull(UpdateNotice.autoOutcome(null)) // offline / timeout / HTTP error
        assertNull(UpdateNotice.autoOutcome(UpdateDecision.Error("invalid JSON")))
        val bad = UpdateChecker.decide("{nope", Installed(4, "0.4.0-alpha", 37))
        assertNull(UpdateNotice.autoOutcome(bad))
        // real answers are kept so settings can show them without a tap
        val up = UpdateDecision.UpToDate("0.4.0-alpha")
        assertEquals(up, UpdateNotice.autoOutcome(up))
        assertEquals(UpdateDecision.NotPublished, UpdateNotice.autoOutcome(UpdateDecision.NotPublished))
        val av = available("0.5.0")
        assertEquals(av, UpdateNotice.autoOutcome(av))
    }

    // ── keyboard chip ───────────────────────────────────────────────────
    @Test fun onlyAnAvailableUpdateIsAnnounced() { // N1
        assertNull(UpdateNotice.announce(null, null))
        assertNull(UpdateNotice.announce(UpdateDecision.UpToDate("0.4.0"), null))
        assertNull(UpdateNotice.announce(UpdateDecision.NotPublished, null))
        assertNull(UpdateNotice.announce(UpdateDecision.Error("x"), null))
        assertNull(UpdateNotice.announce(UpdateDecision.Incompatible(rel("0.5.0"), 99), null))
        assertEquals("0.5.0", UpdateNotice.announce(available("0.5.0"), null)?.version)
        assertEquals("0.5.0", UpdateNotice.announce(available("0.5.0"), "  ")?.version)
    }

    @Test fun dismissHoldsUntilANewerVersion() { // N2
        assertNull(UpdateNotice.announce(available("0.5.0"), "0.5.0"))
        assertNull("v-prefix / build metadata are the same version", UpdateNotice.announce(available("0.5.0"), "v0.5.0+3"))
        assertNull("an older manifest after a dismiss stays quiet", UpdateNotice.announce(available("0.4.1"), "0.5.0"))
        assertEquals("0.5.1", UpdateNotice.announce(available("0.5.1"), "0.5.0")?.version)
        assertEquals("0.6.0-alpha", UpdateNotice.announce(available("0.6.0-alpha"), "0.5.0")?.version)
        assertEquals("release outranks its pre-release", "0.5.0", UpdateNotice.announce(available("0.5.0"), "0.5.0-alpha")?.version)
        // unreadable names: only the same string is silenced
        assertNull(UpdateNotice.announce(available("next"), "next"))
        assertNotNull(UpdateNotice.announce(available("next2"), "next"))
    }

    @Test fun chipLabelsNeverCutTheVersion() { // N3
        val v = "0.12.3-alpha.2"
        for (l in UpdateNotice.labels(v)) assertTrue(l.toString(), l.title.contains(v))
        // a fake 7 px/char font, 0.85× for the stacked lines
        fun w(t: String, two: Boolean) = t.length * if (two) 6f else 7f
        val wide = UpdateNotice.fit("0.5.0", 2000f, ::w, ::w)!!
        assertEquals("Resyst VK 0.5.0 disponible", wide.title)
        assertEquals(" · Toca para actualizar", wide.action)
        assertFalse(wide.twoLine)
        // shrinking room walks down the tiers, always keeping the version whole
        var last = Int.MAX_VALUE
        for (room in 400 downTo 10 step 5) {
            val l = UpdateNotice.fit("0.5.0", room.toFloat(), ::w, ::w) ?: continue
            val i = UpdateNotice.labels("0.5.0").indexOf(l)
            assertTrue("tiers only shrink as room shrinks", i >= 0 && (last == Int.MAX_VALUE || i >= last))
            last = i
            assertTrue(l.title.contains("0.5.0"))
        }
        assertNull("no room → no chip (⚙ dot instead)", UpdateNotice.fit("0.5.0", 20f, ::w, ::w))
    }

    @Test fun chipStepsAsideForTypingAndPaste() { // N4
        assertEquals(UpdateSurface.CHIP, UpdateNotice.surface(true, typedInField = false, pasteOffered = false, secret = false))
        assertEquals(UpdateSurface.BADGE, UpdateNotice.surface(true, typedInField = true, pasteOffered = false, secret = false))
        assertEquals(UpdateSurface.BADGE, UpdateNotice.surface(true, typedInField = false, pasteOffered = true, secret = false))
        assertEquals(UpdateSurface.BADGE, UpdateNotice.surface(true, typedInField = false, pasteOffered = false, secret = true))
        for (t in listOf(true, false)) for (p in listOf(true, false)) for (s in listOf(true, false))
            assertEquals(UpdateSurface.NONE, UpdateNotice.surface(false, t, p, s))
    }

    @Test fun chipNeverChangesTheKeyboardHeight() { // N5 (source guard; E2E measures the key bounds)
        val src = File("src/main/kotlin/com/resyst/vk/ime/KeyboardView.kt").readText()
        val body = src.substringAfter("fun setUpdate(").substringBefore("\n    private fun animateChip")
        assertFalse(body.contains("requestLayout"))
        assertTrue(src.contains("private val stripH get() = 42 * dp"))
        // the ✕ is a 48 dp target
        assertTrue(src.contains("const val CHIP_X_W = 48f"))
    }

    // ── settings ────────────────────────────────────────────────────────
    @Test fun startupCheckToggleIsDeviceWideAndDefaultsOn() { // S1
        assertTrue(ProfileCodec.seed().autoUpdateCheck)
        val r8 = ProfileCodec.encode(ProfileCodec.seed()).filterKeys { it != ProfileCodec.UPDATE_AUTO_KEY }
        assertTrue("r8 storage lands ON", ProfileCodec.decode(r8).autoUpdateCheck)
        val off = ProfileCodec.seed().copy(autoUpdateCheck = false)
        assertFalse(ProfileCodec.decode(ProfileCodec.encode(off)).autoUpdateCheck)
        val junk = ProfileCodec.encode(ProfileCodec.seed()).toMutableMap<String, Any?>().apply { put(ProfileCodec.UPDATE_AUTO_KEY, "maybe") }
        assertTrue(ProfileCodec.decode(junk).autoUpdateCheck)
        assertFalse("not per tema", ProfileCodec.encode(off).keys.any { (it.startsWith("p.") || it.startsWith("t.")) && it.endsWith(".auto") })
        assertEquals(Scope.DEVICE, Ctl.UPDATE_AUTO.scope)
        assertEquals("acerca", SettingsIA.pageOf(Ctl.UPDATE_AUTO)?.id)
        assertFalse(Ctl.UPDATE_AUTO in SettingsIA.HOME)
        // switching tema keeps it
        assertFalse(ProfileCodec.decode(ProfileCodec.encode(off.withTema(off.nextTemaId()))).autoUpdateCheck)
    }

    @Test fun privacyCopyMatchesTheToggle() { // S2
        val on = UpdateNotice.promise(true)
        assertFalse(on.contains("por sí solo"))
        assertTrue(on.contains("al abrir el teclado") && on.contains("una vez")) // r11c (OC14)
        assertTrue(on.contains("solo cuando tú se lo pides"))
        val off = UpdateNotice.promise(false)
        assertTrue(off.contains("No se conecta a internet por sí solo"))
    }
}
