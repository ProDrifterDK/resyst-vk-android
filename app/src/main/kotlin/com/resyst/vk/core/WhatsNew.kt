package com.resyst.vk.core

import com.resyst.vk.R

/**
 * r13c (N6, docs/failure-modes.md WN1–WN8): the "Novedades de la 0.10.0" chip, shown once in the
 * suggestion strip the first time the keyboard opens after an update. Pure and JVM-tested in
 * Round13WhatsNewTest. The only thing stored is the last versionName seen ([Seen]); there is no
 * request: a tap hands [URL] to the browser.
 */
object WhatsNew {
    const val URL = "https://kv.resyst.cl/novedades/"

    /** What the first keyboard start of this process decides from the stored version. */
    sealed interface Start {
        /** Nothing to show; [write] (when not null) is the silent baseline to store. */
        data class Quiet(val write: String?) : Start
        /** Show the notice for the running version until it is tapped / dismissed / typed past. */
        object Pending : Start
    }

    /**
     * [seen] = the stored versionName (null: never stored, a fresh install or an update from a build
     * that did not store it), [current] = the running versionName, [updatedInPlace] = the package
     * was updated rather than installed ([updatedInPlace] below).
     */
    fun start(seen: String?, current: String, updatedInPlace: Boolean): Start {
        if (seen == null) return if (updatedInPlace) Start.Pending else Start.Quiet(current)
        if (seen == current) return Start.Quiet(null)
        val c = UpdateChecker.compareNames(current, seen)
        // newer = an update; an older or unreadable stored value = a downgrade / sideload: no notice, resync
        return if (c != null && c > 0) Start.Pending else Start.Quiet(current)
    }

    /**
     * A fresh install has firstInstallTime == lastUpdateTime (a few ms at most apart); an in-place
     * update moves lastUpdateTime on. [slackMs] absorbs the installer's own clock reads.
     */
    fun updatedInPlace(firstInstallTime: Long, lastUpdateTime: Long, slackMs: Long = 2_000L): Boolean =
        lastUpdateTime - firstInstallTime > slackMs

    /**
     * Whether the chip is on the strip now (WN2, WN4): never in a secret or incognito field, never
     * once the user typed in the field, never beside a paste offer, and an announced update (r9)
     * owns the chip slot. A notice that is not shown stays pending.
     */
    fun visible(
        pending: Boolean, typedInField: Boolean, pasteOffered: Boolean,
        secret: Boolean, incognito: Boolean, updateAnnounced: Boolean,
    ): Boolean = pending && !typedInField && !pasteOffered && !secret && !incognito && !updateAnnounced

    /** The version as shown: the debug build's "-debug" suffix is not part of the news. */
    fun display(versionName: String): String = versionName.removeSuffix("-debug")

    /** Longest first, every tier carries the whole version (WN5); the action says it opens the browser. */
    fun labels(t: Txt, version: String): List<UpdateNotice.Label> {
        val v = display(version)
        return listOf(
            UpdateNotice.Label(t.get(R.string.wn_title, v), " · " + t.get(R.string.wn_tap), twoLine = false),
            UpdateNotice.Label(t.get(R.string.wn_title, v), t.get(R.string.wn_browser), twoLine = true),
            UpdateNotice.Label(t.get(R.string.wn_title_short, v), t.get(R.string.wn_browser), twoLine = true),
            UpdateNotice.Label(v, t.get(R.string.wn_bare), twoLine = true),
        )
    }

    fun describe(t: Txt, version: String): String = t.get(R.string.wn_desc, display(version))

    fun dismissDescription(t: Txt, version: String): String = t.get(R.string.wn_dismiss, display(version))

    /**
     * Where "Novedades" and the links to the site open (TR9). The site is one page with both languages in it (the
     * language is the visitor's saved toggle) and has no English address, so both languages open the same page.
     * When the site publishes one (`/en/novedades/`), this is the only line to change.
     */
    fun url(t: Txt): String = URL
}
