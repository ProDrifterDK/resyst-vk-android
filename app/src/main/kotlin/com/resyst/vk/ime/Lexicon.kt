package com.resyst.vk.ime

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.resyst.vk.core.Lang
import com.resyst.vk.core.Region
import com.resyst.vk.core.Regional
import com.resyst.vk.core.Suggest
import com.resyst.vk.core.ValidWords
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Loads assets/lexicon/{es,en}.txt off the main thread; suggestions appear once ready.
 * r10: the Spanish list is re-ranked for the chosen [region] (assets/lexicon/regional/<rule>.txt
 * through [Regional]); English ignores it. A region change loads Spanish again (once) and drops
 * the previous region's list.
 * r13a: the same io task then maps the language's valid-word set ([valid]); region-independent,
 * loaded once per process.
 */
class Lexicon(context: Context) {
    private val assets = context.applicationContext.assets
    private val ready = ConcurrentHashMap<String, Suggest>()
    private val loading = ConcurrentHashMap.newKeySet<String>()
    private val validSets = ConcurrentHashMap<Lang, ValidWords>()
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
            if (!validSets.containsKey(lang)) loadValid(lang)?.let { validSets[lang] = it }
        }
    }

    private fun read(path: String): String? = runCatching {
        assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()

    /**
     * r13a (VW9–VW11): maps the uncompressed APK entry (build.gradle.kts noCompress "dawg"): no heap
     * copy, clean file-backed pages the kernel can drop under pressure. An entry that cannot be
     * mapped (compressed) is read into one array; a missing or broken asset leaves the set absent.
     */
    private fun loadValid(lang: Lang): ValidWords? {
        val t0 = SystemClock.uptimeMillis()
        val path = ValidWords.assetPath(lang)
        var mapped = true
        val buf: ByteBuffer? = runCatching {
            assets.openFd(path).use { fd ->
                FileInputStream(fd.fileDescriptor).use { it.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength) }
            }
        }.getOrNull() ?: runCatching {
            mapped = false
            ByteBuffer.wrap(assets.open(path).use { it.readBytes() })
        }.getOrNull()
        val set = buf?.let { ValidWords.read(it) }
        // VW10: the first call of a fresh process is the slow one (class init, interpreter): pay
        // it here on the io thread, not at the first word typed
        set?.contains("que")
        set?.accentVariants("que")
        Log.i(TAG, "lexicon: valid-${lang.code} words=${set?.size} mapped=$mapped ms=${SystemClock.uptimeMillis() - t0} thread=${Thread.currentThread().name}")
        return set
    }

    fun get(lang: Lang): Suggest? = ready[key(lang)] ?: run { warm(lang); null }

    /** Only if already loaded (never triggers a load): the bilingual guard's other lexicon. */
    fun peek(lang: Lang): Suggest? = ready[key(lang)]

    /**
     * r13a: [lang]'s full-form valid-word set once [warm] has loaded it, else null (never triggers
     * a load). No correction reads it in r13a (VW12).
     */
    fun valid(lang: Lang): ValidWords? = validSets[lang]

    private companion object {
        const val TAG = "ResystVK"
    }
}
