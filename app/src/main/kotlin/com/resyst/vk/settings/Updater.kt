package com.resyst.vk.settings

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.FileProvider
import com.resyst.vk.core.ConnectionLog
import com.resyst.vk.core.Installed
import com.resyst.vk.core.OpenCheck
import com.resyst.vk.core.Release
import com.resyst.vk.core.UpdateChecker
import com.resyst.vk.core.UpdateDecision
import com.resyst.vk.core.UpdateNotice
import com.resyst.vk.core.UpdateOrigin
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

/**
 * The app's only network code. It runs from the "Buscar actualizaciones" / "Descargar e instalar"
 * buttons in Settings and, when "Buscar actualizaciones automáticamente" is on, from the keyboard
 * opening (r11c, [onKeyboardShown]): at most one [check] per [OpenCheck.OK_HOURS] h after an answer,
 * per [OpenCheck.FAILED_HOURS] h after a failure, persisted across processes. The same single GET;
 * nothing scheduled, polled, or sent. The only other client is the opt-in GIF search (r11b,
 * [KlipyClient]): its own gate and allowlist, never this GET; both log to [ConnectionBook].
 *
 * check()    → one HTTPS GET of release.json → [UpdateChecker.decide]
 * download() → DownloadManager (system progress notification) into the app's external files dir
 * on DOWNLOAD_COMPLETE → copy into private cache while hashing ([UpdateChecker.copyAndHash]);
 *              install only if the SHA-256 + size match the manifest and the archive is this
 *              package, newer, and signed by the same key
 * install()  → ACTION_VIEW on a FileProvider URI of the verified copy; Android's installer asks
 *              the user and re-verifies the signature against the installed app.
 *
 * State lives in this process-wide object so re-rendering the screen never loses it and the
 * keyboard + settings read the same result (A4); the pending download id + manifest are also in
 * prefs so a download survives the process dying. An automatic check never shows "Buscando…" and
 * a failed one changes nothing on screen: the previous state stays and only the log knows (A5, OC12).
 */
object Updater {
    private const val TAG = "ResystVK"
    private const val PREFS = "resyst_vk_update"
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val DL_NAME = "resyst-vk-update.apk"
    private const val UPDATES_DIR = "updates"

    /** No device model / OS build in requests (Dalvik's default UA carries both). */
    const val USER_AGENT = "ResystVK-Updater"

    sealed class State {
        data object Idle : State()
        data object Checking : State()
        data class Checked(val decision: UpdateDecision) : State()
        data class Downloading(val release: Release) : State()
        data class Verifying(val release: Release) : State()
        data class Ready(val release: Release, val file: File) : State()
        data class Failed(val message: String) : State()
    }

    var state: State = State.Idle
        private set(v) {
            field = v
            for (l in listeners.toList()) l()
        }

    /**
     * The settings screen (while visible) and the keyboard service (while alive) listen here.
     * Always called on the main thread.
     */
    val listeners = LinkedHashSet<() -> Unit>()

    /** When the current [State.Checked] result arrived, and who asked for it (r12, UR1/UR2). */
    var checkedAt = 0L
        private set
    var checkedBy = UpdateOrigin.Origin.UNKNOWN
        private set

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var started = false

    fun installed(context: Context): Installed {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else pi.versionCode
        return Installed(code, pi.versionName ?: "?", Build.VERSION.SDK_INT)
    }

    // ── check (r11c: the keyboard-open check, OC1–OC13) ─────────────────

    private const val AT_KEY = "attemptAt"
    private const val OK_KEY = "attemptOk"

    @Volatile private var appContext: Context? = null

    /** The last attempt, in the updater prefs: the throttle survives the process (OC2). */
    private val attempts = object : OpenCheck.Store {
        override fun read(): OpenCheck.Attempt? {
            val p = prefs(appContext ?: return null)
            val at = p.getLong(AT_KEY, 0L)
            return if (at == 0L) null else OpenCheck.Attempt(at, p.getBoolean(OK_KEY, false))
        }
        override fun write(a: OpenCheck.Attempt) {
            // commit, not apply: a process killed right after the GET started still backs off (OC4)
            prefs(appContext ?: return).edit().putLong(AT_KEY, a.at).putBoolean(OK_KEY, a.reached).commit()
        }
    }

    private val gate = OpenCheck.Gate(attempts)

    private const val AVAIL_KEY = "availManifest"
    private const val AVAIL_AT_KEY = "availAt"
    @Volatile private var restored = false

    /**
     * OC16: an announced update outlives the process. r9 re-checked on every process start; with
     * the throttle a restart inside the interval would hide the chip for up to 12 h. The manifest
     * of the last "available" answer is kept in prefs and re-decided against the installed version
     * here, without network (updated meanwhile → no longer available → forgotten).
     */
    private fun restore(app: Context) {
        if (restored) return
        restored = true
        val p = prefs(app)
        // r12 (UR3): ≤ 0.7.0's `autoAt` is never read since r11c; drop it once, nothing else
        val orphans = UpdateOrigin.cleanup(p.all.keys)
        if (orphans.isNotEmpty()) {
            p.edit().apply { orphans.forEach(::remove) }.apply()
            Log.i(TAG, "update prefs: removed orphan $orphans")
        }
        if (state !is State.Idle) return
        val json = p.getString(AVAIL_KEY, null) ?: return
        val d = UpdateChecker.decide(json, installed(app))
        if (d is UpdateDecision.Available) {
            lastManifest = json
            checkedAt = p.getLong(AVAIL_AT_KEY, 0L)
            // r12 (UR1/UR2): the stored origin; a manifest stored by 0.7.1 / 0.8.0 has none (both paths wrote it)
            checkedBy = UpdateOrigin.restored(p.all[UpdateOrigin.KEY])
            Log.i(TAG, "update notice restored: ${d.release.version} by=${checkedBy.name.lowercase()} (no request)")
            state = State.Checked(d)
        } else {
            p.edit().remove(AVAIL_KEY).remove(AVAIL_AT_KEY).remove(UpdateOrigin.KEY).apply()
        }
    }

    /**
     * Keep / forget the answer [restore] brings back, with who asked for it ([auto] = the
     * keyboard-open check and nobody promoted it, UR1). A failure keeps what was known (OC12).
     */
    private fun remember(app: Context, decision: UpdateDecision?, json: String?, auto: Boolean) {
        if (decision == null || decision is UpdateDecision.Error) return
        val e = prefs(app).edit()
        if (decision is UpdateDecision.Available && json != null) {
            e.putString(AVAIL_KEY, json).putLong(AVAIL_AT_KEY, System.currentTimeMillis()).putBoolean(UpdateOrigin.KEY, auto)
        } else e.remove(AVAIL_KEY).remove(AVAIL_AT_KEY).remove(UpdateOrigin.KEY)
        e.apply()
    }

    /** A download id from this or an earlier process, or a flow of the user's on screen (OC7). */
    private fun busy(app: Context): Boolean =
        prefs(app).getLong("id", -1) >= 0 || state is State.Checking || state is State.Downloading ||
            state is State.Verifying || state is State.Ready

    /**
     * r11c: the keyboard was shown for a new field (never a timer or any background trigger, OC9).
     * Runs [check] when [OpenCheck.Gate.claim] says so: toggle on, not a secret field, updater
     * free, no check in flight, and the interval since the last attempt has passed. Main thread.
     */
    fun onKeyboardShown(context: Context, enabled: Boolean, secret: Boolean): OpenCheck.Verdict {
        val app = context.applicationContext
        appContext = app
        restore(app)
        val now = System.currentTimeMillis()
        val v = gate.claim(now, enabled, secret, busy(app))
        if (v == OpenCheck.Verdict.RUN) {
            Log.i(TAG, "update open-check: start")
            fetch(app, auto = true, startedAt = now)
        } else if (v != OpenCheck.Verdict.WAIT) {
            Log.i(TAG, "update open-check: skip ${v.name.lowercase()}")
        }
        return v
    }

    /** A user tap while an automatic check runs: its answer is shown as the user's (errors too). */
    @Volatile private var promoted = false

    /**
     * The user's "Buscar actualizaciones": shows "Buscando…" and the answer, errors too. Shares the
     * gate's clock and in-flight guard with the keyboard-open check (OC5, OC10).
     */
    fun check(context: Context) {
        if (state is State.Checking || state is State.Downloading || state is State.Verifying) return
        val app = context.applicationContext
        appContext = app
        val now = System.currentTimeMillis()
        if (!gate.begin(now)) { // an automatic check is in flight: one GET answers both (OC5)
            promoted = true
            state = State.Checking
            return
        }
        state = State.Checking
        fetch(app, auto = false, startedAt = now)
    }

    /** The one GET. The gate was claimed by the caller; [OpenCheck.Gate.end] always follows. */
    private fun fetch(app: Context, auto: Boolean, startedAt: Long) {
        val installed = installed(app)
        promoted = false
        Log.i(TAG, "update check: GET release.json (auto=$auto)") // E2E counts these: one GET per check
        io.execute {
            var decision: UpdateDecision? = null
            var error: Exception? = null
            var json: String? = null
            try {
                json = fetchManifest()
                lastManifest = json
                decision = UpdateChecker.decide(json, installed)
            } catch (e: Exception) {
                Log.w(TAG, "update check failed: ${e.javaClass.simpleName}")
                error = e
            }
            // reached = the server gave a readable answer; offline / HTTP error / bad JSON back off 1 h
            gate.end(startedAt, reached = decision != null && decision !is UpdateDecision.Error)
            logConnection(app, ConnectionLog.What.CHECK, auto, checkOutcome(decision, error))
            main.post {
                val asUser = !auto || promoted
                promoted = false
                // r12 (UR1): stored with its origin, known only here (a user tap may have promoted an automatic check)
                remember(app, decision, json, auto = !asUser)
                val next: State? = if (!asUser) {
                    // A5/OC12: a failed automatic check changes nothing on screen, log only
                    val kept = UpdateNotice.autoOutcome(decision)
                    Log.i(TAG, "update open-check: " + (kept?.let { it::class.simpleName } ?: "silent (${error?.javaClass?.simpleName ?: "bad manifest"})"))
                    kept?.let { State.Checked(it) }
                } else {
                    decision?.let { State.Checked(it) } ?: State.Failed(networkMessage(error ?: IOException()))
                }
                // the user may have started a download meanwhile: never clobber it (OC7)
                if (next != null && (state is State.Idle || state is State.Checked || state is State.Failed || state is State.Checking)) {
                    if (next is State.Checked) { checkedAt = System.currentTimeMillis(); checkedBy = UpdateOrigin.of(auto = !asUser) }
                    state = next
                }
            }
        }
    }

    // ── r10: the connection log ("Libro de conexiones", V5) ─────────────

    /** Every request this app made, newest first ([ConnectionLog.CAP] kept, [ConnectionLog.total] counted). */
    fun connections(context: Context): ConnectionLog = ConnectionBook.read(context)

    /**
     * One entry per network request (V5): called from the request paths only ([check] after its
     * GET, [download] when DownloadManager accepted the job). Any thread; r11b: written through
     * [ConnectionBook], the same lock the GIF client logs under (GL4).
     */
    private fun logConnection(app: Context, what: ConnectionLog.What, auto: Boolean, outcome: String) {
        val why = if (auto) ConnectionLog.Why.OPEN else ConnectionLog.Why.USER
        ConnectionBook.add(app, what, why, outcome)
    }

    private fun checkOutcome(d: UpdateDecision?, e: Exception?): String = when (d) {
        null -> networkMessage(e ?: IOException()).removeSuffix(".")
        is UpdateDecision.Available -> "Hay versión nueva: ${d.release.version}"
        is UpdateDecision.UpToDate -> "Ya al día (${d.latest})"
        is UpdateDecision.Incompatible -> "Versión ${d.release.version}, no compatible"
        UpdateDecision.NotPublished -> "Sin versión publicada"
        is UpdateDecision.Error -> "Respuesta ilegible"
    }

    // ── r9: the keyboard's update chip ──────────────────────────────────

    /** The release the keyboard announces now (available and not dismissed), or null. */
    fun announced(context: Context): Release? =
        UpdateNotice.announce((state as? State.Checked)?.decision, dismissedVersion(context))

    fun dismissedVersion(context: Context): String? = prefs(context.applicationContext).getString("dismissed", null)

    /** The chip's ✕ (or settings' "Ahora no"): quiet until a newer version than [version]. */
    fun dismissNotice(context: Context, version: String) {
        prefs(context.applicationContext).edit().putString("dismissed", version).apply()
        Log.i(TAG, "update notice dismissed for $version")
        for (l in listeners.toList()) l()
    }

    /** The manifest text that produced the current decision (stored with a pending download). */
    @Volatile private var lastManifest: String? = null

    private fun fetchManifest(): String {
        val c = URL(UpdateChecker.MANIFEST_URL).openConnection() as HttpsURLConnection
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.useCaches = false
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("Cache-Control", "no-cache")
            val code = c.responseCode
            if (code != 200) throw HttpStatus(code)
            val out = ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf = ByteArray(4096)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > UpdateChecker.MAX_MANIFEST_BYTES) throw IOException("manifest too large")
                }
            }
            return out.toString("UTF-8")
        } finally {
            c.disconnect()
        }
    }

    private class HttpStatus(val code: Int) : IOException("HTTP $code")

    private fun networkMessage(e: Exception): String = when (e) {
        is UnknownHostException -> "Sin conexión a internet."
        is SocketTimeoutException -> "El servidor no respondió a tiempo."
        is HttpStatus -> "El servidor respondió con un error (HTTP ${e.code})."
        is javax.net.ssl.SSLException -> "No se pudo establecer una conexión segura."
        else -> "No se pudo conectar."
    }

    // ── download ────────────────────────────────────────────────────────

    fun download(context: Context, release: Release) {
        val app = context.applicationContext
        val manifest = lastManifest ?: return
        val dm = app.getSystemService(DownloadManager::class.java) ?: return
        val dir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        File(dir, DL_NAME).delete() // DownloadManager would otherwise write "-1" next to a stale file
        val req = DownloadManager.Request(Uri.parse(release.url))
            .setTitle("Resyst VK ${release.version}")
            .setDescription("Actualización")
            .setMimeType(APK_MIME)
            .addRequestHeader("User-Agent", USER_AGENT)
            // progress while running only: a "completed" notification would let a tap open the
            // file straight in the installer, skipping the SHA-256 check below
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, DL_NAME)
        val id = try {
            dm.enqueue(req)
        } catch (e: Exception) {
            state = State.Failed("No se pudo iniciar la descarga.")
            return
        }
        prefs(app).edit().putLong("id", id).putString("manifest", manifest).apply()
        logConnection(app, ConnectionLog.What.DOWNLOAD, auto = false, outcome = "${release.version}${release.size?.let { " · $it" } ?: ""}")
        state = State.Downloading(release)
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        val id = prefs(app).getLong("id", -1)
        if (id >= 0) app.getSystemService(DownloadManager::class.java)?.remove(id)
        clearPending(app)
        state = State.Idle
    }

    /** ACTION_DOWNLOAD_COMPLETE (any sender: the id is ours to check, and the bytes are verified). */
    fun onDownloadComplete(context: Context, id: Long) {
        val app = context.applicationContext
        if (id < 0 || id != prefs(app).getLong("id", -2)) return
        resume(app)
    }

    /**
     * Settings came to the foreground: reconcile with DownloadManager (the screen or the process
     * may have been gone when the download finished). On a fresh process with nothing pending,
     * stale verified copies are deleted.
     */
    fun resume(context: Context) {
        val app = context.applicationContext
        appContext = app
        restore(app)
        if (!started) {
            started = true
            if (prefs(app).getLong("id", -1) < 0) File(app.cacheDir, UPDATES_DIR).deleteRecursively()
        }
        val id = prefs(app).getLong("id", -1)
        if (id < 0) return
        val release = prefs(app).getString("manifest", null)
            ?.let { UpdateChecker.parse(it) as? com.resyst.vk.core.ManifestParse.Ok }?.release
        if (release == null) { cancel(app); return }
        val dm = app.getSystemService(DownloadManager::class.java) ?: return
        val status = dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (c.moveToFirst()) c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) to
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            else null
        }
        when (status?.first) {
            null -> { clearPending(app); state = State.Failed("La descarga se canceló.") }
            DownloadManager.STATUS_SUCCESSFUL -> if (state !is State.Verifying) verify(app, dm, id, release)
            DownloadManager.STATUS_FAILED -> {
                dm.remove(id); clearPending(app)
                state = State.Failed("La descarga falló (código ${status.second}).")
            }
            else -> if (state !is State.Downloading) state = State.Downloading(release)
        }
    }

    private fun verify(app: Context, dm: DownloadManager, id: Long, release: Release) {
        state = State.Verifying(release)
        io.execute {
            val dir = File(app.cacheDir, UPDATES_DIR).apply { deleteRecursively(); mkdirs() }
            val out = File(dir, "resyst-vk-${release.versionCode ?: release.version}.apk")
            val next: State = try {
                val hashed = ParcelFileDescriptor.AutoCloseInputStream(dm.openDownloadedFile(id)).use { input ->
                    FileOutputStream(out).use { o -> UpdateChecker.copyAndHash(input, o).also { o.fd.sync() } }
                }
                when {
                    !UpdateChecker.matches(hashed, release.sha256, release.sizeBytes) -> {
                        Log.w(TAG, "update sha256 mismatch: got ${hashed.sha256} (${hashed.bytes} B)")
                        State.Failed("El archivo descargado no coincide con la huella publicada (SHA-256). No se instaló.")
                    }
                    else -> archiveProblem(app, out, release)?.let { State.Failed(it) } ?: State.Ready(release, out)
                }
            } catch (e: Exception) {
                Log.w(TAG, "update verify failed: ${e.javaClass.simpleName}")
                State.Failed("No se pudo leer el archivo descargado.")
            }
            if (next !is State.Ready) out.delete()
            dm.remove(id) // drops the unverified copy in external storage
            clearPending(app)
            main.post { state = next }
        }
    }

    /** Defense in depth on top of the hash: right package, the promised version, same signer. */
    private fun archiveProblem(app: Context, apk: File, release: Release): String? {
        val pm = app.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = pm.getPackageArchiveInfo(apk.path, flags) ?: return "El archivo descargado no es un APK válido."
        if (info.packageName != app.packageName) {
            return "La actualización es para ${info.packageName}; esta instalación es ${app.packageName} (compilación de desarrollo)."
        }
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
        if (release.versionCode != null && code != release.versionCode) return "El APK no es la versión anunciada."
        if (code <= installed(app).versionCode) return "El APK no es más nuevo que la versión instalada."
        if (Build.VERSION.SDK_INT >= 28) {
            val theirs = info.signingInfo?.apkContentsSigners?.toSet()
            val mine = pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.toSet()
            if (theirs != null && mine != null && theirs != mine) return "El APK no está firmado por Resyst. No se instaló."
        }
        return null
    }

    // ── install ─────────────────────────────────────────────────────────

    /** False when Android first needs "Install unknown apps" allowed for this app. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettings(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    fun installIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun dismiss() {
        if (state is State.Checked || state is State.Failed) state = State.Idle
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun clearPending(c: Context) = prefs(c).edit().remove("id").remove("manifest").apply()
}
