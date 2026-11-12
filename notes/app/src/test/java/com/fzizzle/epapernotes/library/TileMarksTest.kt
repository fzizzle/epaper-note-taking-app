package com.fzizzle.epapernotes.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TileMarksTest {
    @Test fun neverOpenedHasNoBar() = assertNull(progressOf(opened = false, lastPage = 0, pageCount = 30))

    @Test fun anUnknownCountHasNoBar() = assertNull(progressOf(opened = true, lastPage = 4, pageCount = 0))

    @Test fun theFirstOfThirtyIsAThirtieth() = assertEquals(1f / 30, progressOf(true, 0, 30)!!, 1e-6f)

    @Test fun theLastPageIsFull() = assertEquals(1f, progressOf(true, 29, 30)!!, 0f)

    /** The file was replaced by a shorter one since it was left on page 41. */
    @Test fun aStalePagePastTheEndIsFull() = assertEquals(1f, progressOf(true, 40, 30)!!, 0f)
}
