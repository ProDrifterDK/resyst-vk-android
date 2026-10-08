package com.resyst.vk.ime

import android.content.Context
import com.resyst.vk.core.Lang
import com.resyst.vk.core.Region
import com.resyst.vk.core.Regional
import com.resyst.vk.core.Suggest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Loads assets/lexicon/{es,en}.txt off the main thread; suggestions appear once ready.
 * r10: the Spanish list is re-ranked for the chosen [region] (assets/lexicon/regional/<rule>.txt
 * through [Regional]); English ignores it. A region change loads Spanish again (once) and drops
 * the previous region's list.
 */
class Lexicon(context: Context) {
    private val assets = context.applicationContext.assets
    private val ready = ConcurrentHashMap<String, Suggest>()
    private val loading = ConcurrentHashMap.newKeySet<String>()
    private val io = Executors.newSingleThreadExecutor()

    /** Set from the settings before [warm]/[get]; main thread. */
    @Volatile var region: Region = Region.ES_CL

    private fun key(lang: Lang, r: Region = region) = if (lang == Lang.ES) "es:${r.name}" else lang.code

    fun warm(lang: Lang) {
        val r = region
        val k = key(lang, r)
        if (ready.containsKey(k) || !loading.add(k)) return
        io.execute {
            val words = read("lexicon/${lang.code}.txt")?.lineSequence()
                ?.map { it.trim() }?.filter { it.isNotEmpty() }?.toList() ?: emptyList()
            val ranked = if (lang == Lang.ES) Regional.forRegion(words, r) { read("lexicon/regional/$it.txt") } else words
            ready[k] = Suggest(ranked, lang)
            if (lang == Lang.ES) {
                ready.keys.removeIf { it.startsWith("es:") && it != k }
                loading.removeIf { it.startsWith("es:") && it != k }
            }
        }
    }

    private fun read(path: String): String? = runCatching {
        assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()

    fun get(lang: Lang): Suggest? = ready[key(lang)] ?: run { warm(lang); null }

    /** Only if already loaded (never triggers a load): the bilingual guard's other lexicon. */
    fun peek(lang: Lang): Suggest? = ready[key(lang)]
}
