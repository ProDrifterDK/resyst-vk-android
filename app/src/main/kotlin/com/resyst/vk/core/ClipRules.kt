package com.resyst.vk.core

/**
 * The system clipboard's primary clip, flattened so the rules stay JVM-pure.
 * [stamp] = ClipDescription.getTimestamp (wall clock, API 26+), 0 when unknown.
 * [sensitive] = ClipDescription.EXTRA_IS_SENSITIVE (password managers set it).
 */
data class ClipSnapshot(
    val text: String?,
    val mimeTypes: List<String>,
    val hasUri: Boolean,
    val stamp: Long,
    val sensitive: Boolean,
)

/** What the leading strip chip offers. */
sealed class ClipOffer {
    abstract val stamp: Long
    data class Text(val text: String, val label: String, override val stamp: Long) : ClipOffer()
    data class Image(val mime: String, override val stamp: Long) : ClipOffer()
}

/** Device-wide clipboard settings (not per profile: the history itself is device-wide). */
data class ClipSettings(val history: Boolean = true, val purgeHour: Boolean = false)

/**
 * The clipboard decisions (r6): when the "Pegar" chip shows, what is captured into the history,
 * where the history may be read. Secret fields (passwords, PINs) are closed on every path.
 */
object ClipRules {
    /** A clip copied longer ago than this is not offered (the history still has it). */
    const val FRESH_MS = 5 * 60 * 1000L
    const val LABEL_MAX = 24
    const val MASK = "••••••"

    /**
     * The chip for [clip] in a field with [policy], or null. [acceptMimes] = the field's
     * EditorInfo.contentMimeTypes (commitContent support). [consumedStamp] = the clip already
     * pasted or dismissed from the chip (it doesn't come back until something new is copied).
     */
    fun offer(clip: ClipSnapshot?, policy: FieldPolicy, acceptMimes: List<String>, now: Long, consumedStamp: Long): ClipOffer? {
        if (clip == null || policy.secret) return null
        if (clip.stamp != 0L && (clip.stamp == consumedStamp || now - clip.stamp > FRESH_MS)) return null
        if (clip.hasUri) {
            val img = clip.mimeTypes.firstOrNull { m -> m.startsWith("image/", ignoreCase = true) && acceptMimes.any { mimeMatches(it, m) } }
            if (img != null) return ClipOffer.Image(img, clip.stamp)
        }
        val t = clip.text ?: return null
        if (t.isBlank() || !ClipboardHistory.fits(t)) return null
        // number / phone fields: only clips that carry a digit ("hola" can't go there)
        if ((policy.kind == FieldKind.NUMBER || policy.kind == FieldKind.PHONE) && t.none { it.isDigit() }) return null
        return ClipOffer.Text(t, label(t, clip.sensitive), clip.stamp)
    }

    /** May a clip be captured into the history while [policy]'s field is focused (null = none)? */
    fun mayCapture(policy: FieldPolicy?, s: ClipSettings, sensitive: Boolean): Boolean =
        s.history && !sensitive && (policy == null || (!policy.secret && !policy.incognito))

    /** May the history panel open (the history be read) in this field? */
    fun mayShowHistory(policy: FieldPolicy, s: ClipSettings): Boolean = s.history && !policy.secret

    /** "ahora", "hace 5 min", "hace 2 h", "hace 3 d" — the panel's age label. */
    fun ago(now: Long, at: Long): String {
        val s = ((now - at) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "ahora"
            s < 3600 -> "hace ${s / 60} min"
            s < 86_400 -> "hace ${s / 3600} h"
            else -> "hace ${s / 86_400} d"
        }
    }

    /** `type/subtype` against a pattern with `*` wildcards, case-insensitive. */
    fun mimeMatches(pattern: String, mime: String): Boolean {
        val p = pattern.lowercase().split('/')
        val m = mime.lowercase().split('/')
        if (p.size != 2 || m.size != 2) return false
        return (p[0] == "*" || p[0] == m[0]) && (p[1] == "*" || p[1] == m[1])
    }

    /** One short line: whitespace collapsed, capped at [LABEL_MAX] with an ellipsis, masked if sensitive. */
    fun label(text: String, sensitive: Boolean): String {
        if (sensitive) return MASK
        val one = text.trim().replace(Regex("\\s+"), " ")
        if (one.length <= LABEL_MAX) return one
        var n = LABEL_MAX - 1
        if (Character.isHighSurrogate(one[n - 1])) n--
        return one.substring(0, n) + "…"
    }
}
