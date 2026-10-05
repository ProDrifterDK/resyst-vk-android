package com.resyst.vk.core

/**
 * Keyboard languages ⇄ Android IME subtypes. Android's language picker lists one entry per
 * enabled `<subtype>` of res/xml/method.xml; the app keeps its own per-profile [Lang].
 * This object holds the pure mapping and the sync decision so both stay in step.
 */
object Subtypes {
    /** Language tag declared in method.xml for each [Lang]. */
    fun tagFor(lang: Lang): String = when (lang) {
        Lang.ES -> "es"
        Lang.EN -> "en-US"
    }

    /** `es`, `es_CL`, `es-419`, `en_US`, `en-GB` → [Lang]; anything else → null. */
    fun langOf(localeOrTag: String?): Lang? {
        val primary = localeOrTag?.trim()?.split('_', '-')?.firstOrNull()?.lowercase()
        if (primary.isNullOrEmpty()) return null
        return Lang.values().firstOrNull { it.code == primary }
    }

    enum class Sync { NONE, PUSH_TO_SYSTEM, PULL_FROM_SYSTEM }

    /**
     * Three-way merge between the app's language, the system's current subtype and the
     * language both sides last agreed on. Whoever moved since the last agreement wins;
     * acting only on a difference means a push's own callback is a no-op (no ping-pong).
     */
    fun reconcile(app: Lang, system: Lang?, lastSynced: Lang?): Sync = when {
        system == app -> Sync.NONE
        system == null -> Sync.PUSH_TO_SYSTEM
        app != lastSynced -> Sync.PUSH_TO_SYSTEM
        else -> Sync.PULL_FROM_SYSTEM
    }
}
