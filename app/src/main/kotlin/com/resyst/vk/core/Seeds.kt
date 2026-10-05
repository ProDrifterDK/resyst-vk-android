package com.resyst.vk.core

/**
 * A small, hand-written table of everyday continuations so next-word prediction works on a
 * fresh install ("hola, " → cómo). Generic language knowledge, not user data: it ranks below
 * everything the user taught the keyboard (B1) and never feeds the learned model.
 */
object Seeds {
    private val ES: Map<String, List<String>> = mapOf(
        "hola" to listOf("cómo", "qué", "buenas"),
        "cómo" to listOf("estás", "está", "te"),
        "como" to listOf("estás", "está", "te"),
        "qué" to listOf("tal", "haces", "pasa"),
        "que" to listOf("tal", "te", "no"),
        "buenos" to listOf("días"),
        "buenas" to listOf("tardes", "noches"),
        "muchas" to listOf("gracias"),
        "gracias" to listOf("por"),
        "por" to listOf("favor", "qué", "eso"),
        "de" to listOf("nada", "verdad"),
        "nos" to listOf("vemos"),
        "hasta" to listOf("luego", "mañana", "pronto"),
        "lo" to listOf("siento", "que"),
        "muy" to listOf("bien"),
        "está" to listOf("bien"),
        "todo" to listOf("bien"),
        "estoy" to listOf("bien"),
        "feliz" to listOf("cumpleaños", "día"),
        "un" to listOf("abrazo", "beso"),
        "no" to listOf("sé", "hay", "te"),
        "yo" to listOf("también"),
        "para" to listOf("que", "ti"),
        "te" to listOf("quiero", "parece"),
        "tal" to listOf("vez"),
        "sin" to listOf("embargo"),
    )

    private val EN: Map<String, List<String>> = mapOf(
        "hi" to listOf("how", "there"),
        "hello" to listOf("how", "there"),
        "hey" to listOf("how", "there"),
        "how" to listOf("are", "is", "about"),
        "are" to listOf("you"),
        "do" to listOf("you"),
        "can" to listOf("you"),
        "thank" to listOf("you"),
        "thanks" to listOf("for"),
        "good" to listOf("morning", "night", "luck"),
        "see" to listOf("you"),
        "take" to listOf("care"),
        "let" to listOf("me"),
        "of" to listOf("course"),
        "no" to listOf("problem"),
        "happy" to listOf("birthday"),
        "miss" to listOf("you"),
        "love" to listOf("you"),
        "what" to listOf("are", "is", "do"),
        "talk" to listOf("soon", "later"),
        "have" to listOf("fun", "you"),
        "will" to listOf("be"),
        "on" to listOf("my", "the"),
    )

    fun table(lang: Lang): Map<String, List<String>> = when (lang) {
        Lang.ES -> ES
        Lang.EN -> EN
    }

    fun next(lang: Lang, prev: String): List<String> = table(lang)[Tokens.key(prev)] ?: emptyList()
}
