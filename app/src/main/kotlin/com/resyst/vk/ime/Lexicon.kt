package com.resyst.vk.ime

import android.content.Context
import com.resyst.vk.core.Lang
import com.resyst.vk.core.Suggest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Loads assets/lexicon/{es,en}.txt off the main thread; suggestions appear once ready. */
class Lexicon(context: Context) {
    private val assets = context.applicationContext.assets
    private val ready = ConcurrentHashMap<Lang, Suggest>()
    private val loading = ConcurrentHashMap.newKeySet<Lang>()
    private val io = Executors.newSingleThreadExecutor()

    fun warm(lang: Lang) {
        if (ready.containsKey(lang) || !loading.add(lang)) return
        io.execute {
            val words = runCatching {
                assets.open("lexicon/${lang.code}.txt").bufferedReader(Charsets.UTF_8).useLines { seq ->
                    seq.map { it.trim() }.filter { it.isNotEmpty() }.toList()
                }
            }.getOrDefault(emptyList())
            ready[lang] = Suggest(words, lang)
        }
    }

    fun get(lang: Lang): Suggest? = ready[lang] ?: run { warm(lang); null }
}
