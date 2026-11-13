package com.fzizzle.epapernotes.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryWalkTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun pdf(path: String) = File(tmp.root, path).apply { parentFile.mkdirs(); writeText("x") }

    private fun names(limit: Int = 2000) = LibraryWalk.pdfsUnder(tmp.root, limit).map { it.file.relativeTo(tmp.root).path }.toList()

    /** A folder's own PDFs first, then each of its folders all the way down, everything in natural order. */
    @Test fun walksFoldersInNaturalOrderDepthFirst() {
        pdf("Course 10/b.pdf"); pdf("Course 2/Topic/a.pdf"); pdf("Course 2/z.pdf"); pdf("loose.pdf"); pdf("Course 2/Topic 10/c.pdf"); pdf("Course 2/Topic 9/d.pdf")
        assertEquals(
            listOf("loose.pdf", "Course 2/z.pdf", "Course 2/Topic/a.pdf", "Course 2/Topic 9/d.pdf", "Course 2/Topic 10/c.pdf", "Course 10/b.pdf"),
            names(),
        )
    }

    @Test fun leavesOutHiddenAndTemporaryFiles() {
        pdf("a.pdf"); pdf(".hidden.pdf"); pdf(".a.pdf.notes-tmp"); pdf(".git/b.pdf"); pdf("notes.txt"); pdf("C/D.PDF")
        assertEquals(listOf("a.pdf", "C/D.PDF"), names())
    }

    @Test fun stopsAtTheLimit() {
        (1..5).forEach { pdf("p$it.pdf") }
        assertEquals(listOf("p1.pdf", "p2.pdf"), names(limit = 2))
    }

    /** Lazy: a folder is read only when the walk reaches it, so one added meanwhile is still found. */
    @Test fun listsAFolderOnlyWhenItGetsThere() {
        pdf("A/a.pdf")
        File(tmp.root, "B").mkdirs() // empty when the walk starts
        val walk = LibraryWalk.pdfsUnder(tmp.root).iterator()
        assertEquals("a.pdf", walk.next().file.name)
        pdf("B/b.pdf")
        assertEquals("b.pdf", walk.next().file.name)
    }

    @Test fun aFolderThatIsGoneEndsTheWalkQuietly() {
        assertEquals(emptyList<String>(), LibraryWalk.pdfsUnder(File(tmp.root, "card-out")).map { it.name }.toList())
    }
}
