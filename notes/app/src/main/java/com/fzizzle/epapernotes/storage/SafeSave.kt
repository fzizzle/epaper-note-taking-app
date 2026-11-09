package com.fzizzle.epapernotes.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Replaces a file without ever leaving it half-written.
 *
 * The new version goes to a hidden temporary file in the same folder. It's flushed to disk,
 * then moved over the original in one step. If anything fails before that move, the original
 * is untouched and the temporary file is removed. A crash can leave a stale temporary file
 * behind, which [cleanStale] removes. It can never leave a broken PDF.
 *
 * When [replace] returns, the new version is on disk, as far as the storage allows.
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
        syncFolder(target)
    }

    /**
     * The rename itself lives in the folder, so flush the folder too. Only then is the new
     * version sure to survive a power cut, which matters because the crash journal is cleared
     * once replace() returns. Some storage refuses to sync folders; the file is already
     * swapped in by then, so that's not treated as a failed save.
     */
    private fun syncFolder(target: File) {
        try {
            FileChannel.open(target.absoluteFile.parentFile.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (e: IOException) {
            // Not supported here. Nothing more can be done.
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
