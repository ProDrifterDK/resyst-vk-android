package com.resyst.vk.core

/**
 * r12 (UR1–UR3, docs/failure-modes.md): who asked for the update answer the settings card shows,
 * and how that survives a process restart. Pure; JVM-tested in Round12UpdateOriginTest.
 *
 * The "available" manifest is stored by `Updater.remember()` for both paths (the keyboard-open
 * check and the user's tap; 0.7.1 and 0.8.0 wrote it from both without saying which), so a stored
 * manifest without [KEY] has an unknown origin and restores with the neutral line.
 */
object UpdateOrigin {
    enum class Origin { OPEN, USER, UNKNOWN }

    /** Updater prefs key next to `availManifest`: true = the keyboard-open check, false = the user. */
    const val KEY = "availAuto"

    /** Updater prefs written by older versions and never read again: removed once (UR3). */
    val ORPHANS: List<String> = listOf("autoAt")

    fun of(auto: Boolean): Origin = if (auto) Origin.OPEN else Origin.USER

    /** The origin a stored value restores to; anything but a boolean (missing, 0.8.0 data) is unknown (UR2). */
    fun restored(raw: Any?): Origin = when (raw) {
        true -> Origin.OPEN
        false -> Origin.USER
        else -> Origin.UNKNOWN
    }

    /** The settings card's first words (UR1): only a keyboard-open answer says so. */
    fun checkedLabel(o: Origin): String = if (o == Origin.OPEN) "Comprobado al abrir el teclado" else "Comprobado"

    /** Of [stored] (the updater prefs' keys), the ones to remove: the orphans only, nothing else (UR3). */
    fun cleanup(stored: Set<String>): List<String> = ORPHANS.filter { it in stored }
}
