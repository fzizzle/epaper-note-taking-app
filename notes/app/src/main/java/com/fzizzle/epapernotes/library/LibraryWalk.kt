package com.fzizzle.epapernotes.library

import java.io.File

/**
 * Every PDF in the library, for drawing thumbnails ahead (lighter thumbnails plan, decision 2):
 * a folder's own PDFs first, then each of its folders all the way down, everything in natural
 * order, as the library lists them. Hidden and temporary files are left out as there.
 *
 * Lazy: a folder is read only when the walk gets to it, so starting it costs nothing, a walk
 * stopped early has read no more than it needed, and folders filled meanwhile are seen.
 */
object LibraryWalk {
    fun pdfsUnder(root: File, limit: Int = 2000): Sequence<PdfEntry> = walk(root).take(limit)

    private fun walk(folder: File): Sequence<PdfEntry> = sequence {
        // Gone (a card taken out, a folder deleted on the laptop): nothing more here, quietly.
        val children = folder.listFiles()?.filter { !FolderListing.isHidden(it.name) } ?: return@sequence
        children.filter { FolderListing.isPdf(it.name) && it.isFile }
            .sortedWith(compareBy(NaturalOrder) { it.name })
            .forEach { yield(PdfEntry(it, it.name.dropLast(4), it.length(), it.lastModified())) }
        children.filter { it.isDirectory }
            .sortedWith(compareBy(NaturalOrder) { it.name })
            .forEach { yieldAll(walk(it)) }
    }
}
