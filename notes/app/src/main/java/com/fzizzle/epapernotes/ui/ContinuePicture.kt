package com.fzizzle.epapernotes.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * The picture beside "Continue" in the library: the page the owner left, with their ink, as
 * a small PNG in the app's cache. One file for the one document opened last; [whose] says
 * which document it shows, so another file never gets it.
 *
 * Written by the document's worker as the document is left, read by the library's disk thread.
 */
class ContinuePicture(
    private val dir: File,
    private val whose: () -> File?,
    private val setWhose: (File?) -> Unit,
) {
    constructor(cacheDir: File, prefs: LibraryPrefs) : this(cacheDir, { prefs.continuePictureOf }, { prefs.continuePictureOf = it })

    private val file = File(dir, "continue.png")

    /** When the picture was last written (0 if there's none): the library only reads it again when this changes. */
    val stamp: Long get() = file.lastModified()

    /** Through a temporary file and a rename, so a half-written picture is never read. */
    fun store(pdf: File, png: ByteArray) {
        dir.mkdirs()
        val tmp = File.createTempFile(".continue-", ".tmp", dir)
        try {
            tmp.writeBytes(png)
            if (tmp.renameTo(file)) setWhose(pdf)
        } finally {
            tmp.delete() // gone already after a good rename
        }
    }

    /** The picture, if it is [pdf]'s. */
    fun load(pdf: File): Bitmap? {
        if (whose() != pdf || !file.isFile) return null
        return BitmapFactory.decodeFile(file.path, SMALL)
    }

    /** [from] was renamed or moved to [to] in the library: the picture stays with its document, whether that was the file or a folder above it. */
    fun moved(from: File, to: File) {
        val pdf = whose() ?: return
        if (pdf == from) setWhose(to) else if (pdf.startsWith(from)) setWhose(File(to, pdf.relativeTo(from).path))
    }

    private companion object {
        /** 16-bit colour, as the thumbnails: half the memory. */
        val SMALL = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
    }
}
