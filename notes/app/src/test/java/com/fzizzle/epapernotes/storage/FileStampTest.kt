package com.fzizzle.epapernotes.storage

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileStampTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Lets the storage's clock move on, as it has between a save and a sync app's visit. */
    private fun aMomentLater() = Thread.sleep(3 * FileStamp.CLOCK_STEP_MS)

    @Test fun sameFileUntouchedGivesTheSameStamp() {
        val f = tmp.newFile("a.pdf").apply { writeText("hello") }
        val first = FileStamp.of(f)
        assertNotNull(first)
        aMomentLater()
        f.readText() // reading isn't touching
        assertEquals(first, FileStamp.of(f))
    }

    /** The case size and date alone would miss (see FileIdentityTest.editedContentDifferentHash). */
    @Test fun rewritingWithTheSameSizeAndDateChangesTheStamp() {
        val f = tmp.newFile("a.pdf").apply { writeText("hello") }
        val before = FileStamp.of(f)!!
        aMomentLater()
        f.writeText("jello")
        f.setLastModified(before.modifiedMs)
        val after = FileStamp.of(f)!!
        assertEquals(before.size, after.size)
        assertEquals(before.modifiedMs, after.modifiedMs)
        assertNotEquals(before, after)
    }

    /** How most programs save: a new file is written next to the old one and renamed over it. */
    @Test fun replacingByRenameChangesTheStamp() {
        val f = tmp.newFile("a.pdf").apply { writeText("hello") }
        val before = FileStamp.of(f)!!
        aMomentLater()
        val other = tmp.newFile("b.pdf").apply { writeText("jello") }
        other.setLastModified(before.modifiedMs)
        Files.move(other.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        val after = FileStamp.of(f)!!
        assertEquals(before.size, after.size)
        assertEquals(before.modifiedMs, after.modifiedMs)
        assertNotEquals(before, after)
    }

    @Test fun aMissingFileHasNoStamp() {
        assertNull(FileStamp.of(File(tmp.root, "nothing.pdf")))
    }

    @Test fun aFileReadAWhileAfterItsLastChangeIsTrusted() {
        val f = tmp.newFile("a.pdf").apply { writeText("hello") }
        val before = FileStamp.of(f)
        aMomentLater()
        assertEquals(before, FileStamp.afterReading(f, before))
    }

    /** A change in the same clock step as the stamp could go unseen, so such a stamp isn't trusted. */
    @Test fun aFileChangedJustNowIsNotTrustedYet() {
        val f = tmp.newFile("a.pdf").apply { writeText("hello") }
        val before = FileStamp.of(f)!!
        assertNull(FileStamp.afterReading(f, before, nowMs = before.changedNs / 1_000_000 + 1))
    }

    @Test fun aFileChangedWhileItWasReadIsNotTrusted() {
        val f = tmp.newFile("a.pdf").apply { writeText("hello") }
        val before = FileStamp.of(f)
        aMomentLater()
        f.writeText("jello")
        aMomentLater()
        assertNull(FileStamp.afterReading(f, before))
        assertNull(FileStamp.afterReading(f, before = null))
    }

    private fun moveIntoPlace(temp: File, target: File) {
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    @Test fun aFileMovedIntoPlaceIsTrusted() {
        val target = tmp.newFile("a.pdf").apply { writeText("old") }
        val temp = tmp.newFile("a.tmp").apply { writeText("new version") }
        aMomentLater() // as a save does: the new file is flushed and read before it's moved
        val tempStamp = FileStamp.of(temp)
        moveIntoPlace(temp, target)
        val trusted = FileStamp.afterMove(target, tempStamp)
        assertNotNull(trusted)
        assertEquals(FileStamp.of(target), trusted)
        assertNotEquals("a rename moves the last-changed time", tempStamp, trusted)
    }

    /** Written and moved within one clock step: a write just after could then go unseen. */
    @Test fun aFileMovedTheMomentItWasWrittenIsNotTrusted() {
        val target = tmp.newFile("a.pdf").apply { writeText("old") }
        val temp = tmp.newFile("a.tmp").apply { writeText("new version") }
        temp.setLastModified(System.currentTimeMillis() + 1000) // surely not before the move
        val tempStamp = FileStamp.of(temp)
        moveIntoPlace(temp, target)
        assertNull(FileStamp.afterMove(target, tempStamp))
    }

    /** Something else got in between the move and the look. */
    @Test fun aFileChangedRightAfterTheMoveIsNotTrusted() {
        val target = tmp.newFile("a.pdf").apply { writeText("old") }
        val temp = tmp.newFile("a.tmp").apply { writeText("new version") }
        aMomentLater()
        val tempStamp = FileStamp.of(temp)
        moveIntoPlace(temp, target)
        target.writeText("NEW VERSION") // same size, written in place
        assertNull(FileStamp.afterMove(target, tempStamp))

        val another = tmp.newFile("c.pdf").apply { writeText("new version") } // or a different file altogether
        another.setLastModified(tempStamp!!.modifiedMs)
        moveIntoPlace(another, target)
        assertNull(FileStamp.afterMove(target, tempStamp))
        assertNull(FileStamp.afterMove(target, temp = null))
    }
}
