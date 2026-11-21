package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * r15 (UC1, UC2 in docs/failure-modes.md): the automatic check every 3 h, and an «up to date» answer that says when it
 * was true. Each test names the failure it catches.
 */
class Round15UpdateTest {
    private val H = 3_600_000L
    private val T0 = 1_790_000_000_000L // a Sep 2026 wall clock

    private class Prefs : OpenCheck.Store {
        var stored: OpenCheck.Attempt? = null
        override fun read() = stored
        override fun write(a: OpenCheck.Attempt) { stored = a }
    }

    // ── UC1: 3 h after an answer, 1 h after a failure ─────────────────────
    @Test fun uc1AnAnswerIsCheckedAgainAfterThreeHoursNotTwelve() {
        assertEquals(3, OpenCheck.OK_HOURS)
        assertEquals(1, OpenCheck.FAILED_HOURS)
        val prefs = Prefs()
        val g = OpenCheck.Gate(prefs)
        assertEquals(OpenCheck.Verdict.RUN, g.claim(T0, enabled = true, secret = false, busy = false))
        g.end(T0, reached = true)
        assertEquals(OpenCheck.Verdict.WAIT, g.claim(T0 + 3 * H - 1, enabled = true, secret = false, busy = false))
        // the 0.14.0 value (12 h) would still say WAIT here
        assertEquals(OpenCheck.Verdict.RUN, g.claim(T0 + 3 * H, enabled = true, secret = false, busy = false))
        // a new process (another Gate on the same prefs) obeys the same clock
        g.end(T0 + 3 * H, reached = true)
        assertEquals(OpenCheck.Verdict.WAIT, OpenCheck.Gate(prefs).claim(T0 + 6 * H - 1, enabled = true, secret = false, busy = false))
        assertEquals(OpenCheck.Verdict.RUN, OpenCheck.Gate(prefs).claim(T0 + 6 * H, enabled = true, secret = false, busy = false))
    }

    @Test fun uc1AFailureStillRetriesAfterOneHour() {
        assertFalse(OpenCheck.due(T0 + H - 1, OpenCheck.Attempt(T0, reached = false)))
        assertTrue(OpenCheck.due(T0 + H, OpenCheck.Attempt(T0, reached = false)))
        assertFalse(OpenCheck.due(T0 + H, OpenCheck.Attempt(T0, reached = true)))
    }

    @Test fun uc1EveryTextThatStatesTheIntervalSaysThree() {
        assertEquals("Al abrir el teclado, como mucho una vez cada 3 horas. Nunca envía lo que escribes.", UpdateNotice.autoSubtitle(TestTxt.ES))
        assertEquals("When the keyboard opens, at most once every 3 hours. It never sends what you type.", UpdateNotice.autoSubtitle(TestTxt.EN))
        for (t in listOf(TestTxt.ES, TestTxt.EN)) {
            val promise = UpdateNotice.promise(t, true)
            assertTrue(promise, promise.contains(" 3 "))
            assertFalse(promise, promise.contains("12"))
        }
        val readme = File("../README.md").readText()
        assertTrue("README states the interval", readme.contains("at most every 3 h, 1 h after a failure"))
        assertFalse("README still says 12 h", readme.contains("every 12 h"))
    }

    // ── UC2: the answer carries its age ───────────────────────────────────
    @Test fun uc2TheUpToDateLineSaysWhenTheCheckRan() {
        val now = T0 + 5 * H
        assertEquals("No había versiones nuevas cuando revisé, hace 1 h", UpdateNotice.upToDate(TestTxt.ES, now - H, now))
        assertEquals("No newer version when checked, 1 h ago", UpdateNotice.upToDate(TestTxt.EN, now - H, now))
        assertEquals("No había versiones nuevas cuando revisé, hace 12 min", UpdateNotice.upToDate(TestTxt.ES, now - 12 * 60_000, now))
        assertEquals("No newer version when checked, 2 days ago", UpdateNotice.upToDate(TestTxt.EN, now - 50 * H, now))
        assertEquals("No había versiones nuevas cuando revisé, hace un momento", UpdateNotice.upToDate(TestTxt.ES, now - 20_000, now))
        // a clock moved back: never a negative age
        assertEquals("No newer version when checked, a moment ago", UpdateNotice.upToDate(TestTxt.EN, now + H, now))
        // no known time: no age is claimed
        assertEquals("No había versiones nuevas cuando revisé.", UpdateNotice.upToDate(TestTxt.ES, 0L, now))
    }

    @Test fun uc2ThePresentTenseClaimIsGone() {
        for (dir in listOf("values", "values-en")) {
            val xml = File("src/main/res/$dir").listFiles()!!.filter { it.extension == "xml" }.joinToString("\n") { it.readText() }
            assertFalse(dir, xml.contains("Ya tienes la última versión") || xml.contains("You have the latest version"))
            assertFalse(dir, xml.contains("name=\"s_uptodate\""))
        }
        val settings = File("src/main/kotlin/com/resyst/vk/settings/SettingsActivity.kt").readText()
        assertTrue(settings.contains("UpdateNotice.upToDate(tx, Updater.checkedAt, System.currentTimeMillis())"))
    }
}
