package com.fzizzle.epapernotes.storage

import java.io.File
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CopyNamesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val now = LocalDateTime.of(2026, 11, 9, 14, 5)
    private val dir = File("/sdcard/Workbooks")

    @Test fun copyNameUsesDate() =
        assertEquals("Lecture 3 (my edits 2026-11-09).pdf", CopyNames.myEdits(tmp.newFile("Lecture 3.pdf"), now).name)

    @Test fun copyNameAddsTimeWhenTaken() {
        val original = tmp.newFile("Lecture 3.pdf")
        tmp.newFile("Lecture 3 (my edits 2026-11-09).pdf")
        assertEquals("Lecture 3 (my edits 2026-11-09 14-05).pdf", CopyNames.myEdits(original, now).name)
    }

    @Test fun copyNameAddsCounterWhenBothTaken() {
        val original = tmp.newFile("Lecture 3.pdf")
        tmp.newFile("Lecture 3 (my edits 2026-11-09).pdf")
        tmp.newFile("Lecture 3 (my edits 2026-11-09 14-05).pdf")
        assertEquals("Lecture 3 (my edits 2026-11-09 14-05 2).pdf", CopyNames.myEdits(original, now).name)
    }

    @Test fun copyIsNextToOriginal() {
        val original = tmp.newFile("a.pdf")
        assertEquals(original.parentFile, CopyNames.myEdits(original, now).parentFile)
    }

    @Test fun theFirstAttemptIsNumberTwo() =
        assertEquals(File(dir, "BSA Beams — workbook (attempt 2).pdf"), CopyNames.attempt(File(dir, "BSA Beams — workbook.pdf")) { false })
    @Test fun anAttemptOfAnAttemptCountsOn() =
        assertEquals("W (attempt 3).pdf", CopyNames.attempt(File(dir, "W (attempt 2).pdf")) { false }.name)
    @Test fun aTakenNumberIsSkipped() =
        assertEquals("W (attempt 4).pdf", CopyNames.attempt(File(dir, "W.pdf")) { it.name in setOf("W (attempt 2).pdf", "W (attempt 3).pdf") }.name)

    @Test fun anAttemptKeepsTheExtensionAsItIs() =
        assertEquals("W (attempt 2).PDF", CopyNames.attempt(File(dir, "W.PDF")) { false }.name)

    /** Only a number in brackets at the very end counts: anything else is part of the name. */
    @Test fun onlyATrailingAttemptCountsOn() {
        assertEquals("W (attempt 2) notes (attempt 2).pdf", CopyNames.attempt(File(dir, "W (attempt 2) notes.pdf")) { false }.name)
        assertEquals("W (attempt x) (attempt 2).pdf", CopyNames.attempt(File(dir, "W (attempt x).pdf")) { false }.name)
    }

    /** A number too big to count on is part of the name: no overflow to a negative attempt. */
    @Test fun aHugeNumberIsPartOfTheName() =
        assertEquals("W (attempt 2147483647) (attempt 2).pdf", CopyNames.attempt(File(dir, "W (attempt 2147483647).pdf")) { false }.name)

    @Test fun anAttemptIsBesideTheOriginalAndNeverAnExistingFile() {
        val original = tmp.newFile("W.pdf")
        tmp.newFile("W (attempt 2).pdf")
        assertEquals(File(tmp.root, "W (attempt 3).pdf"), CopyNames.attempt(original))
    }

    private fun long() = File(tmp.root, "é".repeat(125) + ".pdf")

    @Test fun myEditsOfAVeryLongNameFits() {
        val name = CopyNames.myEdits(long(), now).name
        assertTrue(name.toByteArray().size <= 255)
        assertTrue(name.endsWith(" (my edits 2026-11-09).pdf"))
        assertTrue(name.startsWith("éééé"))
        assertTrue(name.removeSuffix(" (my edits 2026-11-09).pdf").all { it == 'é' })
    }

    @Test fun anAttemptOfAVeryLongNameFits() {
        val name = CopyNames.attempt(long()) { false }.name
        assertTrue(name.toByteArray().size <= 255)
        assertTrue(name.endsWith(" (attempt 2).pdf"))
        assertTrue(name.startsWith("éééé"))
    }
}
