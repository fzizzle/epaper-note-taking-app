package com.fzizzle.epapernotes.library

import org.junit.Assert.assertEquals
import org.junit.Test

class TileNamesTest {
    private fun same(vararg names: String) = assertEquals(names.toList(), TileNames.labels(names.toList()))

    @Test fun workbookAndAnswerKeyLoseTheirSharedPart() = assertEquals(
        listOf("answer key", "workbook"),
        TileNames.labels(listOf("BSA Degree of Hyperstaticity — answer key", "BSA Degree of Hyperstaticity — workbook")),
    )

    @Test fun numberedNamesAreLeftWhole() = same("Lecture 1", "Lecture 2", "Lecture 10")

    @Test fun aSinglePdfKeepsItsName() = same("X — workbook")

    @Test fun enDashHyphenAndColonCountToo() {
        assertEquals(listOf("b", "c"), TileNames.labels(listOf("A – b", "A – c")))
        assertEquals(listOf("b", "c"), TileNames.labels(listOf("A - b", "A - c")))
        assertEquals(listOf("b", "c"), TileNames.labels(listOf("A: b", "A: c")))
    }

    @Test fun theLastDashInsideTheSharedPartIsUsed() =
        assertEquals(listOf("c", "d"), TileNames.labels(listOf("A — B — c", "A — B — d")))

    @Test fun aCopyKeepsWhatSetsItApart() = assertEquals(
        listOf("workbook", "workbook (my edits 2026-11-12)"),
        TileNames.labels(listOf("X — workbook", "X — workbook (my edits 2026-11-12)")),
    )

    @Test fun oneNameThatDoesNotShareMeansNoneIsShortened() = same("X — a", "X — b", "Y notes")

    @Test fun aLabelThatWouldBeEmptyMeansNoneIsShortened() = same("X — ", "X — a")

    private fun pdf(path: String, group: String? = null) =
        PdfEntry(java.io.File(path), java.io.File(path).nameWithoutExtension, 1, 1, group?.let { java.io.File(it) })

    /** Within an opened-out folder the shared start goes, and the folder's name is the heading. */
    @Test fun groupedLabelsDropTheSharedStartWithinEachGroup() {
        val wb = pdf("/r/Beams/BSA Beams — workbook.pdf", "/r/Beams")
        val key = pdf("/r/Beams/BSA Beams — answer key.pdf", "/r/Beams")
        val other = pdf("/r/Frames/BSA Frames — workbook.pdf", "/r/Frames")
        val loose1 = pdf("/r/Notes — a.pdf"); val loose2 = pdf("/r/Notes — b.pdf")
        val names = TileNames.grouped(listOf(key, wb, other, loose1, loose2))
        assertEquals("Beams" to "workbook", names[wb])
        assertEquals("Beams" to "answer key", names[key])
        // Alone in its folder: nothing is shared, so it keeps its whole name under the heading.
        assertEquals("Frames" to "BSA Frames — workbook", names[other])
        assertEquals(null to "a", names[loose1])
        assertEquals(null to "b", names[loose2])
    }
}
