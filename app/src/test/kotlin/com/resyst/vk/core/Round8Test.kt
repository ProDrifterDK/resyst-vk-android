package com.resyst.vk.core

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 8 field feedback. Failure modes, written before the code:
 *
 * N8-1 gesture nav, API 35+, IME nav bar hidden by us: reserve only the pill (measured 63/63 → 63).
 * N8-2 3-button nav: reserve the whole bar (measured 126/126 → 126), no gesture floor.
 * N8-3 double reserve (User 1): the system already placed our window above its 3-button bar but
 *      the insets still report the bar → padding 126 on top = an empty band. Must reserve 0.
 * N8-4 the guard must not fire before layout (geometry unknown) nor when we do cover the bar.
 * N8-5 a visible IME nav bar (globe drawn inside our window) is always reserved.
 * N8-6 API ≤ 34 keeps its own strip below us (inset 0): no 88 px floor on top.
 * N8-7 pre-35 gesture nav with an in-window pill: the globe floor (88) still applies.
 *
 * F8-1 Instagram-style composers (prose + NO_SUGGESTIONS) get the lexicon + space correction.
 * F8-2 … but never the personal model (the flag still means "don't learn here").
 * F8-3 handle / username / code boxes with NO_SUGGESTIONS stay closed.
 * F8-4 a NO_SUGGESTIONS search box stays closed even if capitalized.
 * F8-5 password / PIN / email / URL never become prose, whatever flags they add.
 * F8-6 a notification reply (RemoteInput: no opt-out) keeps everything, personal included.
 */
class Round8Test {

    // ── insets: measured on the API 35 emulator (build/e2e-r8/insets-before.json) ──
    private val screen = 2400

    @Test fun gestureNavHiddenGlobeReservesThePill() { // N8-1
        assertEquals(63, NavInsets.bottomPadding(63, 63, navigationMode = 2, captionHeight = 126, captionVisible = false))
        assertEquals(63, NavInsets.reserve(63, 63, 2, 126, false, windowBottom = screen, screenHeight = screen))
    }

    @Test fun threeButtonReservesTheBar() { // N8-2
        assertEquals(126, NavInsets.bottomPadding(126, 126, navigationMode = 0, captionHeight = 0, captionVisible = false))
        assertEquals(126, NavInsets.reserve(126, 126, 0, 0, false, windowBottom = screen, screenHeight = screen))
        assertEquals(126, NavInsets.reserve(126, 126, 1, 0, false, windowBottom = screen, screenHeight = screen))
    }

    @Test fun windowAlreadyAboveTheBarReservesNothing() { // N8-3
        // OEM 3-button: the window ends at 2274 = 2400 - 126, the insets still say 126
        assertEquals(0, NavInsets.overlap(windowBottom = 2274, screenHeight = screen, navHeight = 126))
        assertEquals(0, NavInsets.reserve(126, 126, 0, 0, false, windowBottom = 2274, screenHeight = screen))
        // and once at 0 the view still ends there: the decision is stable (no oscillation)
        assertEquals(0, NavInsets.reserve(126, 126, 0, 0, false, windowBottom = 2274, screenHeight = screen))
    }

    @Test fun guardTrustsInsetsUntilLaidOutAndWhenCovering() { // N8-4
        assertEquals(null, NavInsets.overlap(0, 0, 126))
        assertEquals(126, NavInsets.reserve(126, 126, 0, 0, false, windowBottom = 0, screenHeight = 0))
        // an unresized window (r8b: view taller than window) reaches past the screen: full bar
        assertEquals(126, NavInsets.overlap(windowBottom = 2500, screenHeight = screen, navHeight = 126))
        assertEquals(126, NavInsets.reserve(126, 126, 0, 0, false, windowBottom = 2500, screenHeight = screen))
        // partial cover keeps the whole reserve (the globe floor sits above the strip)
        assertEquals(88, NavInsets.reserve(53, 53, 2, 0, false, windowBottom = 2380, screenHeight = screen))
    }

    @Test fun visibleImeNavBarIsAlwaysReserved() { // N8-5
        assertEquals(126, NavInsets.reserve(63, 63, 2, 126, true, windowBottom = 2337, screenHeight = screen))
    }

    @Test fun api34OwnStripGetsNoFloor() { // N8-6 (insets-after-emulator-5556.json: root 0/0)
        assertEquals(0, NavInsets.reserve(0, 0, 2, 0, false, windowBottom = 2337, screenHeight = screen))
    }

    @Test fun pre35GesturePillKeepsTheGlobeFloor() { // N8-7
        assertEquals(88, NavInsets.reserve(53, 53, 2, 0, false, windowBottom = screen, screenHeight = screen))
    }

    // ── FieldPolicy: exact EditorInfo combos ─────────────────────────────
    private val on = KbSettings()
    private val cls = InputType.TYPE_CLASS_TEXT
    private val noSugg = InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    private val caps = InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    private val multi = InputType.TYPE_TEXT_FLAG_MULTI_LINE

    @Test fun pinnedConstants() {
        assertEquals(InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, FieldPolicy.FLAG_CAP_SENTENCES)
        assertEquals(InputType.TYPE_TEXT_FLAG_AUTO_CORRECT, FieldPolicy.FLAG_AUTO_CORRECT)
        assertEquals(InputType.TYPE_TEXT_FLAG_MULTI_LINE, FieldPolicy.FLAG_MULTI_LINE)
        assertEquals(InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE, FieldPolicy.VARIATION_SHORT_MESSAGE)
        assertEquals(InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE, FieldPolicy.VARIATION_LONG_MESSAGE)
        assertEquals(InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT, FieldPolicy.VARIATION_EMAIL_SUBJECT)
        assertEquals(EditorInfo.IME_MASK_ACTION, FieldPolicy.IME_MASK_ACTION)
        assertEquals(EditorInfo.IME_ACTION_SEARCH, FieldPolicy.IME_ACTION_SEARCH)
    }

    @Test fun socialComposersAreCorrected() { // F8-1 + F8-2
        val composers = listOf(
            cls or caps or multi or noSugg,                                       // 0xa4001 DM / comment composer
            cls or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE or noSugg,         // 0x80041
            cls or caps or noSugg,                                                // 0x84001 single-line caption
            cls or multi or noSugg,                                               // 0xa0001
            cls or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or noSugg,               // 0x88001
        )
        for (t in composers) for (action in listOf(EditorInfo.IME_ACTION_SEND, EditorInfo.IME_ACTION_UNSPECIFIED)) {
            val p = FieldPolicy.of(t, action)
            assertEquals("0x%x".format(t), FieldKind.TEXT, p.kind)
            assertTrue("0x%x suggestions".format(t), p.suggestions)
            assertTrue("0x%x optedOut".format(t), p.optedOut)
            assertFalse("0x%x personal".format(t), p.personalWords(on))
            // r11 (X6, deliberate change): the composer offers learned words, still learns nothing
            assertTrue("0x%x offers".format(t), p.offerWords(on))
            assertFalse(p.secret)
        }
        // incognito composer: lexicon yes, personal no (neither offered nor learned)
        val inc = FieldPolicy.of(cls or caps or multi or noSugg, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertTrue(inc.suggestions && inc.incognito)
        assertFalse(inc.personalWords(on))
        assertFalse(inc.offerWords(on))
    }

    @Test fun handlesAndCodesStayClosed() { // F8-3
        for (t in listOf(cls or noSugg, cls or InputType.TYPE_TEXT_VARIATION_PERSON_NAME or noSugg,
            cls or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or noSugg)) {
            val p = FieldPolicy.of(t, EditorInfo.IME_ACTION_DONE)
            assertFalse("0x%x".format(t), p.suggestions || p.personalWords(on) || p.offerWords(on))
        }
    }

    @Test fun optedOutSearchStaysClosed() { // F8-4
        assertFalse(FieldPolicy.of(cls or caps or noSugg, EditorInfo.IME_ACTION_SEARCH).suggestions)
        // a search box without the opt-out keeps the lexicon (unchanged behavior)
        assertTrue(FieldPolicy.of(cls, EditorInfo.IME_ACTION_SEARCH).suggestions)
    }

    @Test fun secretAndAddressFieldsNeverBecomeProse() { // F8-5
        val extra = caps or multi or noSugg or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        for (v in listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_URI)) {
            val p = FieldPolicy.of(cls or v or extra, 0)
            assertFalse("0x%x".format(v), p.suggestions)
            assertFalse(p.personalWords(on))
        }
        assertTrue(FieldPolicy.of(cls or InputType.TYPE_TEXT_VARIATION_PASSWORD or extra, 0).secret)
        val pin = FieldPolicy.of(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD or noSugg, 0)
        assertTrue(pin.secret); assertFalse(pin.suggestions)
        assertFalse(FieldPolicy.prose(InputType.TYPE_CLASS_NUMBER or multi))
    }

    @Test fun undocumentedHighBitsDoNotChangeTheClass() { // F8-7: Pixel 6 API 37 logged 0x2a4001 / 0x280001 / 0x200091
        val hi = 0x200000
        val composer = FieldPolicy.of(hi or cls or caps or multi or noSugg, EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI)
        assertTrue(composer.suggestions && composer.optedOut)
        assertFalse(FieldPolicy.of(hi or cls or noSugg, EditorInfo.IME_ACTION_DONE).suggestions)
        val visiblePw = FieldPolicy.of(hi or cls or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, EditorInfo.IME_ACTION_DONE)
        assertEquals(FieldKind.PASSWORD, visiblePw.kind); assertTrue(visiblePw.secret)
    }

    @Test fun notificationReplyKeepsEverything() { // F8-6
        val remoteInput = cls or caps or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or multi or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE
        val p = FieldPolicy.of(remoteInput, EditorInfo.IME_ACTION_SEND)
        assertTrue(p.suggestions); assertFalse(p.optedOut); assertTrue(p.personalWords(on))
    }
}
