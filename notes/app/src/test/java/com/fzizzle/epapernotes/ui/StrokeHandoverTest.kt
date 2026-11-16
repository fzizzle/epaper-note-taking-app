package com.fzizzle.epapernotes.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeHandoverTest {
    @Test fun nothingBegunNothingComing() = assertFalse(StrokeHandover().coming(1_000))

    @Test fun aStrokeIsComingFromThePenDown() {
        val h = StrokeHandover()
        h.begun()
        assertTrue(h.coming(1_000))
        assertTrue(h.coming(60_000)) // still writing: the lift hasn't come
    }

    /** Review of Task 2: Onyx reports the lift before it hands over the points. In between, the stroke is still coming. */
    @Test fun betweenTheLiftAndThePointsItIsStillComing() {
        val h = StrokeHandover()
        h.begun()
        h.lifted(1_000)
        assertTrue(h.coming(1_050))
    }

    @Test fun onceHandedOverItIsNotComing() {
        val h = StrokeHandover()
        h.begun()
        h.lifted(1_000)
        h.handedOver()
        assertFalse(h.coming(1_050))
    }

    /** Points handed over before the lift is reported: the lift doesn't make it come again. */
    @Test fun pointsBeforeTheLiftAreHandedOverToo() {
        val h = StrokeHandover()
        h.begun()
        h.handedOver()
        h.lifted(1_000)
        assertFalse(h.coming(1_050))
    }

    /** A stroke Onyx never hands over (a shortcut, say) can't hold a reload off for ever. */
    @Test fun pointsThatNeverComeStopCountingAfterAWhile() {
        val h = StrokeHandover()
        h.begun()
        h.lifted(1_000)
        assertTrue(h.coming(1_000 + StrokeHandover.WAIT_MS - 1))
        assertFalse(h.coming(1_000 + StrokeHandover.WAIT_MS))
    }

    /** Fast-ink paused under the pen: Onyx may send no lift, and maybe no points. The wait starts then. */
    @Test fun aCutStrokeStopsCountingAfterAWhile() {
        val h = StrokeHandover()
        h.begun()
        h.cut(1_000)
        assertTrue(h.coming(1_050)) // its points may still come
        assertFalse(h.coming(1_000 + StrokeHandover.WAIT_MS))
    }

    /** A pause after the lift doesn't put the wait off: it runs from the lift. */
    @Test fun aPauseAfterTheLiftKeepsTheLiftsWait() {
        val h = StrokeHandover()
        h.begun()
        h.lifted(1_000)
        h.cut(1_500)
        assertFalse(h.coming(1_000 + StrokeHandover.WAIT_MS))
    }

    @Test fun aNewStrokeComesAfterAnOldOneTimedOut() {
        val h = StrokeHandover()
        h.begun()
        h.lifted(1_000)
        h.begun()
        assertTrue(h.coming(5_000))
    }
}
