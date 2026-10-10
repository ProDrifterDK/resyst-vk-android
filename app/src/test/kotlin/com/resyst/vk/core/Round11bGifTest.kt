package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * r11b — opt-in GIF search (docs/failure-modes.md, "Round 11b"). Fixtures are real KLIPY
 * responses captured on 2026-10-10 with the key and the customer id scrubbed and the inline
 * `blur_preview` data emptied (src/test/resources/klipy/).
 */
class Round11bGifTest {
    private fun fixture(name: String) = javaClass.getResource("/klipy/$name.json")!!.readText()
    private val key = "A".repeat(64)
    private val params = KlipyUrls.Params("0f8fad5b-d9cb-469f-a165-70867728950e", "cl", "high")

    // ── GA1–GA4: allowlist ─────────────────────────────────────────────

    @Test fun allowlistRefusesEveryOtherOriginAndShape() { // GA1
        assertTrue(KlipyUrls.isMedia("https://static.klipy.com/ii/c3a19a0b747a76e98651f2b9a3cca5ff/1b/6b/nNYlB9RV.gif"))
        assertTrue(KlipyUrls.isApi("https://api.klipy.com/api/v1/$key/gifs/trending?page=1"))
        val media = listOf(
            "http://static.klipy.com/a.gif", "https://static.klipy.com.evil.io/a.gif", "https://static.klipy.com@evil.io/a.gif",
            "https://evil.io/static.klipy.com/a.gif", "https://static.klipy.com:8443/a.gif", "//static.klipy.com/a.gif",
            "https://STATIC.klipy.com/a.gif", "https://static.klipy.com/a b.gif", "https://static.klipy.com/a\\b.gif",
            "https://static.klipy.com/../x.gif", "https://static.klipy.com/ii/./x.gif", "https://static.klipy.com/a.gif#x",
            "https://static.klipy.com/ii/x@evil.io/a.gif", "https://static.klipy.com/a.gif\n", "https://static.klipy.com/ñ.gif",
            "https://static.klipy.com/", "https://api.klipy.com/api/v1/x", "https://static1.klipy.com/a.gif", "", " https://static.klipy.com/a.gif",
            "https://static.klipy.com/" + "a".repeat(KlipyUrls.MAX_URL),
        )
        for (u in media) assertFalse("media must refuse: $u", KlipyUrls.isMedia(u))
        for (u in listOf("https://api.klipy.com/api/v2/x", "https://api.klipy.com/api/v1", "https://api.klipy.com/api/v1/", "https://api.klipy.com.evil.io/api/v1/x",
            "https://static.klipy.com/api/v1/x", "https://api.klipy.com/api/v1/../../x"))
            assertFalse("api must refuse: $u", KlipyUrls.isApi(u))
    }

    @Test fun builtRequestsCarryOnlyTheirParametersEncoded() { // GA4 + GQ3
        val u = KlipyUrls.search(key, "gato & perro#1/?x=ñ", 2, params)!!
        assertTrue(u, u.startsWith("https://api.klipy.com/api/v1/$key/gifs/search?page=2&per_page=24&q="))
        assertTrue(u, u.contains("&q=gato%20%26%20perro%231%2F%3Fx%3D%C3%B1&"))
        assertTrue(u, u.endsWith("&customer_id=0f8fad5b-d9cb-469f-a165-70867728950e&locale=cl&content_filter=high"))
        assertNull("a blank query is trending, not an empty search", KlipyUrls.search(key, "   ", 1, params))
        assertEquals("trending has no q", false, KlipyUrls.trending(key, 1, params)!!.contains("&q="))
        assertNull("no key, no request", KlipyUrls.trending("", 1, params))
        assertNull("a malformed key (GK3) builds nothing", KlipyUrls.trending("abc def" + "x".repeat(20), 1, params))
        assertNull("page 0", KlipyUrls.trending(key, 0, params))
        assertEquals("per_page is clamped to KLIPY's 8..50", true, KlipyUrls.trending(key, 1, params.copy(perPage = 500))!!.contains("per_page=50&"))
        assertNull("a slug we would have to escape sends no share", KlipyUrls.share(key, "a/../b"))
        assertEquals("https://api.klipy.com/api/v1/$key/gifs/share/good-night-donkiss", KlipyUrls.share(key, "good-night-donkiss"))
        assertEquals("{\"customer_id\":\"id\",\"q\":\"di \\\"hola\\\"\"}", KlipyUrls.shareBody("id", "di \"hola\""))
        assertEquals("{\"customer_id\":\"id\",\"q\":\"\"}", KlipyUrls.shareBody("id", null))
    }

    @Test fun safetyLevelAndRegionFollowTheRules() { // brief: high with the filter on, medium off; locale from the country
        assertEquals("high", KlipyUrls.contentFilter(true))
        assertEquals("medium", KlipyUrls.contentFilter(false))
        assertEquals("cl", KlipyUrls.locale("CL"))
        assertNull(KlipyUrls.locale(""))
        assertNull(KlipyUrls.locale("419"))
        assertNull(KlipyUrls.locale(null))
    }

    @Test fun redactNeverLeavesTheKeyOrTheId() { // GK1
        val u = KlipyUrls.trending(key, 1, params)!!
        val r = KlipyUrls.redact(u, key, params.customerId)
        assertFalse(r.contains(key))
        assertFalse(r.contains(params.customerId))
    }

    // ── GP1–GP3: parsing ───────────────────────────────────────────────

    @Test fun realTrendingPageKeepsKlipyOrderAndPagination() { // GP2 + GP3
        val p = KlipyParse.page(fixture("trending"))
        assertTrue(p.ok)
        assertEquals(listOf("good-night-donkiss", "kermit-weightlifting", "leif-erickson", "ditka-mike-ditka-1",
            "happy-cute-99", "hurricane-irma-windy-2", "mr-bean-silly-6", "fsu-football-fsu-fireworks"), p.items.map { it.slug })
        assertEquals(0, p.skipped)
        assertTrue(p.hasNext)
        assertEquals(1, p.page)
        val s = KlipyParse.page(fixture("search"))
        assertEquals(8, s.items.size)
        assertEquals("cat-67-1--kfAseRIq6", s.items.first().slug)
        for (g in p.items + s.items) for (sz in g.files.values) for (m in sz.values) assertTrue(m.url, KlipyUrls.isMedia(m.url))
    }

    @Test fun malformedResponsesAreAnEmptyPageNeverACrash() { // GP1
        val junk = listOf(null, "", "   ", "<html>502 Bad Gateway</html>", "{", "[]", "{\"result\":false}", "{\"result\":true}",
            "{\"result\":true,\"data\":[]}", "{\"result\":true,\"data\":{\"data\":5}}", "\u0000\u0001", "{\"result\":\"true\",\"data\":{\"data\":[]}}",
            fixture("trending").dropLast(40), "{\"result\":true,\"data\":{\"data\":[" + "[".repeat(40) + "]}}", "x".repeat(KlipyParse.MAX_JSON + 1))
        for (j in junk) {
            val p = KlipyParse.page(j)
            assertFalse("must be BAD: ${j?.take(40)}", p.ok)
            assertTrue(p.items.isEmpty())
            assertFalse(p.hasNext)
        }
    }

    @Test fun undrawableItemsAreSkippedAndCountedAdsNeverParsed() { // GP2
        val good = "{\"type\":\"gif\",\"slug\":\"ok-1\",\"title\":\"a\",\"file\":{\"sm\":{\"gif\":{\"url\":\"https://static.klipy.com/a.gif\",\"width\":220,\"height\":200,\"size\":5000}}}}"
        val ad = "{\"type\":\"ad\",\"content\":\"<script>\",\"width\":300}"
        val offHost = good.replace("ok-1", "off-1").replace("https://static.klipy.com/a.gif", "https://evil.io/a.gif")
        val badSlug = good.replace("ok-1", "a b")
        val text = "{\"result\":true,\"data\":{\"data\":[$ad,$good,$offHost,$badSlug,${good.replace("ok-1", "ok-2")}],\"current_page\":3,\"has_next\":\"yes\"}}"
        val p = KlipyParse.page(text)
        assertEquals(listOf("ok-1", "ok-2"), p.items.map { it.slug })
        assertEquals(3, p.skipped)
        assertEquals(3, p.page)
        assertFalse("has_next of the wrong type = no next page", p.hasNext)
        val empty = KlipyParse.page("{\"result\":true,\"data\":{\"data\":[],\"has_next\":true}}")
        assertTrue(empty.ok)
        assertFalse("never page past an empty page", empty.hasNext)
    }

    // ── GF1–GF2: format choice ─────────────────────────────────────────

    @Test fun previewIsChosenBySizeNotByName() { // GF1: the brief's 479 KB sm webp vs 59 KB sm gif
        val g = KlipyParse.page(fixture("trending")).items.first { it.slug == "good-night-donkiss" }
        val pv = KlipyParse.preview(g, animated = true)!!
        assertEquals("gif", pv.format)
        assertEquals(58827L, pv.bytes)
        val k = KlipyParse.page(fixture("trending")).items.first { it.slug == "kermit-weightlifting" }
        assertEquals("the smaller one wins even when it is webp", "webp", KlipyParse.preview(k, animated = true)!!.format)
        assertEquals("API 26–27: the static jpg", "jpg", KlipyParse.preview(g, animated = false)!!.format)
        // every real preview is under the cap and from sm/xs
        for (it in KlipyParse.page(fixture("trending")).items + KlipyParse.page(fixture("search")).items) {
            val m = KlipyParse.preview(it, animated = true) ?: continue
            assertTrue(m.bytes <= KlipyParse.PREVIEW_CAP)
        }
        // nothing under the cap in sm → falls back to xs; nothing at all → null
        val big = Gif("x", "", mapOf("sm" to mapOf("gif" to GifMedia("https://static.klipy.com/s.gif", 220, 220, 900_000, "gif")),
            "xs" to mapOf("gif" to GifMedia("https://static.klipy.com/x.gif", 90, 90, 40_000, "gif"))))
        assertEquals("https://static.klipy.com/x.gif", KlipyParse.preview(big, animated = true)!!.url)
        assertNull(KlipyParse.preview(big.copy(files = mapOf("sm" to big.files.getValue("sm"))), animated = true))
    }

    /**
     * Review m3. Fails when the grid cell takes its aspect from any file other than the one the
     * thumbnail loader fetches: the first format of `sm` (here an over-cap 3:1 webp), the `sm`
     * size when the loader fell back to `xs`, or the animated file on the static (jpg) path.
     */
    @Test fun gridAspectIsTheAspectOfTheFetchedThumbnail() { // GF1 + GP2
        fun m(f: String, w: Int, h: Int, bytes: Long) = GifMedia("https://static.klipy.com/$w$f.$f", w, h, bytes, f)
        val g = Gif("a", "", mapOf(
            "sm" to linkedMapOf("webp" to m("webp", 300, 100, 500_000), "gif" to m("gif", 200, 200, 50_000), "jpg" to m("jpg", 100, 50, 9_000)),
            "xs" to linkedMapOf("gif" to m("gif", 90, 45, 8_000)),
        ))
        assertEquals(KlipyParse.thumbnail(g, animated = true), KlipyParse.preview(g, animated = true))
        assertEquals("the 200×200 gif is fetched, not the over-cap 3:1 webp", 1.0f, KlipyParse.previewAspect(g, animated = true), 1e-4f)
        assertEquals("static path: the jpg's 2:1", 2.0f, KlipyParse.previewAspect(g, animated = false), 1e-4f)
        val xsOnly = Gif("b", "", mapOf(
            "sm" to linkedMapOf("gif" to m("gif", 400, 100, 900_000)),
            "xs" to linkedMapOf("gif" to m("gif", 100, 100, 30_000)),
        ))
        assertEquals("sm is over the cap: the xs file (1:1) is fetched", 1.0f, KlipyParse.previewAspect(xsOnly, animated = true), 1e-4f)
        // animated build with only a jpg: the loader falls back to the static file, and so does the cell
        val jpgOnly = Gif("c", "", mapOf("sm" to linkedMapOf("jpg" to m("jpg", 150, 100, 9_000))))
        assertEquals("jpg", KlipyParse.thumbnail(jpgOnly, animated = true)?.format)
        assertEquals(1.5f, KlipyParse.previewAspect(jpgOnly, animated = true), 1e-4f)
        // every real item: the cell's aspect is the fetched file's
        for (it in KlipyParse.page(fixture("trending")).items + KlipyParse.page(fixture("search")).items) {
            val f = KlipyParse.thumbnail(it, animated = true) ?: continue
            assertEquals(it.slug, f.width.toFloat() / f.height, KlipyParse.previewAspect(it, animated = true), 1e-4f)
        }
        val src = File("src/main/kotlin/com/resyst/vk/ime/GifPanel.kt").readText()
        assertTrue("GifPanel sizes cells from the fetched file", src.contains("KlipyParse.previewAspect("))
        val feed = File("src/main/kotlin/com/resyst/vk/ime/GifFeed.kt").readText()
        assertTrue("GifFeed fetches the same file", feed.contains("KlipyParse.thumbnail("))
    }

    // ── GP2: the justified grid (review m1) ────────────────────────────

    /**
     * Fails on: an order swap (cells not left-to-right, top-to-bottom in KLIPY's order), a full
     * row that overflows or underfills the width (gaps miscounted), a trailing row stretched to
     * the width instead of keeping its natural size, or rows that overlap.
     */
    @Test fun justifyKeepsOrderFillsFullRowsAndLeavesTheTrailingRowNatural() { // GP2
        val w = 300f; val h = 100f; val gap = 4f
        val cells = GifLayout.justify(listOf(2.0f, 1.0f, 0.5f, 1.0f), w, h, gap)
        assertEquals(listOf(0, 1, 2, 3), cells.map { it.index })
        // row 1 = items 0, 1 (2.0·100 + 1.0·100 + gap ≥ 300); row 2 = items 2, 3 (the trailing row)
        val rows = cells.groupBy { it.top }.values.toList()
        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), rows.map { r -> r.map { it.index } })
        for (r in rows) for (i in 1 until r.size) {
            assertEquals("left to right with one gap", r[i - 1].right + gap, r[i].left, 1e-3f)
            assertEquals("one height per row", r[0].height, r[i].height, 1e-3f)
        }
        val full = rows[0]
        assertEquals(0f, full.first().left, 1e-3f)
        assertEquals("a full row spans the width exactly", w, full.last().right, 1e-3f)
        assertEquals("…so its widths are the width minus the gaps", w - gap, full.sumOf { it.width.toDouble() }.toFloat(), 1e-3f)
        assertEquals("aspects kept", 2.0f, full[0].width / full[0].height, 1e-3f)
        val last = rows[1]
        assertEquals("rows stack with one gap", full[0].bottom + gap, last[0].top, 1e-3f)
        assertEquals("the trailing row keeps the target height", h, last[0].height, 1e-3f)
        assertEquals("…and its natural widths", listOf(50f, 100f), last.map { it.width })
        assertTrue("…so it does not reach the edge", last.last().right < w - 1f)

        // a row closed by maxPerRow (4) before the width is reached is still a full row
        val capped = GifLayout.justify(List(6) { 0.5f }, w, h, gap)
        assertEquals((0 until 6).toList(), capped.map { it.index })
        val first = capped.filter { it.top == 0f }
        assertEquals(listOf(0, 1, 2, 3), first.map { it.index })
        assertEquals(w, first.last().right, 1e-3f)
        assertEquals(h, capped.last().height, 1e-3f)
        assertTrue(GifLayout.justify(emptyList(), w, h, gap).isEmpty())
        assertTrue(GifLayout.justify(listOf(1f), 0f, h, gap).isEmpty())
    }

    @Test fun insertOnlyHandsTheFieldATypeItDeclaredUnderTheCap() { // GF2
        val g = KlipyParse.page(fixture("trending")).items.first()
        val any = KlipyParse.insert(g, listOf("image/*"))!!
        assertEquals("gif", any.format)
        assertTrue(any.bytes <= KlipyParse.INSERT_CAP)
        assertEquals("webp", KlipyParse.insert(g, listOf("image/webp"))?.format)
        assertNull("a png-only field gets nothing", KlipyParse.insert(g, listOf("image/png")))
        assertNull("no declared types = no download", KlipyParse.insert(g, emptyList()))
        assertNull("video is never inserted", KlipyParse.insert(g, listOf("video/*")))
        assertFalse(KlipyParse.fieldTakesGifs(listOf("image/png", "text/plain")))
        assertTrue(KlipyParse.fieldTakesGifs(listOf("*/*")))
        // the hd gif of this item is 5.4 MB: never chosen
        assertFalse(any.url == g.files["hd"]?.get("gif")?.url)
    }

    // ── GO1–GO3, GI1: opt-in state + anonymous id ──────────────────────

    private var n = 0
    private fun uuid() = "0f8fad5b-d9cb-469f-a165-7086772895%02x".format(n++)

    @Test fun offByDefaultAndEveryBrokenStoredStateIsOff() { // GO1 + GO3
        assertFalse(GifOptIn().active)
        assertFalse(GifOptIn.decode { null }.active)
        val good = GifOptIn().accept(::uuid).encode()
        assertTrue(GifOptIn.decode { good[it] }.active)
        val broken = listOf(
            good + (GifOptIn.K_ON to "false"),
            good + (GifOptIn.K_CONSENT to "0"),
            good + (GifOptIn.K_CONSENT to (GifOptIn.DISCLOSURE_VERSION - 1).toString()), // older disclosure
            good + (GifOptIn.K_CONSENT to "x"),
            good + (GifOptIn.K_ID to null),
            good + (GifOptIn.K_ID to "not-a-uuid"),
            good + (GifOptIn.K_ID to "0F8FAD5B-D9CB-469F-A165-70867728950E"),
            good + (GifOptIn.K_ID to "0f8fad5b-d9cb-169f-a165-70867728950e"), // v1 (time/MAC based)
        )
        for (m in broken) {
            val s = GifOptIn.decode { m[it] }
            assertFalse("$m must be OFF", s.active)
            assertNull("an OFF state holds no id", s.customerId)
        }
    }

    @Test fun idIsCreatedOnEnableReplacedOnRequestDeletedOnDisable() { // GI1
        val on = GifOptIn().accept(::uuid)
        assertTrue(on.active)
        assertEquals(GifOptIn.DISCLOSURE_VERSION, on.consentVersion)
        val renewed = on.newId(::uuid)
        assertTrue(renewed.active)
        assertNotNull(renewed.customerId)
        assertFalse("«Nuevo ID anónimo» changes it", renewed.customerId == on.customerId)
        val off = renewed.turnOff()
        assertFalse(off.active)
        assertNull(off.customerId)
        assertEquals("no id to renew while off", off, off.newId(::uuid))
        val again = off.accept(::uuid)
        assertFalse("re-enabling never brings the old id back", again.customerId == on.customerId || again.customerId == renewed.customerId)
        assertTrue("java.util.UUID.randomUUID() is accepted", GifOptIn.UUID.matches(java.util.UUID.randomUUID().toString()))
    }

    // ── GQ1–GQ3: the query buffer ──────────────────────────────────────

    @Test fun queryBufferIsBoundedTrimmedAndNeverSplitsAnEmoji() { // GQ3
        val q = GifQuery()
        assertNull("empty = trending", q.submitted())
        "  hola   mundo ".forEach { q.type(it.toString()) }
        assertEquals("hola mundo", q.submitted())
        q.clear(); q.type("gato"); q.type("😺")
        assertEquals("gato😺", q.text)
        q.backspace()
        assertEquals("gato", q.text)
        assertFalse("newlines never enter", q.type("\n"))
        q.clear(); repeat(80) { q.type("a") }
        assertEquals(GifQuery.MAX, q.text.length)
        q.clear(); assertFalse(q.backspace())
    }

    @Test fun queryNeverReachesTheAppFieldOrTheLearningPath() { // GQ1/GQ2 (source guard on the service seam)
        val src = File("src/main/kotlin/com/resyst/vk/ime/ResystImeService.kt").readText()
        val gifKey = src.substringAfter("fun onGifKey(").substringBefore("\n    override fun ")
        for (bad in listOf("currentInputConnection", "commitText", "learn(", "learner", "engine.press", "PersonalStore"))
            assertFalse("the GIF box key path must not touch $bad", gifKey.contains(bad))
        assertTrue("typed keys go to the private buffer", gifKey.contains("gifQuery"))
        val open = src.substringAfter("private fun openGif(").substringBefore("\n    private fun ")
        assertFalse("the query is never pre-filled from the field", open.contains("getTextBeforeCursor") || open.contains("getTextAfterCursor"))
    }

    // ── GL1–GL3: connection log v2 ─────────────────────────────────────

    @Test fun oldV1LogsStillReadAfterTheUpgrade() { // GL2: a real r10 log string
        val v1 = "{\"v\":1,\"total\":7,\"first\":1700000000000,\"items\":[[1760000000000,\"check\",\"startup\",\"Ya al día (0.7.0)\"],[1759000000000,\"download\",\"user\",\"0.7.0 · 3.1 MB\"],[1758000000000,\"check\",\"user\",\"Sin conexión a internet\"]]}"
        val log = ConnectionLog.decode(v1)
        assertEquals(7, log.total)
        assertEquals(1700000000000L, log.firstAt)
        assertEquals(listOf(ConnectionLog.What.CHECK, ConnectionLog.What.DOWNLOAD, ConnectionLog.What.CHECK), log.recent().map { it.what })
        assertEquals("v1 entries get their sequence numbers (newest = total)", listOf(7, 6, 5), log.recent().map { it.n })
        assertEquals(listOf(0, 0, 0), log.recent().map { it.media })
        val next = log.add(ConnectionLog.Entry(1761000000000, ConnectionLog.What.GIF_TRENDING, ConnectionLog.Why.USER, "tendencias · 24 GIF"))
        assertEquals(8, next.total)
        assertEquals(8, next.recent().first().n)
        val back = ConnectionLog.decode(next.encode())
        assertEquals(next, back)
        assertTrue(next.encode().startsWith("{\"v\":2,"))
    }

    @Test fun aGifPageIsOneEntryAmendedWithItsThumbnails() { // GL1 + GL3
        var log = ConnectionLog()
        val n = log.nextN
        log = log.add(ConnectionLog.Entry(1, ConnectionLog.What.GIF_SEARCH, ConnectionLog.Why.USER, "«gato» · 24 GIF"))
        log = log.add(ConnectionLog.Entry(2, ConnectionLog.What.CHECK, ConnectionLog.Why.STARTUP, "Ya al día"))
        log = log.amend(n, 24)
        assertEquals(2, log.total)
        assertEquals("the page entry carries the count, no entry per thumbnail", 24, log.recent().first { it.n == n }.media)
        assertEquals(0, log.recent().first { it.what == ConnectionLog.What.CHECK }.media)
        assertEquals("unknown n is a no-op", log, log.amend(99, 3))
        assertEquals(log, ConnectionLog.decode(log.encode()))
        assertEquals("KLIPY", ConnectionLog.What.GIF_SHARE.host)
        assertEquals("kv.resyst.cl", ConnectionLog.What.CHECK.host)
    }

    /**
     * r11b on top of r11c (0.7.1): one book for both features. Fails when a 0.7.1 log (v1, with
     * r11c's `open` and 0.7.0's `startup` reasons) loses entries on upgrade, when `open` or a
     * KLIPY kind does not survive the v2 round trip, or when the updater logs anything but OPEN.
     */
    @Test fun oneBookHoldsTheUpdateCheckAndTheGifSearch() { // GL2 + OC15
        val v071 = "{\"v\":1,\"total\":4,\"first\":1000,\"items\":[[4000,\"check\",\"open\",\"Ya al día (0.7.1)\"],[3000,\"check\",\"user\",\"Ya al día (0.7.0)\"],[2000,\"download\",\"user\",\"0.7.0\"],[1000,\"check\",\"startup\",\"Hay versión nueva: 0.7.0\"]]}"
        var log = ConnectionLog.decode(v071)
        assertEquals(listOf("open", "user", "user", "startup"), log.recent().map { it.why.id })
        assertEquals(listOf(4, 3, 2, 1), log.recent().map { it.n })
        val page = log.nextN
        log = log.add(ConnectionLog.Entry(5000, ConnectionLog.What.GIF_TRENDING, ConnectionLog.Why.USER, "tendencias · 24 GIF"))
        log = log.add(ConnectionLog.Entry(6000, ConnectionLog.What.CHECK, ConnectionLog.Why.OPEN, "Hay versión nueva: 0.8.1"))
        log = log.amend(page, 20)
        val back = ConnectionLog.decode(log.encode())
        assertEquals(log, back)
        assertEquals(listOf("check/open", "gif_trending/user", "check/open", "check/user", "download/user", "check/startup"),
            back.recent().map { "${it.what.id}/${it.why.id}" })
        assertEquals(listOf("kv.resyst.cl", "KLIPY"), back.recent().take(2).map { it.what.host })
        assertEquals(20, back.recent()[1].media)
        assertEquals(6, back.total)
        assertEquals(1000L, back.firstAt)
        assertEquals("al abrir el teclado", back.recent()[0].why.label)
        assertEquals("al iniciar el teclado", back.recent().last().why.label)
        val upd = File("src/main/kotlin/com/resyst/vk/settings/Updater.kt").readText()
        val logFn = upd.substringAfter("private fun logConnection(").substringBefore("\n    }\n")
        assertTrue("the updater logs OPEN through the shared book", logFn.contains("ConnectionLog.Why.OPEN") && logFn.contains("ConnectionBook.add("))
        assertFalse("the updater no longer writes the log itself", upd.contains("LOG_KEY"))
        val klipy = File("src/main/kotlin/com/resyst/vk/settings/KlipyClient.kt").readText()
        assertFalse("the GIF client never reaches the update path", klipy.contains("Updater.") || klipy.contains("OpenCheck"))
        assertFalse("the updater never reaches the GIF client", upd.contains("KlipyClient."))
    }

    @Test fun junkV2EntriesAreDroppedNeverThrown() { // GL2
        val junk = "{\"v\":2,\"total\":3,\"items\":[[5,\"gif_search\",\"user\",\"ok\",3,3],[6,\"gif_search\",\"user\",\"ok\",-1,2],[7,\"nope\",\"user\",\"x\",0,1],[8,\"gif_share\",\"user\",\"x\"]]}"
        val log = ConnectionLog.decode(junk)
        assertEquals(1, log.recent().size)
        assertEquals(3, log.total)
        for (j in listOf("{\"v\":3}", "{\"v\":2,\"items\":\"x\"}", "{\"v\":2,\"total\":-5,\"items\":[]}")) assertEquals(0, ConnectionLog.decode(j).recent().size)
    }

    @Test fun errorsMapToOneCalmLineAndAreLogged() { // GE1
        assertEquals(GifCopy.Fail.RATE_LIMIT, GifCopy.Fail.ofStatus(429))
        assertEquals("Demasiadas búsquedas, intenta en un rato", GifCopy.Fail.RATE_LIMIT.line)
        assertEquals(GifCopy.Fail.REDIRECT, GifCopy.Fail.ofStatus(302))
        assertEquals(GifCopy.Fail.HTTP, GifCopy.Fail.ofStatus(500))
        assertNull(GifCopy.Fail.ofStatus(200))
        assertEquals("Sin conexión", GifCopy.Fail.OFFLINE.line)
        assertEquals("«gato» · Límite de KLIPY (HTTP 429)", GifCopy.pageOutcome("gato", 1, 0, GifCopy.Fail.RATE_LIMIT))
        assertEquals("tendencias · pág. 2 · 24 GIF", GifCopy.pageOutcome(null, 2, 24, null))
        assertEquals("«x» · Error HTTP 503", GifCopy.pageOutcome("x", 1, 0, GifCopy.Fail.HTTP, 503))
        assertTrue(GifCopy.pageOutcome("q".repeat(50), 9, 24, null).length <= 80 + 20) // stored outcome is capped by the log
    }

    @Test fun noCopyClaimsAnOnlyConnectionOrConnectingToNobody() { // r11b-copy: two clients exist, each line talks about its own
        val updates = listOf(UpdateNotice.promise(true), UpdateNotice.promise(false), UpdateNotice.autoSubtitle()) +
            (listOf("1.2.3", "10.20.30").flatMap { v -> UpdateNotice.labels(v).flatMap { listOf(it.title, it.action) } })
        val gif = listOf(
            GifCopy.TITLE, GifCopy.SETTING, GifCopy.PLACEHOLDER, GifCopy.ATTRIBUTION, GifCopy.OFF_LINE1, GifCopy.OFF_LINE2,
            GifCopy.OFF_BUTTON, GifCopy.ACCEPT, GifCopy.CANCEL, GifCopy.LOADING, GifCopy.EMPTY, GifCopy.NOT_ACCEPTED, GifCopy.TOO_BIG,
        ) + GifCopy.DISCLOSURE + GifCopy.Fail.values().flatMap { listOf(it.line, it.log) }
        val bad = Regex("""\bún(ica|ico)s?\b|\bunic[ao]s?\b|\bunicamente\b|únicamente|\bnadie\b|\bninguna otra\b|no se conecta a internet|nunca se conecta""", RegexOption.IGNORE_CASE)
        for (s in updates + gif) assertFalse("claims an only connection or no connection at all: $s", bad.containsMatchIn(s))
        // the OFF line names what it is about (KLIPY), never the whole keyboard's connections
        assertTrue(GifCopy.OFF_LINE2, GifCopy.OFF_LINE2.contains("no se conecta a KLIPY"))
        // the updates line names its host and says it is about updates, GIF on or off
        assertTrue(UpdateNotice.promise(true).contains("kv.resyst.cl"))
        assertTrue(UpdateNotice.promise(false).contains("actualizaciones"))
    }

    @Test fun disclosureSaysWhatWhoNeverWhereAndHowToStop() { // GO2 (the words are the contract)
        val d = GifCopy.DISCLOSURE.joinToString(" ")
        for (must in listOf("buscador de GIF", "ID anónimo", "país", "filtro", "nunca se envía", "KLIPY", "otra empresa", "Libro de conexiones", "Apágalo", "se borra el ID"))
            assertTrue("disclosure must say: $must", d.contains(must))
        assertEquals("Buscar en KLIPY", GifCopy.PLACEHOLDER) // GT1: KLIPY's required placeholder
    }

    // ── V7 → r11b: exactly two network clients, both logged, both allowlisted ──

    @Test fun onlyTwoNetworkClientsAndBothLogEveryRequest() { // GL1 + GA1 (source guard)
        val root = File("src/main/kotlin")
        val net = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readText().let { it.contains("openConnection") || it.contains("HttpsURLConnection") || it.contains("java.net.Socket") } }
            .map { it.name }.sorted().toList()
        assertEquals(listOf("KlipyClient.kt", "Updater.kt"), net)
        val k = File("src/main/kotlin/com/resyst/vk/settings/KlipyClient.kt").readText()
        assertTrue("every connect checks the allowlist first", k.substringAfter("private fun connect(").substringBefore("\n    }").contains("require(allowed(url))"))
        assertTrue("redirects are never followed", k.contains("instanceFollowRedirects = false"))
        for (fn in listOf("fun page(", "fun file(", "fun share(")) {
            val body = k.substringAfter(fn).substringBefore("\n    fun ").substringBefore("\n    // ──")
            assertTrue("$fn logs its request", body.contains("ConnectionBook.add("))
        }
        assertFalse("no ad code", k.contains("\"ad\"") || k.lowercase().contains("advert"))
    }

    // ── GK1: the key never lands in a tracked file ─────────────────────

    @Test fun klipyKeyIsInNoTrackedFile() { // GK1
        val keyFile = File("../keystore/klipy.key")
        if (!keyFile.exists()) return // public clone: there is no key to leak
        val secret = keyFile.readText().trim()
        assertTrue(secret.length >= 16)
        val proc = ProcessBuilder("git", "ls-files", "-z").directory(File("..")).redirectErrorStream(false).start()
        val files = proc.inputStream.readBytes().toString(Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }
        proc.waitFor()
        assertTrue("git ls-files listed the repo", files.size > 50)
        val leaks = files.filter { f -> File("..", f).let { it.isFile && it.length() < 20_000_000 && it.readBytes().toString(Charsets.ISO_8859_1).contains(secret) } }
        assertEquals("the KLIPY key is in a tracked file", emptyList<String>(), leaks)
        assertFalse("keystore/ stays ignored", files.any { it.startsWith("keystore/") })
    }
}
