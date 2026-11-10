package com.fzizzle.epapernotes.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fzizzle.epapernotes.pdf.PdfFile
import com.fzizzle.epapernotes.pdf.TestPdfs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThumbnailRenderTest {
    @Test fun firstPageRendersSmall() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file = TestPdfs.blank(dir)
        try {
            val start = System.nanoTime()
            val bmp = PdfFile.open(file).use { it.render(0, THUMB_WIDTH_PX) }
            val ms = (System.nanoTime() - start) / 1_000_000
            assertEquals(THUMB_WIDTH_PX, bmp.width)
            assertTrue("took $ms ms", ms < 300)
        } finally {
            file.delete()
        }
    }
}
