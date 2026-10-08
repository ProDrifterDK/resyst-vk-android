package com.resyst.vk.core

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Round 6 — clipboard history, paste offer, privacy gate, settings. Ids map to docs/failure-modes.md. */
class ClipboardTest {
    private val t0 = 1_760_000_000_000L
    private fun h() = ClipboardHistory()
    private fun texts(h: ClipboardHistory) = h.items().map { it.text }

    // ── history store ───────────────────────────────────────────────────
    @Test fun blankClipsAreNotStored() { // C1
        val h = h()
        assertEquals(ClipboardHistory.Capture.EMPTY, h.capture("", t0, 1))
        assertEquals(ClipboardHistory.Capture.EMPTY, h.capture("  \n\t ", t0, 2))
        assertTrue(h.isEmpty())
    }

    @Test fun oversizedClipsAreSkippedAtExactlyTheLimit() { // C2
        val h = h()
        val max = "a".repeat(ClipboardHistory.MAX_BYTES)
        assertEquals(ClipboardHistory.Capture.ADDED, h.capture(max, t0, 1))
        assertEquals(ClipboardHistory.Capture.TOO_LARGE, h.capture(max + "a", t0, 2))
        // UTF-8 bytes, not chars: ñ is 2 bytes, an emoji (surrogate pair) is 4
        assertFalse(ClipboardHistory.fits("ñ".repeat(ClipboardHistory.MAX_BYTES / 2 + 1)))
        assertTrue(ClipboardHistory.fits("ñ".repeat(ClipboardHistory.MAX_BYTES / 2)))
        assertTrue(ClipboardHistory.fits("😀".repeat(ClipboardHistory.MAX_BYTES / 4)))
        assertFalse(ClipboardHistory.fits("😀".repeat(ClipboardHistory.MAX_BYTES / 4) + "a"))
        assertEquals(1, h.size)
    }

    @Test fun reReadingTheSameClipChangesNothing() { // C3
        val h = h()
        h.capture("hola", t0, 100)
        h.capture("otra", t0 + 1, 200)
        assertEquals(ClipboardHistory.Capture.UNCHANGED, h.capture("otra", t0 + 50, 200))
        assertEquals(listOf("otra", "hola"), texts(h))
        assertEquals(1, h.items()[0].count)
        // unknown stamp: the newest entry re-read is a no-op
        assertEquals(ClipboardHistory.Capture.UNCHANGED, h.capture("otra", t0 + 60, 0))
        // a deleted entry doesn't come back from a re-read of the same clip
        h.delete(h.items()[0].id)
        assertEquals(ClipboardHistory.Capture.UNCHANGED, h.capture("otra", t0 + 70, 200))
        h.clear()
        assertEquals(ClipboardHistory.Capture.UNCHANGED, h.capture("otra", t0 + 80, 200))
        assertTrue(h.isEmpty())
    }

    @Test fun copyingAgainCollapsesAndMovesToTop() { // C4
        val h = h()
        h.capture("a", t0, 1); h.capture("b", t0 + 1, 2); h.capture("c", t0 + 2, 3)
        val idA = h.items().last().id
        h.setPinned(idA, true)
        assertEquals(ClipboardHistory.Capture.BUMPED, h.capture("a", t0 + 3, 4))
        assertEquals(3, h.size)
        val a = h.get(idA)!!
        assertEquals(2, a.count)
        assertEquals(t0 + 3, a.at)
        assertTrue("pin kept", a.pinned)
        h.setPinned(idA, false)
        assertEquals(listOf("a", "c", "b"), texts(h))
    }

    @Test fun capEvictsTheOldestUnpinned() { // C5
        val h = h()
        for (i in 0 until ClipboardHistory.MAX_ITEMS) h.capture("clip $i", t0 + i, i + 1L)
        h.setPinned(h.items().last().id, true) // "clip 0", the oldest, is pinned
        h.capture("new", t0 + 100, 1000)
        assertEquals(ClipboardHistory.MAX_ITEMS, h.size)
        val all = texts(h)
        assertTrue("pinned oldest survives", "clip 0" in all)
        assertFalse("oldest unpinned went", "clip 1" in all)
        assertTrue("newest kept", "new" in all)
    }

    @Test fun pinsHaveACeiling() { // C6
        val h = h()
        for (i in 0 until ClipboardHistory.MAX_ITEMS) h.capture("c$i", t0 + i, i + 1L)
        val ids = h.items().map { it.id }
        ids.take(ClipboardHistory.MAX_PINS).forEach { assertTrue(h.setPinned(it, true)) }
        assertFalse(h.setPinned(ids[ClipboardHistory.MAX_PINS], true))
        assertEquals(ClipboardHistory.MAX_PINS, h.pinCount)
        // a full history with the max pins still takes new copies
        assertEquals(ClipboardHistory.Capture.ADDED, h.capture("fresh", t0 + 500, 999))
        assertEquals("fresh", h.items()[ClipboardHistory.MAX_PINS].text)
        assertEquals(ClipboardHistory.MAX_ITEMS, h.size)
    }

    @Test fun purgeDropsOnlyStaleUnpinned() { // C7
        val h = h()
        h.capture("old pinned", t0, 1)
        h.capture("old", t0 + 1, 2)
        h.capture("fresh", t0 + ClipboardHistory.PURGE_MS, 3)
        h.setPinned(h.items().last().id, true)
        val now = t0 + ClipboardHistory.PURGE_MS + 2
        assertTrue(h.purge(now, ClipboardHistory.PURGE_MS))
        assertEquals(listOf("old pinned", "fresh"), texts(h))
        assertFalse("nothing more to purge", h.purge(now, ClipboardHistory.PURGE_MS))
    }

    @Test fun persistenceRoundTripsEverything() { // C8
        val h = h()
        val tricky = "línea 1\n\"comillas\" \\ barra\ttab 😀 ñ"
        h.capture("primero", t0, 1)
        h.capture(tricky, t0 + 1, 2)
        h.capture("primero", t0 + 2, 3) // count 2, on top
        h.setPinned(h.get(2)!!.id, true)
        val back = ClipboardHistory.fromJson(h.toJson())
        assertEquals(h.items(), back.items())
        assertEquals(h.toJson(), back.toJson())
        // ids keep growing after a reload; the last stamp is remembered (C3 across restarts)
        assertEquals(ClipboardHistory.Capture.UNCHANGED, back.capture("primero", t0 + 9, 3))
        back.capture("nuevo", t0 + 10, 4)
        assertTrue(back.items().map { it.id }.toSet().size == 3)
    }

    @Test fun corruptOrTamperedStorageIsSanitized() { // C8
        for (bad in listOf("", "{", "[]", "null", "{\"v\":99,\"items\":[[1,\"x\",1,1,0]]}", "{\"v\":1,\"items\":\"no\"}"))
            assertTrue(bad, ClipboardHistory.fromJson(bad).isEmpty())
        val big = "a".repeat(ClipboardHistory.MAX_BYTES + 1)
        val items = buildList {
            add("[1,\"dup\",5,1,0]"); add("[2,\"dup\",4,1,0]") // duplicate text
            add("[3,\"$big\",3,1,0]") // oversized
            add("[4,\"  \",3,1,0]") // blank
            add("[-1,\"neg id\",3,1,0]")
            add("[5,\"zero count\",3,0,0]")
            for (i in 0 until 40) add("[${10 + i},\"t$i\",${100 - i},1,1]") // all pinned, too many
        }.joinToString(",")
        val h = ClipboardHistory.fromJson("{\"v\":1,\"next\":2,\"last\":7,\"items\":[$items]}")
        assertEquals(ClipboardHistory.MAX_ITEMS, h.size)
        assertEquals(1, h.items().count { it.text == "dup" })
        assertFalse(h.items().any { it.text.isBlank() || it.text == "neg id" || it.text == "zero count" || it.text.length > 1000 })
        assertEquals(ClipboardHistory.MAX_PINS, h.pinCount)
        // next id is past every loaded id
        h.capture("after load", 200, 8)
        assertEquals(h.items().map { it.id }.distinct().size, h.size)
    }

    @Test fun staleIdsTouchNothing() { // C9
        val h = h()
        h.capture("a", t0, 1)
        assertFalse(h.delete(999))
        assertFalse(h.setPinned(999, true))
        assertEquals(1, h.size)
        assertEquals(0, h.pinCount)
    }

    @Test fun panelOrderIsPinnedFirstThenNewest() { // C10
        val h = h()
        for ((i, s) in listOf("a", "b", "c", "d").withIndex()) h.capture(s, t0 + i, i + 1L)
        h.setPinned(h.items().first { it.text == "b" }.id, true)
        h.setPinned(h.items().first { it.text == "a" }.id, true)
        assertEquals(listOf("b", "a", "d", "c"), texts(h))
    }

    // ── paste offer ─────────────────────────────────────────────────────
    private val textField = FieldPolicy.of(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 0)
    private val now = t0 + 10_000
    private fun clip(text: String? = "hola mundo", mimes: List<String> = listOf("text/plain"), uri: Boolean = false,
                     stamp: Long = t0, sensitive: Boolean = false) = ClipSnapshot(text, mimes, uri, stamp, sensitive)

    private val secrets = listOf(
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
    ).map { FieldPolicy.of(it, 0) }

    @Test fun pinnedPinConstantMatchesThePlatform() {
        assertEquals(InputType.TYPE_NUMBER_VARIATION_PASSWORD, FieldPolicy.NUMBER_VARIATION_PASSWORD)
        // a URL field (same variation bits, text class) is not a PIN
        assertFalse(FieldPolicy.of(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, 0).secret)
        assertFalse(FieldPolicy.of(InputType.TYPE_CLASS_PHONE, 0).secret)
        assertFalse(FieldPolicy.of(InputType.TYPE_CLASS_NUMBER, 0).secret)
    }

    @Test fun secretFieldsNeverGetAChip() { // O1
        for (p in secrets) {
            assertTrue(p.secret)
            assertNull(ClipRules.offer(clip(), p, emptyList(), now, 0))
            assertNull(ClipRules.offer(clip(text = null, mimes = listOf("image/png"), uri = true), p, listOf("image/*"), now, 0))
        }
    }

    @Test fun nothingToPasteNoChip() { // O2
        assertNull(ClipRules.offer(null, textField, emptyList(), now, 0))
        assertNull(ClipRules.offer(clip(text = null), textField, emptyList(), now, 0))
        assertNull(ClipRules.offer(clip(text = "   \n"), textField, emptyList(), now, 0))
        assertNull(ClipRules.offer(clip(text = "x".repeat(ClipboardHistory.MAX_BYTES + 1)), textField, emptyList(), now, 0))
    }

    @Test fun staleOrAlreadyUsedClipsAreNotOffered() { // O3
        val fresh = ClipRules.offer(clip(stamp = now - ClipRules.FRESH_MS), textField, emptyList(), now, 0)
        assertTrue(fresh is ClipOffer.Text)
        assertNull(ClipRules.offer(clip(stamp = now - ClipRules.FRESH_MS - 1), textField, emptyList(), now, 0))
        assertNull("dismissed / pasted", ClipRules.offer(clip(stamp = t0), textField, emptyList(), now, consumedStamp = t0))
        assertNotNull("a newer copy shows again", ClipRules.offer(clip(stamp = t0 + 1), textField, emptyList(), now, consumedStamp = t0))
        assertNotNull("unknown stamp: offer", ClipRules.offer(clip(stamp = 0), textField, emptyList(), now, 0))
        for (t in listOf(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
            assertNotNull("url / email fields get the chip", ClipRules.offer(clip(), FieldPolicy.of(t, 0), emptyList(), now, 0))
        // number / phone fields: only a clip with a digit
        for (t in listOf(InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE)) {
            val p = FieldPolicy.of(t, 0)
            assertNull(ClipRules.offer(clip(text = "hola mundo"), p, emptyList(), now, 0))
            assertNotNull(ClipRules.offer(clip(text = "+56 9 1234 5678"), p, emptyList(), now, 0))
        }
    }

    @Test fun ageLabels() {
        assertEquals("ahora", ClipRules.ago(t0 + 59_000, t0))
        assertEquals("hace 5 min", ClipRules.ago(t0 + 5 * 60_000, t0))
        assertEquals("hace 2 h", ClipRules.ago(t0 + 2 * 3_600_000, t0))
        assertEquals("hace 3 d", ClipRules.ago(t0 + 3 * 86_400_000L, t0))
        assertEquals("ahora", ClipRules.ago(t0, t0 + 5000)) // clock went back
    }

    @Test fun imagesOnlyWhereTheFieldTakesThem() { // O4
        val img = clip(text = null, mimes = listOf("image/png"), uri = true)
        assertNull(ClipRules.offer(img, textField, emptyList(), now, 0))
        assertNull(ClipRules.offer(img, textField, listOf("image/gif"), now, 0))
        assertNull(ClipRules.offer(img, textField, listOf("text/*"), now, 0))
        assertEquals(ClipOffer.Image("image/png", t0), ClipRules.offer(img, textField, listOf("image/*"), now, 0))
        assertEquals(ClipOffer.Image("image/png", t0), ClipRules.offer(img, textField, listOf("IMAGE/PNG"), now, 0))
        assertTrue(ClipRules.mimeMatches("*/*", "image/webp"))
        assertFalse(ClipRules.mimeMatches("image/*", "imagex/png"))
        // an image clip that also carries text: the text is offered where images aren't taken
        val both = clip(text = "pie de foto", mimes = listOf("image/png", "text/plain"), uri = true)
        assertTrue(ClipRules.offer(both, textField, emptyList(), now, 0) is ClipOffer.Text)
        assertTrue(ClipRules.offer(both, textField, listOf("image/*"), now, 0) is ClipOffer.Image)
    }

    @Test fun imageWithoutUriIsNotAnImage() { // O5
        assertNull(ClipRules.offer(clip(text = null, mimes = listOf("image/png"), uri = false), textField, listOf("image/*"), now, 0))
    }

    @Test fun sensitiveClipsAreMasked() { // O6
        val o = ClipRules.offer(clip(text = "hunter2", sensitive = true), textField, emptyList(), now, 0) as ClipOffer.Text
        assertEquals("hunter2", o.text)
        assertFalse(o.label.contains("hunter2"))
        assertEquals(ClipRules.MASK, o.label)
    }

    @Test fun labelsAreOneShortLine() { // O7
        assertEquals("hola mundo", ClipRules.label("  hola\n\n  mundo \t", false))
        val long = ClipRules.label("x".repeat(500), false)
        assertEquals(ClipRules.LABEL_MAX, long.length)
        assertTrue(long.endsWith("…"))
        // never split an emoji
        val e = ClipRules.label("😀".repeat(40), false)
        assertFalse(Character.isHighSurrogate(e[e.length - 2]) && !Character.isLowSurrogate(e[e.length - 1]))
    }

    // ── capture / read gate ─────────────────────────────────────────────
    private val on = ClipSettings()

    @Test fun secretFieldsAreNeverCaptured() { // W1
        for (p in secrets) assertFalse(ClipRules.mayCapture(p, on, sensitive = false))
        assertTrue(ClipRules.mayCapture(textField, on, sensitive = false))
        assertTrue("no field active (another app copied)", ClipRules.mayCapture(null, on, sensitive = false))
        // a refused clip stays refused when it is re-read later in an ordinary field
        val h = h()
        h.ignore(42)
        assertEquals(ClipboardHistory.Capture.UNCHANGED, h.capture("copied in a password field", t0, 42))
        assertTrue(h.isEmpty())
        assertEquals(ClipboardHistory.Capture.ADDED, h.capture("next real copy", t0, 43))
        // and it survives a restart
        val back = ClipboardHistory.fromJson(ClipboardHistory().apply { ignore(77) }.toJson())
        assertEquals(ClipboardHistory.Capture.UNCHANGED, back.capture("x", t0, 77))
    }

    @Test fun incognitoFieldsAreNotCaptured() { // W2
        val incognito = FieldPolicy.of(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertFalse(ClipRules.mayCapture(incognito, on, sensitive = false))
    }

    @Test fun historyOffCapturesNothing() { // W3
        val off = ClipSettings(history = false)
        assertFalse(ClipRules.mayCapture(textField, off, sensitive = false))
        assertFalse(ClipRules.mayCapture(null, off, sensitive = false))
        assertFalse(ClipRules.mayShowHistory(textField, off))
    }

    @Test fun sensitiveClipsAreNotCaptured() { // W4
        assertFalse(ClipRules.mayCapture(textField, on, sensitive = true))
        assertFalse(ClipRules.mayCapture(null, on, sensitive = true))
    }

    @Test fun historyIsNeverReadInSecretFields() { // W5
        for (p in secrets) assertFalse(ClipRules.mayShowHistory(p, on))
        assertTrue(ClipRules.mayShowHistory(textField, on))
    }

    @Test fun clipboardCodeHasNoNetworkPath() { // W6
        // every clipboard source file (name contains "Clip"), wherever it lives
        val root = File("src/main/kotlin")
        assertTrue("run from the app module", root.isDirectory)
        val files = root.walkTopDown().filter { it.isFile && it.name.contains("Clip") }.toList()
        assertTrue(files.map { it.name }.toString(), files.size >= 2)
        for (f in files) {
            val src = f.readText()
            for (bad in listOf("java.net", "HttpURLConnection", "okhttp", "Socket", "URL(", "Updater"))
                assertFalse("${f.name} mentions $bad", src.contains(bad))
        }
    }

    // ── settings ────────────────────────────────────────────────────────
    @Test fun defaultsAreHistoryOnPurgeOff() { // Q1
        val seed = ProfileCodec.seed()
        assertEquals(ClipSettings(history = true, purgeHour = false), seed.clip)
        // r5 storage (no clip keys) lands on the defaults
        val r5 = ProfileCodec.encode(seed).filterKeys { !it.startsWith("clip.") }
        assertEquals(ClipSettings(), ProfileCodec.decode(r5).clip)
    }

    @Test fun clipSettingsRoundTripAndAreSanitized() { // Q2
        val st = ProfileCodec.seed().copy(clip = ClipSettings(history = false, purgeHour = true))
        val back = ProfileCodec.decode(ProfileCodec.encode(st))
        assertEquals(st, back)
        val bad = ProfileCodec.encode(ProfileCodec.seed()).toMutableMap<String, Any?>()
        bad["clip.history"] = "maybe"; bad["clip.purge"] = 3
        assertEquals(ClipSettings(), ProfileCodec.decode(bad).clip)
        // device-wide: switching tema or mode doesn't change it
        assertEquals(st.clip, back.withTema("arcade").withMode(Mode.GAME).clip)
    }
}
