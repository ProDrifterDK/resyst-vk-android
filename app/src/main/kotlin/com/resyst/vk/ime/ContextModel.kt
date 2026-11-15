package com.resyst.vk.ime

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.resyst.vk.core.ContextNgram
import java.io.FileInputStream
import java.nio.channels.FileChannel

/**
 * r13f (offers, HP1, HP2, AS1): the context-offer model (assets/context/wt-m2.vkng), memory-mapped from the
 * uncompressed APK entry (build.gradle.kts noCompress "vkng") the first time an offer may be needed — clean,
 * file-backed pages the kernel can drop, never a heap copy (an entry that cannot be mapped means no offers, not a
 * 11.8 MB read). Released with the IME and when the switch goes off. Used from the offers' own thread only.
 */
class ContextModel(context: Context, private val debug: Boolean) {
    private val assets = context.applicationContext.assets
    @Volatile private var model: ContextNgram? = null
    /** AS1: a missing, compressed or broken asset is reported once and never retried in this process. */
    @Volatile private var failed = false

    /** The model, mapped on first use (call off the main thread); null when the asset is unusable. */
    fun get(): ContextNgram? {
        model?.let { return it }
        if (failed) return null
        synchronized(this) {
            model?.let { return it }
            if (failed) return null
            val t0 = SystemClock.uptimeMillis()
            val heap0 = if (debug) heapUsed() else 0L
            val m = runCatching {
                val buf = assets.openFd(PATH).use { fd ->
                    FileInputStream(fd.fileDescriptor).use { it.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength) }
                }
                ContextNgram.read(buf)
            }.onFailure {
                failed = true
                Log.w(TAG, "context: model unavailable (${it.javaClass.simpleName}): no offers")
            }.getOrNull() ?: return null
            model = m
            // HP1 (debuggable builds): the Java heap the model keeps, measured around a GC; never text
            val heap = if (debug) " heapKb=${(heapUsed() - heap0) / 1024}" else ""
            Log.i(TAG, "context: model mapped vocab=${m.vocabSize} ms=${SystemClock.uptimeMillis() - t0}$heap thread=${Thread.currentThread().name}")
            return m
        }
    }

    /** HP2: drop the model; the mapping goes with its buffer. A later [get] maps it again. */
    fun release() {
        if (model != null) Log.i(TAG, "context: model released")
        model = null
    }

    private fun heapUsed(): Long {
        val rt = Runtime.getRuntime()
        repeat(2) { System.gc(); Thread.sleep(40) }
        return rt.totalMemory() - rt.freeMemory()
    }

    companion object {
        const val PATH = "context/wt-m2.vkng"
        private const val TAG = "ResystVK"
    }
}
