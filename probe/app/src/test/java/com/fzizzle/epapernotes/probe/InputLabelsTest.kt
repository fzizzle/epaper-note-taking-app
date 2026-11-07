package com.fzizzle.epapernotes.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputLabelsTest {
    // Onyx's begin/end flag says whether a shortcut (side button) started the stroke.
    @Test fun onyxActionKeepsShortcutFlag() = assertEquals("BEGIN shortcut=true", onyxAction("BEGIN", true))

    @Test fun keyActionShowsScanCodeAndDevice() =
        assertEquals("KEY_DOWN scan=331 dev=5 src=0x5002 rep=0", keyAction(0, 331, 5, 0x5002, 0))

    @Test fun keyActionNamesMultiple() =
        assertEquals("KEY_MULTIPLE scan=0 dev=1 src=0x101 rep=3", keyAction(2, 0, 1, 0x101, 3))

    // A pen button mapped to Back would otherwise close the probe mid-test.
    @Test fun stylusBackIsSwallowed() = assertTrue(shouldSwallowKey(source = 0x4002, keyCode = 4))

    @Test fun keyboardBackStillWorks() = assertFalse(shouldSwallowKey(source = 0x101, keyCode = 4))
}
