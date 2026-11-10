package com.fzizzle.epapernotes.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThumbnailCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_000_000L
    private val dir by lazy { tmp.newFolder("thumbs") }
    private val cache by lazy { ThumbnailCache(dir) { now } }

    private fun pdf(path: String, size: Long = 10, modified: Long = 5) =
        PdfEntry(File(path), File(path).nameWithoutExtension, size, modified)

    private fun prefix(f: File) = f.name.substringBefore('-')

    @Test fun changedFileGetsANewName() {
        val a = cache.fileFor(pdf("/s/a.pdf", 10, 5))
        assertEquals(a, cache.fileFor(pdf("/s/a.pdf", 10, 5)))
        assertNotEquals(a, cache.fileFor(pdf("/s/a.pdf", 11, 5)))
        assertNotEquals(a, cache.fileFor(pdf("/s/a.pdf", 10, 6)))
        assertEquals(prefix(a), prefix(cache.fileFor(pdf("/s/a.pdf", 11, 6))))
        assertNotEquals(prefix(a), prefix(cache.fileFor(pdf("/s/b.pdf"))))
        assertTrue(Regex("[0-9a-f]{16}-10-5\\.png").matches(a.name))
    }

    @Test fun storeReplacesTheOldThumbnail() {
        val old = pdf("/s/a.pdf", 10, 5)
        val new = pdf("/s/a.pdf", 12, 7)
        val other = pdf("/s/b.pdf")
        assertNull(cache.cached(old))
        cache.store(old, byteArrayOf(1))
        cache.store(other, byteArrayOf(3))
        assertEquals(1, cache.cached(old)!!.readBytes().size)
        cache.store(new, byteArrayOf(2, 2))
        assertNull(cache.cached(old))
        assertEquals(2, cache.cached(new)!!.readBytes().size)
        assertTrue(cache.cached(other) != null)
        assertTrue(dir.list()!!.none { it.endsWith(".tmp") })
    }

    @Test fun pruneRunsAtMostDaily() {
        val a = pdf("/s/a.pdf")
        cache.store(a, byteArrayOf(1))
        cache.pruneMissing { false }
        assertNull(cache.cached(a))
        cache.store(a, byteArrayOf(1))
        now += 60_000
        cache.pruneMissing { false } // too soon
        assertTrue(cache.cached(a) != null)
        now += 24 * 60 * 60 * 1000L
        cache.pruneMissing { false }
        assertNull(cache.cached(a))
    }

    @Test fun pruneKeepsLiveOnes() {
        val a = pdf("/s/a.pdf")
        val b = pdf("/s/b.pdf")
        cache.store(a, byteArrayOf(1))
        cache.store(b, byteArrayOf(1))
        val live = prefix(cache.fileFor(a))
        cache.pruneMissing { it == live }
        assertTrue(cache.cached(a) != null)
        assertFalse(cache.cached(b) != null)
    }
}
