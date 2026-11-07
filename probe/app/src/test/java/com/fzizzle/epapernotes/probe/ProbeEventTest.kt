package com.fzizzle.epapernotes.probe

import org.junit.Assert.assertEquals
import org.junit.Test

class ProbeEventTest {
    @Test fun noButtons() = assertEquals("none", buttonNames(0))

    @Test fun stylusPrimary() = assertEquals("STYLUS_PRIMARY", buttonNames(32))

    @Test fun bothStylusButtons() = assertEquals("STYLUS_PRIMARY+STYLUS_SECONDARY", buttonNames(96))

    @Test fun unknownBitsShown() = assertEquals("STYLUS_SECONDARY+0x100", buttonNames(64 or 256))

    @Test fun eraserTool() = assertEquals("ERASER", toolName(4))

    @Test fun notApplicableTool() = assertEquals("-", toolName(-1))

    @Test fun describeHoverWithButton() {
        val e = ProbeEvent(
            EventSource.HOVER, fastInk = false, toolType = 2, action = "HOVER_MOVE",
            buttonState = 64, actionButton = -1, keyCode = -1, x = 10f, y = 20f, pressure = 0f,
        )
        assertEquals(
            "HOVER fastInk=off tool=STYLUS action=HOVER_MOVE buttons=STYLUS_SECONDARY actionButton=- key=- x=10 y=20 p=0.00",
            e.describe(),
        )
    }

    @Test fun describeOnyxErase() {
        val e = ProbeEvent(
            EventSource.ONYX_ERASE, fastInk = true, toolType = -1, action = "BEGIN",
            buttonState = -1, actionButton = -1, keyCode = -1, x = 5f, y = 6f, pressure = 0.5f,
        )
        assertEquals(
            "ONYX_ERASE fastInk=on tool=- action=BEGIN buttons=- actionButton=- key=- x=5 y=6 p=0.50",
            e.describe(),
        )
    }
}
