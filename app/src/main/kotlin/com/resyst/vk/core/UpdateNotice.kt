package com.resyst.vk.core

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

    /** The subtitle of "Buscar actualizaciones automáticamente" (OC14). */
    fun autoSubtitle(): String =
        "Al abrir el teclado, como mucho una vez cada ${OpenCheck.OK_HOURS} horas. Nunca envía lo que escribes."

    /** The privacy line under "Versión instalada", true to what the app does (S2, OC14). */
    fun promise(autoCheck: Boolean): String = if (autoCheck) {
        "Su única conexión: al abrir el teclado consulta en kv.resyst.cl si hay una versión nueva, " +
            "como mucho una vez cada ${OpenCheck.OK_HOURS} horas, sin enviar nada de lo que escribes. " +
            "Descarga actualizaciones solo cuando tú se lo pides."
    } else {
        "No se conecta a internet por sí solo. Descarga actualizaciones solo cuando tú se lo pides."
    }
}
