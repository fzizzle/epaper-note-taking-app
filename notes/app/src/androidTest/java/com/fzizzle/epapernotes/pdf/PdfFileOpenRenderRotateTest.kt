package com.fzizzle.epapernotes.pdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfFileOpenRenderRotateTest {
    private val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    @Test fun opensAndCountsPages() = PdfFile.open(TestPdfs.blank(dir, pages = 3)).use { assertEquals(3, it.pageCount) }

    @Test fun rendersAtRequestedWidth() = PdfFile.open(TestPdfs.blank(dir)).use { assertEquals(1860, it.render(0, 1860).width) }

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
}
