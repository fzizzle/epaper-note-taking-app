package com.fzizzle.epapernotes.library

import com.fzizzle.epapernotes.storage.SafeSave
import java.io.File

/** One thing the library shows: a folder or a PDF. */
sealed interface Entry {
    val file: File
    val name: String
}

data class FolderEntry(override val file: File, override val name: String, val pdfCount: Int, val folderCount: Int = 0) : Entry

/** [name] has no ".pdf" on the end. */
data class PdfEntry(override val file: File, override val name: String, val size: Long, val modified: Long) : Entry

object FolderListing {
    /**
     * What is directly inside [folder]: folders first, then PDFs, each in natural order. Names
     * starting with "." (which includes SafeSave temp files) and anything that is neither a folder
     * nor a PDF are left out. Never recursive, so it is cheap enough for one screen load.
     */
    fun list(folder: File): List<Entry> {
        StrandedRename.recover(folder) // a case-only rename that didn't finish
        val children = folder.listFiles() ?: return emptyList() // unreadable or not a folder
        val folders = ArrayList<FolderEntry>()
        val pdfs = ArrayList<PdfEntry>()
        for (child in children) {
            if (isHidden(child.name)) continue
            if (child.isDirectory) {
                folders += FolderEntry(child, child.name, countPdfs(child), countFolders(child))
            } else if (isPdf(child.name) && child.isFile) {
                pdfs += PdfEntry(child, child.name.dropLast(PDF_SUFFIX.length), child.length(), child.lastModified())
            }
        }
        return folders.sortedWith(compareBy(NaturalOrder) { it.name }) +
            pdfs.sortedWith(compareBy(NaturalOrder) { it.name })
    }

    private fun countPdfs(folder: File): Int =
        folder.listFiles()?.count { !isHidden(it.name) && isPdf(it.name) && it.isFile } ?: 0

    private fun countFolders(folder: File): Int =
        folder.listFiles()?.count { !isHidden(it.name) && it.isDirectory } ?: 0

    private fun isHidden(name: String) = name.startsWith(".") || SafeSave.isTempFile(name)

    private fun isPdf(name: String) = name.endsWith(PDF_SUFFIX, ignoreCase = true)

    private const val PDF_SUFFIX = ".pdf"
}
