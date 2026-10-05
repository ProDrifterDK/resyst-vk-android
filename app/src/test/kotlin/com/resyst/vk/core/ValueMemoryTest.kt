package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round 4 — complete field values remembered per kind (F*). Synthetic addresses only. */
class ValueMemoryTest {
    private val email = FieldKind.EMAIL

    @Test fun onlyCompleteValidValuesAreRemembered() { // F1
        val m = ValueMemory()
        for (bad in listOf("", "user", "user@", "@example.com", "user@example", "user @example.com",
            "a@b@example.com", "user@exa\nmple.com", "x".repeat(90) + "@example.com", "user@.com", "user@example."))
            assertFalse(bad, m.remember(email, bad))
        assertTrue(m.remember(email, "  user@example.com  ")) // field text is trimmed
        assertEquals(listOf("user@example.com"), m.suggest(email, "", 5))
        assertFalse("only emails are kept for now", m.remember(FieldKind.TEXT, "user@example.com"))
        assertFalse(m.remember(FieldKind.PASSWORD, "user@example.com"))
    }

    @Test fun prefixMatchIsCaseInsensitiveAndSkipsTheTypedValue() { // F2
        val m = ValueMemory()
        m.remember(email, "Someone.Long@Example.org")
        m.remember(email, "user@example.com")
        assertEquals(listOf("Someone.Long@Example.org"), m.suggest(email, "some", 5))
        assertEquals(listOf("Someone.Long@Example.org"), m.suggest(email, "SOMEONE.LONG@", 5))
        assertTrue(m.suggest(email, "someone.long@example.org", 5).isEmpty())
        assertTrue(m.suggest(email, "nobody", 5).isEmpty())
        assertEquals(2, m.suggest(email, "", 5).size) // an empty field offers the top values
    }

    @Test fun pickReplacesTheTypedChunkWithTheWholeValue() { // F3
        assertEquals(listOf(Out.DeleteBefore(3), Out.Commit("user@example.com")), ValueMemory.pick("use", "user@example.com"))
        assertEquals(listOf(Out.Commit("user@example.com")), ValueMemory.pick("", "user@example.com"))
        // only the chunk after the last whitespace is the value being typed
        assertEquals(listOf(Out.DeleteBefore(2), Out.Commit("user@example.com")), ValueMemory.pick("cc: us", "user@example.com"))
        assertEquals("cc: user@example.com", Edits.apply("cc: us", ValueMemory.pick("cc: us", "user@example.com")))
        assertEquals("us", ValueMemory.typed("cc: us"))
    }

    @Test fun barOnlyWithNothingAfterTheCursor() { // F3
        val m = ValueMemory()
        m.remember(email, "user@example.com")
        assertEquals(listOf("user@example.com"), ValueMemory.bar("us", "", m, email))
        assertTrue(ValueMemory.bar("us", "er@example.com", m, email).isEmpty())
        assertTrue(ValueMemory.bar("us", "", null, email).isEmpty()) // gate closed (X1/X3)
    }

    @Test fun cappedAndRankedPerKind() { // F4
        val m = ValueMemory()
        repeat(3) { m.remember(email, "user@example.com") }
        for (i in 0 until ValueMemory.CAP + 5) m.remember(email, "user$i@example.org")
        val all = m.suggest(email, "", 100)
        assertEquals(ValueMemory.CAP, all.size)
        assertEquals("user@example.com", all.first()) // the frequent one survives eviction
        assertTrue("the newest one is kept", "user${ValueMemory.CAP + 4}@example.org" in all)
        assertFalse("the oldest one-off went", "user0@example.org" in all)
        assertEquals("recent first among equals", "user${ValueMemory.CAP + 4}@example.org", all[1])
        assertTrue(m.suggest(FieldKind.TEXT, "", 5).isEmpty()) // kinds don't mix
        assertTrue(m.suggest(FieldKind.URL, "user", 5).isEmpty())
    }

    @Test fun sameValueDifferentCaseIsOneEntry() { // F4
        val m = ValueMemory()
        m.remember(email, "User@Example.com")
        m.remember(email, "user@example.com")
        assertEquals(listOf("user@example.com"), m.suggest(email, "", 5)) // latest spelling wins
    }

    @Test fun persistenceRoundTripAndCorruptInput() { // F5
        val m = ValueMemory()
        m.remember(email, "user@example.com")
        m.remember(email, "user@example.com")
        m.remember(email, "other@example.net")
        val back = ValueMemory.fromJson(m.toJson())
        assertEquals(m.suggest(email, "", 10), back.suggest(email, "", 10))
        assertEquals(m.toJson(), back.toJson())
        for (bad in listOf("", "{", "[]", "{\"v\":1,\"kinds\":{\"EMAIL\":[[\"not-an-email\",1,1]]}}", "{\"v\":1,\"kinds\":{\"NOPE\":[]}}", "{\"v\":7}"))
            assertTrue(bad, ValueMemory.fromJson(bad).suggest(email, "", 10).isEmpty())
    }

    @Test fun clearForgetsEverything() { // X4 (value side)
        val m = ValueMemory()
        m.remember(email, "user@example.com")
        m.clear()
        assertTrue(m.suggest(email, "", 10).isEmpty())
        assertTrue(m.isEmpty())
    }

    @Test fun oneSessionCountsOnce() { // F4: DONE then field exit must not double count
        val m = ValueMemory()
        val s = ValueMemory.Session(m, email)
        s.commit("user@example.com")
        s.commit("user@example.com")
        s.commit("  user@example.com")
        assertEquals(1, m.countOf(email, "user@example.com"))
        s.commit("other@example.net") // the user changed the value afterwards: that one counts
        assertEquals(1, m.countOf(email, "other@example.net"))
        assertFalse("gate closed: nothing to remember into", ValueMemory.Session(null, email).commit("user@example.com"))
    }
}
