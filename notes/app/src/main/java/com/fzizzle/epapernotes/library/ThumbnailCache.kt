package com.fzizzle.epapernotes.library

import java.io.File
import java.security.MessageDigest

/**
 * Width the library renders first-page thumbnails at: about the size a tile shows them on the
 * Note Air 6 C (a page's picture there is about 233 by 330 px). Made at 300 before, a third
 * more pixels to draw, store and keep in memory for nothing the panel could show.
 */
const val THUMB_WIDTH_PX = 240

/**
 * And the most a thumbnail may be tall. With no limit, the thumbnail of a very long page (a
 * scan or an exported web page) is thousands of pixels tall; a few of those on screen didn't
 * fit the loader's picture memory, which then drew tiles again and again.
 */
const val THUMB_MAX_HEIGHT_PX = 340

/**
 * How wide to render the thumbnail of a page of this size (in points, as shown): the usual
 * width, or narrower if that would make it taller than [THUMB_MAX_HEIGHT_PX]. Never under
 * 20 px, so there's always something to see. Rounded down, so the height stays within the limit.
 */
fun thumbWidthPx(pageWidth: Float, pageHeight: Float): Int {
    if (pageWidth <= 0f || pageHeight <= 0f) return THUMB_WIDTH_PX
    val widthAtMaxHeight = (THUMB_MAX_HEIGHT_PX * pageWidth / pageHeight).toInt()
    return minOf(THUMB_WIDTH_PX, widthAtMaxHeight).coerceAtLeast(20)
}

/**
 * PNG thumbnails on disk, named `<path hash>-<size>-<modified>.png`. A changed PDF has a new
 * size or time, so it gets a new name and a stale thumbnail is never shown. Rendering is the
 * caller's job.
 */
class ThumbnailCache(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {

    fun fileFor(pdf: PdfEntry): File = File(dir, "${hashOf(pdf)}-${pdf.size}-${pdf.modified}.png")

    /**
     * The file if it exists. It can vanish before it is opened (a newer version's [store]
     * sweeps it), so decoders must treat a missing or undecodable file as a cache miss.
     */
    fun cached(pdf: PdfEntry): File? = fileFor(pdf).takeIf { it.isFile }

    /**
     * Writes through a unique temp file so a half-written PNG is never read and two stores at
     * once can't mix; drops older versions. Also records the path in `<hash>.path` so
     * [pruneMissing] can tell when the PDF is gone.
     */
    fun store(pdf: PdfEntry, png: ByteArray) {
        dir.mkdirs()
        val target = fileFor(pdf)
        val hash = hashOf(pdf)
        if (!publish(target, png)) return
        publish(File(dir, "$hash.path"), pathOf(pdf).toByteArray())
        // This file's older versions only. Names, not File objects: the cache folder holds
        // hundreds of files, and this runs for every thumbnail made.
        dir.list { _, name -> name.startsWith("$hash-") && name != target.name && name != factsFileFor(pdf).name }
            ?.forEach { File(dir, it).delete() }
    }

    /** Where [pdf]'s facts are kept: beside its thumbnail, under the same name, so a changed file has none. */
    private fun factsFileFor(pdf: PdfEntry): File = File(dir, "${hashOf(pdf)}-${pdf.size}-${pdf.modified}.facts")

    /**
     * Keeps [facts] for this version of [pdf]. Call it after [store], which sweeps everything
     * kept for the file's older versions (and would sweep this too).
     */
    fun storeFacts(pdf: PdfEntry, facts: PdfFacts) {
        dir.mkdirs()
        publish(factsFileFor(pdf), facts.encode().toByteArray())
    }

    /** The facts kept for this version of [pdf], or null: none yet, or unreadable. */
    fun facts(pdf: PdfEntry): PdfFacts? = try {
        factsFileFor(pdf).takeIf { it.isFile }?.readText()?.let(PdfFacts::decode)
    } catch (e: java.io.IOException) {
        null // swept between the look and the read
    }

    private fun publish(target: File, bytes: ByteArray): Boolean {
        val tmp = File.createTempFile(".thumb", ".tmp", dir)
        try {
            tmp.writeBytes(bytes)
            if (tmp.renameTo(target)) return true
            runCatching { android.util.Log.w("Notes", "thumbnail rename failed: ${target.name}") }
            return false
        } finally {
            tmp.delete() // gone already after a good rename
        }
    }

    /**
     * Deletes the thumbnails of PDFs that no longer exist, plus leftover temp files older than
     * an hour and thumbnails with no recorded path. Runs at most once a day.
     *
     * [storageIn] says whether the storage a PDF lies on is there at all. A PDF on a memory
     * card that's out can't be found, but it isn't gone: its thumbnail is kept, or one start
     * of the app without the card would cost every thumbnail of the card's PDFs.
     */
    fun pruneMissing(exists: (File) -> Boolean = File::isFile, storageIn: (File) -> Boolean = { true }) {
        val stamp = File(dir, STAMP)
        val now = clock()
        val last = stamp.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
        if (last != null && now - last in 0 until DAY_MS) return
        val files = dir.listFiles() ?: return
        val live = HashSet<String>()
        for (side in files) {
            if (!side.name.endsWith(".path")) continue
            val hash = side.name.removeSuffix(".path")
            val pdf = File(side.readText())
            if (exists(pdf) || !storageIn(pdf)) live += hash else side.delete()
        }
        for (f in files) {
            val name = f.name
            if ((name.endsWith(".png") || name.endsWith(".facts")) && name.substringBefore('-') !in live) f.delete()
            else if (name.endsWith(".tmp") && now - f.lastModified() > HOUR_MS) f.delete()
        }
        stamp.writeText(now.toString())
    }

    private companion object {
        const val STAMP = ".last-prune"
        const val HOUR_MS = 60 * 60 * 1000L
        const val DAY_MS = 24 * 60 * 60 * 1000L

        // Normalized absolute path, not canonical, so keys match the rest of the app (ruling L3).
        fun pathOf(pdf: PdfEntry): String = pdf.file.normalize().absolutePath

        fun hashOf(pdf: PdfEntry): String =
            MessageDigest.getInstance("SHA-256")
                .digest(pathOf(pdf).toByteArray())
                .take(8).joinToString("") { "%02x".format(it) }
    }
}
