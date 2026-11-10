package com.fzizzle.epapernotes.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumesTest {
    private val storage = File("/storage/emulated/0")
    private val card = File("/storage/3331-3031")
    private val tablet = Volume("Tablet storage", storage, isPrimary = true)
    private val sd = Volume("SD card", card, isPrimary = false)

    @Test fun tabletStorageFirstThenTheCard() = assertEquals(
        listOf(tablet, sd),
        Volumes.labelled(listOf(VolumeInfo(card, false, "SanDisk SD card"), VolumeInfo(storage, true, "Internal shared storage"))),
    )

    @Test fun severalOthersGoByTheirDescriptions() = assertEquals(
        listOf("Tablet storage", "SanDisk SD card", "USB drive", "1234-5678"),
        Volumes.labelled(listOf(
            VolumeInfo(storage, true, null),
            VolumeInfo(card, false, "SanDisk SD card"),
            VolumeInfo(File("/storage/ABCD-0001"), false, "USB drive"),
            VolumeInfo(File("/storage/1234-5678"), false, " "),
        )).map { it.label },
    )

    @Test fun holdingFindsTheVolume() {
        assertEquals(sd, Volumes.holding(listOf(tablet, sd), File("/storage/3331-3031/Workbooks")))
        assertEquals(tablet, Volumes.holding(listOf(tablet, sd), File("/storage/emulated/0/Documents")))
        assertNull(Volumes.holding(listOf(tablet, sd), File("/storage/3331-30310/Workbooks")))
        assertNull(Volumes.holding(listOf(tablet), File("/storage/3331-3031/Workbooks")))
    }

    @Test fun cardThatsOut() {
        assertTrue(Volumes.isOnCardThatsOut(listOf(tablet), File("/storage/3331-3031/Workbooks")))
        assertFalse(Volumes.isOnCardThatsOut(listOf(tablet, sd), File("/storage/3331-3031/Workbooks")))
        assertFalse(Volumes.isOnCardThatsOut(listOf(tablet), File("/storage/emulated/0/Gone")))
    }

    /** Alone, the tablet's storage shows as before: just the folders under it. */
    @Test fun whereIsOnTheTabletAlone() {
        assertEquals("Documents › university-work", Volumes.whereIs(listOf(tablet), File("/storage/emulated/0/Documents/university-work")))
        assertEquals("Tablet storage", Volumes.whereIs(listOf(tablet), storage))
    }

    @Test fun whereIsWithACard() {
        assertEquals("Tablet storage › Documents", Volumes.whereIs(listOf(tablet, sd), File("/storage/emulated/0/Documents")))
        assertEquals("SD card › Workbooks", Volumes.whereIs(listOf(tablet, sd), File("/storage/3331-3031/Workbooks")))
        assertEquals("SD card", Volumes.whereIs(listOf(tablet, sd), card))
        assertEquals("/storage/3331-3031/Workbooks", Volumes.whereIs(listOf(tablet), File("/storage/3331-3031/Workbooks")))
    }
}
