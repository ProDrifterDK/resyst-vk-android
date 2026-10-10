package com.resyst.vk.core

/**
 * r11c: when the keyboard may check for an update by itself (failure modes OC1–OC10,
 * docs/failure-modes.md). Replaces r9's once-per-process gate: Android keeps the IME process alive
 * for days, so "once per process" meant "almost never".
 *
 * The check is evaluated on every keyboard show and runs at most once per interval: [OK_INTERVAL_MS]
 * after an attempt that reached the server (any answer), [FAILED_INTERVAL_MS] after one that did
 * not (offline, timeout, HTTP error, unreadable manifest). The last attempt is persisted by the
 * caller ([Store], the updater prefs) so the throttle survives process death. Pure apart from the
 * store; JVM-tested in Round11UpdateCheckTest.
 */
object OpenCheck {
    const val OK_HOURS = 12
    const val FAILED_HOURS = 1
    const val OK_INTERVAL_MS = OK_HOURS * 3_600_000L
    const val FAILED_INTERVAL_MS = FAILED_HOURS * 3_600_000L

    /** One `Updater.check()`: when it started and whether it got an answer from the server. */
    data class Attempt(val at: Long, val reached: Boolean)

    enum class Verdict {
        /** Check now. */
        RUN,
        /** "Buscar actualizaciones automáticamente" is off. */
        OFF,
        /** Password / PIN field: no request while the user types a secret. */
        SECRET,
        /** The user's own flow owns the updater (checking, downloading, verifying, ready, pending id). */
        BUSY,
        /** A check is already running (a burst of opens). */
        IN_FLIGHT,
        /** The interval since the last attempt has not passed. */
        WAIT,
    }

    /**
     * The interval since [last] has passed. Never checked → due. A [last] in the future (the clock
     * was moved back) counts as elapsed: the attempt it allows overwrites it, so it happens once.
     */
    fun due(now: Long, last: Attempt?): Boolean {
        if (last == null || last.at <= 0L) return true
        if (last.at > now) return true
        return now - last.at >= if (last.reached) OK_INTERVAL_MS else FAILED_INTERVAL_MS
    }

    /** The order matters only for the log line: every non-RUN verdict starts nothing. */
    fun verdict(now: Long, enabled: Boolean, secret: Boolean, busy: Boolean, inFlight: Boolean, last: Attempt?): Verdict = when {
        !enabled -> Verdict.OFF
        secret -> Verdict.SECRET
        inFlight -> Verdict.IN_FLIGHT
        busy -> Verdict.BUSY
        !due(now, last) -> Verdict.WAIT
        else -> Verdict.RUN
    }

    /** Where the last attempt lives (the updater prefs on the device; memory in tests). */
    interface Store {
        fun read(): Attempt?
        fun write(a: Attempt)
    }

    /**
     * The in-flight guard + the persisted last attempt, shared by the automatic and the manual
     * check (one clock, OC10). [begin] records the start as a failed attempt BEFORE the request,
     * so a process killed mid-check backs off like a failure; [end] records the real outcome.
     */
    class Gate(private val store: Store) {
        private var inFlight = false

        val running: Boolean @Synchronized get() = inFlight

        /**
         * A keyboard show asks. On [Verdict.RUN] the check is already claimed ([begin]): the caller
         * must run it and call [end]. Every other verdict starts nothing and writes nothing.
         */
        @Synchronized
        fun claim(now: Long, enabled: Boolean, secret: Boolean, busy: Boolean): Verdict {
            val last = if (enabled && !secret && !inFlight && !busy) store.read() else null
            val v = verdict(now, enabled, secret, busy, inFlight, last)
            if (v == Verdict.RUN) begin(now)
            return v
        }

        /** The user's own check (or a claimed one). False when a check is already running. */
        @Synchronized
        fun begin(now: Long): Boolean {
            if (inFlight) return false
            inFlight = true
            store.write(Attempt(now, reached = false))
            return true
        }

        @Synchronized
        fun end(startedAt: Long, reached: Boolean) {
            inFlight = false
            store.write(Attempt(startedAt, reached))
        }
    }
}
