package com.fzizzle.epapernotes.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CopyMessageTest {
    private val hint = "Copied. To paste, hold a finger on the page, or tap Paste."

    @Test fun theFirstThreeCopiesSayHowToPaste() {
        assertEquals(hint, copyMessage(0))
        assertEquals(hint, copyMessage(1))
        assertEquals(hint, copyMessage(2))
    }

    @Test fun laterCopiesJustSayCopied() {
        assertEquals("Copied.", copyMessage(3))
        assertEquals("Copied.", copyMessage(50))
    }
}
