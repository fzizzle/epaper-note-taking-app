package com.fzizzle.epapernotes.storage

import android.os.Environment
import android.os.storage.StorageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The gate for skipping the whole-file read before a save (review speed plan, Task 2): on the
 * storage the owner's files live on, does a file's stamp really move at every outside change?
 * If any of these fails, the session must not trust stamps there.
 *
 * Runs in a scratch folder under Documents, and in one on the memory card when a card is in.
 * Both are removed afterwards whatever happens.
 */
@RunWith(AndroidJUnit4::class)
class FileStampDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scratch = ArrayList<File>()

    @Before fun setUp() {
        assumeTrue("needs All files access", Environment.isExternalStorageManager())
        val documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val card = context.getSystemService(StorageManager::class.java).storageVolumes
            .firstOrNull { it.isRemovable && it.state == Environment.MEDIA_MOUNTED && it.directory != null }
            ?.directory
        for (root in listOfNotNull(documents, card)) {
            val dir = File(root, ".pnotes-check-" + UUID.randomUUID().toString().take(8))
            root.mkdirs()
            assertTrue("couldn't make ${dir.path}", dir.mkdir())
            scratch += dir
        }
    }

    @After fun tearDown() {
        scratch.forEach { it.deleteRecursively() }
    }

    /** Lets the storage's clock move on, as it has between a save and a sync app's visit. */
    private fun aMomentLater() = Thread.sleep(3 * FileStamp.CLOCK_STEP_MS)

    @Test fun sameFileUntouchedGivesTheSameStamp() = scratch.forEach { dir ->
        val f = File(dir, "a.pdf").apply { writeText("hello") }
        val first = FileStamp.of(f)
        assertNotNull("no stamp on $dir: stamps can't be used there", first)
        aMomentLater()
        f.readText()
        assertEquals("on $dir", first, FileStamp.of(f))
    }

    @Test fun rewritingWithTheSameSizeAndDateChangesTheStamp() = scratch.forEach { dir ->
        val f = File(dir, "a.pdf").apply { writeText("hello") }
        val before = FileStamp.of(f)
        assertNotNull("no stamp on $dir", before)
        aMomentLater()
        f.writeText("jello")
        f.setLastModified(before!!.modifiedMs)
        val after = FileStamp.of(f)!!
        // If the date couldn't be set back here, the test proves less than it says: show it.
        assertEquals("the date wasn't set back on $dir", before.modifiedMs, after.modifiedMs)
        assertEquals(before.size, after.size)
        assertNotEquals("on $dir", before, after)
    }

    @Test fun replacingByRenameChangesTheStamp() = scratch.forEach { dir ->
        val f = File(dir, "a.pdf").apply { writeText("hello") }
        val before = FileStamp.of(f)
        assertNotNull("no stamp on $dir", before)
        aMomentLater()
        val other = File(dir, "b.pdf").apply { writeText("jello") }
        other.setLastModified(before!!.modifiedMs)
        Files.move(other.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        assertNotEquals("on $dir", before, FileStamp.of(f))
    }

    /**
     * What the app's own save relies on ([FileStamp.afterMove]): a rename keeps the file's
     * number, size and date, so the saved file can be told from one put there by something else.
     * If this fails the session is still safe, but never skips a read on that storage.
     */
    @Test fun aFileMovedIntoPlaceIsTrusted() = scratch.forEach { dir ->
        val target = File(dir, "a.pdf").apply { writeText("old") }
        val temp = File(dir, ".a.pdf.pnotes-tmp").apply { writeText("new version") }
        aMomentLater()
        val tempStamp = FileStamp.of(temp)
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        val trusted = FileStamp.afterMove(target, tempStamp)
        assertNotNull("on $dir: before $tempStamp, after ${FileStamp.of(target)}", trusted)
        // And a write in place right after is seen.
        aMomentLater()
        target.writeText("NEW VERSION")
        target.setLastModified(trusted!!.modifiedMs)
        assertNotEquals("on $dir", trusted, FileStamp.of(target))
    }
}
