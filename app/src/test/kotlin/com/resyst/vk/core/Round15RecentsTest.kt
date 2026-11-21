package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** r15 (ER1–ER4 in docs/failure-modes.md): emoji recents do not move under the finger while the panel is open. */
class Round15RecentsTest {

    @Test fun er1TheThirdRecentTappedThreeTimesStaysInPlaceAndLeadsAtTheNextOpen() {
        val stored = EmojiRecents(listOf("😀", "👍", "🦅", "❤️", "🎉"))
        val row = RecentsRow()
        row.open(stored.items())
        val before = row.shown
        val committed = ArrayList<String>()
        repeat(3) {
            val tapped = row.shown[2] // the same spot every time
            committed += tapped
            stored.push(tapped) // recorded at once (the service persists it)
        }
        assertEquals("three identical emoji", listOf("🦅", "🦅", "🦅"), committed)
        assertEquals("the row did not move while open", before, row.shown)
        assertEquals("the use is recorded at once", "🦅", stored.items().first())
        // the encoded list (what the service writes to emoji_recents.txt) already has the new order: a killed process loses nothing
        assertEquals("🦅", EmojiRecents.decode(stored.encode()).items().first())
        // close and reopen: the new order shows
        row.open(stored.items())
        assertEquals(listOf("🦅", "😀", "👍", "❤️", "🎉"), row.shown)
    }

    @Test fun er2APickFromAnotherTabDoesNotJumpIntoTheOpenRow() {
        val stored = EmojiRecents(listOf("😀", "👍"))
        val row = RecentsRow()
        row.open(stored.items())
        stored.push("🍕")
        assertEquals(listOf("😀", "👍"), row.shown)
        row.open(stored.items())
        assertEquals(listOf("🍕", "😀", "👍"), row.shown)
    }

    @Test fun er3AWipeEmptiesTheOpenRowAtOnce() {
        val row = RecentsRow()
        row.open(listOf("😀", "👍"))
        row.wipe()
        assertTrue(row.shown.isEmpty())
    }

    @Test fun er1TheServiceNoLongerPushesTheNewOrderIntoTheOpenPanel() {
        val ime = File("src/main/kotlin/com/resyst/vk/ime/ResystImeService.kt").readText()
        val pick = ime.substringAfter("EmojiPanel.Act.EMOJI -> {").substringBefore("EmojiPanel.Act.SPACE ->")
        assertTrue("the pick is persisted", pick.contains("emojiFile.writeText(enc)"))
        assertFalse("the open row is not reordered by a pick", pick.contains("Recents("))
        val panel = File("src/main/kotlin/com/resyst/vk/ime/EmojiPanel.kt").readText()
        assertTrue(panel.contains("private val recents: List<String> get() = row.shown"))
        assertFalse("no setter that reorders the open row", panel.contains("fun setRecents("))
    }
}
