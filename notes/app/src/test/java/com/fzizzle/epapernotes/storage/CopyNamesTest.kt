package com.fzizzle.epapernotes.storage

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CopyNamesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val now = LocalDateTime.of(2026, 11, 9, 14, 5)

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
}
