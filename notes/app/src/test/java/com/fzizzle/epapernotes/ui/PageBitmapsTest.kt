package com.fzizzle.epapernotes.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageBitmapsTest {
    /** "Bitmaps" are strings here, and their size is their length. */
    private fun cache(maxBytes: Long) = PageBitmaps<String>(maxBytes, { it.length.toLong() })

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

    /** A cache that notes every picture handed back: the app frees a Bitmap's memory there. */
    private fun cache(maxBytes: Long, handedBack: MutableList<String>) =
        PageBitmaps<String>(maxBytes, { it.length.toLong() }) { handedBack += it }

    @Test fun evictedPicturesAreHandedBack() {
        val back = mutableListOf<String>()
        val c = cache(10, back)
        c.put(0, 100, "aaaa")
        c.put(1, 100, "bbbb")
        assertEquals(emptyList<String>(), back)
        c.put(2, 100, "cccc") // over budget: page 0, used longest ago, goes
        assertEquals(listOf("aaaa"), back)
        // The same over and over, as with erase and undo fifty times: every old one comes back, once.
        repeat(50) { c.put(3, 100, "dddd$it") }
        assertEquals(49, back.count { it.startsWith("dddd") })
        assertEquals(back.size, back.toSet().size)
    }

    @Test fun replacedPictureIsHandedBack() {
        val back = mutableListOf<String>()
        val c = cache(100, back)
        c.put(0, 100, "old")
        c.put(0, 100, "new")
        assertEquals(listOf("old"), back)
        assertEquals("new", c.get(0, 100))
    }

    @Test fun invalidatedPagesAreHandedBack() {
        val back = mutableListOf<String>()
        val c = cache(100, back)
        c.put(3, 100, "x")
        c.put(3, 200, "y")
        c.put(4, 100, "z")
        c.invalidate(3)
        assertEquals(setOf("x", "y"), back.toSet())
        assertEquals(2, back.size)
    }

    @Test fun thePictureJustPutIsNeverHandedBack() {
        val back = mutableListOf<String>()
        val c = cache(10, back)
        // Bigger than the whole budget: everything else goes, but not the one just put.
        c.put(0, 100, "aaaa")
        c.put(1, 100, "a-picture-far-over-the-budget")
        assertEquals(listOf("aaaa"), back)
        assertEquals("a-picture-far-over-the-budget", c.get(1, 100))
        // Nor when the very same picture is put again in its own place.
        val same = "same"
        val d = cache(100, back.also { it.clear() })
        d.put(0, 100, same)
        d.put(0, 100, same)
        assertEquals(emptyList<String>(), back)
    }
}
