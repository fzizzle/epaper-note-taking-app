package com.fzizzle.epapernotes.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HookPathTest {
    private val storage = File("/storage/emulated/0")

    @Test fun sdcardBecomesTheStoragePath() = assertEquals(
        File("/storage/emulated/0/Documents/E-paper Notes test/PWT test copy.pdf"),
        HookPath.under(storage, "/sdcard/Documents/E-paper Notes test/PWT test copy.pdf"),
    )

    @Test fun sdcardAloneIsTheStorage() = assertEquals(storage, HookPath.under(storage, "/sdcard"))

    @Test fun storagePathIsKeptAsGiven() = assertEquals(
        File("/storage/emulated/0/Documents"),
        HookPath.under(storage, "/storage/emulated/0/Documents/"),
    )

    @Test fun outsideTheStorageIsRefused() {
        assertNull(HookPath.under(storage, "/data/data/com.fzizzle.epapernotes/files/x.pdf"))
        assertNull(HookPath.under(storage, "/storage/emulated/01/x.pdf"))
        assertNull(HookPath.under(storage, "/sdcardx/x.pdf"))
    }

    @Test fun climbingOutIsRefused() {
        assertNull(HookPath.under(storage, "/sdcard/../../data/x.pdf"))
        assertNull(HookPath.under(storage, "/storage/emulated/0/Documents/../../1/x.pdf"))
    }

    @Test fun relativeIsRefused() = assertNull(HookPath.under(storage, "Documents/x.pdf"))
}
