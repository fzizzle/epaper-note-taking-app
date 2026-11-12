package com.fzizzle.epapernotes.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PdfFactsTest {
    @Test fun factsRoundTrip() {
        assertEquals("30 1", PdfFacts(30, true).encode())
        assertEquals(PdfFacts(30, true), PdfFacts.decode("30 1"))
        assertEquals(PdfFacts(1, false), PdfFacts.decode(PdfFacts(1, false).encode()))
        assertEquals(PdfFacts(7, false), PdfFacts.decode("7 0\n")) // a file's last newline is no harm
    }

    @Test fun rubbishDecodesToNothing() {
        for (text in listOf("", "30", "30 2", "x 1", "30 1 1", "-3 0", "0 0")) assertNull(text, PdfFacts.decode(text))
    }
}
