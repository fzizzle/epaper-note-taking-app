package com.fzizzle.epapernotes.storage

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

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

    /** The longest file name the storage takes, in UTF-8 bytes. */
    const val MAX_NAME_BYTES = 255

    /** True if [name] is short enough to be a file name. */
    fun fitsName(name: String): Boolean = name.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES

    /**
     * `.<name>.pnotes-tmp` in the same folder. Same folder so the final move is a rename, never a copy.
     * When that would be too long a name, it is `.<32 hex digits of the name's sha256>.pnotes-tmp`.
     */
    fun tempFileFor(target: File): File {
        val plain = ".${target.name}$SUFFIX"
        val name = if (fitsName(plain)) plain else {
            val digest = MessageDigest.getInstance("SHA-256").digest(target.name.toByteArray(Charsets.UTF_8))
            ".${digest.joinToString("") { "%02x".format(it) }.take(32)}$SUFFIX"
        }
        return File(target.absoluteFile.parentFile, name)
    }

    fun isTempFile(name: String): Boolean = name.startsWith(".") && name.endsWith(SUFFIX)

    /**
     * Calls [write] to fill a temporary file, then swaps it in for [target]. [target] doesn't
     * have to exist yet. Any failure is rethrown so the caller can tell the owner: a failed save
     * must never be silent.
     *
     * [beforeSwap] gets the finished, flushed temporary file just before it is moved over
     * [target]: the last moment to note what the new version looks like while the old one is
     * still in place. If it throws, the swap doesn't happen and the temporary file is removed.
     */
    fun replace(target: File, beforeSwap: (File) -> Unit = {}, write: (File) -> Unit) {
        val temp = tempFileFor(target)
        temp.delete()
        try {
            write(temp)
            // Never swap in nothing. If the temp file is missing or empty (say another session
            // removed it), the original must stay. Opening it to sync must not create it either.
            if (!temp.exists() || temp.length() == 0L) throw IOException("the new version of ${target.name} came out empty")
            // Make sure the bytes are on the storage chip, not just in memory, before the swap.
            FileChannel.open(temp.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
            beforeSwap(temp)
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
        syncFolder(target)
    }

    /**
     * [replace] for a file that must not exist yet, such as a fresh attempt's copy. If a file
     * arrives at [target] while the new one is written (a sync app dropping that name, say),
     * it is left alone and this throws instead of swapping over it. On a memory card the check
     * ignores case, as the card does.
     */
    fun createNew(target: File, write: (File) -> Unit) = replace(
        target,
        beforeSwap = { if (target.exists()) throw IOException("'${target.name}' appeared meanwhile") },
        write = write,
    )

    /**
     * The rename itself lives in the folder, so flush the folder too. Only then is the new
     * version sure to survive a power cut, which matters because the crash journal is cleared
     * once replace() returns. Some storage refuses to sync folders; the file is already
     * swapped in by then, so that's not treated as a failed save.
     *
     * The journal uses this too, after it has moved a new journal file into place.
     */
    fun syncFolder(target: File) {
        try {
            FileChannel.open(target.absoluteFile.parentFile.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (e: IOException) {
            // Not supported here. Nothing more can be done.
        }
    }

    /** A temporary file untouched for this long is left from a crash, not a save under way. */
    const val STALE_AFTER_MS = 10 * 60 * 1000L

    /**
     * Deletes temporary files left in [folder] by a save that crashed: only ones last changed
     * more than [olderThanMs] before [now], since a save may be writing a fresh one. Subfolders
     * aren't touched, nor any other file. Call it when no session is open in that folder
     * (DocumentSession.anyOpenIn). Returns how many it removed.
     */
    fun cleanStale(folder: File, olderThanMs: Long = STALE_AFTER_MS, now: Long = System.currentTimeMillis()): Int {
        // The name is tested first: it asks the disk nothing, and nearly every file fails it.
        val stale = folder.listFiles { f -> isTempFile(f.name) && f.isFile && f.lastModified() < now - olderThanMs } ?: return 0
        return stale.count { it.delete() }
    }
}
