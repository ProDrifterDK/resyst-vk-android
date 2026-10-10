package com.resyst.vk.ime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.resyst.vk.core.EmojiCatalog
import java.util.concurrent.Executors

/**
 * r11: the Unicode 18.0 catalog (assets/emoji/emoji.txt), parsed once per process and shared by
 * the keyboard and the settings screen, so both decode emoji_tones.txt against the same cells
 * (r11a-fix F3). A malformed asset line is skipped by the parser (EC4); a missing asset = EMPTY.
 *
 * r12 (SA1): settings never parses it on the main thread: it asks [load] (a background thread,
 * the answer posted to the main thread) and reads [cached] meanwhile. The keyboard keeps calling
 * [catalog] when the emoji key opens the panel.
 */
object EmojiAsset {
    private const val TAG = "ResystVK"
    @Volatile private var cache: EmojiCatalog? = null
    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "vk-emoji-asset").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    fun catalog(context: Context): EmojiCatalog = cache ?: run {
        val t0 = SystemClock.uptimeMillis()
        runCatching {
            EmojiCatalog.parse(context.assets.open("emoji/emoji.txt").bufferedReader(Charsets.UTF_8).use { it.readText() })
        }.onFailure { Log.w(TAG, "emoji catalog not loaded", it) }.getOrDefault(EmojiCatalog.EMPTY)
            .also {
                if (it.groups.isNotEmpty()) cache = it
                Log.i(TAG, "emoji catalog parsed: ${it.baseCount} ms=${SystemClock.uptimeMillis() - t0} thread=${Thread.currentThread().name}")
            }
    }

    /** The parsed catalog, or null while nobody parsed it yet in this process. Never parses. */
    fun cached(): EmojiCatalog? = cache

    /** SA1: parses on a background thread (unless already parsed) and hands the result to [done] on the main thread. */
    fun load(context: Context, done: (EmojiCatalog) -> Unit) {
        val app = context.applicationContext
        loader.execute {
            val c = catalog(app)
            main.post { done(c) }
        }
    }
}
