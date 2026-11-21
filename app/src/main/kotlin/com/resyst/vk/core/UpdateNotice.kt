package com.resyst.vk.core

import com.resyst.vk.R

/** Where the keyboard strip shows an announced update (N4). */
enum class UpdateSurface { CHIP, BADGE, NONE }

/**
 * r9: what the keyboard and the settings screen show after the automatic check (failure modes
 * A5, N1–N4, docs/failure-modes.md). Pure, JVM-tested in Round9Test. When the check runs is
 * [OpenCheck] (r11c).
 */
object UpdateNotice {

    /**
     * What a finished automatic check leaves in the updater. Failures ([decision] null: offline,
     * timeout, HTTP error) and unreadable manifests are silent: null = the screen keeps what it
     * showed before, log only (A5, OC12).
     */
    fun autoOutcome(decision: UpdateDecision?): UpdateDecision? = when (decision) {
        null, is UpdateDecision.Error -> null
        else -> decision
    }

    /**
     * The release the keyboard announces, or null (N1, N2): only an installable update, and not a
     * version the user already dismissed. Only a strictly newer version comes back after a dismiss.
     */
    fun announce(decision: UpdateDecision?, dismissed: String?): Release? {
        val r = (decision as? UpdateDecision.Available)?.release ?: return null
        val d = dismissed?.trim()?.takeIf { it.isNotEmpty() } ?: return r
        val c = UpdateChecker.compareNames(r.version, d) ?: return if (r.version.trim() == d) null else r
        return if (c > 0) r else null
    }

    /**
     * The chip only while the field is fresh: nothing typed in it yet, no paste chip offered (the
     * clipboard is contextual and short-lived, so it wins), not a secret field. Otherwise an amber
     * dot on ⚙ keeps the update discoverable without taking the strip from typing (N4).
     */
    fun surface(announced: Boolean, typedInField: Boolean, pasteOffered: Boolean, secret: Boolean): UpdateSurface = when {
        !announced -> UpdateSurface.NONE
        typedInField || pasteOffered || secret -> UpdateSurface.BADGE
        else -> UpdateSurface.CHIP
    }

    /** A chip label: [title] in the accent, [action] quieter; [twoLine] stacks them. */
    data class Label(val title: String, val action: String, val twoLine: Boolean)

    /** Longest first. Every tier carries the whole version (N3). */
    fun labels(t: Txt, version: String): List<Label> {
        val tap = t.get(R.string.un_tap)
        return listOf(
            Label(t.get(R.string.un_full, version), " · $tap", twoLine = false),
            Label(t.get(R.string.un_full, version), tap, twoLine = true),
            Label(t.get(R.string.un_vk, version), tap, twoLine = true),
            Label(t.get(R.string.un_version, version), tap, twoLine = true),
            Label(version, t.get(R.string.un_short), twoLine = true),
        )
    }

    /**
     * The longest label that fits [room] px. [title] / [action] measure a text with the paint of
     * that line (`twoLine` = the smaller stacked sizes). null when not even the bare version fits:
     * the strip then shows only the ⚙ dot, never a cut version.
     */
    fun fit(
        t: Txt, version: String, room: Float,
        title: (text: String, twoLine: Boolean) -> Float,
        action: (text: String, twoLine: Boolean) -> Float,
    ): Label? = fitOf(labels(t, version), room, title, action)

    /** [fit] over any ordered tiers (the "Novedades" chip, [WhatsNew.labels], shares the chip). */
    fun fitOf(
        tiers: List<Label>, room: Float,
        title: (text: String, twoLine: Boolean) -> Float,
        action: (text: String, twoLine: Boolean) -> Float,
    ): Label? = tiers.firstOrNull { l ->
        val w = if (l.twoLine) maxOf(title(l.title, true), action(l.action, true))
        else title(l.title, false) + action(l.action, false)
        w <= room
    }

    /**
     * r15 (UC2): how long ago a check ran, as Settings writes it: «ahora mismo», «hace 3 min», «hace 1 h», «hace 2 días»
     * (en: «just now», «3 min ago», …). A time in the future (the clock moved back) reads «ahora mismo».
     */
    fun age(t: Txt, ms: Long): String {
        val mins = (ms / 60_000).coerceAtLeast(0)
        return when {
            mins < 1 -> t.get(R.string.s_just_now)
            mins < 60 -> t.get(R.string.ago_min, mins)
            mins < 48 * 60 -> t.get(R.string.ago_h, mins / 60)
            else -> t.get(R.string.s_ago_days, mins / (24 * 60))
        }
    }

    /**
     * r15 (UC2): an «up to date» answer is a fact about the moment of the check, so it says when: «No había versiones
     * nuevas cuando revisé, hace 1 h». [checkedAt] ≤ 0 = the time is not known: no age is claimed.
     */
    fun upToDate(t: Txt, checkedAt: Long, now: Long): String = when {
        checkedAt <= 0L -> t.get(R.string.s_uptodate_unknown)
        now - checkedAt < 60_000 -> t.get(R.string.s_uptodate_at, t.get(R.string.s_ago_moment))
        else -> t.get(R.string.s_uptodate_at, age(t, now - checkedAt))
    }

    /** The subtitle of "Buscar actualizaciones automáticamente" (OC14). */
    fun autoSubtitle(t: Txt): String = t.get(R.string.un_auto_subtitle, OpenCheck.OK_HOURS)

    /** The privacy line under "Versión instalada", true to what the app does (S2, OC14). */
    fun promise(t: Txt, autoCheck: Boolean): String =
        if (autoCheck) t.get(R.string.un_promise_auto, OpenCheck.OK_HOURS) else t.get(R.string.un_promise_manual)
}
