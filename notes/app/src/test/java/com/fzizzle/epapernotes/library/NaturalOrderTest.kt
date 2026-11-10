package com.fzizzle.epapernotes.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalOrderTest {
    @Test fun naturalNumbers() =
        assertEquals(listOf("Lecture 2", "Lecture 10"), listOf("Lecture 10", "Lecture 2").sortedWith(NaturalOrder))

    @Test fun caseIsIgnored() =
        assertEquals(listOf("apple", "Banana", "cherry"), listOf("cherry", "Banana", "apple").sortedWith(NaturalOrder))

    @Test fun tiesAreBrokenByPlainString() {
        assertTrue(NaturalOrder.compare("a", "A") != 0)
        assertEquals(-NaturalOrder.compare("a", "A"), NaturalOrder.compare("A", "a"))
    }

    @Test fun hugeDigitRunsDoNotOverflow() =
        assertTrue(NaturalOrder.compare("x99999999999999999999999", "x100000000000000000000000") < 0)
}
