package com.resyst.vk.core

/**
 * What a field allows, from its EditorInfo inputType / imeOptions (passed as plain ints so the
 * core stays JVM-pure; the constants are pinned against the platform in tests).
 *
 * The privacy gate (X1–X3): personal data — learned words and remembered values — is read and
 * written only when the field is not secret (passwords, PINs, phone), not incognito
 * (IME_FLAG_NO_PERSONALIZED_LEARNING), did not opt out of suggestions, and the profile has both
 * "Sugerencias" and "Sugerencias personales" on. [suggestions] gates the static lexicon + the
 * space correction.
 *
 * r8 (social apps): message composers (Instagram & co.) often carry TYPE_TEXT_FLAG_NO_SUGGESTIONS
 * because the app draws its own @mention / #hashtag dropdown, not because correction is unwanted —
 * the same text typed into a notification reply (RemoteInput, no flag) was corrected. A field
 * that is prose by its own declaration ([prose]) gets the on-device lexicon and the space
 * correction anyway; the flag still closes learning ([optedOut]): nothing is learned there.
 * Secret, email, URL, number and phone fields are never prose.
 *
 * r11 (X6, deliberate change to r8): reading and writing personal words are separate gates. Such a
 * prose composer now OFFERS the words the user already taught the keyboard elsewhere ([offerWords])
 * — the DM is where they write their names and slang — but still never LEARNS from it
 * ([personalWords]). Handles, opted-out search boxes, secret, incognito and non-text fields stay
 * closed both ways.
 */
data class FieldPolicy(
    val kind: FieldKind,
    val suggestions: Boolean,
    val incognito: Boolean,
    /** Password variations and numeric PINs: no clipboard chip, capture or history, ever (r6). */
    val secret: Boolean = kind == FieldKind.PASSWORD,
    /** The field set TYPE_TEXT_FLAG_NO_SUGGESTIONS (personal data stays closed even if [suggestions]). */
    val optedOut: Boolean = false,
) {

    /** WRITE gate: words typed in this field may be learned (X1–X3, X6: never in an opted-out field). */
    fun personalWords(s: KbSettings): Boolean = suggestions && !optedOut && !incognito && s.suggest && s.personal

    /**
     * r11 READ gate (X6): the learned words may be offered / protect a typed word from space here.
     * [suggestions] is only true with the opt-out for prose composers (never search, handles or
     * non-text kinds), so this opens exactly those plus everything [personalWords] opens.
     */
    fun offerWords(s: KbSettings): Boolean = suggestions && !incognito && s.suggest && s.personal

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
        const val VARIATION_EMAIL_SUBJECT = 0x30
        const val VARIATION_SHORT_MESSAGE = 0x40
        const val VARIATION_LONG_MESSAGE = 0x50
        const val VARIATION_PASSWORD = 0x80
        const val VARIATION_VISIBLE_PASSWORD = 0x90
        const val VARIATION_WEB_EDIT_TEXT = 0xa0
        const val VARIATION_WEB_EMAIL = 0xd0
        const val VARIATION_WEB_PASSWORD = 0xe0
        const val NUMBER_VARIATION_PASSWORD = 0x10
        const val FLAG_CAP_SENTENCES = 0x4000
        const val FLAG_AUTO_CORRECT = 0x8000
        const val FLAG_MULTI_LINE = 0x20000
        const val FLAG_NO_SUGGESTIONS = 0x80000
        const val IME_FLAG_NO_PERSONALIZED_LEARNING = 0x1000000
        const val IME_MASK_ACTION = 0xff
        const val IME_ACTION_SEARCH = 3

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
            val optedOut = inputType and FLAG_NO_SUGGESTIONS != 0
            // a search box that opted out is looking up names / handles, not writing prose
            val search = imeOptions and IME_MASK_ACTION == IME_ACTION_SEARCH
            val suggestions = kind == FieldKind.TEXT && (!optedOut || (prose(inputType) && !search))
            // TYPE_NUMBER_VARIATION_PASSWORD shares its value with the text URI variation: only
            // meaningful together with the number class.
            val pin = cls == CLASS_NUMBER && variation == NUMBER_VARIATION_PASSWORD
            return FieldPolicy(kind, suggestions, imeOptions and IME_FLAG_NO_PERSONALIZED_LEARNING != 0,
                secret = kind == FieldKind.PASSWORD || pin, optedOut = optedOut)
        }

        /**
         * A text field that declares itself as sentences someone writes: multi-line, sentence
         * capitalization, explicit AUTO_CORRECT, or a message / subject / long-text variation.
         * Usernames, codes and handles declare none of these (single line, no caps).
         */
        fun prose(inputType: Int): Boolean {
            if (inputType and MASK_CLASS != CLASS_TEXT) return false
            val variation = inputType and MASK_VARIATION
            if (variation == VARIATION_SHORT_MESSAGE || variation == VARIATION_LONG_MESSAGE || variation == VARIATION_EMAIL_SUBJECT) return true
            return inputType and (FLAG_MULTI_LINE or FLAG_CAP_SENTENCES or FLAG_AUTO_CORRECT) != 0
        }

        /** One log line describing a field (debug builds): flags only, never text. */
        fun describe(inputType: Int, imeOptions: Int): String {
            val p = of(inputType, imeOptions)
            return "inputType=0x%x imeOptions=0x%x → kind=%s suggestions=%s optedOut=%s prose=%s incognito=%s secret=%s"
                .format(inputType, imeOptions, p.kind, p.suggestions, p.optedOut, prose(inputType), p.incognito, p.secret)
        }
    }
}
