package com.fzizzle.epapernotes.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageBitmapsTest {
    /** "Bitmaps" are strings here, and their size is their length. */
    private fun cache(maxBytes: Long) = PageBitmaps<String>(maxBytes) { it.length.toLong() }

    @Test fun evictsLeastRecentlyUsed() {
        val c = cache(10)
        c.put(0, 100, "aaaa")
        c.put(1, 100, "bbbb")
        c.get(0, 100) // page 0 is now the most recently used
        c.put(2, 100, "cccc") // over budget: page 1 must go
        assertEquals("aaaa", c.get(0, 100))
        assertNull(c.get(1, 100))
        assertEquals("cccc", c.get(2, 100))
    }

    @Test fun invalidateDropsAllWidthsOfPage() {
        val c = cache(100)
        c.put(3, 100, "x")
        c.put(3, 200, "y")
        c.put(4, 100, "z")
        c.invalidate(3)
        assertNull(c.get(3, 100))
        assertNull(c.get(3, 200))
        assertEquals("z", c.get(4, 100))
    }

    @Test fun anyWidthGivesAStandInWhileTheRightOneRenders() {
        val c = cache(100)
        c.put(5, 100, "small")
        assertEquals("small", c.anyFor(5))
        assertNull(c.anyFor(6))
    }
}
