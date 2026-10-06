package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Failure modes G1–G5 (docs/failure-modes.md, round 7). */
class ImeSwitcherTest {

    private val allSpecs = Lang.values().flatMap { l ->
        TopRow.values().flatMap { t -> FieldKind.values().map { f -> LayoutSpec(l, t, f) } }
    }

    @Test fun noLayerDrawsAGlobeKey() { // G1
        for (sp in allSpecs) for (layer in Layer.values()) {
            val labels = KeyboardLayouts.rows(layer, sp).flatten().map { it.label }
            assertFalse("$layer $sp", "🌐" in labels)
        }
    }

    @Test fun olderAndroidCannotHideTheSystemGlobe() { // G2
        // API 34 has no hideable IME caption bar: even an invisible caption means nothing.
        assertFalse(ImeSwitcher.systemGlobeHidden(sdk = 34, captionHeight = 63, captionVisible = false))
        assertFalse(ImeSwitcher.systemGlobeHidden(sdk = 30, captionHeight = 0, captionVisible = false))
        assertEquals(88, NavInsets.bottomPadding(53, 53, navigationMode = 2, systemGlobeHidden = false))
    }

    @Test fun hiddenOnlyWhenTheImeNavBarExistsAndIsHidden() { // G2, G3
        assertTrue(ImeSwitcher.systemGlobeHidden(sdk = 35, captionHeight = 63, captionVisible = false))
        assertTrue(ImeSwitcher.systemGlobeHidden(sdk = 37, captionHeight = 63, captionVisible = false))
        // request not honored → the bar (and its globe) is still drawn
        assertFalse(ImeSwitcher.systemGlobeHidden(sdk = 37, captionHeight = 63, captionVisible = true))
        // no IME nav bar at all → SystemUI's own bar draws the globe
        assertFalse(ImeSwitcher.systemGlobeHidden(sdk = 37, captionHeight = 0, captionVisible = false))
        assertFalse(ImeSwitcher.systemGlobeHidden(sdk = 37, captionHeight = -1, captionVisible = false))
    }

    @Test fun floorDropsOnlyWhenTheGlobeIsGone() { // G3
        // Pixel 6 gesture pill: 53 px; the globe needed 88.
        assertEquals(53, NavInsets.bottomPadding(53, 53, navigationMode = 2, systemGlobeHidden = true))
        assertEquals(88, NavInsets.bottomPadding(53, 53, navigationMode = 2, systemGlobeHidden = false))
        assertEquals(126, NavInsets.bottomPadding(126, 126, navigationMode = 2, systemGlobeHidden = false))
        assertEquals(0, NavInsets.bottomPadding(0, 53, navigationMode = 2, systemGlobeHidden = true))
    }

    @Test fun nonGestureModesUnchanged() { // G4
        for (hidden in listOf(true, false)) {
            assertEquals(126, NavInsets.bottomPadding(126, 126, navigationMode = 0, systemGlobeHidden = hidden))
            assertEquals(53, NavInsets.bottomPadding(53, 53, navigationMode = 1, systemGlobeHidden = hidden))
        }
    }

    @Test fun spaceHintOnlyWhenSwitchingIsPossibleAndTheGlobeIsGone() { // G5
        assertTrue(ImeSwitcher.spaceHint(systemGlobeHidden = true, switchRequested = true))
        assertFalse(ImeSwitcher.spaceHint(systemGlobeHidden = true, switchRequested = false))
        assertFalse(ImeSwitcher.spaceHint(systemGlobeHidden = false, switchRequested = true))
        assertFalse(ImeSwitcher.spaceHint(systemGlobeHidden = false, switchRequested = false))
    }
}
