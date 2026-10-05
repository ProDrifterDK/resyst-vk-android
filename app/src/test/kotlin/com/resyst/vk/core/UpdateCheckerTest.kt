package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Round 5 — self-update decision logic (U*, V*, D*, H* in docs/failure-modes.md). */
class UpdateCheckerTest {
    private val sha = "a".repeat(64)

    /** The shape kv.resyst.cl ships (written by the site's scripts/publish-apk.sh). */
    private fun manifest(
        available: Any? = true,
        version: Any? = "0.3.0",
        versionCode: Any? = 3,
        sha256: Any? = sha,
        url: Any? = "/download/resyst-vk-0.3.0.apk",
        sizeBytes: Any? = 1_900_000,
        minSdk: Any? = 26,
        minAndroid: Any? = "8.0",
        drop: Set<String> = emptySet(),
    ): String {
        val fields = linkedMapOf(
            "available" to available, "version" to version, "versionCode" to versionCode,
            "size" to "1.9 MB", "sizeBytes" to sizeBytes, "sha256" to sha256, "date" to "2026-10-05",
            "minSdk" to minSdk, "minAndroid" to minAndroid, "url" to url,
        )
        return fields.filterKeys { it !in drop }.entries.joinToString(",", "{", "}") { (k, v) ->
            MiniJson.quote(k) + ":" + when (v) {
                null -> "null"
                is String -> MiniJson.quote(v)
                else -> v.toString()
            }
        }
    }

    private val v020 = Installed(versionCode = 2, versionName = "0.2.0", sdk = 34)

    private fun ok(json: String) = (UpdateChecker.parse(json) as ManifestParse.Ok).release
    private fun bad(json: String) = UpdateChecker.parse(json) is ManifestParse.Bad

    // ── parsing ─────────────────────────────────────────────────────────

    @Test fun validManifestParsesEveryField() {
        val r = ok(manifest())
        assertEquals("0.3.0", r.version)
        assertEquals(3, r.versionCode)
        assertEquals(sha, r.sha256)
        assertEquals("https://kv.resyst.cl/download/resyst-vk-0.3.0.apk", r.url)
        assertEquals(1_900_000L, r.sizeBytes)
        assertEquals(26, r.minSdk)
    }

    @Test fun invalidJsonOrNonObjectIsAnErrorNotACrash() { // U1
        for (j in listOf("", "   ", "{", "not json", "[]", "\"x\"", "null", "42", "{\"available\":true,}", manifest() + "x"))
            assertTrue(j, bad(j))
    }

    @Test fun missingOrMistypedRequiredFieldsAreErrors() { // U2
        assertTrue(bad(manifest(drop = setOf("sha256"))))
        assertTrue(bad(manifest(drop = setOf("url"))))
        assertTrue(bad(manifest(drop = setOf("version"))))
        assertTrue(bad(manifest(versionCode = "3")))
        assertTrue(bad(manifest(versionCode = 3.5)))
        assertTrue(bad(manifest(sha256 = 12345)))
        assertTrue(bad(manifest(url = 7)))
        assertTrue(bad(manifest(version = 3)))
        assertTrue(bad(manifest(sha256 = null)))
        // versionCode is optional (name fallback), but then the version name must parse
        assertEquals(null, ok(manifest(drop = setOf("versionCode"))).versionCode)
        assertTrue(bad(manifest(drop = setOf("versionCode"), version = "latest")))
    }

    @Test fun unavailableManifestIsNotAnUpdate() { // U3
        for (a in listOf(false, null, "true")) {
            val d = UpdateChecker.decide(manifest(available = a), v020)
            assertTrue("available=$a -> $d", d is UpdateDecision.NotPublished)
        }
        assertTrue(UpdateChecker.decide(manifest(drop = setOf("available")), v020) is UpdateDecision.NotPublished)
        // the site's pre-release placeholder (no sha, no versionCode) is not an update either
        val placeholder = """{"available": true, "version": "0.2-alpha", "size": "2.4 MB", "sha256": null,
            |"date": null, "minAndroid": null, "url": "/download/latest"}""".trimMargin()
        assertTrue(UpdateChecker.decide(placeholder, v020) is UpdateDecision.Error)
    }

    @Test fun sha256MustBe64HexAndIsNormalizedToLowercase() { // U4
        assertTrue(bad(manifest(sha256 = "a".repeat(63))))
        assertTrue(bad(manifest(sha256 = "a".repeat(65))))
        assertTrue(bad(manifest(sha256 = "g".repeat(64))))
        assertTrue(bad(manifest(sha256 = "")))
        assertEquals("ab".repeat(32), ok(manifest(sha256 = "AB".repeat(32))).sha256)
    }

    @Test fun downloadUrlCannotLeaveTheReleaseOrigin() { // U5
        val rejected = listOf(
            "http://kv.resyst.cl/download/x.apk",
            "https://evil.com/download/x.apk",
            "https://kv.resyst.cl.evil.com/download/x.apk",
            "https://evil.com/kv.resyst.cl/x.apk",
            "https://kv.resyst.cl@evil.com/x.apk",
            "https://user@kv.resyst.cl/download/x.apk",
            "//evil.com/download/x.apk",
            "javascript:alert(1)",
            "file:///sdcard/x.apk",
            "content://downloads/x.apk",
            "https://kv.resyst.cl/download/x.zip",
            "https://kv.resyst.cl:8443/download/x.apk",
            "/download/../../x.apk",
            "/download/x.apk?y=1",
            "/download/x.apk#frag",
            "download/x.apk",
            "/download/ x.apk",
            "",
        )
        for (u in rejected) {
            assertNull(u, UpdateChecker.resolveDownloadUrl(u))
            assertTrue(u, bad(manifest(url = u)))
        }
        assertEquals("https://kv.resyst.cl/download/resyst-vk-0.3.0.apk", UpdateChecker.resolveDownloadUrl("/download/resyst-vk-0.3.0.apk"))
        assertEquals("https://kv.resyst.cl/download/resyst-vk-0.3.0.apk", UpdateChecker.resolveDownloadUrl("https://kv.resyst.cl/download/resyst-vk-0.3.0.apk"))
        assertEquals("https://kv.resyst.cl/download/resyst-vk-0.3.0.apk", UpdateChecker.resolveDownloadUrl("https://KV.Resyst.CL/download/resyst-vk-0.3.0.apk"))
    }

    @Test fun absurdSizesAreRejected() { // U6
        assertTrue(bad(manifest(sizeBytes = -1)))
        assertTrue(bad(manifest(sizeBytes = 0)))
        assertTrue(bad(manifest(sizeBytes = 300L * 1024 * 1024)))
        assertTrue(bad(manifest(versionCode = -2)))
        assertTrue(bad(manifest(minSdk = -1)))
        assertNull(ok(manifest(drop = setOf("sizeBytes"))).sizeBytes) // optional
    }

    // ── version comparison ──────────────────────────────────────────────

    @Test fun versionCodeDecidesWhenPresent() { // V1
        val newer = UpdateChecker.decide(manifest(versionCode = 3, version = "0.3.0"), v020)
        assertTrue(newer is UpdateDecision.Available)
        assertEquals("0.3.0", (newer as UpdateDecision.Available).release.version)
        assertTrue(UpdateChecker.decide(manifest(versionCode = 2, version = "0.2.0"), v020) is UpdateDecision.UpToDate)
        assertTrue("lower code is never offered (no downgrade)",
            UpdateChecker.decide(manifest(versionCode = 1, version = "0.1.0"), v020) is UpdateDecision.UpToDate)
        // the code wins over a name that disagrees
        assertTrue(UpdateChecker.decide(manifest(versionCode = 2, version = "9.9.9"), v020) is UpdateDecision.UpToDate)
        assertTrue(UpdateChecker.decide(manifest(versionCode = 10, version = "0.1.0"), v020) is UpdateDecision.Available)
    }

    @Test fun versionNameComparisonMatrix() { // V2
        val cmp = UpdateChecker::compareNames
        val lessThan = listOf(
            "0.9.0" to "0.10.0", "0.2.0" to "0.2.1", "0.2.9" to "0.3", "1.9.9" to "2.0.0",
            "0.3.0-alpha" to "0.3.0", "0.3.0-alpha" to "0.3.0-beta", "0.3.0-alpha.2" to "0.3.0-alpha.10",
            "0.3.0-alpha" to "0.3.0-alpha.1", "0.2-alpha" to "0.2.0", "v0.2.0" to "0.2.1",
        )
        for ((a, b) in lessThan) {
            assertTrue("$a < $b", cmp(a, b)!! < 0)
            assertTrue("$b > $a", cmp(b, a)!! > 0)
        }
        for ((a, b) in listOf("0.2" to "0.2.0", "0.2.0" to "0.2.0", "1" to "1.0.0", "v1.0" to "1.0.0", "0.3.0+build5" to "0.3.0"))
            assertEquals("$a == $b", 0, cmp(a, b))
        assertNull(cmp("latest", "0.2.0"))
        assertNull(cmp("0.2.0", ""))
        // fallback in the decision when the manifest has no versionCode
        assertTrue(UpdateChecker.decide(manifest(drop = setOf("versionCode"), version = "0.10.0"), Installed(2, "0.9.0", 34)) is UpdateDecision.Available)
        assertTrue(UpdateChecker.decide(manifest(drop = setOf("versionCode"), version = "0.2"), v020) is UpdateDecision.UpToDate)
        assertTrue(UpdateChecker.decide(manifest(drop = setOf("versionCode"), version = "0.3.0-alpha"), Installed(3, "0.3.0", 34)) is UpdateDecision.UpToDate)
    }

    @Test fun debugSuffixIsIgnoredInTheNameFallback() { // V3
        val dbg = Installed(versionCode = 2, versionName = "0.2.0-debug", sdk = 34)
        assertTrue(UpdateChecker.decide(manifest(drop = setOf("versionCode"), version = "0.2.0"), dbg) is UpdateDecision.UpToDate)
        assertTrue(UpdateChecker.decide(manifest(drop = setOf("versionCode"), version = "0.2.1"), dbg) is UpdateDecision.Available)
    }

    // ── decision ────────────────────────────────────────────────────────

    @Test fun minAndroidGatesTheUpdate() { // D1
        val old = Installed(versionCode = 2, versionName = "0.2.0", sdk = 28)
        val d = UpdateChecker.decide(manifest(minSdk = 29), old)
        assertTrue(d is UpdateDecision.Incompatible)
        assertEquals(29, (d as UpdateDecision.Incompatible).minSdk)
        assertTrue(UpdateChecker.decide(manifest(minSdk = 28), old) is UpdateDecision.Available)
        // minSdk absent: fall back to the human minAndroid string
        assertTrue(UpdateChecker.decide(manifest(drop = setOf("minSdk"), minAndroid = "10"), old) is UpdateDecision.Incompatible)
        assertTrue(UpdateChecker.decide(manifest(drop = setOf("minSdk"), minAndroid = "9"), old) is UpdateDecision.Available)
        assertTrue(UpdateChecker.decide(manifest(drop = setOf("minSdk", "minAndroid")), old) is UpdateDecision.Available)
        // an older-or-equal release is "up to date" even if this device couldn't install it
        assertTrue(UpdateChecker.decide(manifest(versionCode = 2, minSdk = 35), old) is UpdateDecision.UpToDate)
        assertEquals(26, UpdateChecker.apiForAndroid("8.0"))
        assertEquals(27, UpdateChecker.apiForAndroid("8.1"))
        assertEquals(28, UpdateChecker.apiForAndroid("9"))
        assertEquals(32, UpdateChecker.apiForAndroid("12L"))
        assertEquals(36, UpdateChecker.apiForAndroid("16"))
        assertEquals(37, UpdateChecker.apiForAndroid("API 37")) // publish-apk.sh's form for unknown levels
        assertNull(UpdateChecker.apiForAndroid("Pie"))
        assertNull(UpdateChecker.apiForAndroid(""))
    }

    @Test fun errorsNeverReadAsUpToDate() { // D2
        for (j in listOf("", "<html>Cloudflare error</html>", manifest(sha256 = "short"), manifest(url = "https://evil.com/x.apk"))) {
            val d = UpdateChecker.decide(j, v020)
            assertTrue("$j -> $d", d is UpdateDecision.Error)
        }
    }

    // ── download verification ───────────────────────────────────────────

    @Test fun copyAndHashHashesExactlyTheBytesItWrites() { // H1, H2
        val data = ByteArray(300_123) { (it * 31 + 7).toByte() }
        val out = ByteArrayOutputStream()
        val h = UpdateChecker.copyAndHash(ByteArrayInputStream(data), out)
        assertEquals(data.size.toLong(), h.bytes)
        assertTrue(data.contentEquals(out.toByteArray()))
        // known vector: sha256("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            UpdateChecker.copyAndHash(ByteArrayInputStream("abc".toByteArray()), ByteArrayOutputStream()).sha256)
        assertTrue(UpdateChecker.matches(h, h.sha256, h.bytes))
        assertTrue("uppercase expected hash", UpdateChecker.matches(h, h.sha256.uppercase(), h.bytes))
        assertTrue("size unknown in manifest", UpdateChecker.matches(h, h.sha256, null))
        assertFalse("one flipped hex digit", UpdateChecker.matches(h, h.sha256.dropLast(1) + (if (h.sha256.last() == '0') '1' else '0'), h.bytes))
        assertFalse("size mismatch", UpdateChecker.matches(h, h.sha256, h.bytes + 1))
        val empty = UpdateChecker.copyAndHash(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream())
        assertFalse("an empty file never passes", UpdateChecker.matches(empty, empty.sha256, null))
    }

    @Test fun copyAndHashStopsAtTheCap() { // H1: a runaway download can't fill the disk
        val data = ByteArray(5000)
        try {
            UpdateChecker.copyAndHash(ByteArrayInputStream(data), ByteArrayOutputStream(), maxBytes = 4096)
            throw AssertionError("expected the cap to trip")
        } catch (e: java.io.IOException) { /* expected */ }
    }
}
