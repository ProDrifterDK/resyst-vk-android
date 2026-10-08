package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Failure modes I1–I9 (docs/failure-modes.md, round 7), on the r10 tema/phone keys. */
class SettingsIATest {

    private val pages = SettingsIA.PAGES
    private val placed = pages.flatMap { it.controls }
    private val enc = ProfileCodec.encode(ProfileCodec.seed())

    /** Fields every tema stores (minus the tema's own identity). */
    private val storedTemaKeys = enc.keys
        .filter { it.startsWith("t.resyst.") }
        .map { it.removePrefix("t.resyst.") }
        .filter { it !in setOf("name", "icon") }
        .toSet()

    /** Phone-wide keys, stored under their full name. `v` and `order` are bookkeeping, not settings. */
    private val storedDeviceKeys = enc.keys
        .filter { !it.startsWith(ProfileCodec.TEMA) && it !in setOf("v", "order") }.toSet()

    @Test fun everyStoredSettingHasAControl() { // I1 / T9
        val temaKeys = Ctl.values().filter { it.scope == Scope.TEMA }.mapNotNull { it.key }.toSet()
        val deviceKeys = Ctl.values().filter { it.scope == Scope.DEVICE }.mapNotNull { it.key }.toSet()
        assertEquals(storedTemaKeys, temaKeys)
        assertEquals(storedDeviceKeys, deviceKeys)
    }

    @Test fun everyControlIsOnAPage() { // I1
        for (c in Ctl.values()) assertNotNull("$c has no page", SettingsIA.pageOf(c))
    }

    @Test fun everyKeyedControlIsARealStoredField() { // I2
        for (c in Ctl.values()) {
            val k = c.key ?: continue
            val known = if (c.scope == Scope.TEMA) k in storedTemaKeys else k in storedDeviceKeys
            assertTrue("$c → $k is not stored by ProfileCodec", known)
        }
    }

    @Test fun noControlOnTwoPages() { // I3
        assertEquals(placed.size, placed.toSet().size)
    }

    @Test fun homeShortcutsAlsoLiveOnTheirPage() { // I3
        for (c in SettingsIA.HOME) assertNotNull("$c", SettingsIA.pageOf(c))
        assertEquals(SettingsIA.HOME.size, SettingsIA.HOME.toSet().size)
    }

    @Test fun dependentsShareThePageAndFollowTheirSwitch() { // I4
        for (c in Ctl.values()) {
            val parent = c.dependsOn ?: continue
            val page = SettingsIA.pageOf(c)!!
            assertEquals("$c vs $parent", page, SettingsIA.pageOf(parent))
            assertTrue("$c listed before $parent", page.controls.indexOf(parent) < page.controls.indexOf(c))
        }
    }

    @Test fun homeStaysSmallAndHoldsTheDailySettings() { // I5
        assertTrue(SettingsIA.HOME.size <= SettingsIA.MAX_HOME)
        for (c in listOf(Ctl.TEMA, Ctl.MODE, Ctl.HAPTICS, Ctl.SOUND, Ctl.HIDE_TOP_ROW, Ctl.SUGGEST, Ctl.LANG)) {
            assertTrue("$c must be on home", c in SettingsIA.HOME)
        }
        for (c in listOf(Ctl.SUB_LEGENDS, Ctl.POPUPS, Ctl.AUTO_CAP, Ctl.DOUBLE_SPACE, Ctl.LONG_PRESS)) {
            assertFalse("$c is long tail", c in SettingsIA.HOME)
        }
        // A dependent on home would show up without its switch.
        for (c in SettingsIA.HOME) assertEquals("$c", null, c.dependsOn)
    }

    @Test fun everythingWithinTwoTaps() { // I6
        for (c in Ctl.values()) {
            val t = SettingsIA.taps(c)
            assertNotNull("$c unreachable", t)
            assertTrue("$c needs $t taps", t!! <= 2)
        }
    }

    @Test fun scopesAreNotMixed() { // I7
        for (p in pages) for (c in p.controls) assertEquals("${p.id}: $c", p.scope, c.scope)
        // the only per-tema page is the appearance one; everything else is phone-wide
        assertEquals(listOf("apariencia"), pages.filter { it.scope == Scope.TEMA }.map { it.id })
    }

    @Test fun pagesAreWellFormed() { // I8
        assertEquals(pages.size, pages.map { it.id }.toSet().size)
        assertEquals(pages.size, pages.map { it.title }.toSet().size)
        for (p in pages) {
            assertTrue(p.id, p.controls.isNotEmpty())
            assertTrue("${p.id} summary", p.summary.isNotBlank())
        }
        assertEquals(pages.first(), SettingsIA.page(pages.first().id))
        assertEquals(null, SettingsIA.page("nope"))
        assertEquals(null, SettingsIA.page(null))
        assertEquals(Ctl.values().size, Ctl.values().map { it.label }.toSet().size)
    }

    @Test fun destructiveActionsStayOffHome() { // I9
        for (c in SettingsIA.HOME) assertFalse("$c", c.destructive)
        val destructive = Ctl.values().filter { it.destructive }
        assertTrue(destructive.isNotEmpty())
        for (c in destructive) assertEquals("$c", Scope.DEVICE, SettingsIA.pageOf(c)!!.scope)
    }
}
