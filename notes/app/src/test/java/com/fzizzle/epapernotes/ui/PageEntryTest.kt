package com.fzizzle.epapernotes.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageEntryTest {
    private fun typed(pageCount: Int, vararg digits: Int) = PageEntry(pageCount).apply { digits.forEach(::press) }

    @Test fun digitsBuildANumber() {
        val e = typed(125, 1, 2)
        assertEquals("12", e.text)
        assertEquals(11, e.page())
    }

    @Test fun aNumberPastTheEndGoesToTheLastPage() = assertEquals(29, typed(30, 4, 5).page())

    @Test fun noMoreDigitsThanTheCountHas() = assertEquals("12", typed(30, 1, 2, 3).text)

    @Test fun aLeadingZeroIsNotTaken() {
        assertEquals("", typed(30, 0).text)
        assertEquals("10", typed(30, 0, 1, 0).text)
    }

    @Test fun backRemovesTheLastDigit() {
        val e = typed(125, 1, 2, 3)
        e.back()
        assertEquals("12", e.text)
        e.back(); e.back(); e.back() // one more than there are: no harm
        assertEquals("", e.text)
    }

    @Test fun nothingTypedIsNoPage() = assertNull(PageEntry(30).page())

    @Test fun aOnePageDocumentAcceptsOnlyOne() {
        assertEquals(0, typed(1, 1).page())
        assertEquals(0, typed(1, 7).page())
        assertEquals("7", typed(1, 7, 3).text)
    }
}
