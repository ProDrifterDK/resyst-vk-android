package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * r13c phase B (docs/failure-modes.md LI1–LI5, WN1–WN8): the license texts the APK carries agree
 * with the repo, and the "Novedades" notice's decisions. The device run (scripts/e2e_r13c.py,
 * section N) covers the rendered chip, the VIEW intent and the update-in-place.
 */
class Round13PhaseBTest {
    private val assets = File("src/main/assets")
    private val lexicon = File(assets, "lexicon")
    private fun repo(path: String) = File("..", path)

    // ── LI2: the GPL text travels in the APK and stays the repo's LICENSE ───
    @Test fun theGplTextInTheApkIsTheRepoLicenseByteForByte() {
        val shipped = File(assets, "LICENSE-GPL-3.txt").readBytes()
        assertTrue(shipped.isNotEmpty())
        assertTrue("asset differs from the repo root LICENSE", shipped.contentEquals(repo("LICENSE").readBytes()))
        val text = shipped.toString(Charsets.UTF_8)
        assertTrue(text.contains("GNU GENERAL PUBLIC LICENSE") && text.contains("Version 3, 29 June 2007"))
    }

    // ── LI1 / LI4: README, lexicon README, lexicon-src README and the settings credits agree ──
    @Test fun theCreditsAgreeWithTheReadmeLicenseSection() {
        val readme = repo("README.md").readText()
        val section = readme.substringAfter("## License").substringBefore("\n---")
        assertTrue(section.contains("GNU General Public License") && section.contains("version 3") && section.contains("any later version"))
        val offer = "GPL-3+ or LGPL-3+ or MPL-1.1+"
        assertTrue("README: RLA-ES offer", section.contains("RLA-ES") && section.contains(offer))
        assertTrue("lexicon README.txt: RLA-ES offer + GPL-3+ use", File(lexicon, "README.txt").readText().let {
            it.contains(offer) && it.contains("used under GPL-3+") && it.contains("LICENSE-GPL-3.txt")
        })
        assertTrue("lexicon-src README: RLA-ES offer + GPL-3+ use", repo("lexicon-src/README.md").readText().let {
            it.contains(offer) && it.contains("uses it under GPL-3+")
        })
        val settings = File("src/main/kotlin/com/resyst/vk/settings/SettingsActivity.kt").readText()
        // r14b: the credits are resources, the same facts in both languages
        val es = Res.parse(File(Res.resDir(), "values")).strings
        val en = Res.parse(File(Res.resDir(), "values-en")).strings
        val footer = settings.substringAfter("private fun footer()").substringBefore("companion object")
        for (k in listOf("s_credits_data", "s_credits_context", "s_source")) assertTrue("the footer shows $k", footer.contains("R.string.$k"))
        val esCredits = es.getValue("s_credits_data") + es.getValue("s_source")
        val enCredits = en.getValue("s_credits_data") + en.getValue("s_source")
        assertTrue("settings: dictionary used under the GPL, offered as GPL/LGPL/MPL",
            esCredits.contains("RLA-ES") && esCredits.contains("se usa bajo la GPL") && esCredits.contains("GPL, LGPL o MPL"))
        assertTrue("settings (English): dictionary used under the GPL, offered as GPL/LGPL/MPL",
            enCredits.contains("RLA-ES") && enCredits.contains("used under the GPL") && enCredits.contains("GPL, LGPL or MPL"))
        assertTrue("settings: the app is free software under GPL 3, source on GitHub",
            esCredits.contains("software libre bajo la GPL 3") && esCredits.contains("código fuente en GitHub"))
        assertTrue("settings (English): the app is free software under GPL 3, source on GitHub",
            enCredits.contains("free software under the GPL 3") && enCredits.contains("source code on GitHub"))
        assertTrue(settings.contains("https://github.com/ProDrifterDK/resyst-vk-android\""))
        assertFalse(esCredits.contains("RLA-ES (MPL)") || enCredits.contains("RLA-ES (MPL)"))
        // the other credits stay true to the README list
        for (c in listOf("FrequencyWords (CC BY-SA 4.0)", "DM Sans (OFL)", "SCOWL")) { assertTrue(c, esCredits.contains(c)); assertTrue(c, enCredits.contains(c)) }
    }

    // ── LI5: the browser link is not a keyboard request: no connection-book entry, no network class ──
    @Test fun theLinksAreIntentsNotRequests() {
        val footer = File("src/main/kotlin/com/resyst/vk/settings/SettingsActivity.kt").readText()
            .substringAfter("private fun footer()").substringBefore("companion object")
        assertTrue(footer.contains("ACTION_VIEW") && !footer.contains("ConnectionBook"))
        for (f in listOf("core/WhatsNew.kt", "ime/WhatsNewStore.kt")) {
            val src = File("src/main/kotlin/com/resyst/vk/$f").readText()
            for (net in listOf("HttpURLConnection", "openConnection", "Socket", "OkHttp", "ConnectionBook", "DownloadManager"))
                assertFalse("$f must not touch $net", src.contains(net))
        }
        assertEquals("https://kv.resyst.cl/novedades/", WhatsNew.URL)
    }

    // ── WN1: when it is due ──────────────────────────────────────────────
    @Test fun dueOnlyAfterAnUpdate() {
        val pending = WhatsNew.Start.Pending
        // fresh install: baseline written silently, nothing shown
        assertEquals(WhatsNew.Start.Quiet("0.10.0"), WhatsNew.start(null, "0.10.0", updatedInPlace = false))
        // an update from a build that never stored a version (0.9.0 → 0.10.0)
        assertEquals(pending, WhatsNew.start(null, "0.10.0", updatedInPlace = true))
        // an update from a stored older version, release and debug naming
        assertEquals(pending, WhatsNew.start("0.9.0", "0.10.0", false))
        assertEquals(pending, WhatsNew.start("0.9.0-debug", "0.10.0-debug", false))
        assertEquals(pending, WhatsNew.start("0.10.0", "0.10.1", false))
        assertEquals(pending, WhatsNew.start("0.9.9", "0.10.0", false)) // numeric, not textual: 10 > 9
        // already seen: nothing, nothing to write
        assertEquals(WhatsNew.Start.Quiet(null), WhatsNew.start("0.10.0", "0.10.0", true))
        // downgrade / sideload of an older build / unreadable stored value: no notice, the store resyncs
        assertEquals(WhatsNew.Start.Quiet("0.9.0"), WhatsNew.start("0.10.0", "0.9.0", false))
        assertEquals(WhatsNew.Start.Quiet("0.10.0"), WhatsNew.start("???", "0.10.0", false))
        // a pre-release does not outrank its release
        assertEquals(WhatsNew.Start.Quiet("0.10.0-rc1"), WhatsNew.start("0.10.0", "0.10.0-rc1", false))
    }

    @Test fun anUpdateInPlaceIsToldFromAFreshInstall() {
        assertFalse(WhatsNew.updatedInPlace(1_000_000L, 1_000_000L))
        assertFalse("the installer's own clock reads", WhatsNew.updatedInPlace(1_000_000L, 1_000_900L))
        assertTrue(WhatsNew.updatedInPlace(1_000_000L, 1_000_000L + 60_000L))
    }

    // ── WN2 / WN3 / WN4: where it may be on the strip ───────────────────
    @Test fun visibleOnlyOnAFreshPlainField() {
        fun v(pending: Boolean = true, typed: Boolean = false, paste: Boolean = false,
              secret: Boolean = false, incognito: Boolean = false, update: Boolean = false) =
            WhatsNew.visible(pending, typed, paste, secret, incognito, update)
        assertTrue(v())
        assertFalse("nothing pending", v(pending = false))
        assertFalse("typed in the field", v(typed = true))
        assertFalse("a paste offer is contextual and wins", v(paste = true))
        assertFalse("secret field", v(secret = true))
        assertFalse("incognito field", v(incognito = true))
        assertFalse("an announced update owns the chip slot", v(update = true))
    }

    // ── WN5: every tier carries the whole version, none the debug suffix, the action says "navegador" ──
    @Test fun everyLabelTierCarriesTheWholeVersion() {
        for (name in listOf("0.10.0", "0.10.0-debug", "1.2.3")) {
            val shown = name.removeSuffix("-debug")
            val tiers = WhatsNew.labels(TestTxt.ES, name)
            assertEquals("Novedades de la $shown", tiers.first().title)
            for (l in tiers) {
                assertTrue("$l carries $shown", (l.title + l.action).contains(shown))
                assertFalse("$l shows the debug suffix", (l.title + l.action).contains("debug"))
            }
            assertTrue(tiers.drop(1).dropLast(1).all { it.action.contains("navegador") })
        }
        // the fit picks the longest tier that fits and never cuts the version: null when not even the bare one fits
        val w = { t: String, _: Boolean -> t.length * 10f }
        assertEquals("Novedades de la 0.10.0", UpdateNotice.fitOf(WhatsNew.labels(TestTxt.ES, "0.10.0"), 1000f, w, w)!!.title)
        assertEquals("0.10.0", UpdateNotice.fitOf(WhatsNew.labels(TestTxt.ES, "0.10.0"), 95f, w, w)!!.title)
        assertNull(UpdateNotice.fitOf(WhatsNew.labels(TestTxt.ES, "0.10.0"), 40f, w, w))
        // the update chip's tiers are unchanged by the refactor behind fitOf
        assertEquals(UpdateNotice.labels(TestTxt.ES, "0.10.0").first(), UpdateNotice.fit(TestTxt.ES, "0.10.0", 10_000f, w, w))
    }

    @Test fun talkBackSaysWhatATapDoes() {
        assertTrue(WhatsNew.describe(TestTxt.ES, "0.10.0-debug").let { it.contains("0.10.0") && it.contains("navegador") && !it.contains("debug") })
        assertTrue(WhatsNew.dismissDescription(TestTxt.ES, "0.10.0").contains("Descartar"))
    }

    // ── WN3 / WN4 wiring pins (the transitions live in the service; the device run exercises them) ──
    @Test fun theServiceConsumesOnTapDismissAndTypingOnly() {
        val svc = File("src/main/kotlin/com/resyst/vk/ime/ResystImeService.kt").readText()
        assertTrue(svc.contains("consumeNews(\"opened\")") && svc.contains("consumeNews(\"dismissed\")") && svc.contains("consumeNews(\"typed past\")"))
        // a failed intent keeps it pending: consume only on success
        assertTrue(svc.contains("if (ok) consumeNews(\"opened\")"))
        // the visibility rule is the pure one
        assertTrue(svc.contains("WhatsNew.visible(true, typedForUpdate, offer != null, policy.secret, policy.incognito, r != null)"))
    }
}
