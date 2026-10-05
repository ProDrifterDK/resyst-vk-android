package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KeyboardEngineTest {

    private lateinit var e: KeyboardEngine
    private val a = Key(KeyType.CHAR, "a", text = "a")
    private val dot = Key(KeyType.CHAR, ".", text = ".")
    private val space = Key(KeyType.SPACE, "", width = 4f)
    private val enter = Key(KeyType.ENTER, "")
    private val bksp = Key(KeyType.BACKSPACE, "")

    @Before fun setUp() {
        e = KeyboardEngine()
        e.start(FieldInfo())
    }

    // E1
    @Test fun shiftOnceAppliesToOneCharacter() {
        e.shiftDown(0); e.shiftUp()
        assertEquals(listOf(Out.Commit("A")), e.press(a, "", 100))
        assertEquals(listOf(Out.Commit("a")), e.press(a, "A", 200))
    }

    // E2 + E3
    @Test fun doubleTapLocksAndTapReleases() {
        e.shiftDown(1000); e.shiftUp()
        e.shiftDown(1200); e.shiftUp()
        assertEquals(ShiftState.LOCKED, e.shift)
        e.press(a, "", 1300)
        assertEquals(listOf(Out.Commit("A")), e.press(a, "A", 1400))
        assertEquals(ShiftState.LOCKED, e.shift)
        e.shiftDown(5000); e.shiftUp()
        assertEquals(ShiftState.OFF, e.shift)
    }

    @Test fun slowSecondTapTurnsShiftOff() {
        e.shiftDown(1000); e.shiftUp()
        e.shiftDown(3000); e.shiftUp()
        assertEquals(ShiftState.OFF, e.shift)
    }

    // E4
    @Test fun heldShiftChordsAndReleases() {
        e.shiftDown(0)
        assertEquals(listOf(Out.Commit("A")), e.press(a, "", 10))
        assertEquals(listOf(Out.Commit("A")), e.press(a, "A", 20))
        e.shiftUp()
        assertEquals(ShiftState.OFF, e.shift)
        assertEquals(listOf(Out.Commit("a")), e.press(a, "AA", 30))
    }

    // E5
    @Test fun layerKeysRoute() {
        e.press(Key(KeyType.LAYER, "?123", target = Layer.SYMBOLS), "", 0)
        assertEquals(Layer.SYMBOLS, e.layer)
        e.press(Key(KeyType.LAYER, "=\\<", target = Layer.SYMBOLS2), "", 0)
        assertEquals(Layer.SYMBOLS2, e.layer)
        e.press(Key(KeyType.LAYER, "ABC", target = Layer.LETTERS), "", 0)
        assertEquals(Layer.LETTERS, e.layer)
    }

    @Test fun numericFieldsStartOnNumpad() {
        e.start(FieldInfo(kind = FieldKind.NUMBER))
        assertEquals(Layer.NUMPAD, e.layer)
        e.start(FieldInfo(kind = FieldKind.PHONE))
        assertEquals(Layer.NUMPAD, e.layer)
        e.start(FieldInfo(kind = FieldKind.EMAIL))
        assertEquals(Layer.LETTERS, e.layer)
    }

    // E6
    @Test fun enterDependsOnField() {
        e.start(FieldInfo(multiLine = true, action = ImeAction.SEND))
        assertEquals(listOf(Out.Commit("\n")), e.press(enter, "", 0))
        e.start(FieldInfo(action = ImeAction.SEARCH))
        assertEquals(listOf(Out.Action(ImeAction.SEARCH)), e.press(enter, "", 0))
        e.start(FieldInfo(action = ImeAction.NONE))
        assertEquals(listOf(Out.EnterKey), e.press(enter, "", 0))
    }

    // E7
    @Test fun autoCapRespectsLockAndManualState() {
        e.updateAutoCap(true)
        assertEquals(ShiftState.AUTO, e.shift)
        e.updateAutoCap(false)
        assertEquals(ShiftState.OFF, e.shift)
        e.shiftDown(0); e.shiftUp(); e.shiftDown(100); e.shiftUp()
        e.updateAutoCap(false)
        assertEquals(ShiftState.LOCKED, e.shift)
        e.autoCapEnabled = false
        e.shiftDown(9000); e.shiftUp()
        e.updateAutoCap(true)
        assertEquals(ShiftState.OFF, e.shift)
    }

    @Test fun autoCapIsDisabledByField() {
        e.start(FieldInfo(autoCap = false))
        e.updateAutoCap(true)
        assertEquals(ShiftState.OFF, e.shift)
    }

    // E8
    @Test fun variantsHonorShift() {
        e.updateAutoCap(true)
        assertEquals(listOf(Out.Commit("É")), e.variant("é"))
        assertEquals(ShiftState.OFF, e.shift)
        assertEquals(listOf(Out.Commit("é")), e.variant("é"))
    }

    // E9
    @Test fun doubleSpaceInsertsPeriod() {
        assertEquals(listOf(Out.Commit(" ")), e.press(space, "hola", 0))
        assertEquals(listOf(Out.DeleteBefore(1), Out.Commit(". ")), e.press(space, "hola ", 100))
        assertEquals(listOf(Out.Commit(" ")), e.press(space, "hola. ", 200))
    }

    @Test fun doubleSpaceDoesNotFireAfterPunctuationOrWhenDisabled() {
        e.press(dot, "hola", 0)
        e.press(space, "hola.", 0)
        assertEquals(listOf(Out.Commit(" ")), e.press(space, "hola. ", 0))
        e.doubleSpacePeriod = false
        e.press(space, "hola", 0)
        assertEquals(listOf(Out.Commit(" ")), e.press(space, "hola ", 0))
    }

    @Test fun backspaceAndSwitch() {
        assertEquals(listOf(Out.Backspace), e.press(bksp, "x", 0))
        assertEquals(listOf(Out.SwitchIme), e.press(Key(KeyType.SWITCH_IME, ""), "", 0))
    }

    @Test fun pickingASuggestionReplacesTheWord() {
        assertEquals(listOf(Out.DeleteBefore(3), Out.Commit("hola ")), e.pickSuggestion("hola", "hol"))
        assertTrue(e.pickSuggestion("x", "").first() is Out.Commit)
    }
}
