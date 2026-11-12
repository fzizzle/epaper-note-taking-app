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

    private fun real(name: String): PdfEntry {
        val f = tmp.newFile(name)
        return PdfEntry(f, f.nameWithoutExtension, 10, 5)
    }

    @Test fun pruneRemovesThumbnailsOfMissingFiles() {
        val gone = real("gone.pdf")
        cache.store(gone, byteArrayOf(1))
        gone.file.delete()
        cache.pruneMissing()
        assertNull(cache.cached(gone))
        assertTrue(dir.list()!!.none { it.endsWith(".path") })
    }

    /**
     * The app started with the memory card out: none of the card's PDFs can be found, but
     * they aren't deleted. Their thumbnails must still be there when the card is back.
     */
    @Test fun pruneKeepsThumbnailsWhoseStorageIsOut() {
        val onCard = pdf("/storage/3331-3031/Workbooks/a.pdf")
        val deleted = real("gone.pdf")
        cache.store(onCard, byteArrayOf(1))
        cache.store(deleted, byteArrayOf(2))
        cache.pruneMissing(exists = { false }, storageIn = { !it.path.startsWith("/storage/3331-3031") })
        assertTrue(cache.cached(onCard) != null)
        assertNull(cache.cached(deleted))
    }

    @Test fun pruneKeepsLiveOnes() {
        val live = real("live.pdf")
        val gone = real("gone.pdf")
        cache.store(live, byteArrayOf(1))
        cache.store(gone, byteArrayOf(1))
        gone.file.delete()
        cache.pruneMissing()
        assertTrue(cache.cached(live) != null)
        assertNull(cache.cached(gone))
    }

    @Test fun pruneRunsAtMostDaily() {
        val a = real("a.pdf")
        cache.store(a, byteArrayOf(1))
        cache.pruneMissing()
        a.file.delete()
        now += 60_000
        cache.pruneMissing() // too soon
        assertTrue(cache.cached(a) != null)
        now += 24 * 60 * 60 * 1000L
        cache.pruneMissing()
        assertNull(cache.cached(a))
    }

    @Test fun pruneClearsOldTempFiles() {
        now = System.currentTimeMillis()
        val old = File(dir, ".thumb1.tmp").apply { writeBytes(byteArrayOf(1)); setLastModified(now - 2 * 3600_000L) }
        val fresh = File(dir, ".thumb2.tmp").apply { writeBytes(byteArrayOf(1)); setLastModified(now - 60_000L) }
        cache.pruneMissing()
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test fun twoStoresAtOnceLeaveAWholePng() {
        val a = pdf("/s/a.pdf")
        val x = ByteArray(200_000) { 1 }
        val y = ByteArray(300_000) { 2 }
        repeat(20) {
            val go = java.util.concurrent.CountDownLatch(1)
            val ts = listOf(x, y).map { b -> Thread { go.await(); cache.store(a, b) }.apply { start() } }
            go.countDown()
            ts.forEach { it.join() }
            val got = cache.cached(a)!!.readBytes()
            assertTrue(got.contentEquals(x) || got.contentEquals(y))
        }
        assertTrue(dir.list()!!.none { it.endsWith(".tmp") })
    }

    /**
     * Review 2.3: with no limit on height, a few very tall first pages overflowed the picture
     * memory, and the loader then drew tiles again and again.
     */
    @Test fun tallPageGetsANarrowerThumbnail() {
        assertEquals(THUMB_WIDTH_PX, thumbWidthPx(612f, 792f)) // an ordinary page: as before
        assertEquals(THUMB_WIDTH_PX, thumbWidthPx(960f, 540f)) // a slide
        assertEquals(THUMB_WIDTH_PX, thumbWidthPx(300f, 450f)) // exactly at the limit
        val narrow = thumbWidthPx(300f, 6000f)
        assertTrue("$narrow px wide", narrow >= 20 && narrow * 6000f / 300f <= THUMB_MAX_HEIGHT_PX)
        assertEquals(20, thumbWidthPx(300f, 600_000f)) // never too thin to see
        assertEquals(THUMB_WIDTH_PX, thumbWidthPx(0f, 0f)) // a page with no size: nothing to work out
    }
}
