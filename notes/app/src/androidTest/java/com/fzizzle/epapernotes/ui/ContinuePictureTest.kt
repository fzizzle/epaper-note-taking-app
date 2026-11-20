package com.fzizzle.epapernotes.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContinuePictureTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "continue-test").apply { deleteRecursively(); mkdirs() }
    // Its own "whose picture" note, so the test never touches the library's real one.
    private var whose: File? = null
    private val picture = ContinuePicture(dir, { whose }, { whose = it })

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun png(colour: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(30, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(colour) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Test fun aStoredPictureLoadsForItsFile() {
        val pdf = File("/sdcard/Documents/a.pdf")
        picture.store(pdf, png(Color.RED))
        val loaded = picture.load(pdf)
        assertNotNull(loaded)
        assertEquals(30, loaded!!.width)
        assertEquals(40, loaded.height)
    }

    @Test fun anotherFileGetsNoPicture() {
        picture.store(File("/sdcard/Documents/a.pdf"), png(Color.RED))
        assertNull(picture.load(File("/sdcard/Documents/b.pdf")))
    }

    /** Continue follows its file through a rename or move in the library, and so does its picture: the file, or a folder above it. */
    @Test fun thePictureFollowsARename() {
        val pdf = File("/sdcard/Documents/Course/a.pdf")
        picture.store(pdf, png(Color.RED))
        picture.moved(pdf, File("/sdcard/Documents/Course/b.pdf"))
        assertNotNull(picture.load(File("/sdcard/Documents/Course/b.pdf")))
        assertNull(picture.load(pdf))
        picture.moved(File("/sdcard/Documents/Course"), File("/sdcard/Documents/Renamed"))
        assertNotNull(picture.load(File("/sdcard/Documents/Renamed/b.pdf")))
        // Something else moving changes nothing.
        picture.moved(File("/sdcard/Documents/Other"), File("/sdcard/Documents/Else"))
        assertNotNull(picture.load(File("/sdcard/Documents/Renamed/b.pdf")))
    }

    /** The app killed while a new picture was being written: the last whole picture is still the one shown. */
    @Test fun aHalfWrittenPictureIsNeverLoaded() {
        val pdf = File("/sdcard/Documents/a.pdf")
        picture.store(pdf, png(Color.RED))
        val killedMidWrite = ContinuePicture(dir, { whose }, { whose = it }) { f, bytes ->
            f.writeBytes(bytes.copyOf(20)) // part of the picture is on disk…
            throw IOException("killed") // …when the app dies
        }
        assertThrows(IOException::class.java) { killedMidWrite.store(pdf, png(Color.BLUE)) }
        val shown = picture.load(pdf)
        assertNotNull("the last whole picture is still there", shown)
        assertEquals(Color.RED, shown!!.getPixel(0, 0))
        assertEquals(emptyList<String>(), dir.list()!!.filter { it.endsWith(".tmp") })
    }
}
