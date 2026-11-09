package com.fzizzle.epapernotes.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileIdentityTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun sameContentSameIdentity() {
        val f = tmp.newFile("a.pdf").apply { writeText("hello") }
        assertEquals(FileIdentity.of(f), FileIdentity.of(f))
    }

    /** A sync app can rewrite a file with the same size and an old timestamp. The hash still notices. */
    @Test fun editedContentDifferentHash() {
        val f = tmp.newFile("a.pdf").apply { writeText("hello") }
        val before = FileIdentity.of(f)
        f.writeText("jello")
        f.setLastModified(before.modifiedMs)
        val after = FileIdentity.of(f)
        assertEquals(before.size, after.size)
        assertNotEquals(before.sha256, after.sha256)
    }

    @Test fun identityRoundTrips() {
        val id = FileIdentity(1234L, 1700000000000L, "ab".repeat(32))
        assertEquals(id, FileIdentity.decode(id.encode()))
    }

    @Test fun decodeRejectsGarbage() {
        assertNull(FileIdentity.decode("12 x y"))
        assertNull(FileIdentity.decode(""))
    }
}
