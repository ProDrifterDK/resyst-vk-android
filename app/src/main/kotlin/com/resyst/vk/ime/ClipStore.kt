package com.resyst.vk.ime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import android.util.Log
import com.resyst.vk.core.ClipboardHistory
import java.io.File
import java.util.concurrent.Executors

/**
 * The clipboard history, one instance per process (the IME captures into it, the settings screen
 * wipes it). One private file, filesDir/clipboard/history.json — excluded from cloud backup and
 * device transfer by the res/xml backup rules — read off the main thread, written atomically
 * (AtomicFile) and debounced, like [PersonalStore]. No network, ever.
 */
object ClipStore {
    private const val TAG = "ResystVK"
    private const val DIR = "clipboard"
    private const val FILE = "history.json"
    private const val SAVE_DELAY_MS = 800L

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var dir: File? = null
    private var generation = 0
    private var dirty = false
    private val pending = ArrayList<() -> Unit>()

    /** null until loaded: nothing is captured or shown before that. */
    var history: ClipboardHistory? = null
        private set

    /** Something changed (capture, pin, delete): the open panel / settings screen re-render. */
    val listeners = LinkedHashSet<() -> Unit>()

    /** [onLoaded] runs on the main thread once the history is in memory (now, if it already is). */
    fun init(context: Context, onLoaded: (() -> Unit)? = null) {
        if (onLoaded != null) { if (history != null) onLoaded() else pending += onLoaded }
        if (dir != null) return
        val d = File(context.applicationContext.filesDir, DIR)
        dir = d
        val gen = generation
        io.execute {
            val h = read(File(d, FILE))?.let(ClipboardHistory::fromJson) ?: ClipboardHistory()
            main.post {
                if (gen != generation) return@post
                if (history == null) { history = h; notifyListeners() }
                val run = pending.toList(); pending.clear(); run.forEach { it() }
            }
        }
    }

    fun changed() {
        dirty = true
        main.removeCallbacks(saveNow)
        main.postDelayed(saveNow, SAVE_DELAY_MS)
        notifyListeners()
    }

    fun flush() {
        main.removeCallbacks(saveNow)
        if (dirty) saveNow.run()
    }

    private val saveNow = Runnable {
        dirty = false
        val d = dir ?: return@Runnable
        val json = history?.toJson() ?: return@Runnable
        io.execute { write(File(d, FILE), json) }
    }

    /**
     * "Borrar historial": every entry goes, in memory and on disk, now. The file is rewritten
     * (not just deleted) so the last-seen clip stamp survives and the current clipboard content
     * isn't re-captured at the next field focus. A load still in flight is discarded.
     */
    fun clear(context: Context) {
        generation++
        main.removeCallbacks(saveNow)
        dirty = false
        val h = history ?: ClipboardHistory()
        h.clear()
        history = h
        val d = dir ?: File(context.applicationContext.filesDir, DIR).also { dir = it }
        val json = h.toJson()
        io.execute { write(File(d, FILE), json) }
        notifyListeners()
    }

    private fun notifyListeners() { for (l in listeners.toList()) l() }

    private fun read(f: File): String? = runCatching {
        val af = AtomicFile(f)
        if (!af.baseFile.exists()) null else String(af.readFully(), Charsets.UTF_8)
    }.onFailure { Log.w(TAG, "clipboard: read failed", it) }.getOrNull()

    private fun write(f: File, text: String) {
        f.parentFile?.mkdirs()
        val af = AtomicFile(f)
        val out = runCatching { af.startWrite() }.getOrElse { Log.w(TAG, "clipboard: write failed", it); return }
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            af.finishWrite(out)
        } catch (e: Exception) {
            af.failWrite(out)
            Log.w(TAG, "clipboard: write failed", e)
        }
    }
}
