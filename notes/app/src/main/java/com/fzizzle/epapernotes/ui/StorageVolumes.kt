package com.fzizzle.epapernotes.ui

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import com.fzizzle.epapernotes.library.Volume
import com.fzizzle.epapernotes.library.VolumeInfo
import com.fzizzle.epapernotes.library.Volumes
import java.io.File

/**
 * The storage volumes that are in and writable, tablet storage first. A quick question to the
 * system, not a disk read, so the screen's thread may ask it.
 */
object StorageVolumes {
    fun mounted(context: Context): List<Volume> {
        val found = context.getSystemService(StorageManager::class.java).storageVolumes
            .filter { it.state == Environment.MEDIA_MOUNTED }
            .mapNotNull { v ->
                // The tablet's storage exactly as the library always wrote it (Ruling L1); the
                // others as Android names them, only made absolute and tidy.
                val dir = if (v.isPrimary) STORAGE else v.directory?.absoluteFile?.normalize() ?: return@mapNotNull null
                VolumeInfo(dir, v.isPrimary, v.getDescription(context))
            }
        val labelled = Volumes.labelled(found)
        // Never without the tablet's storage, even if the system left it out.
        return if (labelled.any { it.isPrimary }) labelled else listOf(Volume(Volumes.PRIMARY_LABEL, STORAGE, true)) + labelled
    }
}

/** The tablet's own storage (Ruling L1: /storage/emulated/0, never /sdcard). */
val STORAGE: File get() = Environment.getExternalStorageDirectory()
