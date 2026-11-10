package com.fzizzle.epapernotes.library

import java.io.File
import java.security.MessageDigest

/** Width the library renders first-page thumbnails at. */
const val THUMB_WIDTH_PX = 300

/**
 * PNG thumbnails on disk, named `<path hash>-<size>-<modified>.png`. A changed PDF has a new
 * size or time, so it gets a new name and a stale thumbnail is never shown. Rendering is the
 * caller's job.
 */
class ThumbnailCache(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {

    fun fileFor(pdf: PdfEntry): File = File(dir, "${hashOf(pdf)}-${pdf.size}-${pdf.modified}.png")

    fun cached(pdf: PdfEntry): File? = fileFor(pdf).takeIf { it.isFile }

    /** Writes through a temp file so a half-written PNG is never read; drops older versions. */
    fun store(pdf: PdfEntry, png: ByteArray) {
        dir.mkdirs()
        val target = fileFor(pdf)
        val tmp = File(dir, ".${target.name}.tmp")
        tmp.writeBytes(png)
        if (!tmp.renameTo(target)) { tmp.delete(); return }
        val hash = hashOf(pdf)
        dir.listFiles()?.forEach {
            if (it != target && it.name.startsWith("$hash-")) it.delete()
        }
    }

    /** Deletes thumbnails whose hash [isLive] rejects. Runs at most once a day. */
    fun pruneMissing(isLive: (hashPrefix: String) -> Boolean) {
        val stamp = File(dir, STAMP)
        val now = clock()
        val last = stamp.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
        if (last != null && now - last in 0 until DAY_MS) return
        dir.listFiles()?.forEach {
            if (it.name.endsWith(".png") && !isLive(it.name.substringBefore('-'))) it.delete()
        }
        dir.mkdirs()
        stamp.writeText(now.toString())
    }

    private companion object {
        const val STAMP = ".last-prune"
        const val DAY_MS = 24 * 60 * 60 * 1000L

        // The path as given, not canonical, so keys match the rest of the app (ruling L1).
        fun hashOf(pdf: PdfEntry): String =
            MessageDigest.getInstance("SHA-256")
                .digest(pdf.file.normalize().absolutePath.toByteArray())
                .take(8).joinToString("") { "%02x".format(it) }
    }
}
