package com.fzizzle.epapernotes.library

import com.fzizzle.epapernotes.storage.SafeSave
import java.io.File

/** One thing the library shows: a folder or a PDF. */
sealed interface Entry {
    val file: File
    val name: String
}

data class FolderEntry(override val file: File, override val name: String, val pdfCount: Int, val folderCount: Int = 0) : Entry

/**
 * [name] has no ".pdf" on the end. [group] is set for a PDF shown in place of the small folder
 * it lies in (FolderListing's opening out): that folder.
 */
data class PdfEntry(override val file: File, override val name: String, val size: Long, val modified: Long, val group: File? = null) : Entry

object FolderListing {
    /**
     * What is directly inside [folder]: folders first, then PDFs, each in natural order. Names
     * starting with "." (which includes SafeSave temp files) and anything that is neither a folder
     * nor a PDF are left out. Never recursive, so it is cheap enough for one screen load.
     *
     * A small folder is opened out: one holding 1 to [openOutUpTo] PDFs and no folders is
     * listed as those PDFs, where the folder would be, each with its [PdfEntry.group] set. A
     * topic that is just a workbook and its answer key then costs no tap, and no screen of its
     * own, to get into. With 0 (as inside such a folder, or with the setting off) nothing is.
     */
    fun list(folder: File, openOutUpTo: Int = 0): List<Entry> {
        StrandedRename.recover(folder) // a case-only rename that didn't finish
        val children = folder.listFiles() ?: return emptyList() // unreadable or not a folder
        // Each folder's place in the order, holding the folder itself or the PDFs it's opened out into.
        val folders = ArrayList<Pair<String, List<Entry>>>()
        val pdfs = ArrayList<PdfEntry>()
        for (child in children) {
            if (isHidden(child.name)) continue
            if (child.isDirectory) {
                // Read once for both counts: each read of a folder on the memory card costs a few milliseconds.
                val inside = child.listFiles().orEmpty().filter { !isHidden(it.name) }
                val insidePdfs = inside.filter { isPdf(it.name) && it.isFile }
                val insideFolders = inside.count { it.isDirectory }
                folders += child.name to if (insideFolders == 0 && insidePdfs.size in 1..openOutUpTo) {
                    insidePdfs.map { pdfEntry(it, group = child) }.sortedWith(compareBy(NaturalOrder) { it.name })
                } else {
                    listOf(FolderEntry(child, child.name, insidePdfs.size, insideFolders))
                }
            } else if (isPdf(child.name) && child.isFile) {
                pdfs += pdfEntry(child, group = null)
            }
        }
        return folders.sortedWith(compareBy(NaturalOrder) { it.first }).flatMap { it.second } +
            pdfs.sortedWith(compareBy(NaturalOrder) { it.name })
    }

    private fun pdfEntry(file: File, group: File?) =
        PdfEntry(file, file.name.dropLast(PDF_SUFFIX.length), file.length(), file.lastModified(), group)

    /** How many PDFs a folder may hold and still be opened out ([list]). */
    const val OPEN_OUT_UP_TO = 3

    internal fun isHidden(name: String) = name.startsWith(".") || SafeSave.isTempFile(name)

    internal fun isPdf(name: String) = name.endsWith(PDF_SUFFIX, ignoreCase = true)

    private const val PDF_SUFFIX = ".pdf"
}
