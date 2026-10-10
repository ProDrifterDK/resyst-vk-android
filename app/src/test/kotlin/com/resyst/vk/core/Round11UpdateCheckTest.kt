package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 11c — the update check runs when the keyboard opens, at most once per interval (OC1–OC15 in
 * docs/failure-modes.md). Each test names the failure it catches.
 */
class Round11UpdateCheckTest {

    private val H = 3_600_000L
    private val T0 = 1_760_000_000_000L // an Oct 2025 wall clock

    /** The updater prefs, in memory; survives "processes" (new Gate objects) like the real file. */
    private class Prefs : OpenCheck.Store {
        var stored: OpenCheck.Attempt? = null
        var writes = 0
        override fun read() = stored
        override fun write(a: OpenCheck.Attempt) { stored = a; writes++ }
    }

    private fun OpenCheck.Gate.ask(now: Long, enabled: Boolean = true, secret: Boolean = false, busy: Boolean = false) =
        claim(now, enabled, secret, busy)

    // ── OC1 + OC2: every open is asked; the throttle is persisted, not per process ──
    @Test fun aLongLivedProcessChecksAgainAfterTheInterval() { // OC1 (the r9 bug)
        val prefs = Prefs()
        val g = OpenCheck.Gate(prefs)
        assertEquals(OpenCheck.Verdict.RUN, g.ask(T0))
        g.end(T0, reached = true)
        // the same process, opened all day: nothing until 12 h have passed …
        for (m in listOf(1L, 60, 6 * 60, 11 * 60 + 59)) assertEquals("+$m min", OpenCheck.Verdict.WAIT, g.ask(T0 + m * 60_000))
        // … then exactly one more check, still the same Gate (= the same process)
        assertEquals(OpenCheck.Verdict.RUN, g.ask(T0 + 12 * H))
        assertEquals(OpenCheck.Verdict.IN_FLIGHT, g.ask(T0 + 12 * H + 1))
    }

    @Test fun aNewProcessDoesNotResetTheThrottle() { // OC2
        val prefs = Prefs()
        OpenCheck.Gate(prefs).apply { assertEquals(OpenCheck.Verdict.RUN, ask(T0)); end(T0, reached = true) }
        // process killed and re-created ten times within the hour: the stored attempt rules
        repeat(10) { i -> assertEquals("process $i", OpenCheck.Verdict.WAIT, OpenCheck.Gate(prefs).ask(T0 + (i + 1) * 5 * 60_000L)) }
        assertEquals(OpenCheck.Verdict.RUN, OpenCheck.Gate(prefs).ask(T0 + 12 * H + 1))
    }

    // ── OC4: 12 h after an answer, 1 h after a failure ────────────────────
    @Test fun aFailedAttemptBacksOffOneHourNotTwelveNotZero() { // OC4
        val prefs = Prefs()
        val g = OpenCheck.Gate(prefs)
        assertEquals(OpenCheck.Verdict.RUN, g.ask(T0))
        g.end(T0, reached = false) // offline
        assertEquals("no retry on every open without signal", OpenCheck.Verdict.WAIT, g.ask(T0 + 1))
        assertEquals(OpenCheck.Verdict.WAIT, g.ask(T0 + 59 * 60_000))
        assertEquals("a failure must not wait 12 h", OpenCheck.Verdict.RUN, g.ask(T0 + H))
        g.end(T0 + H, reached = true)
        assertEquals(OpenCheck.Verdict.WAIT, g.ask(T0 + 2 * H))
        assertEquals(OpenCheck.Verdict.WAIT, g.ask(T0 + H + 12 * H - 1))
        assertEquals(OpenCheck.Verdict.RUN, g.ask(T0 + H + 12 * H))
        assertEquals(OpenCheck.OK_INTERVAL_MS, 12 * H)
        assertEquals(OpenCheck.FAILED_INTERVAL_MS, 1 * H)
    }

    @Test fun aProcessKilledMidCheckCountsAsAFailedAttempt() { // OC4: begin writes before the GET
        val prefs = Prefs()
        assertEquals(OpenCheck.Verdict.RUN, OpenCheck.Gate(prefs).ask(T0))
        assertEquals("start recorded before the request", OpenCheck.Attempt(T0, reached = false), prefs.stored)
        // never ended (killed); the next process backs off one hour, then retries
        assertEquals(OpenCheck.Verdict.WAIT, OpenCheck.Gate(prefs).ask(T0 + 30 * 60_000))
        assertEquals(OpenCheck.Verdict.RUN, OpenCheck.Gate(prefs).ask(T0 + H))
    }

    // ── OC3: clock skew ──────────────────────────────────────────────────
    @Test fun aLastAttemptInTheFutureCountsAsElapsedOnce() { // OC3 (clock moved back)
        val prefs = Prefs()
        prefs.stored = OpenCheck.Attempt(T0 + 400 * 24 * H, reached = true) // set when the clock was a year ahead
        val g = OpenCheck.Gate(prefs)
        assertEquals("a future stamp must not block for a year", OpenCheck.Verdict.RUN, g.ask(T0))
        assertEquals("…and is overwritten by this attempt", T0, prefs.stored!!.at)
        g.end(T0, reached = true)
        assertEquals("once: the next open waits normally", OpenCheck.Verdict.WAIT, g.ask(T0 + 60_000))
    }

    @Test fun aClockMovedForwardAllowsOneCheckNotABurst() { // OC3 (clock moved forward)
        val prefs = Prefs()
        val g = OpenCheck.Gate(prefs)
        g.ask(T0); g.end(T0, reached = true)
        val jumped = T0 + 30 * 24 * H
        assertEquals(OpenCheck.Verdict.RUN, g.ask(jumped))
        g.end(jumped, reached = true)
        repeat(20) { assertEquals(OpenCheck.Verdict.WAIT, g.ask(jumped + (it + 1) * 1000L)) }
    }

    @Test fun neverCheckedOrJunkStampIsDue() {
        assertTrue(OpenCheck.due(T0, null))
        assertTrue(OpenCheck.due(T0, OpenCheck.Attempt(0, true)))
        assertTrue(OpenCheck.due(T0, OpenCheck.Attempt(-5, false)))
    }

    // ── OC5: burst ───────────────────────────────────────────────────────
    @Test fun aBurstOfOpensWhileACheckRunsStartsOneRequest() { // OC5
        val prefs = Prefs()
        val g = OpenCheck.Gate(prefs)
        val verdicts = (0 until 30).map { g.ask(T0 + it * 200L) }
        assertEquals(1, verdicts.count { it == OpenCheck.Verdict.RUN })
        assertTrue(verdicts.drop(1).all { it == OpenCheck.Verdict.IN_FLIGHT })
        // a stuck/slow request that failed: still one per hour afterwards
        g.end(T0, reached = false)
        assertEquals(OpenCheck.Verdict.WAIT, g.ask(T0 + 10_000))
    }

    @Test fun concurrentClaimsWinOnce() { // OC5 under a race
        val g = OpenCheck.Gate(Prefs())
        val wins = java.util.concurrent.atomic.AtomicInteger()
        val threads = List(16) { Thread { if (g.ask(T0) == OpenCheck.Verdict.RUN) wins.incrementAndGet() } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(1, wins.get())
    }

    @Test fun theUsersCheckSharesTheClockAndTheGuard() { // OC10 + OC5
        val prefs = Prefs()
        val g = OpenCheck.Gate(prefs)
        assertTrue("the user's tap", g.begin(T0))
        assertEquals("no automatic GET while the user's runs", OpenCheck.Verdict.IN_FLIGHT, g.ask(T0 + 1))
        g.end(T0, reached = true)
        assertEquals("no redundant automatic GET right after a manual answer", OpenCheck.Verdict.WAIT, g.ask(T0 + 60_000))
        assertTrue("the user can always ask again", g.begin(T0 + 120_000))
        assertFalse("but never two requests at once", g.begin(T0 + 120_001))
    }

    // ── OC6, OC7, OC8: no request ─────────────────────────────────────────
    @Test fun toggleOffNeverChecksEvenWhenLongDue() { // OC6
        val prefs = Prefs()
        prefs.stored = OpenCheck.Attempt(1, reached = true) // 1970
        val g = OpenCheck.Gate(prefs)
        repeat(5) { assertEquals(OpenCheck.Verdict.OFF, g.ask(T0 + it, enabled = false)) }
        assertEquals("off writes nothing", 0, prefs.writes)
        assertEquals("turned on: the next open checks", OpenCheck.Verdict.RUN, g.ask(T0 + 10))
    }

    @Test fun aBusyUpdaterSkipsWithoutConsumingTheInterval() { // OC7
        val prefs = Prefs()
        val g = OpenCheck.Gate(prefs)
        assertEquals(OpenCheck.Verdict.BUSY, g.ask(T0, busy = true))
        assertEquals(0, prefs.writes)
        assertEquals("free again: the next open checks", OpenCheck.Verdict.RUN, g.ask(T0 + 1000))
    }

    @Test fun aSecretFieldNeverStartsTheRequest() { // OC8
        val prefs = Prefs()
        val g = OpenCheck.Gate(prefs)
        repeat(3) { assertEquals(OpenCheck.Verdict.SECRET, g.ask(T0 + it, secret = true)) }
        assertEquals(0, prefs.writes)
        assertEquals(OpenCheck.Verdict.RUN, g.ask(T0 + 5))
    }

    // ── source guards: the only trigger, the one path, no background scheduling (OC1, OC9) ──
    @Test fun onlyAKeyboardShowTriggersTheCheck() { // OC1 + OC9
        val ime = File("src/main/kotlin/com/resyst/vk/ime/ResystImeService.kt").readText()
        val show = ime.substringAfter("override fun onStartInputView(").substringBefore("\n    override fun ")
        assertTrue("asked on every keyboard show", show.contains("Updater.onKeyboardShown("))
        assertTrue("restarting=false, plus the first show after onCreate", show.contains("if (!restarting || !askedSinceCreate)"))
        assertEquals("one call site", 1, Regex("""Updater\.onKeyboardShown\(""").findAll(ime).count())
        val onCreate = ime.substringAfter("override fun onCreate()").substringBefore("override fun onDestroy()")
        assertFalse("not once per process any more", onCreate.contains("Updater.onKeyboardShown(") || onCreate.contains("autoCheck("))
        val main = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val callers = main.filter { Regex("""Updater\.onKeyboardShown\(""").containsMatchIn(it.readText()) }.map { it.name }
        assertEquals(listOf("ResystImeService.kt"), callers)
        for (f in main) {
            val t = f.readText()
            for (bg in listOf("AlarmManager", "WorkManager", "JobScheduler", "JobService", "Timer(", "scheduleAtFixedRate", "postDelayed({ Updater"))
                assertFalse("${f.name} uses $bg", t.contains(bg) && t.contains("Updater"))
        }
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("RECEIVE_BOOT_COMPLETED"))
        assertFalse(manifest.contains("SCHEDULE_EXACT_ALARM"))
    }

    @Test fun oneGetPathAndBothChecksGoThroughTheGate() { // OC10 + V7
        val src = File("src/main/kotlin/com/resyst/vk/settings/Updater.kt").readText()
        assertEquals("one GET", 1, Regex("""fetchManifest\(\)""").findAll(src.substringBefore("private fun fetchManifest")).count())
        val manual = src.substringAfter("fun check(context: Context) {").substringBefore("\n    }\n")
        assertTrue("manual check takes the gate", manual.contains("gate.begin("))
        val shown = src.substringAfter("fun onKeyboardShown(").substringBefore("\n    }\n")
        assertTrue(shown.contains("gate.claim("))
        val fetch = src.substringAfter("private fun fetch(").substringBefore("\n    // ──")
        assertTrue("every attempt is recorded", fetch.contains("gate.end("))
        assertTrue("the later answer replaces Checked(UpToDate)", fetch.contains("state is State.Checked"))
        assertFalse("AutoCheckGate is gone", File("src/main/kotlin").walkTopDown().any { it.isFile && it.readText().contains("AutoCheckGate") })
    }

    // ── OC11–OC13: what a later check shows ───────────────────────────────
    @Test fun aLaterCheckAnnouncesANewerReleaseButNotADismissedOne() { // OC11 + OC13
        fun rel(v: String) = Release(v, null, "a".repeat(64), "https://kv.resyst.cl/download/x.apk", null, null, null, null, null)
        // this process first heard "up to date", then a later open found 0.7.1
        assertNull(UpdateNotice.announce(UpdateDecision.UpToDate("0.7.0"), null))
        assertEquals("0.7.1", UpdateNotice.announce(UpdateDecision.Available(rel("0.7.1")), null)?.version)
        // a dismissed 0.7.1 does not come back every 12 h; 0.7.2 does
        assertNull(UpdateNotice.announce(UpdateDecision.Available(rel("0.7.1")), "0.7.1"))
        assertEquals("0.7.2", UpdateNotice.announce(UpdateDecision.Available(rel("0.7.2")), "0.7.1")?.version)
    }

    @Test fun aFailedLaterCheckLeavesTheScreenAlone() { // OC12
        assertNull(UpdateNotice.autoOutcome(null))
        assertNull(UpdateNotice.autoOutcome(UpdateDecision.Error("bad")))
        val src = File("src/main/kotlin/com/resyst/vk/settings/Updater.kt").readText()
        val fetch = src.substringAfter("private fun fetch(").substringBefore("\n    // ──")
        assertTrue("null next = keep the state", fetch.contains("if (next != null &&"))
        assertFalse("never back to Idle from an automatic failure", fetch.contains("?: State.Idle"))
    }

    @Test fun anAnnouncedUpdateSurvivesARestartInsideTheInterval() { // OC16
        val manifest = """{"available":true,"version":"0.7.1","versionCode":8,"sha256":"${"a".repeat(64)}","url":"/download/resyst-vk-0.7.1.apk","minSdk":26}"""
        // the stored answer, re-decided offline after a restart: still available on 0.7.0 …
        assertTrue(UpdateChecker.decide(manifest, Installed(7, "0.7.0", 34)) is UpdateDecision.Available)
        // … and no longer once 0.7.1 is installed (it is forgotten instead of announced)
        assertFalse(UpdateChecker.decide(manifest, Installed(8, "0.7.1", 34)) is UpdateDecision.Available)
        val src = File("src/main/kotlin/com/resyst/vk/settings/Updater.kt").readText()
        val shown = src.substringAfter("fun onKeyboardShown(").substringBefore("\n    }\n")
        assertTrue("restored before the gate is asked", shown.indexOf("restore(app)") in 0 until shown.indexOf("gate.claim("))
        val remember = src.substringAfter("private fun remember(").substringBefore("\n    }\n")
        assertTrue("a failure keeps what was known", remember.contains("if (decision == null || decision is UpdateDecision.Error) return"))
        val restore = src.substringAfter("private fun restore(").substringBefore("\n    }\n")
        assertFalse("restoring never touches the network", restore.contains("fetch") || restore.contains("check("))
        assertTrue(src.substringAfter("private fun fetch(").substringBefore("\n    // ──").contains("remember(app, decision, json)"))
    }

    // ── OC14, OC15: copy and the log ──────────────────────────────────────
    @Test fun copySaysWhenAndHowOften() { // OC14
        assertEquals("Buscar actualizaciones automáticamente", Ctl.UPDATE_AUTO.label)
        assertEquals("Al abrir el teclado, como mucho una vez cada 12 horas. Nunca envía lo que escribes.", UpdateNotice.autoSubtitle())
        val on = UpdateNotice.promise(true)
        assertTrue(on, on.contains("al abrir el teclado") && on.contains("una vez cada 12 horas") && on.contains("sin enviar nada de lo que escribes"))
        assertFalse(on.contains("al iniciar"))
        val settings = File("src/main/kotlin/com/resyst/vk/settings/SettingsActivity.kt").readText()
        assertFalse("settings card still says 'al iniciar'", settings.contains("Comprobado al iniciar"))
        assertTrue(settings.contains("UpdateNotice.autoSubtitle()"))
    }

    @Test fun oldStartupEntriesStillDecodeNextToNewOpenEntries() { // OC15
        // stored by 0.7.0 (r10 format, reason "startup")
        val v070 = "{\"v\":1,\"total\":3,\"first\":1000,\"items\":[[3000,\"check\",\"user\",\"Ya al día (0.7.0)\"],[2000,\"download\",\"user\",\"0.7.0\"],[1000,\"check\",\"startup\",\"Hay versión nueva: 0.7.0\"]]}"
        val old = ConnectionLog.decode(v070)
        assertEquals(3, old.recent().size)
        assertEquals(ConnectionLog.Why.STARTUP, old.recent()[2].why)
        assertEquals("al iniciar el teclado", old.recent()[2].why.label)
        val next = old.add(ConnectionLog.Entry(4000, ConnectionLog.What.CHECK, ConnectionLog.Why.OPEN, "Hay versión nueva: 0.7.1"))
        val back = ConnectionLog.decode(next.encode())
        assertEquals(listOf("open", "user", "user", "startup"), back.recent().map { it.why.id })
        assertEquals("al abrir el teclado", back.recent()[0].why.label)
        assertEquals(4, back.total)
        assertEquals(1000L, back.firstAt)
        val src = File("src/main/kotlin/com/resyst/vk/settings/Updater.kt").readText()
        assertTrue("automatic checks log as OPEN", src.contains("if (auto) ConnectionLog.Why.OPEN"))
    }

    @Test fun versionIs071() {
        val g = File("build.gradle.kts").readText()
        assertTrue(g.contains("?.toInt() ?: 8"))
        assertTrue(g.contains("?: \"0.7.1\""))
    }
}
