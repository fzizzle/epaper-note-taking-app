package com.fzizzle.epapernotes.library

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileIndexDiskTest {
    @get:Rule val tmp = TemporaryFolder()

    private val disk = FileIndexDisk()

    @Test fun aFolderNotThereIsNull() {
        val missing = File(tmp.root, "gone")
        assertNull(disk.timeOf(missing))
        assertNull(disk.read(missing))
    }

    @Test fun aFileIsNotAFolder() {
        val file = File(tmp.root, "a.pdf").apply { writeText("x") }
        assertNull(disk.timeOf(file))
        assertNull(disk.read(file))
    }

    @Test fun aListingGivesSubfoldersAndPdfsWithTheirSizes() {
        File(tmp.root, "Sub").mkdirs()
        File(tmp.root, "a.pdf").writeText("abc")
        val read = disk.read(tmp.root)!!
        assertEquals(listOf("Sub"), read.folders)
        assertEquals(listOf("a.pdf" to 3L), read.pdfs.map { it.fileName to it.size })
        assertEquals(tmp.root.lastModified(), read.time)
    }

    /** Only "not there" and "not a folder" are null: an unreadable folder must not look deleted. */
    @Test fun aFolderThatCantBeListedIsThrownNotNull() {
        val locked = File(tmp.root, "locked").apply { mkdirs() }
        assertTrue(locked.setReadable(false))
        try {
            assumeTrue("running as a user who can read anything", locked.list() == null)
            assertThrows(IOException::class.java) { disk.read(locked) }
        } finally {
            locked.setReadable(true)
        }
    }
}
