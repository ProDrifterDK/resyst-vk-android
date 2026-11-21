package com.resyst.vk.ime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.resyst.vk.core.EmojiCatalog
import com.resyst.vk.core.EmojiSearch
import java.util.concurrent.Executors

/**
 * r15 (ES8): the emoji search index (assets/emoji/search.txt) for the keyboard. Parsed on a background thread when the
 * search opens, held while the keyboard is on screen, dropped when it hides ([release]): the IME process lives for days
 * and the index is only needed while someone searches. One index per interface language (the names TalkBack reads).
 */
object EmojiSearchAsset {
    private const val TAG = "ResystVK"
    @Volatile private var index: EmojiSearch? = null
    /** Bumped by [release]: a parse that finishes after it is dropped, not kept. */
    @Volatile private var generation = 0
    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "vk-emoji-search").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
    private val main = Handler(Looper.getMainLooper())

    /** The loaded index for [catalog] in [lang], or null while it loads (or when the asset is unusable). */
    fun ready(catalog: EmojiCatalog, lang: String): EmojiSearch? = index?.takeIf { it.lang == lang && it.size == EmojiSearch.cellsOf(catalog).size }

    /** Loads (unless ready) and hands the index to [done] on the main thread; a null answer = no search on this build. */
    fun load(context: Context, catalog: EmojiCatalog, lang: String, done: (EmojiSearch?) -> Unit) {
        ready(catalog, lang)?.let { done(it); return }
        val app = context.applicationContext
        val gen = generation
        loader.execute {
            val t0 = SystemClock.uptimeMillis()
            val idx = runCatching {
                EmojiSearch.parse(app.assets.open("emoji/search.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }, catalog, lang)
            }.onFailure { Log.w(TAG, "emoji search index not loaded", it) }.getOrNull()
            Log.i(TAG, "emoji-search: index ${if (idx != null) "loaded emoji=${idx.size} tokens=${idx.tokenCount} postings=${idx.postingCount}" else "unavailable"} ms=${SystemClock.uptimeMillis() - t0}")
            main.post {
                if (gen != generation) return@post // released meanwhile: the search that asked is over
                if (idx != null) index = idx
                done(idx)
            }
        }
    }

    /** The keyboard hid: the index goes (a later search reloads it). */
    fun release() {
        generation++
        if (index != null) Log.i(TAG, "emoji-search: index released")
        index = null
    }
}
