package com.fzizzle.epapernotes.storage

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Replaces a file without ever leaving it half-written.
 *
 * The new version goes to a hidden temporary file in the same folder. It's flushed to disk,
 * then moved over the original in one step. If anything fails before that move, the original
 * is untouched and the temporary file is removed. A crash can leave a stale temporary file
 * behind, which [cleanStale] removes. It can never leave a broken PDF.
 */
object SafeSave {

    private const val SUFFIX = ".pnotes-tmp"

    /** `.<name>.pnotes-tmp` in the same folder. Same folder so the final move is a rename, never a copy. */
    fun tempFileFor(target: File): File = File(target.absoluteFile.parentFile, ".${target.name}$SUFFIX")

    fun isTempFile(name: String): Boolean = name.startsWith(".") && name.endsWith(SUFFIX)

    /**
     * Calls [write] to fill a temporary file, then swaps it in for [target]. [target] doesn't
     * have to exist yet. Any failure is rethrown so the caller can tell the owner: a failed save
     * must never be silent.
     */
    fun replace(target: File, write: (File) -> Unit) {
        val temp = tempFileFor(target)
        temp.delete()
        try {
            write(temp)
            // Make sure the bytes are on the storage chip, not just in memory, before the swap.
            FileOutputStream(temp, true).use { it.fd.sync() }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
    }

    /**
     * Deletes temporary files left in [folder] by a save that crashed. Subfolders aren't
     * touched. Call it when no save can be running in that folder. Returns how many it removed.
     */
    fun cleanStale(folder: File): Int {
        val stale = folder.listFiles { f -> f.isFile && isTempFile(f.name) } ?: return 0
        return stale.count { it.delete() }
    }
}
