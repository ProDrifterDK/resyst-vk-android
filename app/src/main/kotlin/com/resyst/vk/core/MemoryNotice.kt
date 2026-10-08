package com.resyst.vk.core

/** r10 (F-6): why the keyboard is not learning in this field. Most specific first (V9). */
enum class NoMemory(val label: String, val detail: String) {
    SECRET("Sin memoria · contraseña", "Campo de contraseña: el teclado no aprende, no sugiere y no guarda nada de lo que escribes aquí."),
    INCOGNITO("Sin memoria · incógnito", "La app pidió modo incógnito: nada de lo que escribes aquí se aprende ni se recuerda."),
    OPTED_OUT("Sin memoria en este campo", "La app pidió no recibir sugerencias aquí: el teclado no aprende de este campo."),
    MODE("Sin memoria · modo", "El modo encendido no aprende lo que escribes. Al apagarlo vuelve tu ajuste."),
    SETTING_OFF("Sin memoria", "«Sugerencias personales» está apagado: el teclado no aprende de ti en ningún campo."),
}

/**
 * r10 (F-6, V8–V10): the "sin memoria" dot. It shows exactly when the privacy gate keeps
 * personal data closed in a field where the user writes words — so it never claims "memory on"
 * where nothing is learned, and stays out of number / URL / phone fields that have no words to
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
