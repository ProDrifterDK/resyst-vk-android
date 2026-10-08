package com.resyst.vk.core

/**
 * r9: the once-per-process gate of the startup update check (failure modes A1–A3,
 * docs/failure-modes.md). Pure: the Android side ([com.resyst.vk.settings.Updater]) passes the
 * facts and runs its one network path only when [claim] says so.
 *
 * Only the keyboard service's onCreate asks; the settings screen never does — it renders the state
 * the claimed check left (A4). A second service onCreate in the same process gets false (A1).
 */
class AutoCheckGate {
    private var claimed = false

    /** The automatic check was claimed in this process (ran, or was skipped because busy). */
    val ran: Boolean @Synchronized get() = claimed

    /**
     * True exactly once per gate (= per process), and only when the user allows it ([enabled]) and
     * the updater is free: [idle] and no download pending from a previous process. A busy updater
     * consumes the attempt (the user's own flow is the truth for this process); a disabled toggle
     * does not, so turning it on lets the next entry point check.
     */
    @Synchronized
    fun claim(enabled: Boolean, idle: Boolean, pendingDownload: Boolean): Boolean {
        if (claimed || !enabled) return false
        claimed = true
        return idle && !pendingDownload
    }
}

/** Where the keyboard strip shows an announced update (N4). */
enum class UpdateSurface { CHIP, BADGE, NONE }

/**
 * r9: what the keyboard and the settings screen show after the automatic check (failure modes
 * A5, N1–N4, docs/failure-modes.md). Pure, JVM-tested in Round9Test.
 */
object UpdateNotice {

    /**
     * What a finished automatic check leaves in the updater. Failures ([decision] null: offline,
     * timeout, HTTP error) and unreadable manifests are silent: null = back to idle, log only (A5).
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
    fun labels(version: String): List<Label> = listOf(
        Label("Resyst VK $version disponible", " · Toca para actualizar", twoLine = false),
        Label("Resyst VK $version disponible", "Toca para actualizar", twoLine = true),
        Label("VK $version disponible", "Toca para actualizar", twoLine = true),
        Label("$version disponible", "Toca para actualizar", twoLine = true),
        Label(version, "Actualizar", twoLine = true),
    )

    /**
     * The longest label that fits [room] px. [title] / [action] measure a text with the paint of
     * that line (`twoLine` = the smaller stacked sizes). null when not even the bare version fits:
     * the strip then shows only the ⚙ dot, never a cut version.
     */
    fun fit(
        version: String, room: Float,
        title: (text: String, twoLine: Boolean) -> Float,
        action: (text: String, twoLine: Boolean) -> Float,
    ): Label? = labels(version).firstOrNull { l ->
        val w = if (l.twoLine) maxOf(title(l.title, true), action(l.action, true))
        else title(l.title, false) + action(l.action, false)
        w <= room
    }

    /** The privacy line under "Versión instalada", true to what the app does (S2). */
    fun promise(autoCheck: Boolean): String = if (autoCheck) {
        "Su única conexión: al iniciar consulta una vez si hay una versión nueva en kv.resyst.cl, " +
            "sin enviar nada tuyo. Descarga actualizaciones solo cuando tú se lo pides."
    } else {
        "No se conecta a internet por sí solo. Descarga actualizaciones solo cuando tú se lo pides."
    }
}
