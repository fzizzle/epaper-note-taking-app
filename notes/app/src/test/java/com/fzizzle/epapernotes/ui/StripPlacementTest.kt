package com.fzizzle.epapernotes.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class StripPlacementTest {
    @Test fun theIslandIsCentredWhenThereIsRoom() =
        assertEquals(Placement(middleX = 570, startWidth = 300), StripPlacement.place(1860, 110, 300, 720, 400, 13))

    @Test fun aLongNameGetsOnlyTheRoomLeftOfTheIsland() =
        assertEquals(Placement(middleX = 570, startWidth = 557), StripPlacement.place(1860, 110, 2000, 720, 400, 13))

    @Test fun theIslandSlidesLeftToClearTheEnd() =
        assertEquals(Placement(middleX = 123, startWidth = 110), StripPlacement.place(1200, 110, 300, 720, 400, 13))

    @Test fun theIslandWinsWhenThereIsNoRoom() {
        for (width in listOf(900, 1200, 1860, 2480)) {
            val bare = StripPlacement.place(width, 110, 110, 720, 400, 13).middleX
            for (wanted in listOf(110, 300, 800, 5000)) {
                assertEquals(bare, StripPlacement.place(width, 110, wanted, 720, 400, 13).middleX)
            }
        }
    }
}
