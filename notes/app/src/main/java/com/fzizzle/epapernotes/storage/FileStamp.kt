package com.fzizzle.epapernotes.storage

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit

/**
 * A quick look at a file that tells whether anything touched it, without reading it: its size,
 * its date, the storage's "last changed" time and the storage's own number for the file.
 *
 * The "last changed" time is the point. Unlike the date, no program can set it back: the
 * storage moves it forward itself at every write, rename or change of date. And a file swapped
 * in by a rename (how most programs save) is a different file with a different number.
 *
 * So: two equal stamps mean nothing touched the file in between, and the slow fingerprint
 * ([FileIdentity]) needn't be read again. Two different stamps prove nothing (a date set and
 * set back also moves the stamp): the caller then reads the fingerprint, as before.
 *
 * One gap remains, and [afterReading] and [afterMove] are there to close it: the storage's
 * clock moves in small steps (up to about 10 ms), so a change in the very step in which the
 * stamp was taken can leave the "last changed" time as it was. Use those two, not [of], for a
 * stamp that is going to be trusted later.
 */
data class FileStamp(val size: Long, val modifiedMs: Long, val changedNs: Long, val fileNumber: Long) {

    companion object {
        /**
         * Longer than one step of any storage clock the tablet uses (10 ms at most), with room
         * to spare. Two events this far apart never get the same time.
         */
        const val CLOCK_STEP_MS = 20L

        /**
         * [file]'s stamp now, or null when the storage can't give one (some don't keep a
         * "last changed" time or a file number) or the file can't be looked at. Null always
         * means "read the file instead".
         */
        fun of(file: File): FileStamp? = try {
            val a = Files.readAttributes(file.toPath(), "unix:size,lastModifiedTime,ctime,ino")
            FileStamp(
                size = a["size"] as Long,
                modifiedMs = (a["lastModifiedTime"] as FileTime).toMillis(),
                // Nanoseconds: rounding to milliseconds could make two changes look like one.
                changedNs = (a["ctime"] as FileTime).to(TimeUnit.NANOSECONDS),
                fileNumber = (a["ino"] as Number).toLong(),
            )
        } catch (e: Exception) {
            // Not offered here (UnsupportedOperationException, IllegalArgumentException), not
            // readable (IOException), or not the kind of value expected.
            null
        }

        /**
         * The stamp to trust for a file whose fingerprint has just been read. [before] is its
         * stamp from just before that read. Null (don't trust any) unless:
         *
         * - the stamp is the same before and after the read, so the bytes that were read are
         *   one version of the file, not half of one and half of the next; and
         * - the file's last change is more than a clock step in the past ([nowMs] is the time
         *   now). Otherwise a change a moment from now could still get the same time.
         */
        fun afterReading(file: File, before: FileStamp?, nowMs: Long = System.currentTimeMillis()): FileStamp? {
            val after = of(file) ?: return null
            if (after != before) return null
            return if (nowMs - after.changedNs / 1_000_000 >= CLOCK_STEP_MS) after else null
        }

        /**
         * The stamp to trust for [file] just after a finished temporary file was renamed into
         * its place. [temp] is that temporary file's stamp from just before the rename. A
         * rename keeps a file's number, size and date, and moves only its "last changed" time.
         * Null (don't trust any) unless:
         *
         * - [file] has the number, size and date [temp] had: it is the file that was written,
         *   not one something else put there since; and
         * - the rename came more than a clock step after the last byte was written. Then
         *   anything that writes to the file after the rename gives it a later date than
         *   [temp]'s, and is seen here or at the next look.
         *
         * What this leaves open: something that changes the file within the same clock step as
         * the rename AND keeps its size AND sets its date back to exactly the one the app's own
         * save gave it a moment ago. A sync app sets a file's date to that of its own version,
         * so this can't happen by accident.
         */
        fun afterMove(file: File, temp: FileStamp?): FileStamp? {
            val now = of(file) ?: return null
            if (temp == null) return null
            val sameFile = now.fileNumber == temp.fileNumber && now.size == temp.size && now.modifiedMs == temp.modifiedMs
            if (!sameFile) return null
            return if (now.changedNs / 1_000_000 - temp.modifiedMs >= CLOCK_STEP_MS) now else null
        }
    }
}
