package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Round 2 field-feedback fixes; ids map to docs/failure-modes.md. */
class Round2Test {

    // ── N: navigation bar inset ─────────────────────────────────────────
    @Test fun navBarHeightBecomesBottomPadding() { // N1
        assertEquals(126, NavInsets.bottomPadding(current = 126, stable = 126)) // 3-button, 420 dpi
        assertEquals(63, NavInsets.bottomPadding(current = 63, stable = 63))    // gesture pill
    }

    @Test fun hiddenBarReservesNothing() { // N2
        assertEquals(0, NavInsets.bottomPadding(current = 0, stable = 126))
    }

    @Test fun transientTallerBarDoesNotGrowTheKeyboard() { // N3
        assertEquals(63, NavInsets.bottomPadding(current = 200, stable = 63))
    }

    @Test fun garbageInsetsNeverGoNegative() { // N4
        assertEquals(0, NavInsets.bottomPadding(current = -5, stable = 126))
        assertEquals(0, NavInsets.bottomPadding(current = 126, stable = -1))
        assertEquals(500, NavInsets.totalHeight(500, -10))
    }

    // ── N5: gesture nav globe button floats above the strip ─────────────
    @Test fun gestureModeFloorsTheReserve() { // N5a
        // Pixel 6 @356dpi: pill inset 53, but the globe button reaches ~19px above it.
        assertEquals(NavInsets.MIN_GESTURE_RESERVE, NavInsets.bottomPadding(53, 53, navigationMode = 2))
        assertEquals(88, NavInsets.bottomPadding(53, 53, navigationMode = 2))
    }

    @Test fun tallInsetsStillWinOverTheFloor() { // N5b
        assertEquals(126, NavInsets.bottomPadding(126, 126, navigationMode = 2))
    }

    @Test fun nonGestureModesKeepThePureRule() { // N5c
        assertEquals(126, NavInsets.bottomPadding(126, 126, navigationMode = 0))
        assertEquals(53, NavInsets.bottomPadding(53, 53, navigationMode = 1))
        assertEquals(53, NavInsets.bottomPadding(53, 53)) // legacy 2-arg overload = raw rule
    }

    @Test fun totalHeightCountsTheInsetExactlyOnce() { // N5
        assertEquals(829 + 126, NavInsets.totalHeight(829, 126))
        assertEquals(829, NavInsets.totalHeight(829, 0))
    }

    // ── H: hide the special-characters row ──────────────────────────────
    @Test fun rowIsVisibleByDefault() { // H1
        val d = KbSettings()
        assertFalse(d.hideTopRow)
        assertEquals(TopRow.ACCENTS, d.effectiveTopRow)
        val rows = KeyboardLayouts.rows(Layer.LETTERS, LayoutSpec(d.lang, d.effectiveTopRow))
        assertEquals(5, rows.size)
        assertEquals("á", rows[0][0].text)
        assertFalse(ProfileCodec.seed().settings.hideTopRow) // r10: phone-wide
    }

    @Test fun hidingRemovesExactlyTheTopRow() { // H2
        val shown = KeyboardLayouts.rows(Layer.LETTERS, LayoutSpec(Lang.ES, KbSettings().effectiveTopRow))
        val hiddenSettings = KbSettings(hideTopRow = true)
        assertEquals(TopRow.NONE, hiddenSettings.effectiveTopRow)
        val hidden = KeyboardLayouts.rows(Layer.LETTERS, LayoutSpec(Lang.ES, hiddenSettings.effectiveTopRow))
        assertEquals(4, hidden.size)
        assertEquals(shown.drop(1), hidden)
        assertEquals(4, hiddenSettings.baseRowCount)
        assertEquals(5, KbSettings().baseRowCount)
    }

    @Test fun unhidingRestoresTheChosenRowContent() { // H3
        val digits = KbSettings(topRow = TopRow.NUMBERS, hideTopRow = true)
        assertEquals(TopRow.NONE, digits.effectiveTopRow)
        assertEquals(TopRow.NUMBERS, digits.copy(hideTopRow = false).effectiveTopRow)
    }

    @Test fun r1StorageWithNoRowBecomesHidden() { // H4
        // r1 had no hideTopRow key and stored topRow = NONE per profile
        val map = mutableMapOf<String, Any?>("v" to "1", "active" to "noche", "order" to "noche,dia", "p.noche.topRow" to "NONE")
        val s = ProfileCodec.decode(map).settings
        assertTrue(s.hideTopRow)
        assertEquals(TopRow.ACCENTS, s.topRow)
        assertEquals(TopRow.NONE, s.effectiveTopRow)
        // r1 storage with a visible row stays visible
        assertFalse(ProfileCodec.decode(map + ("active" to "dia")).settings.hideTopRow)
    }

    @Test fun hideFlagRoundTripsAndIsPhoneWide() { // H5 (r10: phone-wide)
        val st = ProfileCodec.seed().updatePhone { it.copy(hideTopRow = true, topRow = TopRow.NUMBERS) }
        val back = ProfileCodec.decode(ProfileCodec.encode(st))
        assertEquals(st, back)
        assertTrue(back.settings.hideTopRow)
        assertTrue(back.withTema(back.nextTemaId()).settings.hideTopRow)
    }

    @Test fun otherLayersIgnoreTheFlag() { // H6
        for (layer in listOf(Layer.SYMBOLS, Layer.SYMBOLS2, Layer.NUMPAD)) {
            assertEquals(
                layer.name,
                KeyboardLayouts.rows(layer, LayoutSpec(Lang.ES, KbSettings().effectiveTopRow)),
                KeyboardLayouts.rows(layer, LayoutSpec(Lang.ES, KbSettings(hideTopRow = true).effectiveTopRow)),
            )
        }
    }

    // ── K: haptics gate ─────────────────────────────────────────────────
    @Test fun toggleOnAlwaysPulses() { // K1
        for (e in HapticEvent.values()) assertTrue(e.name, Haptics.pulseFor(e, enabled = true) != null)
    }

    @Test fun toggleOffNeverPulses() { // K2
        for (e in HapticEvent.values()) assertNull(e.name, Haptics.pulseFor(e, enabled = false))
    }

    @Test fun eventsHaveDistinctPulses() { // K3
        val pulses = HapticEvent.values().map { Haptics.pulseFor(it, true) }
        assertEquals(pulses.size, pulses.toSet().size)
        assertEquals(HapticPulse.CLICK, Haptics.pulseFor(HapticEvent.KEY, true))
    }

    @Test fun appToggleIsTheSwitchEvenWithSystemTouchFeedbackOff() { // K4
        assertEquals(HapticRoute.TOUCH, Haptics.route(systemTouchFeedbackOn = true))
        assertEquals(HapticRoute.OTHER, Haptics.route(systemTouchFeedbackOn = false))
    }

    // ── U: languages ⇄ IME subtypes ─────────────────────────────────────
    private fun subtypesInMethodXml(): List<Map<String, String>> {
        val f = File("src/main/res/xml/method.xml")
        assertTrue("method.xml not found from ${File(".").absolutePath}", f.exists())
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(f)
        val nodes = doc.getElementsByTagName("subtype")
        return (0 until nodes.length).map { i ->
            val a = nodes.item(i).attributes
            (0 until a.length).associate { j -> a.item(j).localName to a.item(j).nodeValue }
        }
    }

    private fun stringRes(name: String): String? {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/values/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) }
            .firstOrNull { it.attributes.getNamedItem("name").nodeValue == name }?.textContent
    }

    @Test fun everyLangHasExactlyOneKeyboardSubtype() { // U2
        val subs = subtypesInMethodXml()
        assertEquals(Lang.values().size, subs.size)
        for (lang in Lang.values()) {
            val matching = subs.filter { Subtypes.langOf(it["languageTag"]) == lang }
            assertEquals(lang.name, 1, matching.size)
            assertEquals(Subtypes.tagFor(lang), matching[0]["languageTag"])
            assertEquals(lang, Subtypes.langOf(matching[0]["imeSubtypeLocale"]))
            assertEquals("keyboard", matching[0]["imeSubtypeMode"])
            assertEquals("true", matching[0]["isAsciiCapable"])
        }
    }

    @Test fun subtypeLabelsAreProper() { // U3
        val labels = subtypesInMethodXml().associate {
            Subtypes.langOf(it["languageTag"]) to stringRes(it["label"]!!.removePrefix("@string/"))
        }
        assertEquals("Español (Resyst)", labels[Lang.ES])
        assertEquals("English (Resyst)", labels[Lang.EN])
    }

    @Test fun localeTagsMapToLang() { // U4
        assertEquals(Lang.ES, Subtypes.langOf("es"))
        assertEquals(Lang.ES, Subtypes.langOf("es_CL"))
        assertEquals(Lang.ES, Subtypes.langOf("es-419"))
        assertEquals(Lang.EN, Subtypes.langOf("en_US"))
        assertEquals(Lang.EN, Subtypes.langOf("EN-gb"))
        assertNull(Subtypes.langOf("fr_FR"))
        assertNull(Subtypes.langOf(""))
        assertNull(Subtypes.langOf(null))
        for (l in Lang.values()) assertEquals(l, Subtypes.langOf(Subtypes.tagFor(l)))
    }

    @Test fun reconcileDecidesWhoWins() { // U5 + U6 + U7
        // first start: the app's saved language is pushed to the system
        assertEquals(Subtypes.Sync.PUSH_TO_SYSTEM, Subtypes.reconcile(Lang.ES, Lang.EN, null))
        // user picks a language in the system picker → the app follows
        assertEquals(Subtypes.Sync.PULL_FROM_SYSTEM, Subtypes.reconcile(Lang.ES, Lang.EN, Lang.ES))
        // user changes the language in the app → the system follows
        assertEquals(Subtypes.Sync.PUSH_TO_SYSTEM, Subtypes.reconcile(Lang.EN, Lang.ES, Lang.ES))
        // the callback of our own push / save sees agreement → nothing (no ping-pong)
        assertEquals(Subtypes.Sync.NONE, Subtypes.reconcile(Lang.EN, Lang.EN, Lang.ES))
        assertEquals(Subtypes.Sync.NONE, Subtypes.reconcile(Lang.ES, Lang.ES, null))
        // system subtype unknown (other language / none): push ours
        assertEquals(Subtypes.Sync.PUSH_TO_SYSTEM, Subtypes.reconcile(Lang.ES, null, Lang.ES))
    }
}
