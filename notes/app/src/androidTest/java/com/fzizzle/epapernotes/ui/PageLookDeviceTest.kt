package com.fzizzle.epapernotes.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageLookDeviceTest {

    /**
     * The contrast step runs once per page picture, on the worker, between MuPDF and the
     * screen: on a full page at the panel's size it must cost no more than a frame or two.
     */
    @Test fun aFullPageTakesUnderAFrameOrTwo() {
        val page = slide(1860, 2480)
        try {
            // Once first, untimed: the app runs this for every picture, so the code is warm then.
            val warm = slide(1860, 2480)
            PageLook.applyTo(warm, Contrast.STRONGEST, darkTextAsBlack = false)
            warm.recycle()

            val start = System.nanoTime()
            PageLook.applyTo(page, Contrast.STRONGEST, darkTextAsBlack = false)
            val ms = (System.nanoTime() - start) / 1_000_000
            Log.i("Notes", "contrast 1860x2480 at STRONGEST: ms=$ms")
            assertTrue("took $ms ms", ms < 80)

            // And it did its job, down to the last row (the last band of 64 is a short one).
            assertEquals(Color.WHITE, page.getPixel(10, 10))
            assertTrue(Color.red(page.getPixel(10, page.height - 1)) < 128)
        } finally {
            page.recycle()
        }
    }

    /**
     * Dark text as black runs in the same pass as contrast (readability plan Task 8, step 5):
     * with both on, a full page still costs under 80 ms.
     */
    @Test fun aFullPageWithDarkTextAsBlackTakesUnderAFrameOrTwo() {
        val page = slide(1860, 2480, text = SLATE, formula = GREEN)
        try {
            val warm = slide(1860, 2480, text = SLATE, formula = GREEN)
            PageLook.applyTo(warm, Contrast.STRONGEST, darkTextAsBlack = true)
            warm.recycle()

            val start = System.nanoTime()
            PageLook.applyTo(page, Contrast.STRONGEST, darkTextAsBlack = true)
            val ms = (System.nanoTime() - start) / 1_000_000
            Log.i("Notes", "contrast + dark text 1860x2480 at STRONGEST: ms=$ms")
            assertTrue("took $ms ms", ms < 80)

            // White stays white; the slate text, down to the last row, is black; the formula is still green.
            assertEquals(Color.WHITE, page.getPixel(10, 10))
            assertEquals(Color.BLACK, page.getPixel(10, page.height - 1))
            val green = page.getPixel(10, page.height / 2 + 10)
            assertTrue("formula ${Integer.toHexString(green)}", Color.green(green) > Color.red(green) + 40)
        } finally {
            page.recycle()
        }
    }

    /**
     * Most of a slide is white: a white page, with "text" in its lower quarter, down to the very
     * bottom, and optionally a band of coloured "formula" just below the middle.
     */
    private fun slide(w: Int, h: Int, text: Int = Color.rgb(128, 128, 128), formula: Int? = null): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        canvas.drawRect(0f, h * 0.75f, w.toFloat(), h.toFloat(), Paint().apply { color = text })
        if (formula != null) {
            canvas.drawRect(0f, h * 0.5f, w.toFloat(), h * 0.6f, Paint().apply { color = formula })
        }
        return bmp
    }

    private companion object {
        /** The owner's workbook: body text in dark slate, formulas in green (plan Task 8). */
        val SLATE = Color.rgb(43, 47, 64)
        val GREEN = Color.rgb(46, 147, 102)
    }
}
