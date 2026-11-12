package com.fzizzle.epapernotes.ui

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fzizzle.epapernotes.ink.Box
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageViewReleaseTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /**
     * Leaving a document while a page picture is still being made: it arrives after the page
     * view has let go of its pictures. It must be freed there and then, not kept by a view
     * nobody will draw again.
     */
    @Test fun aPictureArrivingAfterReleaseIsFreed() {
        instrumentation.runOnMainSync {
            val view = PageView(instrumentation.targetContext)
            view.release()
            val late = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
            view.pageRendered(0, 10, late)
            assertTrue(late.isRecycled)
            val patch = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
            view.regionRendered(0, Box(0f, 0f, 1f, 1f), token = 0, version = 0, bitmap = patch)
            assertTrue(patch.isRecycled)
        }
    }
}
