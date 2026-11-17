package com.fzizzle.epapernotes.library

import java.io.File
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * The card as the index check reads it. A listing reads each entry's kind, size and time in one
 * call. The same names as the library are left out (hidden, temporary, not a PDF), and so are
 * links: the index never follows one, so it can't loop or reach outside the library.
 *
 * Null means only "not there" or "not a folder". Any other error (a flaky card, permission
 * denied) is thrown: the check must stop then, not take an unreadable folder for a deleted one.
 */
class FileIndexDisk : IndexDisk {
    // Follows a link: only the library folder itself can be reached through one, since listings
    // leave links out, and a library folder chosen through a link should still be found.
    override fun timeOf(folder: File): Long? =
        attributes(folder.toPath())?.takeIf { it.isDirectory }?.lastModifiedTime()?.toMillis()

    override fun read(folder: File): DiskFolder? {
        // The time is read before the listing, so a change made while listing moves it past what's saved.
        val time = timeOf(folder) ?: return null
        val folders = mutableListOf<String>()
        val pdfs = mutableListOf<IndexedPdf>()
        try {
            Files.newDirectoryStream(folder.toPath()).use { entries ->
                for (entry in entries) {
                    val name = entry.fileName.toString()
                    if (FolderListing.isHidden(name)) continue
                    // Gone since the listing named it: as if it hadn't been there.
                    val about = attributes(entry, LinkOption.NOFOLLOW_LINKS) ?: continue
                    when {
                        about.isDirectory -> folders += name
                        about.isRegularFile && FolderListing.isPdf(name) ->
                            pdfs += IndexedPdf(name, about.size(), about.lastModifiedTime().toMillis())
                    }
                }
            }
        } catch (e: NoSuchFileException) {
            return null
        } catch (e: NotDirectoryException) {
            return null
        } catch (e: DirectoryIteratorException) {
            throw e.cause ?: e // an error partway through the listing: not a sign the folder is gone
        }
        return DiskFolder(time, folders, pdfs)
    }

    /** Null when the entry isn't there; any other error is thrown. */
    private fun attributes(path: Path, vararg options: LinkOption): BasicFileAttributes? =
        try {
            Files.readAttributes(path, BasicFileAttributes::class.java, *options)
        } catch (e: NoSuchFileException) {
            null
        }
}
