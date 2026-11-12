package com.fzizzle.epapernotes.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RotateOfferTest {
    private val slide = SizePt(960f, 540f)
    private val sheet = SizePt(595f, 842f)
    private fun offer(sizes: List<SizePt>, w: Int = 1860, h: Int = 2480, openedBefore: Boolean = false) =
        RotateOffer.shouldOffer(sizes, w, h, openedBefore)

    @Test fun aSidewaysDeckOnAnUprightTabletIsOffered() = assertTrue(offer(List(30) { slide }))

    @Test fun notASecondTime() = assertFalse(offer(List(30) { slide }, openedBefore = true))

    @Test fun notWhenTheTabletIsHeldSideways() = assertFalse(offer(List(30) { slide }, w = 2480, h = 1860))

    @Test fun notForUprightPages() = assertFalse(offer(List(30) { sheet }))

    /** 1.1 wide to 1 tall: turning it would gain almost nothing. */
    @Test fun notForNearlySquarePages() = assertFalse(offer(List(30) { SizePt(660f, 600f) }))

    @Test fun aMixedFileGoesByTheMajority() {
        assertTrue(offer(List(20) { slide } + List(10) { sheet }))
        assertFalse(offer(List(10) { slide } + List(20) { sheet }))
        assertFalse(offer(List(15) { slide } + List(15) { sheet })) // half isn't most
    }

    @Test fun notBeforeThePagesAreKnown() = assertFalse(offer(emptyList()))
}
