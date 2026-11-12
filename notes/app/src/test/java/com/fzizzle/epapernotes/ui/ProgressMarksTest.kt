package com.fzizzle.epapernotes.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressMarksTest {
    @Test fun theFirstOfTenReachesATenth() = assertEquals(100, ProgressMarks.reachedPx(0, 10, 1000))

    /** Whole pixels are left over when the width doesn't divide: the last page takes them, so the line ends at the edge. */
    @Test fun theLastPageReachesTheEnd() = assertEquals(1001, ProgressMarks.reachedPx(9, 10, 1001))

    @Test fun onePageIsAllProgress() = assertEquals(1860, ProgressMarks.reachedPx(0, 1, 1860))

    @Test fun aTickSitsMidShare() = assertEquals(50, ProgressMarks.tickPx(0, 10, 1000))

    @Test fun ticksNeverLeaveTheLine() {
        for (page in 0 until 500) assertTrue("page $page", ProgressMarks.tickPx(page, 500, 1860) in 0 until 1860)
    }

    @Test fun progressNeverGoesBackwards() {
        var last = 0
        for (page in 0 until 125) {
            val reached = ProgressMarks.reachedPx(page, 125, 1860)
            assertTrue("page $page", reached >= last)
            last = reached
        }
    }

    /** Before the document is laid out there are no pages: nothing is reached, and nothing divides by zero. */
    @Test fun noPagesNoProgress() = assertEquals(0, ProgressMarks.reachedPx(0, 0, 1860))
}
