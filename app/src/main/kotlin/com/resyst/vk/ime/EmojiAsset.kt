package com.resyst.vk.ime

import android.content.Context
import android.util.Log
import com.resyst.vk.core.EmojiCatalog

/**
 * r11: the Unicode 18.0 catalog (assets/emoji/emoji.txt), parsed once per process and shared by
 * the keyboard and the settings screen, so both decode emoji_tones.txt against the same cells
 * (r11a-fix F3). A malformed asset line is skipped by the parser (EC4); a missing asset = EMPTY.
 */
object EmojiAsset {
    private const val TAG = "ResystVK"
    @Volatile private var cache: EmojiCatalog? = null

    fun catalog(context: Context): EmojiCatalog = cache ?: runCatching {
        EmojiCatalog.parse(context.assets.open("emoji/emoji.txt").bufferedReader(Charsets.UTF_8).use { it.readText() })
    }.onFailure { Log.w(TAG, "emoji catalog not loaded", it) }.getOrDefault(EmojiCatalog.EMPTY)
        .also { if (it.groups.isNotEmpty()) cache = it }
}
