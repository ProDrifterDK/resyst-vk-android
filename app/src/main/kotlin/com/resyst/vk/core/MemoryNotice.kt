package com.resyst.vk.core

/** r10 (F-6): why the keyboard is not learning in this field. Most specific first (V9). */
enum class NoMemory {
    SECRET,
    INCOGNITO,
    // r13d (OR6): an opted-out field still READS what was learned elsewhere; it only never stores
    OPTED_OUT,
    MODE,
    SETTING_OFF,
    ;

    /** The dot's short line (`nomem_<name>`) and its longer explanation (`nomem_<name>_detail`). */
    fun label(t: Txt): String = t.need("nomem_" + name.lowercase())
    fun detail(t: Txt): String = t.need("nomem_" + name.lowercase() + "_detail")
}

/**
 * r10 (F-6, V8–V10): the "sin memoria" dot. It shows exactly when the privacy gate keeps
 * personal data closed in a field where the user writes words — so it never claims "memory on"
 * where nothing is learned (r13d, OR6: in an opted-out field the learned words are still read, and
 * the dot says that nothing typed there is stored), and stays out of number / URL / phone fields that have no words to
 * learn (a permanent dot there would be noise). Pure: the IME passes the field policy and both the
 * effective settings ([s], a mode applied) and the user's own ([user]) to tell a mode from a switch.
 */
object MemoryNotice {
    fun reason(policy: FieldPolicy, s: KbSettings, user: KbSettings): NoMemory? {
        if (policy.personalWords(s) || policy.personalValues(s)) return null
        return when {
            policy.secret -> NoMemory.SECRET
            policy.kind != FieldKind.TEXT && policy.kind != FieldKind.EMAIL -> null
            policy.incognito -> NoMemory.INCOGNITO
            policy.kind == FieldKind.TEXT && policy.optedOut -> NoMemory.OPTED_OUT
            !(user.suggest && user.personal) -> NoMemory.SETTING_OFF
            else -> NoMemory.MODE
        }
    }

    /** The key-preview bubble: only when the user wants it and never over a secret field (V10). */
    fun bubble(popups: Boolean, secret: Boolean): Boolean = popups && !secret
}
