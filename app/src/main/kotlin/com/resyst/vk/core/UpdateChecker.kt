package com.resyst.vk.core

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** What is installed on this device, as the updater needs it. */
data class Installed(val versionCode: Int, val versionName: String, val sdk: Int)

/** A published release, as read from the site's `release.json` and validated. */
data class Release(
    val version: String,
    /** null when the manifest omits it: the version name is compared instead. */
    val versionCode: Int?,
    /** lowercase hex, 64 chars */
    val sha256: String,
    /** absolute https URL on [UpdateChecker.ORIGIN], path ending in `.apk` */
    val url: String,
    val sizeBytes: Long?,
    val size: String?,
    val date: String?,
    val minSdk: Int?,
    val minAndroid: String?,
)

sealed class ManifestParse {
    data class Ok(val release: Release) : ManifestParse()
    data object Unavailable : ManifestParse()
    data class Bad(val reason: String) : ManifestParse()
}

sealed class UpdateDecision {
    data class UpToDate(val latest: String) : UpdateDecision()
    data class Available(val release: Release) : UpdateDecision()
    data class Incompatible(val release: Release, val minSdk: Int) : UpdateDecision()
    data object NotPublished : UpdateDecision()
    data class Error(val reason: String) : UpdateDecision()
}

/**
 * The self-updater's logic, pure Kotlin (no Android imports) so every rule is JVM-tested
 * (UpdateCheckerTest; failure modes U/V/D/H in docs/failure-modes.md). The Android side only
 * fetches [MANIFEST_URL] on a tap, calls [decide], and on a second tap downloads [Release.url]
 * through [copyAndHash] and installs it only if [matches].
 */
object UpdateChecker {
    const val ORIGIN = "https://kv.resyst.cl"
    const val MANIFEST_URL = "$ORIGIN/release.json"

    /** A manifest bigger than this is not ours. */
    const val MAX_MANIFEST_BYTES = 16 * 1024

    /** A download bigger than this is aborted (the APK is ~2 MB). */
    const val MAX_APK_BYTES = 200L * 1024 * 1024

    private val SHA = Regex("^[0-9a-fA-F]{64}$")
    private val PATH = Regex("^/download/[A-Za-z0-9._+-]{1,80}\\.apk$")

    fun decide(manifestJson: String, installed: Installed): UpdateDecision =
        when (val p = parse(manifestJson)) {
            is ManifestParse.Bad -> UpdateDecision.Error(p.reason)
            ManifestParse.Unavailable -> UpdateDecision.NotPublished
            is ManifestParse.Ok -> decide(p.release, installed)
        }

    fun decide(r: Release, installed: Installed): UpdateDecision {
        val newer = if (r.versionCode != null) {
            r.versionCode > installed.versionCode
        } else {
            val c = compareNames(r.version, installed.versionName)
                ?: return UpdateDecision.Error("unreadable version '${r.version}'")
            c > 0
        }
        if (!newer) return UpdateDecision.UpToDate(r.version)
        val need = r.minSdk ?: r.minAndroid?.let(::apiForAndroid)
        if (need != null && installed.sdk < need) return UpdateDecision.Incompatible(r, need)
        return UpdateDecision.Available(r)
    }

    fun parse(json: String): ManifestParse {
        val root = try {
            MiniJson.parse(json)
        } catch (e: IllegalArgumentException) {
            return ManifestParse.Bad("invalid JSON: ${e.message}")
        } catch (e: StringIndexOutOfBoundsException) {
            return ManifestParse.Bad("invalid JSON")
        }
        @Suppress("UNCHECKED_CAST")
        val m = root as? Map<String, Any?> ?: return ManifestParse.Bad("manifest is not an object")
        if (m["available"] != true) return ManifestParse.Unavailable

        val version = m["version"] as? String ?: return ManifestParse.Bad("version missing")
        if (version.isBlank() || version.length > 60) return ManifestParse.Bad("bad version")
        val code: Int? = when (val v = m["versionCode"]) {
            null -> null
            is Long -> if (v in 1..Int.MAX_VALUE) v.toInt() else return ManifestParse.Bad("bad versionCode")
            else -> return ManifestParse.Bad("versionCode is not an integer")
        }
        if (code == null && parseName(version) == null) return ManifestParse.Bad("no versionCode and unreadable version")

        val sha = m["sha256"] as? String ?: return ManifestParse.Bad("sha256 missing")
        if (!SHA.matches(sha)) return ManifestParse.Bad("sha256 is not 64 hex chars")
        val url = resolveDownloadUrl(m["url"] as? String ?: return ManifestParse.Bad("url missing"))
            ?: return ManifestParse.Bad("url is not a release download on $ORIGIN")

        val sizeBytes: Long? = when (val v = m["sizeBytes"]) {
            null -> null
            is Long -> if (v in 1..MAX_APK_BYTES) v else return ManifestParse.Bad("bad sizeBytes")
            else -> return ManifestParse.Bad("sizeBytes is not an integer")
        }
        val minSdk: Int? = when (val v = m["minSdk"]) {
            null -> null
            is Long -> if (v in 1..1000) v.toInt() else return ManifestParse.Bad("bad minSdk")
            else -> return ManifestParse.Bad("minSdk is not an integer")
        }
        return ManifestParse.Ok(
            Release(
                version = version, versionCode = code, sha256 = sha.lowercase(), url = url,
                sizeBytes = sizeBytes, size = m["size"] as? String, date = m["date"] as? String,
                minSdk = minSdk, minAndroid = m["minAndroid"] as? String,
            ),
        )
    }

    /**
     * The manifest's `url`, resolved and pinned: a site-relative `/download/<file>.apk` or the
     * same thing spelled absolutely on [ORIGIN]. Anything else (other scheme, host, port,
     * userinfo, query, dot segments) is null. Hand-parsed so no java.net/android.net quirk
     * decides what the "host" is.
     */
    fun resolveDownloadUrl(raw: String): String? {
        val path = when {
            raw.startsWith("/") && !raw.startsWith("//") -> raw
            raw.length > ORIGIN.length && raw.regionMatches(0, "$ORIGIN/", 0, ORIGIN.length + 1, ignoreCase = true) ->
                raw.substring(ORIGIN.length)
            else -> return null
        }
        return if (PATH.matches(path)) ORIGIN + path else null
    }

    // ── versions ────────────────────────────────────────────────────────

    private class Name(val nums: List<Int>, val pre: List<String>)

    /** "v0.3.0-alpha.2+build5" → [0,3,0] / [alpha, 2]. Build metadata and a leading v are ignored. */
    private fun parseName(s: String): Name? {
        val t = s.trim().removePrefix("v").removePrefix("V").substringBefore('+')
        val core = t.substringBefore('-')
        val pre = if ('-' in t) t.substringAfter('-') else ""
        if (core.isEmpty()) return null
        val nums = core.split('.').map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }
        val preIds = if (pre.isEmpty()) emptyList() else pre.split('.')
        if (preIds.any { it.isEmpty() }) return null
        return Name(nums, preIds)
    }

    /**
     * Semver-ish comparison of version names: numeric parts compared as numbers (missing ones
     * are 0, so 0.2 == 0.2.0), a pre-release sorts before its release (0.3.0-alpha < 0.3.0),
     * pre-release ids compare numerically when both are numbers. The build's own `-debug`
     * suffix is not a pre-release. null when either side doesn't parse.
     */
    fun compareNames(a: String, b: String): Int? {
        val x = parseName(a.removeSuffix("-debug")) ?: return null
        val y = parseName(b.removeSuffix("-debug")) ?: return null
        for (i in 0 until maxOf(x.nums.size, y.nums.size)) {
            val c = (x.nums.getOrElse(i) { 0 }).compareTo(y.nums.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        if (x.pre.isEmpty() || y.pre.isEmpty()) return y.pre.size.coerceAtMost(1) - x.pre.size.coerceAtMost(1)
        for (i in 0 until minOf(x.pre.size, y.pre.size)) {
            val p = x.pre[i]
            val q = y.pre[i]
            val pn = p.toIntOrNull()
            val qn = q.toIntOrNull()
            val c = when {
                pn != null && qn != null -> pn.compareTo(qn)
                pn != null -> -1 // numeric ids sort before alphanumeric ones
                qn != null -> 1
                else -> p.compareTo(q)
            }
            if (c != 0) return c.coerceIn(-1, 1)
        }
        return x.pre.size.compareTo(y.pre.size)
    }

    private val ANDROID_API = mapOf(
        "5.0" to 21, "5.1" to 22, "6.0" to 23, "7.0" to 24, "7.1" to 25, "8.0" to 26, "8.1" to 27,
        "9" to 28, "10" to 29, "11" to 30, "12" to 31, "12L" to 32, "13" to 33, "14" to 34,
        "15" to 35, "16" to 36,
    )

    /** The human "minAndroid" string the site shows ("8.0", "12L", "API 37") → API level. */
    fun apiForAndroid(s: String): Int? {
        val t = s.trim()
        ANDROID_API[t]?.let { return it }
        ANDROID_API[t.removeSuffix(".0")]?.let { return it }
        if (t.startsWith("API ")) return t.removePrefix("API ").trim().toIntOrNull()
        return null
    }

    // ── download verification ───────────────────────────────────────────

    class Hashed(val sha256: String, val bytes: Long)

    /**
     * Streams [input] into [output] and hashes exactly the bytes written, so the file that was
     * verified is the file the installer gets (no re-read in between). Throws past [maxBytes].
     */
    fun copyAndHash(input: InputStream, output: OutputStream, maxBytes: Long = MAX_APK_BYTES): Hashed {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw IOException("download exceeds $maxBytes bytes")
            md.update(buf, 0, n)
            output.write(buf, 0, n)
        }
        output.flush()
        return Hashed(md.digest().joinToString("") { "%02x".format(it) }, total)
    }

    fun matches(h: Hashed, expectedSha256: String, expectedSize: Long?): Boolean =
        h.bytes > 0 &&
            (expectedSize == null || expectedSize == h.bytes) &&
            MessageDigest.isEqual(h.sha256.toByteArray(), expectedSha256.lowercase().toByteArray())
}
