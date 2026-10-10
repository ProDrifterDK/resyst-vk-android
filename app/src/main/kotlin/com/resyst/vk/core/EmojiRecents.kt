package com.resyst.vk.core

/**
 * Most-recent-first list of emoji the user picked (on-device only, never written from an
 * incognito field). [MAX] entries; a re-pick moves the emoji to the front. r11: the catalog
 * itself is [EmojiCatalog]; a toned pick is stored as picked (EC6).
 */
class EmojiRecents(initial: List<String> = emptyList()) {
    private val items = ArrayList<String>()

    init { initial.forEach { if (valid(it) && it !in items && items.size < MAX) items += it } }

    fun items(): List<String> = items.toList()

    /** True when the list changed. */
    fun push(e: String): Boolean {
        if (!valid(e)) return false
        if (items.firstOrNull() == e) return false
        items.remove(e)
        items.add(0, e)
        while (items.size > MAX) items.removeAt(items.size - 1)
        return true
    }

    /** r10 ("Lo que sé de ti", V12): drops one recent. True when it was there. */
    fun remove(e: String): Boolean = items.remove(e)

    fun encode(): String = items.joinToString(SEP)

    companion object {
        const val MAX = 32
        private const val SEP = "\u001F"
        /**
         * An emoji is short and has no letters/digits/whitespace of its own (keycaps excepted).
         * r11 (EC6): the full catalog's longest sequence (kiss, mixed tones) is 15 UTF-16 units, and
         * ℹ️ is a BMP "letter" that only the emoji presentation selector (FE0F) marks as an emoji.
         */
        fun valid(e: String): Boolean =
            e.isNotEmpty() && e.length <= 16 && e.none { it.isWhitespace() || it == '\u001F' } &&
                (e.none { it.isLetter() } || e.any { Character.isSurrogate(it) || it == '\uFE0F' })

        fun decode(s: String?): EmojiRecents = EmojiRecents(s?.split(SEP)?.filter { it.isNotEmpty() } ?: emptyList())
    }
}
