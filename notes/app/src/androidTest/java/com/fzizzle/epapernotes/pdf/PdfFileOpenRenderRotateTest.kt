package com.fzizzle.epapernotes.pdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fzizzle.epapernotes.geometry.Pt
import com.fzizzle.epapernotes.ink.Box
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfFileOpenRenderRotateTest {
    private val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    @Test fun opensAndCountsPages() = PdfFile.open(TestPdfs.blank(dir, pages = 3)).use { assertEquals(3, it.pageCount) }

    @Test fun rendersAtRequestedWidth() = PdfFile.open(TestPdfs.blank(dir)).use { assertEquals(1860, it.render(0, 1860).width) }

    /** Closing lets go of the file at once, not whenever the garbage collector runs. */
    @Test fun closeReleasesTheFile() {
        val file = TestPdfs.blank(dir)
        PdfFile.open(file).use { assertEquals(1, held(file)) }
        assertEquals(0, held(file))
    }

    /** Opened from memory, the document holds no handle on the file at all. */
    @Test fun inMemoryHoldsNoHandle() {
        val file = TestPdfs.blank(dir)
        PdfFile.open(file, inMemory = true).use { assertEquals(0, held(file)) }
        assertEquals(0, held(file))
    }

    private fun held(file: File) = File("/proc/self/fd").listFiles().orEmpty().count {
        try { it.canonicalPath == file.canonicalPath } catch (e: java.io.IOException) { false }
    }

    @Test fun garbageFileFailsCleanly() {
        assertThrows(PdfOpenException::class.java) { PdfFile.open(TestPdfs.garbage(dir)) }
    }

    @Test fun inheritedRotationIsReported() =
        PdfFile.open(TestPdfs.blank(dir, parentRotate = 90)).use { assertEquals(90, it.rotation(0)) }

    @Test fun rotationSurvivesSave() {
        val out = File(dir, "rot.pdf")
        PdfFile.open(TestPdfs.blank(dir)).use { it.setRotation(1, 450); it.saveTo(out) }
        PdfFile.open(out).use {
            assertEquals(0, it.rotation(0))
            assertEquals(90, it.rotation(1))
            assertEquals(792f, it.displaySize(1).width, 0.5f) // a rotated portrait page is shown landscape
        }
    }

    @Test fun passwordFileFailsCleanly() {
        val locked = TestPdfs.blank(dir, saveOptions = "encrypt=aes-256,user-password=secret,owner-password=owner")
        assertThrows(PdfOpenException::class.java) { PdfFile.open(locked) }
    }

    /** Either it refuses with a clear error, or it opens fully usable. Never a crash later. */
    @Test fun truncatedFileFailsCleanlyOrWorks() {
        val pdf = try { PdfFile.open(TestPdfs.truncated(dir)) } catch (e: PdfOpenException) { return }
        pdf.use { (0 until it.pageCount).forEach { page -> it.render(page, 100) } }
    }

    @Test(timeout = 5000) fun pageTreeLoopDoesNotHang() =
        PdfFile.open(TestPdfs.blank(dir, parentLoop = true)).use { assertEquals(0, it.rotation(0)) }

    @Test fun pdfToDisplayHandlesOffsetAndRotation() = PdfFile.open(TestPdfs.blank(dir, mediaBox = Box(100f, 100f, 712f, 892f))).use {
        val flat = it.pdfToDisplay(0).map(Pt(150f, 842f))
        assertEquals(50f, flat.x, 0.5f); assertEquals(50f, flat.y, 0.5f)
        it.setRotation(0, 90)
        val turned = it.pdfToDisplay(0).map(Pt(150f, 842f))
        assertEquals(742f, turned.x, 0.5f); assertEquals(50f, turned.y, 0.5f)
    }

    /** Laying out the pages reads both from one page load: it must give what the two separate calls give. */
    @Test fun layoutIsSizeAndTransformTogether() = PdfFile.open(TestPdfs.blank(dir, mediaBox = Box(100f, 100f, 712f, 892f))).use {
        assertEquals(it.displaySize(0) to it.pdfToDisplay(0), it.layout(0))
        it.setRotation(0, 90)
        assertEquals(it.displaySize(0) to it.pdfToDisplay(0), it.layout(0))
        assertEquals(792f, it.layout(0).first.width, 0.5f)
    }

    @Test fun blankFileWasNotRepaired() = PdfFile.open(TestPdfs.blank(dir)).use { assertFalse(it.wasRepaired) }
}
