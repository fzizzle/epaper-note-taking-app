package com.fzizzle.epapernotes.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NameMatchTest {
    @Test fun everyWordMustBeInTheName() {
        assertTrue(NameMatch.matches("Thermo Week 2 answers", "week ther"))
        assertFalse(NameMatch.matches("Thermo Week 2 answers", "week chem"))
    }

    @Test fun capitalsAndAccentsAreIgnored() {
        assertTrue(NameMatch.matches("Résumé", "resume"))
        assertTrue(NameMatch.matches("resume", "RÉSUMÉ"))
    }

    @Test fun anEmptyQueryMatchesNothing() {
        assertFalse(NameMatch.matches("a", ""))
        assertFalse(NameMatch.matches("a", "   "))
        assertTrue(NameMatch.words("  ").isEmpty())
    }

    @Test fun likeSpecialsAreEscaped() {
        assertEquals("%50\\%%", NameMatch.likePattern("50%"))
        assertEquals("%a\\_b%", NameMatch.likePattern("a_b"))
        assertEquals("%a\\\\b%", NameMatch.likePattern("a\\b"))
    }

    @Test fun arrangeOrdersByFolderThenName() {
        val hits = listOf(
            Hit("Week 10", "a.pdf"), Hit("Week 2", "a.pdf"), Hit("", "a.pdf"),
            Hit("", "Lecture 10.pdf"), Hit("", "Lecture 2.pdf"),
        )
        val arranged = NameMatch.arrange(hits, 10)
        assertEquals(listOf("", "", "", "Week 2", "Week 10"), arranged.map { it.folder })
        assertEquals(listOf("a.pdf", "Lecture 2.pdf", "Lecture 10.pdf"), arranged.take(3).map { it.fileName })
        assertEquals(2, NameMatch.arrange(hits, 2).size)
        assertEquals(arranged.take(2), NameMatch.arrange(hits, 2))
    }

    @Test fun folderShownJoinsWithArrows() {
        assertEquals("A › B", NameMatch.folderShown("A/B"))
        assertEquals("", NameMatch.folderShown(""))
    }
}
