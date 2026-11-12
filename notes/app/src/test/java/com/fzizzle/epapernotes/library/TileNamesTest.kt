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
}
