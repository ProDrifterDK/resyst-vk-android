package com.resyst.vk.core

/**
 * What a field allows, from its EditorInfo inputType / imeOptions (passed as plain ints so the
 * core stays JVM-pure; the constants are pinned against the platform in tests).
 *
 * The privacy gate (X1–X3): personal data — learned words and remembered values — is read and
 * written only when the field is not secret (passwords, PINs, phone), not incognito
 * (IME_FLAG_NO_PERSONALIZED_LEARNING) and the profile has both "Sugerencias" and "Sugerencias
 * personales" on. [suggestions] gates the static lexicon (the old `noSuggestField`).
 */
data class FieldPolicy(
    val kind: FieldKind,
    val suggestions: Boolean,
    val incognito: Boolean,
    /** Password variations and numeric PINs: no clipboard chip, capture or history, ever (r6). */
    val secret: Boolean = kind == FieldKind.PASSWORD,
) {

    fun personalWords(s: KbSettings): Boolean = suggestions && !incognito && s.suggest && s.personal

    /**
     * Email value memory. NO_SUGGESTIONS doesn't close it: email fields set that flag to stop
     * autocorrect from mangling addresses, and offering the user's own address is the point.
     */
    fun personalValues(s: KbSettings): Boolean = kind == FieldKind.EMAIL && !incognito && s.suggest && s.personal

    companion object {
        const val MASK_CLASS = 0x0000000f
        const val MASK_VARIATION = 0x00000ff0
        const val CLASS_TEXT = 1
        const val CLASS_NUMBER = 2
        const val CLASS_PHONE = 3
        const val CLASS_DATETIME = 4
        const val VARIATION_URI = 0x10
        const val VARIATION_EMAIL = 0x20
        const val VARIATION_PASSWORD = 0x80
        const val VARIATION_VISIBLE_PASSWORD = 0x90
        const val VARIATION_WEB_EMAIL = 0xd0
        const val VARIATION_WEB_PASSWORD = 0xe0
        const val NUMBER_VARIATION_PASSWORD = 0x10
        const val FLAG_NO_SUGGESTIONS = 0x80000
        const val IME_FLAG_NO_PERSONALIZED_LEARNING = 0x1000000

        fun of(inputType: Int, imeOptions: Int): FieldPolicy {
            val cls = inputType and MASK_CLASS
            val variation = inputType and MASK_VARIATION
            val kind = when {
                cls == CLASS_NUMBER || cls == CLASS_DATETIME -> FieldKind.NUMBER
                cls == CLASS_PHONE -> FieldKind.PHONE
                variation == VARIATION_EMAIL || variation == VARIATION_WEB_EMAIL -> FieldKind.EMAIL
                variation == VARIATION_URI -> FieldKind.URL
                variation == VARIATION_PASSWORD || variation == VARIATION_VISIBLE_PASSWORD ||
                    variation == VARIATION_WEB_PASSWORD -> FieldKind.PASSWORD
                else -> FieldKind.TEXT
            }
            val suggestions = kind == FieldKind.TEXT && inputType and FLAG_NO_SUGGESTIONS == 0
            // TYPE_NUMBER_VARIATION_PASSWORD shares its value with the text URI variation: only
            // meaningful together with the number class.
            val pin = cls == CLASS_NUMBER && variation == NUMBER_VARIATION_PASSWORD
            return FieldPolicy(kind, suggestions, imeOptions and IME_FLAG_NO_PERSONALIZED_LEARNING != 0,
                secret = kind == FieldKind.PASSWORD || pin)
        }
    }
}
