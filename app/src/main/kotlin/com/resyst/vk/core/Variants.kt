package com.resyst.vk.core

/**
 * Long-press alternatives. The FIRST entry is the default: it opens under the finger,
 * so releasing without moving commits it (e.g. long-press "e" → "é").
 */
object Variants {

    private val COMMON: Map<String, List<String>> = mapOf(
        // letters — Spanish accents first
        "a" to listOf("á", "à", "ä", "â", "ã", "å", "æ", "ª"),
        "e" to listOf("é", "è", "ë", "ê", "€"),
        "i" to listOf("í", "ì", "ï", "î"),
        "o" to listOf("ó", "ò", "ö", "ô", "õ", "ø", "º", "œ"),
        "u" to listOf("ú", "ü", "ù", "û"),
        "n" to listOf("ñ"),
        "c" to listOf("ç", "©"),
        "s" to listOf("ß", "§", "$"),
        "y" to listOf("ý", "ÿ"),
        "l" to listOf("ł"),
        "z" to listOf("ž"),
        // accent row
        "á" to listOf("à", "â", "ä"),
        "é" to listOf("è", "ê", "ë"),
        "í" to listOf("ì", "î", "ï"),
        "ó" to listOf("ò", "ô", "ö"),
        "ú" to listOf("ù", "û", "ü"),
        "¿" to listOf("?"),
        "¡" to listOf("!"),
        // punctuation
        "?" to listOf("¿", "‽"),
        "!" to listOf("¡", "‼"),
        "." to listOf("…", ",", ";", ":", "·", "!", "?"),
        "," to listOf(";", ":", "'", "\"", "¿", "¡"),
        "-" to listOf("–", "—", "_", "·"),
        "'" to listOf("‘", "’", "´", "`"),
        "\"" to listOf("«", "»", "“", "”", "„"),
        "€" to listOf("$", "£", "¥", "¢", "₩"),
        "$" to listOf("€", "£", "¥", "¢"),
        "(" to listOf("[", "{", "<"),
        ")" to listOf("]", "}", ">"),
        "%" to listOf("‰"),
        "+" to listOf("±"),
        "=" to listOf("≠", "≈"),
        "*" to listOf("×", "★", "†"),
        "/" to listOf("÷", "\\"),
        "#" to listOf("№"),
        "&" to listOf("§"),
        "_" to listOf("‾"),
        "<" to listOf("≤", "«"),
        ">" to listOf("≥", "»"),
        // digits
        "0" to listOf("°", "+", "⁰", "∅"),
        "1" to listOf("¹", "½", "⅓", "¼"),
        "2" to listOf("²", "⅔"),
        "3" to listOf("³", "¾"),
        "4" to listOf("⁴"),
        "5" to listOf("⁵"),
        "6" to listOf("⁶"),
        "7" to listOf("⁷"),
        "8" to listOf("⁸", "∞"),
        "9" to listOf("⁹"),
    )

    private val EN_OVERRIDES: Map<String, List<String>> = mapOf(
        "a" to listOf("à", "á", "â", "ä", "æ", "ã", "å"),
        "e" to listOf("é", "è", "ê", "ë", "€"),
        "n" to listOf("ñ"),
    )

    fun table(lang: Lang): Map<String, List<String>> =
        if (lang == Lang.EN) COMMON + EN_OVERRIDES else COMMON

    fun forKey(base: String, lang: Lang): List<String> = table(lang)[base] ?: emptyList()

    /** Uppercase a single character; never turns one char into two (ß stays ß). */
    fun shiftChar(s: String): String =
        if (s.length == 1) s[0].uppercaseChar().toString() else s

    fun applyShift(list: List<String>, shifted: Boolean): List<String> =
        if (shifted) list.map(::shiftChar) else list
}
