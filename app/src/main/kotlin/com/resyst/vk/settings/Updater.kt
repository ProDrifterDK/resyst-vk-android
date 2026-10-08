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
import com.resyst.vk.core.AutoCheckGate
import com.resyst.vk.core.ConnectionLog
import com.resyst.vk.core.Installed
import com.resyst.vk.core.Release
import com.resyst.vk.core.UpdateChecker
import com.resyst.vk.core.UpdateDecision
import com.resyst.vk.core.UpdateNotice
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
 * buttons in Settings and, when "Buscar actualizaciones al iniciar" is on (r9), ONE [check] per
 * process start ([autoCheck], claimed through [AutoCheckGate]): the same single GET, nothing
 * scheduled, polled, or sent. Nothing else in the app opens a connection.
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
 * prefs so a download survives the process dying. An automatic check that fails stays silent:
 * the state returns to Idle and only the log knows (A5).
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

    /** When the current [State.Checked] result arrived, and whether the startup check made it. */
    var checkedAt = 0L
        private set
    var checkedAuto = false
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

    // ── check ───────────────────────────────────────────────────────────

    /** The user's "Buscar actualizaciones": errors are shown. */
    fun check(context: Context) = check(context, auto = false)

    private val autoGate = AutoCheckGate()

    /** The startup check already ran (or was claimed) in this process: never fetch again for it. */
    val autoCheckedThisProcess: Boolean get() = autoGate.ran

    /**
     * r9: called when the keyboard service (or the settings screen) comes alive. Runs [check] at
     * most once per process ([AutoCheckGate]); records `autoAt` in prefs. Returns whether it ran.
     */
    fun autoCheck(context: Context, enabled: Boolean): Boolean {
        val app = context.applicationContext
        val pending = prefs(app).getLong("id", -1) >= 0
        if (!autoGate.claim(enabled, idle = state is State.Idle, pendingDownload = pending)) return false
        prefs(app).edit().putLong("autoAt", System.currentTimeMillis()).apply()
        Log.i(TAG, "update auto-check: start")
        check(app, auto = true)
        return true
    }

    private fun check(context: Context, auto: Boolean) {
        if (state is State.Checking || state is State.Downloading || state is State.Verifying) return
        val app = context.applicationContext
        val installed = installed(app)
        state = State.Checking
        Log.i(TAG, "update check: GET release.json (auto=$auto)") // E2E counts these: one GET per check
        io.execute {
            var decision: UpdateDecision? = null
            var error: Exception? = null
            try {
                val json = fetchManifest()
                lastManifest = json
                decision = UpdateChecker.decide(json, installed)
            } catch (e: Exception) {
                Log.w(TAG, "update check failed: ${e.javaClass.simpleName}")
                error = e
            }
            logConnection(app, ConnectionLog.What.CHECK, auto, checkOutcome(decision, error))
            val next: State = if (auto) {
                // A5: a failed startup check never surfaces — back to Idle, log only
                val kept = UpdateNotice.autoOutcome(decision)
                Log.i(TAG, "update auto-check: " + (kept?.let { it::class.simpleName } ?: "silent (${error?.javaClass?.simpleName ?: "bad manifest"})"))
                kept?.let { State.Checked(it) } ?: State.Idle
            } else {
                decision?.let { State.Checked(it) } ?: State.Failed(networkMessage(error ?: IOException()))
            }
            main.post {
                if (next is State.Checked) { checkedAt = System.currentTimeMillis(); checkedAuto = auto }
                state = next
            }
        }
    }

    // ── r10: the connection log ("Libro de conexiones", V5) ─────────────

    private const val LOG_KEY = "connections"

    /** Every request this app made, newest first ([ConnectionLog.CAP] kept, [ConnectionLog.total] counted). */
    fun connections(context: Context): ConnectionLog =
        ConnectionLog.decode(prefs(context.applicationContext).getString(LOG_KEY, null))

    /**
     * One entry per network request (V5): called from the request paths only ([check] after its
     * GET, [download] when DownloadManager accepted the job). Any thread; synchronized because
     * the GET finishes on [io] while a download is logged on main.
     */
    @Synchronized
    private fun logConnection(app: Context, what: ConnectionLog.What, auto: Boolean, outcome: String) {
        val why = if (auto) ConnectionLog.Why.STARTUP else ConnectionLog.Why.USER
        val next = connections(app).add(ConnectionLog.Entry(System.currentTimeMillis(), what, why, outcome))
        prefs(app).edit().putString(LOG_KEY, next.encode()).apply()
        Log.i(TAG, "connection log: ${what.id} (${why.id}) → $outcome")
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
