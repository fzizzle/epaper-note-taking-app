package com.fzizzle.epapernotes.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HookPathTest {
    private val storage = File("/storage/emulated/0")
    private val card = File("/storage/3331-3031")
    private val both = listOf(storage, card)

    @Test fun sdcardBecomesTheStoragePath() = assertEquals(
        File("/storage/emulated/0/Documents/E-paper Notes test/PWT test copy.pdf"),
        HookPath.under(both, "/sdcard/Documents/E-paper Notes test/PWT test copy.pdf"),
    )

    @Test fun sdcardAloneIsTheStorage() = assertEquals(storage, HookPath.under(both, "/sdcard"))

    @Test fun storagePathIsKeptAsGiven() = assertEquals(
        File("/storage/emulated/0/Documents"),
        HookPath.under(both, "/storage/emulated/0/Documents/"),
    )

    @Test fun outsideTheStorageIsRefused() {
        assertNull(HookPath.under(both, "/data/data/com.fzizzle.epapernotes/files/x.pdf"))
        assertNull(HookPath.under(both, "/storage/emulated/01/x.pdf"))
        assertNull(HookPath.under(both, "/sdcardx/x.pdf"))
    }

    @Test fun climbingOutIsRefused() {
        assertNull(HookPath.under(both, "/sdcard/../../data/x.pdf"))
        assertNull(HookPath.under(both, "/storage/emulated/0/Documents/../../1/x.pdf"))
    }

    @Test fun relativeIsRefused() = assertNull(HookPath.under(both, "Documents/x.pdf"))

    @Test fun cardPathIsKeptAsGiven() = assertEquals(
        File("/storage/3331-3031/Workbooks/Physics.pdf"),
        HookPath.under(both, "/storage/3331-3031/Workbooks/Physics.pdf"),
    )

    @Test fun cardThatsOutIsRefused() {
        assertNull(HookPath.under(listOf(storage), "/storage/3331-3031/Workbooks/Physics.pdf"))
        assertNull(HookPath.under(both, "/storage/3331-30310/x.pdf"))
        assertNull(HookPath.under(both, "/storage/3331-3031/../emulated/0/x.pdf"))
    }

    @Test fun noVolumesRefusesEverything() = assertNull(HookPath.under(emptyList(), "/sdcard/x.pdf"))
}
