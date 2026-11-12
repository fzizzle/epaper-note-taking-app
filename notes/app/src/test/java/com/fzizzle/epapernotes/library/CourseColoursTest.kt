package com.fzizzle.epapernotes.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseColoursTest {
    private val root = File("/storage/3331-3031/Workbooks")

    @Test fun aTopFolderIsItsOwnCourse() =
        assertEquals("BSA", CourseColours.courseOf(root, File(root, "BSA Building Stress Analysis")))

    @Test fun aDeepFileBelongsToItsTopFolder() =
        assertEquals("FLM", CourseColours.courseOf(root, File(root, "FLM Fluid Mechanics/Control-Volume Momentum Balance/FLM x — workbook.pdf")))

    @Test fun theLibraryFolderHasNoCourse() = assertNull(CourseColours.courseOf(root, root))

    @Test fun aLooseFileHasNoCourse() = assertNull(CourseColours.courseOf(root, File(root, "notes.pdf")))

    @Test fun somethingOutsideTheLibraryHasNoCourse() = assertNull(CourseColours.courseOf(root, File("/sdcard/Documents/a.pdf")))

    /** Never String.hashCode or anything that could differ between runs: a course keeps its colour for good. */
    @Test fun theSameCodeAlwaysGetsTheSameColour() {
        assertEquals(CourseColours.indexFor("FLM"), CourseColours.indexFor("flm"))
        assertEquals(('F'.code + 'L'.code + 'M'.code) % CourseColours.PALETTE.size, CourseColours.indexFor("FLM"))
    }

    @Test fun everyIndexIsInThePalette() {
        for (code in listOf("", "A", "BSA", "ÉCO", "12345", "a very long course name")) {
            assertTrue(code, CourseColours.indexFor(code) in CourseColours.PALETTE.indices)
        }
    }

    private val owners = listOf("ADVP", "BSA", "CTM", "FLM", "ML", "PBM", "SYSC")

    /** FLM and PBM add up to the same number: the second takes the next colour that's free. */
    @Test fun coursesThatWouldShareAColourGetDifferentOnes() {
        assertEquals(CourseColours.indexFor("FLM"), CourseColours.indexFor("PBM"))
        val given = CourseColours.assign(owners)
        assertEquals(owners.size, given.values.toSet().size)
        assertEquals(CourseColours.indexFor("FLM"), given["FLM"])
        assertEquals((CourseColours.indexFor("PBM") + 1) % CourseColours.PALETTE.size, given["PBM"])
    }

    @Test fun theOrderTheyAreGivenInMakesNoDifference() =
        assertEquals(CourseColours.assign(owners), CourseColours.assign(owners.reversed()))

    @Test fun aNewCourseOnAFreeColourMovesNobody() {
        val before = CourseColours.assign(owners)
        val free = CourseColours.PALETTE.indices.first { it !in before.values }
        val newcomer = ('A'..'Z').map { "$it" }.first { CourseColours.indexFor(it) == free }
        val after = CourseColours.assign(owners + newcomer)
        assertEquals(before, after - newcomer)
        assertEquals(free, after[newcomer])
    }

    @Test fun moreCoursesThanColoursShareRatherThanFail() {
        val many = ('A'..'L').map { "$it$it" }
        val given = CourseColours.assign(many)
        assertEquals(many.toSet(), given.keys)
        assertTrue(given.values.all { it in CourseColours.PALETTE.indices })
        assertEquals(CourseColours.PALETTE.size, given.values.toSet().size)
    }

    @Test fun codesAreCountedOnceWhateverTheirCase() =
        assertEquals(setOf("BSA"), CourseColours.assign(listOf("BSA", "bsa")).keys)
}
