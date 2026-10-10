package com.resyst.vk.ime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import android.util.Log
import com.resyst.vk.core.PersonalModel
import com.resyst.vk.core.ValueMemory
import java.io.File
import java.util.concurrent.Executors

/**
 * The learned data, one instance per process: the IME service learns into it, the settings
 * screen wipes it ("Borrar lo aprendido"), so both must see the same objects. Two separate
 * stores — [words] (n-grams) and [values] (whole field values) — in private files under
 * filesDir/personal (excluded from backup + device transfer by the res/xml backup rules),
 * read off the main thread, written atomically (AtomicFile) and debounced. Everything is
 * touched on the main thread; only file I/O runs on [io].
 */
object PersonalStore {
    private const val TAG = "ResystVK"
    private const val DIR = "personal"
    private const val WORDS = "words.json"
    private const val VALUES = "values.json"
    private const val SAVE_DELAY_MS = 1500L

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var dir: File? = null
    private var generation = 0
    private var dirty = false

    /** null until loaded: nothing is learned or suggested from personal data before that. */
    var words: PersonalModel? = null
        private set
    var values: ValueMemory? = null
        private set

    fun init(context: Context) {
        if (dir != null) return
        val d = File(context.applicationContext.filesDir, DIR)
        dir = d
        val gen = generation
        io.execute {
            val w = read(File(d, WORDS))?.let(PersonalModel::fromJson) ?: PersonalModel()
            val v = read(File(d, VALUES))?.let(ValueMemory::fromJson) ?: ValueMemory()
            main.post {
                if (gen != generation) return@post
                if (words == null) words = w
                if (values == null) values = v
            }
        }
    }

    /** Something was learned: persist soon (coalesces bursts of typing into one write). */
    fun changed() {
        dirty = true
        main.removeCallbacks(saveNow)
        main.postDelayed(saveNow, SAVE_DELAY_MS)
    }

    /** Persist now if anything changed (field closed, service destroyed). */
    fun flush() {
        main.removeCallbacks(saveNow)
        if (dirty) saveNow.run()
    }

    private val saveNow = Runnable {
        dirty = false
        val d = dir ?: return@Runnable
        val w = words?.toJson()
        val v = values?.toJson()
        io.execute {
            if (w != null) write(File(d, WORDS), w)
            if (v != null) write(File(d, VALUES), v)
        }
    }

    /**
     * r11a-fix (F2): told on the main thread once a wipe is done on disk. The keyboard drops the
     * emoji recents + tone defaults it holds in memory; settings re-count what is left.
     */
    val wiped = LinkedHashSet<() -> Unit>()

    /**
     * Wipes everything under filesDir/personal (words, values, emoji recents, tone defaults), in
     * memory and on disk; a load still in flight is discarded (X4). [wiped] runs after the delete.
     */
    fun clear(context: Context) {
        generation++
        dirty = false
        main.removeCallbacks(saveNow)
        words = PersonalModel()
        values = ValueMemory()
        val d = dir ?: File(context.applicationContext.filesDir, DIR).also { dir = it }
        io.execute {
            d.listFiles()?.forEach { it.delete() }
            main.post { for (l in wiped.toList()) l() }
        }
    }

    private fun read(f: File): String? = runCatching {
        val af = AtomicFile(f)
        if (!af.baseFile.exists()) null else String(af.readFully(), Charsets.UTF_8)
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
