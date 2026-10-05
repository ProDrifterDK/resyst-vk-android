package com.resyst.vk.ime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import android.util.Log
import com.resyst.vk.core.PersonalModel
import java.io.File
import java.util.concurrent.Executors

/**
 * The learned data, one instance per process: the IME service learns into it, the settings
 * screen wipes it ("Borrar lo aprendido"), so both must see the same object. Private files
 * under filesDir/personal (excluded from backup + device transfer — res/xml backup rules), read off the main
 * thread, written atomically (AtomicFile) and debounced. Everything is touched on the main
 * thread; only file I/O runs on [io].
 */
object PersonalStore {
    private const val TAG = "ResystVK"
    private const val DIR = "personal"
    private const val WORDS = "words.json"
    private const val SAVE_DELAY_MS = 1500L

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var dir: File? = null
    private var generation = 0

    /** null until loaded: nothing is learned or suggested from personal data before that. */
    var words: PersonalModel? = null
        private set

    fun init(context: Context) {
        if (dir != null) return
        val d = File(context.applicationContext.filesDir, DIR)
        dir = d
        val gen = generation
        io.execute {
            val m = read(File(d, WORDS))?.let(PersonalModel::fromJson) ?: PersonalModel()
            main.post { if (gen == generation && words == null) words = m }
        }
    }

    /** Something was learned: persist soon (coalesces bursts of typing into one write). */
    fun changed() {
        main.removeCallbacks(saveNow)
        main.postDelayed(saveNow, SAVE_DELAY_MS)
    }

    /** Persist now (field closed, service destroyed). */
    fun flush() {
        main.removeCallbacks(saveNow)
        saveNow.run()
    }

    private val saveNow = Runnable {
        val d = dir ?: return@Runnable
        val json = words?.toJson() ?: return@Runnable
        io.execute { write(File(d, WORDS), json) }
    }

    /** Wipes memory and disk; a load still in flight is discarded. */
    fun clear(context: Context) {
        generation++
        main.removeCallbacks(saveNow)
        words = PersonalModel()
        val d = dir ?: File(context.applicationContext.filesDir, DIR).also { dir = it }
        io.execute { d.listFiles()?.forEach { it.delete() } }
    }

    private fun read(f: File): String? = runCatching {
        if (!AtomicFile(f).baseFile.exists()) null else String(AtomicFile(f).readFully(), Charsets.UTF_8)
    }.onFailure { Log.w(TAG, "personal: read ${f.name} failed", it) }.getOrNull()

    private fun write(f: File, text: String) {
        f.parentFile?.mkdirs()
        val af = AtomicFile(f)
        val out = runCatching { af.startWrite() }.getOrElse { Log.w(TAG, "personal: write failed", it); return }
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            af.finishWrite(out)
        } catch (e: Exception) {
            af.failWrite(out)
            Log.w(TAG, "personal: write ${f.name} failed", e)
        }
    }
}
