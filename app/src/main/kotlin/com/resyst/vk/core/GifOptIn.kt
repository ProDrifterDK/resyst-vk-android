package com.resyst.vk.core

/**
 * r11b (GO1–GO3, GI1): the GIF search consent + its anonymous ID, as one immutable value.
 *
 * OFF by default. [accept] (the disclosure's «Activar») turns it on, stamps the disclosure
 * version and creates a fresh random UUID; [newId] replaces the UUID; [turnOff] deletes it. The
 * feature may run only when [active]: on, the current disclosure accepted, and a well-formed id —
 * any partial or corrupt stored state reads as OFF (fail closed). Stored as three strings in the
 * GIF client's own prefs file (excluded from backup and device transfer).
 */
data class GifOptIn(val on: Boolean = false, val consentVersion: Int = 0, val customerId: String? = null) {

    val active: Boolean get() = on && consentVersion == DISCLOSURE_VERSION && customerId != null && UUID.matches(customerId)

    fun accept(newUuid: () -> String): GifOptIn {
        val id = newUuid()
        require(UUID.matches(id)) { "not a random v4 UUID" }
        return GifOptIn(true, DISCLOSURE_VERSION, id)
    }

    /** «Nuevo ID anónimo»: only while active (an OFF state has no id to replace). */
    fun newId(newUuid: () -> String): GifOptIn = if (!active) this else accept(newUuid).let { copy(customerId = it.customerId) }

    fun turnOff(): GifOptIn = GifOptIn()

    fun encode(): Map<String, String?> = mapOf(
        K_ON to on.toString(),
        K_CONSENT to consentVersion.toString(),
        K_ID to customerId,
    )

    companion object {
        /** Bump when the disclosure's content changes: an older consent no longer counts (GO3). */
        const val DISCLOSURE_VERSION = 1
        const val K_ON = "gif.on"
        const val K_CONSENT = "gif.consent"
        const val K_ID = "gif.id"
        /** java.util.UUID.randomUUID().toString(): version 4, IETF variant, lower-case hex. */
        val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

        fun decode(get: (String) -> String?): GifOptIn {
            val on = get(K_ON) == "true"
            val v = get(K_CONSENT)?.toIntOrNull() ?: 0
            val id = get(K_ID)?.takeIf { UUID.matches(it) }
            val s = GifOptIn(on, v, id)
            return if (s.active) s else GifOptIn() // fail closed: a broken state is OFF and holds no id
        }
    }
}

/**
 * r11b (GQ1–GQ3): what the user types in the GIF search box. A private buffer: keys never reach
 * the app's InputConnection, the personal model, the corrector or the suggestions. Bounded; ⌫
 * removes one whole code point.
 */
class GifQuery {
    var text: String = ""
        private set

    fun type(s: String): Boolean {
        if (s.isEmpty() || s.any { it == '\n' || it == '\r' || it == '\t' }) return false
        val next = text + s
        if (next.codePointCount(0, next.length) > MAX) return false
        text = next
        return true
    }

    fun backspace(): Boolean {
        if (text.isEmpty()) return false
        text = text.substring(0, text.offsetByCodePoints(text.length, -1))
        return true
    }

    fun clear() { text = "" }

    /** What a search sends: trimmed, inner whitespace collapsed; null = blank (= trending). */
    fun submitted(): String? = text.trim().replace(Regex("\\s+"), " ").ifEmpty { null }

    companion object { const val MAX = 50 }
}
