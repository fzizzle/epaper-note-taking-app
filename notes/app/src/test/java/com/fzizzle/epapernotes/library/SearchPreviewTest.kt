package com.fzizzle.epapernotes.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the search box previews its matches, and the line under the preview rows. */
class SearchPreviewTest {
    @Test fun oneLetterIsTooFew() = assertFalse(SearchPreview.wanted("a"))

    @Test fun twoLettersAreEnough() = assertTrue(SearchPreview.wanted("ab"))

    @Test fun spacesDontCount() = assertFalse(SearchPreview.wanted(" a  "))

    @Test fun lettersOfTwoWordsCountTogether() = assertTrue(SearchPreview.wanted("a b"))

    @Test fun accentsAloneDontCount() = assertFalse(SearchPreview.wanted("é"))

    @Test fun nothingTypedSaysNothing() = assertEquals("", SearchPreview.line(wanted = false, shown = 0, total = 0))

    @Test fun noMatchSaysSo() = assertEquals("Nothing found.", SearchPreview.line(wanted = true, shown = 0, total = 0))

    @Test fun allShownSaysNothing() = assertEquals("", SearchPreview.line(wanted = true, shown = 12, total = 12))

    @Test fun twoHundredIsNotMany() = assertEquals("", SearchPreview.line(wanted = true, shown = 200, total = 200))

    @Test fun pastTheLimitIsCounted() =
        assertEquals("Showing 200 of 1,234. Type more to narrow it.", SearchPreview.line(wanted = true, shown = 200, total = 1234))

    @Test fun thePreviewHoldsAsManyAsSearchShows() = assertEquals(NameMatch.SEARCH_LIMIT, SearchPreview.LIMIT)
}
