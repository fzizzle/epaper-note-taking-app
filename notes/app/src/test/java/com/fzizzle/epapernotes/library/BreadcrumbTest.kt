package com.fzizzle.epapernotes.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class BreadcrumbTest {
    private val root = File("/storage/emulated/0/Documents")

    @Test fun rootToFolder() {
        val folder = File(root, "Year 2/Physics")
        assertEquals(
            listOf("Documents" to root, "Year 2" to File(root, "Year 2"), "Physics" to folder),
            Breadcrumb.parts(root, folder),
        )
    }

    @Test fun rootAloneIsOnePart() = assertEquals(listOf("Documents" to root), Breadcrumb.parts(root, root))

    @Test fun outsideRootGivesRoot() =
        assertEquals(listOf("Documents" to root), Breadcrumb.parts(root, File("/storage/emulated/0/Download/x")))

    @Test fun siblingWithSamePrefixIsOutside() =
        assertEquals(listOf("Documents" to root), Breadcrumb.parts(root, File("/storage/emulated/0/Documents2/x")))
}
