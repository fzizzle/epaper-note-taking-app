package com.fzizzle.epapernotes.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class EraseSettleTest {
    @Test fun noEraseNoWait() = assertEquals(0L, EraseSettle().waitMs(1_000))

    @Test fun aShowWaitsOutTheRest() {
        val s = EraseSettle()
        s.erased(1_000)
        assertEquals(340L, s.waitMs(1_060))
        assertEquals(0L, s.waitMs(1_400))
        assertEquals(0L, s.waitMs(9_000))
    }

    @Test fun aNewEraseStartsTheWaitAgain() {
        val s = EraseSettle()
        s.erased(1_000)
        s.erased(1_300)
        assertEquals(400L, s.waitMs(1_300))
    }

    /** The owner's erases come about 1.3 s apart with button 2 held throughout: the rest alone would show each one. */
    @Test fun whileTheEraserIsHeldTheShowWaits() {
        val s = EraseSettle()
        s.erased(1_000)
        assertEquals(EraseSettle.CHECK_MS, s.waitMs(2_000, eraserHeld = true))
        assertEquals(0L, s.waitMs(2_000, eraserHeld = false))
    }

    @Test fun heldOrNotTheRestIsWaitedOut() {
        val s = EraseSettle()
        s.erased(1_000)
        assertEquals(340L, s.waitMs(1_060, eraserHeld = true))
    }

    /** A pen left lying near the screen with the button down mustn't keep erased ink on show for good. */
    @Test fun butNeverLongerThanThreeSecondsFromTheFirstErase() {
        val s = EraseSettle()
        s.erased(1_000)
        s.erased(3_900)
        assertEquals(100L, s.waitMs(3_900, eraserHeld = true))
        assertEquals(0L, s.waitMs(4_000, eraserHeld = true))
    }

    @Test fun onceShownTheNextRunStartsAfresh() {
        val s = EraseSettle()
        s.erased(1_000)
        s.shown()
        assertEquals(0L, s.waitMs(1_100, eraserHeld = true))
        s.erased(5_000)
        assertEquals(EraseSettle.CHECK_MS, s.waitMs(7_500, eraserHeld = true)) // counted from 5,000, not 1,000
    }
}
